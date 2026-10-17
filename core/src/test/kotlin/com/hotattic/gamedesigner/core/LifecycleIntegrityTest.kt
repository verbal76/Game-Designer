package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.engine.DesignDimensions
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.ReviewGate
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The project lifecycle end to end: save, reopen, undo, repeat, regenerate. Evidence: AUTOMATED, synthetic projects, rules only. */
class LifecycleIntegrityTest {
    private val songs = "I want a pixel art RPG. I like the population simulation in Songs of Syx, but I don't want its combat system. Android is my primary platform, and I might bring it to Windows later."
    private fun ans(k: String?) = when {
        k == "__proposals__" -> "yes"; k == "__asset_plan__" -> "looks good"; k == "__review__" -> "looks right"
        k?.startsWith("__conflict") == true -> "use alternative 1"; k == "__ready__" -> "generate"
        k == Keys.FIVE_MINUTES || k == Keys.CORE_FANTASY -> "Playing it should feel calm and rewarding."
        else -> "choose for me"
    }
    private fun roundTrip(p: Project) = ProjectCodec.decode(ProjectCodec.encode(p))
    private fun state(p: Project) = p.decisions.mapValues { it.value.value to it.value.prov } to p.activeFacts().map { it.text } to p.rejected to p.references.map { it.name to it.aspects to it.excluded }

    @Test fun savingAndReopeningAfterEveryTurnChangesNothing() = runBlocking {
        val a = director(); val b = director()
        var pa = a.handleUserMessage(a.start(newProject(), settings), settings, songs).project
        var pb = roundTrip(b.handleUserMessage(b.start(newProject(), settings), settings, songs).project)
        repeat(70) {
            if (pa.pendingFieldKey == null) return@repeat
            pa = a.handleUserMessage(pa, settings, ans(pa.pendingFieldKey)).project
            pb = roundTrip(b.handleUserMessage(pb, settings, ans(pb.pendingFieldKey)).project)
            assertEquals(pa.pendingFieldKey, pb.pendingFieldKey)
        }
        assertEquals(state(pa), state(pb), "a reopened project must continue exactly like one that stayed open")
    }

    @Test fun anExclusionSurvivesReopeningUndoAndRegeneration() = runBlocking {
        val d = director()
        var p = d.handleUserMessage(d.start(newProject(), settings), settings, songs).project
        repeat(80) { if (p.pendingFieldKey != "__ready__") p = roundTrip(d.handleUserMessage(p, settings, ans(p.pendingFieldKey)).project) }
        assertEquals("no", p.value(Keys.HAS_COMBAT))
        val back = roundTrip(assertNotNull(d.goBack(p)))
        assertEquals("no", back.value(Keys.HAS_COMBAT), "Back never undoes an unrelated owner decision")
        assertEquals(listOf("Songs of Syx"), back.references.map { it.name })
        assertTrue(back.references.single().excluded.any { it.contains("combat", true) })
        var q = back
        repeat(80) { if (q.pendingFieldKey != null) q = roundTrip(d.handleUserMessage(q, settings, ans(q.pendingFieldKey)).project) }
        assertEquals("no", q.value(Keys.HAS_COMBAT))
    }

    @Test fun aConflictFoundBeforeClosingIsResolvedAfterReopening() = runBlocking {
        val d = director()
        var p = d.handleUserMessage(d.start(newProject(), settings), settings, "A 2D puzzle game for Android and iPhone.").project
        repeat(80) { if (p.pendingFieldKey != "__ready__") p = d.handleUserMessage(p, settings, ans(p.pendingFieldKey)).project }
        val broken = ProjectOps.addFacts(p, listOf("Add PC as the primary platform."), "owner", Provenance.OWNER_EXPLICIT, p.updatedAt + 10_000)
        val reopened = roundTrip(broken)
        assertTrue(ConflictEngine.open(reopened).any { it.id == "fact_contradicts_decision" })
        val asked = d.askNext(ProjectOps.setPending(reopened, null), settings)
        val fixed = d.handleUserMessage(roundTrip(asked), settings, "use alternative 1").project
        assertEquals(Platforms.WINDOWS, fixed.value(Keys.PRIMARY_PLATFORM))
        assertTrue(ConflictEngine.open(fixed).none { it.id == "fact_contradicts_decision" })
    }

    @Test fun regeneratingAfterACorrectionAppendsAVersionAndKeepsTheOldOne() = runBlocking {
        val d = director()
        var p = d.handleUserMessage(d.start(newProject(), settings), settings, "A 2D puzzle game for Android and iPhone.").project
        repeat(100) { if (p.pendingFieldKey != "__ready__") p = d.handleUserMessage(p, settings, ans(p.pendingFieldKey)).project }
        val v1 = SpecVersioning.generate(p, VersionKind.INITIAL, p.updatedAt + 1, "2026-10-17T00:00:00Z")
        assertFalse(v1.blocked, v1.review.findings.joinToString { it.code })
        var q = d.handleUserMessage(v1.project, settings, "Actually, forget iPhone. Android only.").project
        repeat(100) { if (q.pendingFieldKey != null) q = d.handleUserMessage(q, settings, ans(q.pendingFieldKey)).project }
        val v2 = SpecVersioning.generate(q, VersionKind.REVISION, q.updatedAt + 1, "2026-10-17T00:01:00Z")
        assertFalse(v2.blocked, v2.review.findings.joinToString { it.code })
        assertEquals(2, v2.project.versions.size)
        assertEquals(v1.project.versions.first().claudeMd, v2.project.versions.first().claudeMd, "the earlier authoritative spec is never overwritten")
        assertTrue(Regex("Target platforms: Android\\b(?!,)").containsMatchIn(v2.project.versions.last().claudeMd) && !Regex("Target platforms:[^\\n]*iPhone").containsMatchIn(v2.project.versions.last().claudeMd))
    }

    @Test fun anInterruptedMessageIsResumedExactlyOnce() = runBlocking {
        val d = director()
        var p = d.handleUserMessage(d.start(newProject(), settings), settings, "A 2D puzzle game for Android.").project
        val queued = assertNotNull(d.queueTurn(p, "choose for me", "t-1"))
        val resumed = roundTrip(queued)   // the process died after the message was saved
        val once = d.handleUserMessage(resumed, settings, resumed.pendingTurn!!.text, resumed.pendingTurn!!.id)
        assertFalse(once.duplicate); assertTrue(once.project.pendingTurn == null)
        val twice = d.handleUserMessage(roundTrip(once.project), settings, "choose for me", "t-1")
        assertTrue(twice.duplicate); assertEquals(once.project.decisions, twice.project.decisions)
    }

    @Test fun readinessIsNotInflatedByContradictionsOrUnsettledSystems() = runBlocking {
        val d = director()
        var p = d.handleUserMessage(d.start(newProject(), settings), settings, "An RPG with no combat, but I want boss fights.").project
        assertEquals(null, p.decision(Keys.HAS_COMBAT), "contradictory words are left for a question")
        repeat(120) { if (p.pendingFieldKey != "__ready__") p = d.handleUserMessage(p, settings, ans(p.pendingFieldKey)).project }
        // Whatever the owner finally settled, the question was asked rather than guessed.
        assertNotNull(p.decision(Keys.HAS_COMBAT), "the combat question was put to the owner")
        assertTrue(DesignDimensions.percent(p) <= 100 && ReviewGate.approved(p))
        val contradictory = ProjectOps.addFacts(p, listOf("Add PC as the primary platform."), "owner", Provenance.OWNER_EXPLICIT, p.updatedAt + 10_000)
        assertTrue(DesignDimensions.percent(ReviewGate.approve(contradictory, contradictory.updatedAt + 1, "owner")) < 100)
    }
}
