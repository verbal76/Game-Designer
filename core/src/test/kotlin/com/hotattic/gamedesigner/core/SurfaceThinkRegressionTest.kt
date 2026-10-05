package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.engine.ConsistencyReview
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.Reconciler
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The owner's physical test of v2 ("Surface Think"): a 2D vertical-scroller pit game with two characters, one descending
 * and one climbing. v2 exported turn-based/squad/grid requirements the owner had explicitly refused.
 */
class SurfaceThinkRegressionTest {

    private val concept = "I want to make a game called Surface Think. It is a 2D vertical scroller set in a deep pit. There are two characters: one is descending to the bottom and the other is climbing to the surface. I'll play it on my Android phone."

    /** Recreates the v2 failure: the system inferred and the owner accepted a turn-based tactics reading. */
    private suspend fun staleProject(d: Director): Project {
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        val now = 1_700_000_000_500L
        // The stale state v2 produced: tactics genre, grid combat and a main-menu-only answer, all accepted by "yes"/"choose for me".
        p = ProjectOps.setDecision(p, Keys.GENRE, "turn_based_strategy", Provenance.SYSTEM_INFERENCE, now, DecisionStatus.CONFIRMED)
        p = ProjectOps.setDecision(p, Keys.COMBAT_MODEL, "tactical_grid", Provenance.OWNER_ACCEPTED_RECOMMENDATION, now)
        return ProjectOps.setPending(p, null)
    }

    private suspend fun finish(d: Director, start: Project): Project {
        var p = start
        var turns = 0
        while (turns++ < 200) {
            val pending = p.pendingFieldKey
            val reply = when {
                pending == "__proposals__" -> "yes"
                pending == "__asset_plan__" -> "looks good"
                pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__ready__" -> "generate"
                pending == Keys.SESSION_STRUCTURE -> "I'll go with the 30 to 60 minutes"
                pending == Keys.MENUS_SETTINGS -> "all of those"
                else -> "choose for me"
            }
            val t = d.handleUserMessage(p, settings, reply)
            p = t.project
            if (t.action == DirectorAction.GenerateSpec) return p
        }
        error("never reached generation; last: " + p.messages.takeLast(3).joinToString { it.text.take(120) })
    }

    @Test fun correctionRemovesStaleTurnBasedStateAndExportIsConsistent() = runBlocking {
        val d = director()
        var p = staleProject(d)
        assertTrue("turn_based_strategy" in p.list(Keys.GENRE))
        p = d.handleUserMessage(p, settings, "No, it's not turn based. This is a vertical scroller.").project
        assertFalse("turn_based_strategy" in p.list(Keys.GENRE), p.list(Keys.GENRE).toString())
        assertEquals("platformer", p.list(Keys.GENRE).firstOrNull())
        assertNotEquals("tactical_grid", p.value(Keys.COMBAT_MODEL), "grid combat was derived from the rejected reading and must be cleared")
        assertTrue(p.rejected.values.any { "TURN_BASED" in it })
        assertTrue(p.messages.any { it.role == Role.DIRECTOR && it.text.contains("not turn-based", true) }, "the correction is announced")

        p = d.handleUserMessage(d.askAbout(p, Keys.SESSION_STRUCTURE), settings, "I'll go with the 30 to 60 minutes").project
        p = d.handleUserMessage(d.askAbout(p, Keys.MENUS_SETTINGS), settings, "all of those").project
        p = finish(d, p)
        assertEquals("medium_sessions", p.value(Keys.SESSION_STRUCTURE))
        assertTrue(p.list(Keys.MENUS_SETTINGS).size >= 8, "all menus: " + p.list(Keys.MENUS_SETTINGS))
        val gen = SpecVersioning.generate(p, VersionKind.INITIAL, 1_700_000_900_000L, "2026-10-05")
        assertFalse(gen.blocked, gen.review.findings.joinToString("\n") { it.message + " :: " + it.line })
        val md = gen.project.versions.single().claudeMd
        java.io.File("build/sample").also { it.mkdirs() }.resolve("surface-think-CLAUDE.md").writeText(md)
        java.io.File("build/sample").resolve("surface-think-MASTER_PROMPT.md").writeText(gen.project.versions.single().masterPrompt)
        val lower = md.lowercase()
        for (bad in listOf("squad units", "plan each turn", "grid-based tactical", "tactical grid", "grid tactics", "turn order")) assertFalse(bad in lower, "stale text '$bad' leaked into the spec")
        for (section in listOf("PART A - OWNER REQUIREMENTS", "PART B - ACCEPTED RECOMMENDATIONS", "PART C - IMPLEMENTATION GUIDANCE", "PART D - UNRESOLVED AND DELEGATED DECISIONS", "Surface Think")) assertTrue(section in md, "missing $section")
        assertTrue(ConsistencyReview.review(gen.project, md).clean)
        assertTrue("two characters" in lower)
    }

    @Test fun answersInNaturalLanguageAreUnderstood() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        p = d.handleUserMessage(p, settings, "yes").project
        // Session length is routine, so Bob derives it; the owner can still open it from "refine".
        p = d.askAbout(p, Keys.SESSION_STRUCTURE)
        assertEquals(Keys.SESSION_STRUCTURE, p.pendingFieldKey)
        val variants = listOf("30 to 60 minutes", "option three", "the third one", "I'll go with the 30 to 60 minutes")
        for (v in variants) {
            val t = d.handleUserMessage(p, settings, v).project
            assertEquals("medium_sessions", t.value(Keys.SESSION_STRUCTURE), v)
            assertEquals(Provenance.OWNER_EXPLICIT, t.decision(Keys.SESSION_STRUCTURE)!!.prov, v)
            assertFalse(t.messages.last().text.contains("didn't quite catch", true), v)
        }
    }

    @Test fun multiSelectStoresEveryChoiceAndStructuredSubmitWorks() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        p = d.askAbout(p, Keys.MENUS_SETTINGS)
        assertEquals(Keys.MENUS_SETTINGS, p.pendingFieldKey)
        val q = p.messages.last { it.fieldKey == Keys.MENUS_SETTINGS }.question
        assertNotNull(q); assertEquals("MULTI", q.kind)
        val allIds = q.options.map { it.id }
        val viaText = d.handleUserMessage(p, settings, "all of those").project
        assertEquals(allIds.toSet(), viaText.list(Keys.MENUS_SETTINGS).toSet())
        val except = d.handleUserMessage(p, settings, "everything except language").project
        assertEquals(allIds.toSet() - "language", except.list(Keys.MENUS_SETTINGS).toSet())
        val numbers = d.handleUserMessage(p, settings, "1, 3 and 5").project
        assertEquals(setOf(allIds[0], allIds[2], allIds[4]), numbers.list(Keys.MENUS_SETTINGS).toSet())
        val viaChips = d.submitSelection(p, settings, Keys.MENUS_SETTINGS, listOf("main_menu", "pause_menu", "credits")).project
        assertEquals(setOf("main_menu", "pause_menu", "credits"), viaChips.list(Keys.MENUS_SETTINGS).toSet())
        val delegated = d.handleUserMessage(p, settings, "you choose").project
        assertEquals(Provenance.OWNER_ACCEPTED_RECOMMENDATION, delegated.decision(Keys.MENUS_SETTINGS)!!.prov)
    }

    @Test fun reversalCorrectionsAndPrecedence() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A turn-based tactics game for android").project
        assertTrue(p.list(Keys.GENRE).contains("turn_based_strategy"))
        p = d.handleUserMessage(p, settings, "Actually no, forget turn based. Make it real-time.").project
        assertFalse(p.list(Keys.GENRE).contains("turn_based_strategy"))
        // The owner can reverse again: the latest word wins.
        p = d.handleUserMessage(p, settings, "Wait, I changed my mind. It is a turn-based tactics game after all.").project
        assertTrue(p.list(Keys.GENRE).contains("turn_based_strategy"), p.list(Keys.GENRE).toString())
        assertFalse(p.rejected.values.any { "TURN_BASED" in it })
        // A system inference can never overwrite an owner decision.
        val owner = ProjectOps.setDecision(p, Keys.DIMENSION, "2D", Provenance.OWNER_EXPLICIT, 5L)
        val after = ProjectOps.setDecision(owner, Keys.DIMENSION, "3D", Provenance.SYSTEM_INFERENCE, 6L, DecisionStatus.PROPOSED)
        assertEquals("2D", after.value(Keys.DIMENSION))
        val corrected = ProjectOps.setDecision(owner, Keys.DIMENSION, "3D", Provenance.OWNER_EXPLICIT, 7L)
        assertEquals(Provenance.OWNER_CORRECTION, corrected.decision(Keys.DIMENSION)!!.prov)
        assertEquals("3D", corrected.value(Keys.DIMENSION))
    }

    @Test fun reconcilerDropsDerivedDecisionsButKeepsOwnerOnes() {
        var p = ProjectOps.setDecision(newProject(), Keys.GENRE, "turn_based_strategy", Provenance.OWNER_EXPLICIT, 1L)
        p = ProjectOps.setDecision(p, Keys.COMBAT_MODEL, "tactical_grid", Provenance.OWNER_ACCEPTED_RECOMMENDATION, 2L)
        p = ProjectOps.setDecision(p, Keys.PLATFORMS, "android", Provenance.OWNER_EXPLICIT, 3L)
        val (after, _) = ProjectOps.rejectTag(p, com.hotattic.gamedesigner.core.schema.Tag.TURN_BASED, 4L)
        val r = Reconciler.reconcile(p, ProjectOps.setDecision(after, Keys.GENRE, "platformer", Provenance.OWNER_EXPLICIT, 5L), 6L)
        assertEquals(null, r.project.decision(Keys.COMBAT_MODEL), "derived grid combat is cleared")
        assertEquals("android", r.project.value(Keys.PLATFORMS), "owner decisions survive")
        assertTrue(r.notices.isNotEmpty())
    }

    @Test fun reviewCatchesContradictionsInGeneratedText() {
        var p = ProjectOps.setDecision(newProject(), Keys.GENRE, "platformer", Provenance.OWNER_EXPLICIT, 1L)
        p = ProjectOps.rejectTag(p, com.hotattic.gamedesigner.core.schema.Tag.TURN_BASED, 2L).first
        val bad = "Players plan each turn using squad units on a grid-based tactical map."
        val rep = ConsistencyReview.review(p, bad)
        assertTrue(rep.errors.any { it.code == "turn_language" } && rep.errors.any { it.code == "grid_in_scroller" })
        val quoted = ConsistencyReview.VERBATIM_OPEN + "\n> plan each turn\n" + ConsistencyReview.VERBATIM_CLOSE
        assertTrue(ConsistencyReview.review(p, quoted).clean, "the owner's verbatim block is exempt")
        assertTrue(ConsistencyReview.review(p, "Not turn based: no turn order.").clean, "negated lines are exempt")
        // multi-select truncation
        var q = ProjectOps.setDecision(newProject(), Keys.GENRE, "platformer", Provenance.OWNER_EXPLICIT, 1L)
        q = ProjectOps.setDecision(q, Keys.MENUS_SETTINGS, "main_menu", Provenance.OWNER_EXPLICIT, 2L, raw = "all of those")
        assertTrue(ConsistencyReview.review(q, "").errors.any { it.code == "multi_select_truncated" })
        val (fixed, notes) = ConsistencyReview.resolve(q, 3L)
        assertTrue(notes.isNotEmpty() && fixed.list(Keys.MENUS_SETTINGS).size > 1)
        assertTrue(ConsistencyReview.review(fixed, "").clean)
    }

    @Test fun scriptedLlmInterpretsLooseLanguageAndIsValidated() = runBlocking {
        val llm = FakeLlm("""{"intent":"select","selected":["medium_sessions","not_an_option"],"facts":["The player spends about half an hour to an hour per sitting"]}""")
        val d = director(DirectorDeps(cloud = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        p = d.askAbout(p, Keys.SESSION_STRUCTURE)
        val t = d.handleUserMessage(p, settings, "probably about half an hour, maybe an hour if it's going well")
        assertEquals("medium_sessions", t.project.value(Keys.SESSION_STRUCTURE))
        assertEquals(com.hotattic.gamedesigner.core.engine.InterpreterKind.CLOUD_LLM, t.interpreter)
        // garbage output falls back to rules, never crashes
        val bad = director(DirectorDeps(cloud = FakeLlm("I cannot do that"), clock = FakeClock()))
        val t2 = bad.handleUserMessage(p, settings, "30 to 60 minutes")
        assertEquals("medium_sessions", t2.project.value(Keys.SESSION_STRUCTURE))
    }

    @Test fun rulesOnlyModeIsReported() = runBlocking {
        val d = director()
        assertEquals(com.hotattic.gamedesigner.core.engine.InterpreterKind.RULES, d.interpreterKind())
    }

    @Test fun originalConceptIsPreservedVerbatim() = runBlocking {
        val d = director()
        val p = d.handleUserMessage(d.start(newProject(), settings), settings, concept).project
        assertEquals(concept, p.originalConcept)
        assertTrue(p.activeFacts().any { it.text.contains("two characters", true) })
        assertEquals("2D", p.value(Keys.DIMENSION))
    }
}
