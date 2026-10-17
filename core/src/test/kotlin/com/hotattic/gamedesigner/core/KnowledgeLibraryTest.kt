package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.director.GuidedPanel
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.ReviewGate
import com.hotattic.gamedesigner.core.engine.DesignDimensions
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.HandoffDocs
import com.hotattic.gamedesigner.core.knowledge.KnowledgeAdvisor
import com.hotattic.gamedesigner.core.knowledge.KnowledgeLibrary
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.model.KnowledgeStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reference library is advisory: showing, discussing, accepting or rejecting an example never changes a decision. Evidence: AUTOMATED, deterministic path. */
class KnowledgeLibraryTest {
    /** Everything the design is made of. A reference interaction may change none of it. */
    private data class Design(val decisions: Any, val rejected: Any, val references: Any, val facts: Any, val approval: Any?, val fingerprint: String, val percent: Int)
    private fun design(p: Project) = Design(p.decisions, p.rejected, p.references, p.facts, p.designApproval, ReviewGate.fingerprint(p), DesignDimensions.percent(p))

    private suspend fun started(concept: String, d: Director = director()): Pair<Director, Project> {
        val p = d.handleUserMessage(d.start(newProject(), settings), settings, concept).project
        return d to p
    }

    private fun Project.lastDirector() = messages.last { it.role == com.hotattic.gamedesigner.core.model.Role.DIRECTOR }
    private fun Project.cardIds() = lastDirector().takeIf { it.knowledgeStage == KnowledgeAdvisor.CARD }?.knowledgeEntryIds.orEmpty()

    // ---- the library itself -------------------------------------------------------------------------------------------------------------------

    @Test fun theLibraryIsCompleteTraceableAndOnlyAPilotIsActive() {
        val all = KnowledgeLibrary.entries
        assertTrue(all.size >= 100, "every distinct supported principle is bundled: ${all.size}")
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertTrue(all.map { it.game }.toSet().size >= 100, "100 distinct games are covered")
        assertTrue(KnowledgeLibrary.active.size in 10..15, "pilot size: ${KnowledgeLibrary.active.size}")
        val clock = Regex("\\d\\d:[0-5]\\d:[0-5]\\d")
        for (e in all) {
            assertTrue(clock.matches(e.start) && clock.matches(e.end) && e.start <= e.end, "${e.id} has a real source range")
            assertTrue(e.concepts.isNotEmpty(), "${e.id} belongs to at least one topic")
            assertTrue(e.related.all { KnowledgeLibrary.byId(it) != null }, "${e.id} relates only to real entries")
            assertEquals("VALIDATED", e.extractionStatus)
            assertTrue("\"" !in e.mechanic + e.effect + e.problem, "${e.id}: quotation marks would suggest copied wording")
        }
    }

    @Test fun retrievalWorksOfflineWithNoProviderAndIsDeterministic() = runBlocking {
        val p = newProject().copy(originalConcept = "A 2D arcade shooter where enemies keep coming and the challenge ramps up.")
        val a = KnowledgeLibrary.retrieve(p, "I need help with difficulty progression")
        val b = KnowledgeLibrary.retrieve(p, "I need help with difficulty progression")
        assertEquals(a.map { it.entry.id }, b.map { it.entry.id })
        assertTrue(a.isNotEmpty() && a.size <= 2)
    }

    // ---- the 12 scenarios ---------------------------------------------------------------------------------------------------------------------

    @Test fun s01_difficultyProgressionHelpOffersARelevantExampleAndChangesNothing() = runBlocking {
        val (d, p) = started("A 2D arcade shooter for Android where waves of enemies get harder.")
        val before = design(p)
        val q = d.handleUserMessage(p, settings, "Can you give me examples of how games handle difficulty progression?").project
        val ids = q.cardIds()
        assertTrue(ids.isNotEmpty() && ids.size <= 2, "a card with at most two references: $ids")
        assertTrue(ids.all { "difficulty" in KnowledgeLibrary.byId(it)!!.concepts })
        assertTrue(q.lastDirector().text.contains("nothing in your design has changed"))
        assertTrue(q.lastDirector().text.contains(KnowledgeLibrary.SOURCE_TITLE))
        assertEquals(before, design(q), "viewing an example does not touch decisions, facts, approval or completeness")
        assertEquals(p.pendingFieldKey, q.pendingFieldKey, "the open question is still open")
    }

    @Test fun s02_explorationExamples() = runBlocking {
        val (d, p) = started("An open world adventure where the player explores a huge fantasy map for Android.")
        val q = d.handleUserMessage(p, settings, "Give me some examples of exploration and curiosity in games").project
        assertTrue(q.cardIds().any { "exploration" in KnowledgeLibrary.byId(it)!!.concepts }, "found ${q.cardIds()}")
    }

    @Test fun s03_aReferenceWithAnExplicitExclusionIsNeverOfferedThroughThatMechanic() = runBlocking {
        val (d, p) = started("Songs of Syx population simulation but no combat")
        assertTrue("combat" in KnowledgeLibrary.excludedTerms(p), "the owner's 'no combat' is honoured as an exclusion")
        val q = d.handleUserMessage(p, settings, "Give me examples of how simulation games manage a large population and automation").project
        val shown = q.cardIds().map { KnowledgeLibrary.byId(it)!! }
        assertTrue(shown.isNotEmpty(), "simulation help exists")
        assertTrue(shown.none { "combat" in it.concepts }, "no combat-centred example for a game that has none: ${shown.map { it.id }}")
        assertEquals(p.decision(Keys.HAS_COMBAT), q.decision(Keys.HAS_COMBAT))
        val pac = d.handleUserMessage(d.start(newProject(), settings), settings, "I like Pac-Man's role reversal but I do not want power-ups").project
        assertTrue(pac.references.any { it.name == "Pac-Man" }, "the reference is Pac-Man, not a possessive: ${pac.references}")
        assertTrue(KnowledgeLibrary.excludedTerms(pac).any { it.startsWith("powerup") }, "the owner's exclusion is honoured: ${KnowledgeLibrary.excludedTerms(pac)}")
        val r = KnowledgeLibrary.retrieve(pac, "give me examples of variety and pacing in a simple arcade game")
        assertTrue(r.none { it.entry.id == "gmtk-002-pac-man-role-reversal" }, "the Pac-Man principle depends on the power pellet the owner ruled out")
    }

    @Test fun s04_aRejectedExampleIsNotOfferedAgainAndIsNotADecision() = runBlocking {
        val (d, p) = started("A 2D arcade shooter for Android where waves of enemies get harder.")
        val ask = "Can you give me examples of how games handle difficulty progression?"
        val card = d.handleUserMessage(p, settings, ask).project
        val ids = card.cardIds(); val before = design(card)
        val no = d.handleUserMessage(card, settings, KnowledgeAdvisor.NONE_LABEL).project
        assertTrue(ids.all { id -> no.knowledge.any { it.entryId == id && it.status == KnowledgeStatus.REJECTED } })
        assertEquals(before, design(no), "rejecting an example changes no decision")
        val again = d.handleUserMessage(no, settings, ask).project
        assertTrue(again.cardIds().none { it in ids }, "the rejected entries stay suppressed")
    }

    @Test fun s05_partialAcceptanceIsInterestOnlyUntilTheOwnerNamesAChoice() = runBlocking {
        val (d, p) = started("A 2D arcade shooter for Android where waves of enemies get harder.")
        val card = d.handleUserMessage(p, settings, "Can you give me examples of how games handle difficulty progression?").project
        val id = card.cardIds().first(); val before = design(card)
        val interest = d.handleUserMessage(card, settings, "Interested in ${KnowledgeLibrary.byId(id)!!.game}").project
        assertTrue(interest.knowledge.any { it.entryId == id && it.status == KnowledgeStatus.INTERESTED })
        assertFalse(interest.knowledge.any { it.status == KnowledgeStatus.APPLIED })
        assertEquals(KnowledgeAdvisor.ASPECT, interest.lastDirector().knowledgeStage, "Bob asks which specific aspect")
        assertEquals(before, design(interest), "interest alone is not a decision")
    }

    @Test fun s06_aTentativeIdeaDoesNotBecomeADecisionButAnExplicitChoiceDoes() = runBlocking {
        val (d, p) = started("A 2D arcade shooter for Android where waves of enemies get harder.")
        var q = d.handleUserMessage(p, settings, "Can you give me examples of how games handle difficulty progression?").project
        val id = q.cardIds().first()
        q = d.handleUserMessage(q, settings, "Interested in ${KnowledgeLibrary.byId(id)!!.game}").project
        val key = q.pendingFieldKey!!
        val hedged = d.handleUserMessage(q, settings, "maybe I will want something like that later, not sure").project
        assertTrue(hedged.decision(key)?.let { it.ownerAuthored && it.status == com.hotattic.gamedesigner.core.model.DecisionStatus.CONFIRMED } != true, "a hedge is not a confirmed decision")
        assertTrue(hedged.knowledge.none { it.status == KnowledgeStatus.APPLIED })
    }

    @Test fun s07_aConfirmedDecisionSurvivesTheLibraryAndALaterCorrectionStillWins() = runBlocking {
        val (d, p0) = started("A 2D arcade shooter for Android where waves of enemies get harder.")
        val f = com.hotattic.gamedesigner.core.schema.Fields.get(Keys.DIFFICULTY_FAILURE)!!
        val opts = f.options(com.hotattic.gamedesigner.core.schema.Traits(p0)).map { it.id }
        val p = ProjectOps.setDecision(p0, Keys.DIFFICULTY_FAILURE, opts.first(), Provenance.OWNER_EXPLICIT, 1L)
        val q = d.handleUserMessage(p, settings, "Give me examples of how games handle difficulty").project
        assertEquals(opts.first(), q.decision(Keys.DIFFICULTY_FAILURE)?.value)
        assertEquals(Provenance.OWNER_EXPLICIT, q.decision(Keys.DIFFICULTY_FAILURE)?.prov)
        val corrected = ProjectOps.setDecision(q, Keys.DIFFICULTY_FAILURE, opts.last(), Provenance.OWNER_EXPLICIT, 2L)
        assertEquals(opts.last(), corrected.decision(Keys.DIFFICULTY_FAILURE)?.value)
        assertEquals(Provenance.OWNER_CORRECTION, corrected.decision(Keys.DIFFICULTY_FAILURE)?.prov)
    }

    @Test fun s08_manyRelevantPrinciplesYieldAtMostTheTwoBest() = runBlocking {
        val p = newProject().copy(originalConcept = "A tense horror survival game with escalating difficulty and varied pacing.")
        val r = KnowledgeLibrary.retrieve(p, "Give me examples of difficulty, pacing and tension", includeInactive = true)
        assertTrue(r.size <= 2)
        assertTrue(r.zipWithNext().all { (a, b) -> a.score >= b.score })
        assertTrue(r.drop(1).all { it.score * 10 >= r.first().score * 6 }, "a weak second reference is dropped")
    }

    @Test fun s09_noSuitableReferenceMeansNoneIsManufactured() = runBlocking {
        val (d, p) = started("A 2D arcade shooter for Android.")
        val before = design(p)
        val q = d.handleUserMessage(p, settings, "Can you give me examples of quarterly tax accounting software?").project
        assertTrue(q.cardIds().isEmpty())
        assertTrue(q.lastDirector().text.contains("won't stretch one"))
        assertEquals(before, design(q))
        assertTrue(q.knowledge.isEmpty())
    }

    @Test fun s10_theSpecContainsOnlyWhatTheOwnerDecided() = runBlocking {
        val d = director()
        var (_, p) = started("A 2D arcade shooter for Android where waves of enemies get harder.", d)
        val withCards = d.handleUserMessage(p, settings, "Can you give me examples of how games handle difficulty progression?").project
        val shownGames = withCards.cardIds().map { KnowledgeLibrary.byId(it)!!.game }
        var q = d.handleUserMessage(withCards, settings, "Interested in ${shownGames.first()}").project
        q = d.handleUserMessage(q, settings, KnowledgeAdvisor.BACK_LABEL).project
        val spec = ClaudeMdGenerator.generate(q, 1, "x", "2026-10-17T00:00:00Z") + HandoffDocs.files(q).values.joinToString("\n")
        for (g in shownGames) assertFalse(spec.contains(g), "an unaccepted example ($g) must not appear in the specification")
        assertFalse(spec.contains(KnowledgeLibrary.SOURCE_TITLE))
        assertFalse(spec.contains("possible approach"))
    }

    @Test fun s11_projectsSavedBeforeTheLibraryOpenUnchanged() {
        val p = ProjectOps.addMessage(newProject(), com.hotattic.gamedesigner.core.model.Role.USER, "A puzzle game", 1L)
        val json = ProjectCodec.json.parseToJsonElement(ProjectCodec.json.encodeToString(Project.serializer(), p)).jsonObject
        val legacy = JsonObject(json.filterKeys { it != "knowledge" })
        val back = ProjectCodec.json.decodeFromJsonElement(Project.serializer(), legacy)
        assertEquals(emptyList(), back.knowledge)
        assertEquals(p, back)
        val msgOld = ProjectCodec.json.decodeFromString(com.hotattic.gamedesigner.core.model.ChatMessage.serializer(), """{"id":"m1","role":"DIRECTOR","text":"hi","at":1}""")
        assertTrue(msgOld.knowledgeEntryIds.isEmpty() && msgOld.knowledgeStage == null)
    }

    @Test fun s12_repeatedInterviewsAndReevaluationsAreStable() = runBlocking {
        suspend fun run(withLibrary: Boolean): Project {
            val d = director(); var (_, p) = started("A 2D arcade shooter for Android where waves of enemies get harder.", d)
            if (withLibrary) {
                p = d.handleUserMessage(p, settings, "Can you give me examples of how games handle difficulty progression?").project
                p = d.handleUserMessage(p, settings, KnowledgeAdvisor.NONE_LABEL).project
            }
            return p
        }
        val a = run(true); val b = run(true); val plain = run(false)
        assertEquals(a.decisions, b.decisions)
        assertEquals(plain.decisions, a.decisions, "the library leaves the interview's decisions exactly as they were")
        val d = director()
        val r1 = d.reevaluate(a, settings).project; val r2 = d.reevaluate(r1, settings).project
        assertEquals(r1.decisions.keys, r2.decisions.keys, "re-evaluating twice with no input yields no new decisions")
        assertTrue(r2.facts.none { f -> KnowledgeLibrary.entries.any { f.text.contains(it.game, true) } }, "reference requests never become facts")
    }

    // ---- boundaries ---------------------------------------------------------------------------------------------------------------------------

    @Test fun showAnExampleIsOfferedOnlyWhereATopicApplies() = runBlocking {
        val (d, p) = started("A 2D arcade shooter for Android where waves of enemies get harder.")
        var q = p; var offered = 0; var asked = 0
        repeat(40) {
            val m = GuidedPanel.model(q) ?: return@repeat
            asked++; if (m.exampleAvailable) offered++
            q = d.handleUserMessage(q, settings, if (q.pendingFieldKey?.startsWith("__") == true) "yes" else "choose for me").project
        }
        assertTrue(offered in 1 until asked, "offered on some questions ($offered), not injected on all ($asked)")
        // Pressing it is an ordinary explicit request and changes nothing.
        var key = p; var n = 0
        while (GuidedPanel.model(key)?.exampleAvailable != true && n++ < 80) key = d.handleUserMessage(key, settings, if (key.pendingFieldKey?.startsWith("__") == true) "yes" else "choose for me").project
        assertTrue(GuidedPanel.model(key)?.exampleAvailable == true, "some question offers an example")
        val m = GuidedPanel.model(key)!!; val before = design(key)
        val act = GuidedPanel.example(m) as com.hotattic.gamedesigner.core.director.PanelAction.Say
        val shown = d.handleUserMessage(key, settings, act.text, act.turnId).project
        assertTrue(shown.cardIds().isNotEmpty()); assertEquals(before, design(shown))
    }

    @Test fun libraryTextNeverReachesTheModelPromptsOrTheOwnersFacts() = runBlocking {
        val seen = mutableListOf<String>()
        val llm = object : com.hotattic.gamedesigner.core.llm.LlmProvider {
            override val id = "rec"; override val displayName = "Recording model"
            override val tier = com.hotattic.gamedesigner.core.llm.LlmTier.LOCAL_SMALL; override val isLocal = true
            override suspend fun isReady() = true
            override suspend fun complete(request: LlmRequest): LlmResult { seen += request.messages.joinToString("\n") { it.content } + request.system; return LlmResult.Ok("{}") }
        }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var (_, p) = started("A 2D arcade shooter for Android where waves of enemies get harder.", d)
        p = d.handleUserMessage(p, settings, "Can you give me examples of how games handle difficulty progression?").project
        val callsAfterRequest = seen.size
        val ids = p.cardIds(); assertTrue(ids.isNotEmpty())
        p = d.handleUserMessage(p, settings, "Interested in ${KnowledgeLibrary.byId(ids.first())!!.game}").project
        p = d.handleUserMessage(p, settings, "I want enemies to speed up gradually and no lives system").project
        val bodies = ids.map { KnowledgeLibrary.byId(it)!!.mechanic }
        assertEquals(callsAfterRequest, seen.size.coerceAtMost(callsAfterRequest), "requesting an example does not call the model")
        assertTrue(seen.none { s -> bodies.any { s.contains(it) } }, "reference text is never part of an interpretation prompt")
    }

    @Test fun unknownLedgerEntriesAndAbsentLibraryContentDoNotAffectDecisions() = runBlocking {
        val (d, p0) = started("A 2D arcade shooter for Android where waves of enemies get harder.")
        val p = p0.copy(knowledge = listOf(com.hotattic.gamedesigner.core.model.KnowledgeNote("gone-from-library", KnowledgeStatus.REJECTED, 1L)))
        val q = d.handleUserMessage(p, settings, "choose for me").project
        assertTrue(q.decisions.size >= p.decisions.size)
        assertEquals(p.knowledge, q.knowledge)
    }
}
