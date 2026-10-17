package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.DecisionHistory
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.ReviewGate
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.HandoffDocs
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.knowledge.KnowledgeLibrary
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.FactStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.persist.FileProjectStore
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.persist.ProjectTransfer
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Traits
import com.hotattic.gamedesigner.core.session.ProjectSession
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Audit of the data path: owner words -> interpretation -> decisions -> specification -> reevaluation -> export/import.
 * Synthetic projects only. Evidence: AUTOMATED (rules only, no model, no network).
 */
class DataArchitectureAuditTest {
    private val concept = "I like Pac-Man's role reversal but I do not want power-ups. A calm 2D puzzle game for Android about restoring a garden."

    /** A project that has been through a real conversation, an approval, a correction, a rejection and a generated spec. */
    private suspend fun rich(): Pair<Project, List<String>> {
        val d = director(); val sent = mutableListOf<String>()
        suspend fun say(p: Project, t: String): Project { sent += t; return d.handleUserMessage(p, settings, t).project }
        var p = say(d.start(newProject(), settings), concept)
        repeat(60) { val k = p.pendingFieldKey ?: return@repeat; p = say(p, when { k == "__proposals__" -> "yes"; k == "__asset_plan__" -> "looks good"; k == "__review__" -> "looks right"; k.startsWith("__conflict") -> "use alternative 1"; k == "__ready__" -> return@repeat; else -> "choose for me" }) }
        val g = SpecVersioning.generate(p, VersionKind.INITIAL, p.updatedAt + 1, "2026-10-17T00:00:00Z")
        return g.project to sent
    }

    @Test fun ownerWordsStayVerbatimAndRecoverableThroughEveryHop() = runBlocking {
        val (p, sent) = rich()
        assertEquals(concept, p.originalConcept)
        assertEquals(sent, p.messages.filter { it.role == Role.USER }.map { it.text }, "every owner message is stored exactly as typed, in order")
        val root = Files.createTempDirectory("gd-audit").toFile(); val store = FileProjectStore(root); store.save(p)
        assertEquals(p, store.load(p.id)); assertEquals(sent, store.load(p.id)!!.messages.filter { it.role == Role.USER }.map { it.text })
        val imported = FileProjectStore(Files.createTempDirectory("gd-audit2").toFile()).also { ProjectTransfer.import(it, ProjectTransfer.createBackup(store, "t", 1L)) }.load(p.id)
        assertEquals(p, imported, "backup and import preserve every authoritative field")
        val d = director(); val r2 = d.reevaluate(d.reevaluate(p, settings).project, settings).project
        assertEquals(sent, r2.messages.filter { it.role == Role.USER }.map { it.text }, "reevaluating never edits the owner's words")
        assertEquals(concept, r2.originalConcept)
    }

    @Test fun approvalsRejectionsExclusionsAndOpenQuestionsLiveInSeparateRecords() = runBlocking {
        val (p, _) = rich()
        assertTrue(p.references.any { it.name == "Pac-Man" && "power-ups" in it.excluded }, "exclusion: on the reference")
        val rej = ProjectOps.reject(p, Keys.GENRE, listOf("platformer"), 5L)
        assertTrue("platformer" in rej.rejected[Keys.GENRE].orEmpty(), "rejection: its own map")
        val ap = ReviewGate.approve(rej, 6L, "owner")
        assertNotNull(ap.designApproval); assertTrue(ReviewGate.approved(ap), "approval: bound to a fingerprint of what was reviewed")
        assertFalse(ReviewGate.approved(ProjectOps.setDecision(ap, Keys.ORIENTATION, "portrait", Provenance.OWNER_EXPLICIT, 7L)), "a later change invalidates the approval instead of silently riding on it")
        val fresh = newProject(); assertTrue(com.hotattic.gamedesigner.core.engine.CompletenessEngine.compute(fresh).missingRequired.isNotEmpty(), "unresolved questions are computed, not stored as decisions")
        assertTrue(p.decisions.values.any { it.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION } || p.decisions.values.any { it.prov == Provenance.DEFAULT } || p.decisions.isNotEmpty())
    }

    @Test fun aSupersededOwnerDecisionIsPreservedButNeverActive() = runBlocking {
        val p0 = newProject(); val f = Fields.get(Keys.ORIENTATION)!!
        val ids = f.options(Traits(p0)).map { it.id }; val a = ids.first(); val b = ids.last()
        val saved = mutableListOf<Project>()
        val session = ProjectSession { saved += it }; session.open(p0)
        session.mutate { p -> ProjectOps.setDecision(p, Keys.ORIENTATION, a, Provenance.OWNER_EXPLICIT, 10L, raw = "I want ${a}.") to Unit }
        session.mutate { p -> ProjectOps.setDecision(p, Keys.ORIENTATION, b, Provenance.OWNER_EXPLICIT, 20L, raw = "No, make it $b.") to Unit }
        val now = session.current.value!!
        assertEquals(b, now.decision(Keys.ORIENTATION)!!.value); assertEquals(Provenance.OWNER_CORRECTION, now.decision(Keys.ORIENTATION)!!.prov)
        val h = now.decisionHistory.single()
        assertEquals(a, h.value); assertEquals(b, h.replacedBy); assertEquals("I want ${a}.", h.rawAnswer); assertEquals(Provenance.OWNER_EXPLICIT, h.provenance)
        assertTrue(Fields.get(Keys.ORIENTATION) != null && now.decisions.values.none { it.value == a && it.updatedAt == 10L }, "the old value is not an active decision")
        // history never reaches the specification or readiness
        val without = now.copy(decisionHistory = emptyList())
        assertEquals(ClaudeMdGenerator.generate(without, 1, "x", "2026-10-17T00:00:00Z"), ClaudeMdGenerator.generate(now, 1, "x", "2026-10-17T00:00:00Z"))
        assertEquals(HandoffDocs.files(without), HandoffDocs.files(now))
        assertEquals(ReviewGate.fingerprint(without), ReviewGate.fingerprint(now))
        assertEquals(com.hotattic.gamedesigner.core.engine.DesignDimensions.percent(without), com.hotattic.gamedesigner.core.engine.DesignDimensions.percent(now))
        // a removed decision (the owner rules the whole system out) is kept too, and recording twice adds nothing
        val gone = ProjectOps.clearDecision(now, Keys.ORIENTATION, 30L)
        val rec = DecisionHistory.record(now, gone); assertEquals(2, rec.decisionHistory.size); assertNull0(rec.decisionHistory.last().replacedBy)
        assertEquals(rec, DecisionHistory.record(now, rec.copy(decisions = gone.decisions)))
    }

    private fun assertNull0(x: Any?) = assertTrue(x == null)

    @Test fun ownerRulingASystemOutKeepsWhatDependedOnIt() = runBlocking {
        val d = director()
        var p = d.handleUserMessage(d.start(newProject(), settings), settings, "A 2D platformer for Android.").project
        val opt = Fields.get(Keys.COMBAT_MODEL)!!.options(Traits(p)).first().id
        p = ProjectOps.setDecision(p, Keys.HAS_COMBAT, "yes", Provenance.OWNER_EXPLICIT, 40L)
        p = ProjectOps.setDecision(p, Keys.COMBAT_MODEL, opt, Provenance.OWNER_EXPLICIT, 50L, raw = "real time sword fights")
        val session = ProjectSession { }; session.open(p)
        session.mutate { x -> d.handleUserMessage(x, settings, "Actually, there is no combat at all, it is peaceful.").project to Unit }
        val after = session.current.value!!
        assertEquals("no", after.decision(Keys.HAS_COMBAT)?.value); assertNull0(after.decision(Keys.COMBAT_MODEL))
        assertTrue(after.decisionHistory.any { it.key == Keys.COMBAT_MODEL && it.rawAnswer == "real time sword fights" && it.replacedBy == null }, "the owner's earlier combat choice survives as history: ${after.decisionHistory}")
        assertTrue(after.decisionHistory.any { it.key == Keys.HAS_COMBAT && it.value == "yes" && it.replacedBy == "no" })
    }

    @Test fun repeatedReevaluationWithoutNewInputChangesNothingTheOwnerDecided() = runBlocking {
        val (p, _) = rich(); val d = director()
        val r1 = d.reevaluate(p, settings).project; val r2 = d.reevaluate(r1, settings).project; val r3 = d.reevaluate(r2, settings).project
        fun owner(x: Project) = x.decisions.filterValues { it.ownerAuthored || it.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION }
        // Bob's own defaults are re-derived (and re-stamped) on every run; what they say and who decided them must not move.
        fun shape(x: Project) = x.decisions.mapValues { Triple(it.value.value, it.value.prov, it.value.status) }
        assertEquals(owner(p), owner(r1)); assertEquals(owner(r1), owner(r2)); assertEquals(owner(r2), owner(r3))
        assertEquals(p.decisions.filterValues { it.ownerAuthored }.mapValues { it.value.value }, r3.decisions.filterValues { it.ownerAuthored }.mapValues { it.value.value }.filterKeys { it in p.decisions })
        assertEquals(shape(r1), shape(r2), "no new input, no new decisions"); assertEquals(shape(r2), shape(r3))
        assertEquals(p.rejected, r3.rejected); assertEquals(p.references, r3.references)
        assertEquals(p.facts.filter { it.status == FactStatus.ACTIVE }.map { it.text }, r3.facts.filter { it.status == FactStatus.ACTIVE }.map { it.text })
        assertEquals(p.versions, r3.versions, "earlier specification versions are untouched")
        assertEquals(p.decisionHistory, r3.decisionHistory)
        // and discarding the reevaluation restores the design exactly
        val back = d.discardReevaluation(r1)
        assertEquals(p.decisions, back.decisions); assertEquals(p.facts, back.facts)
    }

    @Test fun reevaluationReadsTheOriginalRecordsNotTheLatestGeneratedSpec() = runBlocking {
        val (p, _) = rich()
        // corrupt the generated text of the saved spec: if reevaluation parsed the spec it would change; it must not care
        val poisoned = p.copy(versions = p.versions.map { it.copy(claudeMd = "# nothing useful", masterPrompt = "") })
        val d = director()
        val fromPoisoned = d.reevaluate(poisoned, settings).project; val fromReal = d.reevaluate(p, settings).project
        assertEquals(fromReal.decisions.mapValues { Triple(it.value.value, it.value.prov, it.value.status) }, fromPoisoned.decisions.mapValues { Triple(it.value.value, it.value.prov, it.value.status) }); assertEquals(fromReal.references, fromPoisoned.references)
    }

    @Test fun referenceLibraryKnowledgeNeverMixesWithAuthoritativeRecords() = runBlocking {
        val d = director(); var p = d.handleUserMessage(d.start(newProject(), settings), settings, "A calm 2D puzzle game for Android about gardens.").project
        p = d.handleUserMessage(p, settings, "Can you give me examples of how games handle difficulty progression?").project
        val bodies = KnowledgeLibrary.entries.flatMap { listOf(it.mechanic, it.effect) }
        val authoritative = (p.decisions.values.map { it.value + it.rawAnswer + it.note } + p.facts.map { it.text } + p.references.flatMap { listOf(it.name, it.summary) + it.aspects + it.excluded } + p.originalConcept).joinToString("\n")
        assertTrue(bodies.none { authoritative.contains(it) }, "library text appears in no decision, fact, reference or concept")
        assertTrue(p.knowledge.isNotEmpty(), "the ledger is its own record")
        val owner = p.messages.filter { it.role == Role.USER }.joinToString("\n") { it.text }
        assertTrue(bodies.none { owner.contains(it) })
    }

    @Test fun backupsCarryTheNewRecordsAndOlderFilesStillOpen() = runBlocking {
        val (p, _) = rich(); val withHistory = p.copy(decisionHistory = listOf(com.hotattic.gamedesigner.core.model.DecisionRevision("k", "v", Provenance.OWNER_EXPLICIT, DecisionStatus.CONFIRMED, 1L, 2L, "words", null)))
        val s = FileProjectStore(Files.createTempDirectory("gd-audit3").toFile()); s.save(withHistory)
        val r = FileProjectStore(Files.createTempDirectory("gd-audit4").toFile()); ProjectTransfer.import(r, ProjectTransfer.createBackup(s, "t", 1L))
        assertEquals(withHistory, r.load(p.id))
        val spec = ExportPackage.zip(ExportPackage.files(withHistory, withHistory.versions.last()))
        val r2 = FileProjectStore(Files.createTempDirectory("gd-audit5").toFile()); ProjectTransfer.import(r2, spec)
        assertEquals(withHistory, r2.load(p.id), "a spec package carries the complete project too")
        // a project saved before decisionHistory existed
        val legacy = ProjectCodec.encode(withHistory).lines().filterNot { it.contains("\"decisionHistory\"") }.joinToString("\n")
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(ProjectCodec.encode(withHistory)).let { it as kotlinx.serialization.json.JsonObject }
        val old = kotlinx.serialization.json.JsonObject(parsed - "decisionHistory" - "knowledge")
        assertEquals(emptyList(), ProjectCodec.decode(old.toString()).decisionHistory); assertTrue(legacy.isNotBlank())
    }

    @Test fun fieldsThatThisBuildDoesNotKnowSurviveASaveAfterAnRollback() {
        val root = Files.createTempDirectory("gd-audit6").toFile(); val store = FileProjectStore(root)
        val p = newProject(); store.save(p)
        val f = File(store.projectDir(p.id), "project.json")
        f.writeText(f.readText().replaceFirst("{", "{\n  \"fieldFromANewerLayer\": {\"keep\": [1, 2, 3]},"))
        val loaded = store.load(p.id)!!; store.save(loaded.copy(name = "Renamed"))
        val onDisk = f.readText()
        assertTrue(onDisk.contains("fieldFromANewerLayer") && onDisk.contains("\"keep\""), "an older layer saving the project must not erase a newer layer's field")
        assertEquals("Renamed", store.load(p.id)!!.name)
    }
}
