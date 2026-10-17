package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.director.GuidedPanel
import com.hotattic.gamedesigner.core.engine.DesignDimensions
import com.hotattic.gamedesigner.core.engine.ReviewGate
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.HandoffDocs
import com.hotattic.gamedesigner.core.knowledge.KnowledgeAdvisor
import com.hotattic.gamedesigner.core.knowledge.KnowledgeLibrary
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.model.KnowledgeStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The second source, a research paper on "vitality structures" (CLIMB, FINAL STRETCH, ALERT). Its ideas are vocabulary for talking about player feelings:
 * never a request for ranks, retention loops or notifications. Evidence: AUTOMATED, deterministic path.
 */
class KnowledgeSecondSourceTest {
    private val paper = KnowledgeLibrary.entries.filter { it.sourceId == "karhulahti2024" }
    private data class Design(val decisions: Any, val rejected: Any, val references: Any, val facts: Any, val approval: Any?, val fingerprint: String, val percent: Int)
    private fun design(p: Project) = Design(p.decisions, p.rejected, p.references, p.facts, p.designApproval, ReviewGate.fingerprint(p), DesignDimensions.percent(p))
    private fun Project.lastDirector() = messages.last { it.role == Role.DIRECTOR }
    private suspend fun started(d: com.hotattic.gamedesigner.core.director.Director = director()) =
        d to d.handleUserMessage(d.start(newProject(), settings), settings, "A calm 2D puzzle game for Android about restoring a garden.").project

    @Test fun theThreeConstructsAndTheDimensionalFrameworkAreAllCaptured() {
        val names = paper.map { it.game }
        for (c in listOf("CLIMB", "FINAL STRETCH", "ALERT")) assertTrue(names.any { it.startsWith(c) }, "$c is present")
        assertTrue(names.any { it.contains("Micro", true) && it.contains("macro", true) }, "time scales")
        assertTrue(names.any { it.contains("Self", true) && it.contains("avatar", true) }, "spatial perspective")
        assertTrue(names.any { it.contains("Past", true) && it.contains("future", true) }, "temporal perspective")
        assertTrue(names.any { it.contains("Design and experience", true) }, "the design-experience pairing")
        assertTrue(paper.any { it.classification == "SOURCE_CAVEAT" }, "the paper's limits are an entry of their own")
        assertEquals(setOf("SOURCE_CONSTRUCT", "SOURCE_FRAMEWORK", "SOURCE_OBSERVATION", "SOURCE_CAVEAT"), paper.map { it.classification }.toSet())
    }

    @Test fun everyEntryIsAttributedToASectionAndSeparatesTheSourceFromBobsAnalysis() {
        val sections = setOf("Abstract", "Terminology", "Design", "Theory", "Vitality Structures", "Methods", "Analysis", "Analysis: CLIMB", "Analysis: FINAL STRETCH", "Analysis: ALERT", "Discussion", "Ontology", "Conclusions")
        for (e in paper) {
            assertEquals("Vitality structures in 'addictive' game design", e.sourceTitle)
            assertEquals("Veli-Matti Karhulahti", e.sourceCreator)
            assertTrue(e.sourceKind.contains("10.12688/openreseurope.17177.2"), "DOI recorded")
            assertTrue(e.start in sections && e.end.isBlank(), "${e.id}: a section reference, not a timestamp: ${e.start}")
            assertTrue(!e.inference.isNullOrBlank() && !e.tradeoffs.isNullOrBlank(), "${e.id}: states tradeoffs and labels its own analysis")
            assertTrue(e.inference!!.startsWith("Bob's reading"), "${e.id}: Bob's inference is marked as Bob's")
            assertTrue(e.explicitOnly)
            assertFalse(Regex("(?i)\\b(proves?|causes? (?:addiction|gaming)|is the cause|are the cause)\\b").containsMatchIn(e.problem + e.mechanic + e.effect + e.tradeoffs), "${e.id} must not claim causation")
        }
        for (e in paper.filter { it.classification == "SOURCE_CONSTRUCT" })
            assertTrue(Regex("(?i)hypothes|does not show|not .*prove|not .*established|only as something to test").containsMatchIn(e.tradeoffs!!), "${e.id} keeps the paper's evidence status")
    }

    @Test fun anExplicitRequestRetrievesTheRightSourceWithItsStatusAndCaveat() = runBlocking {
        val (d, p) = started()
        val q = d.handleUserMessage(p, settings, "Give me examples of a ranked ladder and the climb the ranks feeling").project
        val ids = q.lastDirector().knowledgeEntryIds
        assertTrue("karh-001-climb" in ids, "CLIMB retrieved: $ids")
        val text = q.lastDirector().text
        assertTrue(text.contains("Veli-Matti Karhulahti") && text.contains("Analysis: CLIMB"))
        assertTrue(text.contains("Source status:") && text.contains("does not establish a cause"))
        assertTrue(text.contains("not a request to add any mechanic"))
        assertTrue(text.contains("My own analysis (not from the source):"))
        assertTrue(text.contains("Risks and limits:"))
        assertTrue(ids.size <= 2)
        val dims = d.handleUserMessage(p, settings, "Give me an example of vitality structures with a time scale, micro meso macro").project.lastDirector().knowledgeEntryIds
        assertTrue("karh-004-chronotope-scale" in dims, "dimension entry retrieved: $dims")
    }

    @Test fun readingInterestOrRejectingTheseConceptsChangesNothingAndAddsNoMechanic() = runBlocking {
        val (d, p) = started()
        val before = design(p)
        var q = d.handleUserMessage(p, settings, "Give me examples of a final stretch, near completion and retention").project
        assertTrue(q.lastDirector().knowledgeEntryIds.isNotEmpty())
        assertEquals(before, design(q))
        val id = q.lastDirector().knowledgeEntryIds.first()
        q = d.handleUserMessage(q, settings, "Interested in ${KnowledgeLibrary.byId(id)!!.game}").project
        assertTrue(q.knowledge.any { it.entryId == id && it.status == KnowledgeStatus.INTERESTED })
        assertEquals(before, design(q), "interest in FINAL STRETCH is not a retention loop")
        assertEquals(null, q.decision(Keys.HAS_PROGRESSION), "no progression system was introduced")
        q = d.handleUserMessage(q, settings, "none of it").project
        assertTrue(q.knowledge.any { it.entryId == id && it.status == KnowledgeStatus.REJECTED })
        val again = d.handleUserMessage(q, settings, "Give me examples of a final stretch, near completion and retention").project
        assertFalse(id in again.lastDirector().knowledgeEntryIds, "rejected paper entries stay suppressed")
        val spec = ClaudeMdGenerator.generate(q, 1, "x", "2026-10-17T00:00:00Z") + HandoffDocs.files(q).values.joinToString("\n")
        for (w in listOf("FINAL STRETCH", "CLIMB", "ALERT")) assertFalse(spec.contains(w), "'$w' must not reach the specification")
        for (w in listOf("Karhulahti", "vitality", "retention loop")) assertFalse(spec.contains(w, ignoreCase = true), "'$w' must not reach the specification")
    }

    @Test fun theseConceptsAreNeverOfferedUnlessTheOwnerAsks() = runBlocking {
        val (d, p) = started()
        // The optional button never offers them, whatever the question.
        val keys = com.hotattic.gamedesigner.core.schema.Fields.all.map { it.key }
        for (k in keys) assertTrue(KnowledgeLibrary.retrieve(p, "", k, panel = true).none { it.entry.explicitOnly }, "button on $k")
        // Ordinary design requests are answered from the other source.
        for (ask in listOf("Give me examples of difficulty progression", "Give me examples of exploration and curiosity", "Give me examples of a tutorial and onboarding", "I need help with progression and unlocks"))
            assertTrue(KnowledgeLibrary.retrieve(p, ask).none { it.entry.sourceId == "karhulahti2024" }, "ordinary request surfaced the paper: $ask")
        // A game concept that merely uses the words never triggers a card.
        val q = d.handleUserMessage(p, settings, "I want a game with ranked ladders and daily rewards and notifications").project
        assertTrue(q.messages.none { it.knowledgeStage == KnowledgeAdvisor.CARD }, "a feature request is not a request for reference material")
    }

    @Test fun nothingFromThePaperReachesTheModelOrBecomesAFact() = runBlocking {
        val seen = mutableListOf<String>()
        val llm = object : com.hotattic.gamedesigner.core.llm.LlmProvider {
            override val id = "rec"; override val displayName = "Recording model"
            override val tier = com.hotattic.gamedesigner.core.llm.LlmTier.LOCAL_SMALL; override val isLocal = true
            override suspend fun isReady() = true
            override suspend fun complete(request: LlmRequest): LlmResult { seen += request.messages.joinToString("\n") { it.content } + request.system; return LlmResult.Ok("{}") }
        }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var (_, p) = started(d)
        p = d.handleUserMessage(p, settings, "Give me examples of a ranked ladder and the climb the ranks feeling").project
        val bodies = paper.flatMap { listOf(it.mechanic, it.effect, it.inference.orEmpty()) }
        p = d.handleUserMessage(p, settings, "Interested in CLIMB (vitality structure)").project
        p = d.handleUserMessage(p, settings, "I want the player to feel they earned each new garden").project
        assertTrue(seen.none { s -> bodies.any { s.contains(it) } }, "paper text never appears in a model prompt")
        val r = d.reevaluate(p, settings).project
        assertTrue(r.facts.none { Regex("(?i)ladder|climb the ranks|vitality").containsMatchIn(it.text) }, "an example request never becomes an owner fact")
    }

    @Test fun theLibraryStillSupportsBothSourcesAndOldProjects() {
        assertEquals(setOf("gmtk", "karhulahti2024"), KnowledgeLibrary.entries.map { it.sourceId }.toSet())
        assertTrue(KnowledgeLibrary.entries.size >= 112)
        assertTrue(KnowledgeLibrary.entries.all { KnowledgeLibrary.byId(it.id) != null && it.related.all { r -> KnowledgeLibrary.byId(r) != null } })
        val sameSourceRefs = paper.flatMap { it.related }.all { r -> KnowledgeLibrary.byId(r)!!.sourceId == "karhulahti2024" }
        assertTrue(sameSourceRefs, "relations never mix the paper's constructs into the other source's principles")
    }
}
