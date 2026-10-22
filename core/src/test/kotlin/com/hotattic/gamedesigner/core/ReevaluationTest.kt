package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.engine.Reevaluation
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.DecisionSource
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReevaluationTest {
    private val concept = "A top-down action roguelike for my Android phone about surviving a dangerous city. I fight with a pistol and flares."

    /** A finished, approved design with spec v1 - what a saved project like "Songs Syx" is. */
    private suspend fun savedProject(d: Director = director()): Project {
        val (p, ready) = driveToReady(d, newProject(), concept)
        assertTrue(ready)
        return SpecVersioning.createVersion(p, VersionKind.INITIAL, 1_700_000_100_000L, "2026-10-01")
    }

    private suspend fun answerUntilGenerate(d: Director, start: Project): Pair<Project, Boolean> {
        var p = start
        repeat(120) {
            val pending = p.pendingFieldKey
            val reply = when {
                pending == "__proposals__" -> "yes"; pending == "__asset_plan__" -> "looks good"
                pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__review__" -> "looks right"; pending == "__ready__" -> "generate"
                pending == "__more_refs__" -> "done"
                else -> "choose for me"
            }
            val t = d.handleUserMessage(p, settings, reply)
            p = t.project
            if (t.action == DirectorAction.GenerateSpec) return p to true
        }
        return p to false
    }

    @Test fun auditReportsWhatIsReallyStored() = runBlocking {
        val p = savedProject()
        val a = Reevaluation.audit(p)
        assertEquals("VERBATIM", a.fidelity)
        assertTrue(a.ownerMessages > 0 && a.directorMessages > 0 && a.verbatimConcept && a.specVersions == 1)
        assertEquals(p.decisions.size, a.decisionsTotal)
        assertTrue(a.withRawAnswer > 0)
    }

    @Test fun oldProjectCanBeReevaluatedAndOwnerWordsAndDecisionsAreUntouched() = runBlocking {
        val d = director(); val old = savedProject(d)
        val ownerBefore = old.decisions.filterValues { it.ownerAuthored }
        val textsBefore = old.messages.filter { it.role == Role.USER }.map { it.text }
        val turn = d.reevaluate(old, settings)
        val p = turn.project
        val r = assertNotNull(p.reeval); assertEquals("OPEN", r.status); assertEquals(1, r.fromSpec)
        assertTrue(r.preservedOwner > 0)
        // owner decisions: identical value AND provenance
        for ((k, dec) in ownerBefore) { assertEquals(dec.value, p.decision(k)?.value, k); assertEquals(dec.prov, p.decision(k)?.prov, k) }
        // verbatim owner input is preserved (history only grows)
        assertEquals(textsBefore, p.messages.filter { it.role == Role.USER }.map { it.text }.take(textsBefore.size))
        assertEquals(old.originalConcept, p.originalConcept)
        assertTrue(p.messages.last().text.startsWith("REEVALUATION COMPLETE") || p.messages.any { it.text.startsWith("REEVALUATION COMPLETE") })
        assertNull(p.designApproval, "the changed design must be approved again")
    }

    @Test fun explicitAndCorrectionDecisionsOutrankNewerInferenceEvenFromAModel() = runBlocking {
        val old0 = savedProject()
        val prov = Provenance.OWNER_EXPLICIT
        var old = com.hotattic.gamedesigner.core.engine.ProjectOps.setDecision(old0, Keys.DIMENSION, "2D", prov, 5L)
        old = com.hotattic.gamedesigner.core.engine.ProjectOps.setDecision(old, Keys.DIMENSION, "2.5D", prov, 6L)   // becomes OWNER_CORRECTION
        assertEquals(Provenance.OWNER_CORRECTION, old.decision(Keys.DIMENSION)!!.prov)
        old = old.copy(messages = old.messages + com.hotattic.gamedesigner.core.model.ChatMessage("mx", Role.USER, "It should also be a fully 3D world with a first person camera and lots of shooting.", 7L))
        // a scripted local model that eagerly proposes 3D
        val llm = object : com.hotattic.gamedesigner.core.llm.LlmProvider {
            override val id = "s"; override val displayName = "Scripted"; override val tier = com.hotattic.gamedesigner.core.llm.LlmTier.LOCAL_SMALL; override val isLocal = true
            override suspend fun isReady() = true
            override suspend fun complete(request: com.hotattic.gamedesigner.core.llm.LlmRequest) = com.hotattic.gamedesigner.core.llm.LlmResult.Ok("""{"intent":"freeform","edits":{"dimension":"3D"},"facts":["fully 3D world"]}""")
        }
        val p = director(DirectorDeps(local = llm, clock = FakeClock())).reevaluate(old, settings).project
        assertEquals("2.5D", p.value(Keys.DIMENSION)); assertEquals(Provenance.OWNER_CORRECTION, p.decision(Keys.DIMENSION)!!.prov)
    }

    @Test fun oldBobInferencesAreReconsideredButAcceptedRecommendationsAreKeptAndFlagged() = runBlocking {
        val d = director(); val old0 = savedProject(d)
        val stale = Decision("ancient_value", DecisionSource.INFERRED, DecisionStatus.CONFIRMED, updatedAt = 3L, provenance = Provenance.DEFAULT)
        val sf = com.hotattic.gamedesigner.core.schema.Fields.get(Keys.SESSION_STRUCTURE)!!
        val tr = com.hotattic.gamedesigner.core.schema.Traits(old0.copy(decisions = old0.decisions - Keys.SESSION_STRUCTURE))
        val oldChoice = sf.options(tr).map { it.id }.first { it != sf.suggest(tr)?.value }
        val accepted = Decision(oldChoice, DecisionSource.DIRECTOR_CHOICE, DecisionStatus.CONFIRMED, updatedAt = 3L, provenance = Provenance.OWNER_ACCEPTED_RECOMMENDATION)
        val old = old0.copy(decisions = old0.decisions + (Keys.PERFORMANCE to stale) + (Keys.SESSION_STRUCTURE to accepted))
        val p = d.reevaluate(old, settings).project
        assertNotEquals("ancient_value", p.value(Keys.PERFORMANCE), "old Bob's default is recomputed")
        assertEquals(oldChoice, p.value(Keys.SESSION_STRUCTURE), "what the owner accepted is kept")
        assertTrue(p.reeval!!.items.any { it.kind == "KEPT_REC" && it.key == Keys.SESSION_STRUCTURE }, "newer recommendation shown beside it")
    }

    @Test fun answeredQuestionsAreNotAskedAgainAndMissingOnesAreTargeted() = runBlocking {
        val d = director(); val old = savedProject(d)
        // the current design logic knows a question the saved design never had to answer
        val gap = old.copy(decisions = old.decisions - Keys.OTA_UPDATES - Keys.NETWORK_POLICY)
        val p = d.reevaluate(gap, settings).project
        val r = p.reeval!!
        val ownerKeys = old.decisions.filterValues { it.ownerAuthored && it.status == DecisionStatus.CONFIRMED && it.value.isNotBlank() }.keys
        val asked = (r.items.filter { it.kind == "NEEDS_DECISION" }.map { it.key } + listOfNotNull(p.pendingFieldKey))
        assertTrue(asked.none { it in ownerKeys }, "never re-asks an owner-answered question: $asked")
        if (r.newQuestions > 0) assertTrue(p.pendingFieldKey != null)
    }

    @Test fun anEmptyishLegacyProjectGetsTargetedQuestionsAndNothingIsFabricated() = runBlocking {
        val bare = newProject().copy(id = "legacy", name = "Old one")
        val p = director().reevaluate(bare, settings).project
        // nothing stored -> nothing to reevaluate, nothing invented
        assertTrue(p.decisions.values.none { it.ownerAuthored })
        assertEquals("STRUCTURED_ONLY", Reevaluation.audit(bare).fidelity)
    }

    @Test fun legacyJsonWithoutProvenanceOrConceptLoadsAndIsMarkedUncertain() = runBlocking {
        val json = """{"id":"old1","name":"Old","createdAt":1,"updatedAt":2,
          "decisions":{"genre":{"value":"action_roguelite","source":"USER","updatedAt":1},"dimension":{"value":"2D","source":"INFERRED","status":"PROPOSED","updatedAt":1}},
          "messages":[{"id":"m1","role":"USER","text":"I want a small action roguelite on my phone","at":1}]}"""
        val p = ProjectCodec.decode(json)
        val a = Reevaluation.audit(p)
        assertEquals(2, a.legacyProvenance); assertEquals("VERBATIM", a.fidelity); assertTrue(!a.verbatimConcept)
        val out = director().reevaluate(p, settings).project
        assertTrue(out.reeval!!.source.conceptRecovered)
        assertEquals("I want a small action roguelite on my phone", out.originalConcept)       // recovered verbatim from the stored message
        assertEquals(Provenance.OWNER_EXPLICIT, out.decision("genre")!!.prov)                  // legacy USER stays what it was, not upgraded to a correction
        assertTrue(out.decision("dimension")?.ownerAuthored != true)                            // a legacy inference is never promoted to owner authority
    }

    @Test fun oldSpecIsNeverTouchedAndApprovalCreatesTheNextVersionFromTheReevaluatedState() = runBlocking {
        val d = director(); val old0 = savedProject(d)
        val v1 = old0.versions.single().copy(claudeMd = "OLD GENERATOR OUTPUT " + old0.versions.single().claudeMd)
        val old = old0.copy(versions = listOf(v1))
        var p = d.reevaluate(old, settings).project
        assertEquals(listOf(v1), p.versions, "starting a reevaluation changes no spec version")
        val (done, ready) = answerUntilGenerate(d, p); assertTrue(ready)
        p = SpecVersioning.createVersion(done, SpecVersioning.suggestedKind(done), 1_700_000_900_000L, "2026-10-06")
        assertEquals(2, p.versions.size)
        assertEquals(v1, p.versions[0], "Spec v1 is unchanged and recoverable")
        val v2 = p.versions[1]
        assertEquals("Reevaluation", v2.label)
        assertTrue("OLD GENERATOR OUTPUT" !in v2.claudeMd && "PART A - OWNER REQUIREMENTS" in v2.claudeMd && "PART E - MUST NOT CHANGE" in v2.claudeMd)
        assertTrue(v2.masterPrompt.isNotBlank())
        assertEquals("APPROVED", p.reeval!!.status); assertEquals(2, p.reeval!!.approvedSpec)
        assertTrue(SpecVersioning.diff(v1, v2).isNotEmpty() || v1.claudeMd != v2.claudeMd)
    }

    @Test fun uploadedAssetAssociationsSurvive() = runBlocking {
        val d = director(); var old = savedProject(d)
        val asset = com.hotattic.gamedesigner.core.model.BrandingAsset("icon", com.hotattic.gamedesigner.core.model.BrandingMode.UPLOADED, "branding/x.png", "x.png", "abc123", 64, 64, 1L)
        old = old.copy(branding = old.branding + ("icon" to asset), assets = old.assets)
        val p = d.reevaluate(old, settings).project
        assertEquals(asset, p.branding["icon"])
        assertEquals(old.assets, p.assets.take(old.assets.size))
    }

    @Test fun reevaluationSurvivesRestartAndCanBeDiscardedExactly() = runBlocking {
        val d = director(); val old = savedProject(d)
        val mid = d.reevaluate(old, settings).project
        val restored = ProjectCodec.decode(ProjectCodec.encode(mid))
        assertEquals(mid.reeval, restored.reeval); assertEquals(mid.decisions, restored.decisions)
        val (done, ready) = answerUntilGenerate(d, restored)    // continues after the "restart"
        assertTrue(ready)
        // discard from a fresh reevaluation restores the saved design exactly
        val again = d.reevaluate(old, settings).project
        val back = d.discardReevaluation(again)
        assertEquals(old.decisions, back.decisions); assertEquals(old.facts, back.facts); assertEquals(old.versions, back.versions)
        assertEquals(old.designApproval, back.designApproval); assertEquals("DISCARDED", back.reeval!!.status)
    }

    @Test fun rerunningReevaluationIsIdempotentAndDoesNotStackBaselines() = runBlocking {
        val d = director(); val old = savedProject(d)
        val a = d.reevaluate(old, settings).project
        val b = d.reevaluate(a, settings).project
        assertEquals(a.reeval!!.baseline, b.reeval!!.baseline)
        assertEquals(old.decisions.filterValues { it.ownerAuthored }.mapValues { it.value.value }, b.decisions.filterValues { it.ownerAuthored }.filterKeys { it in old.decisions }.mapValues { it.value.value }.filterKeys { it in old.decisions.filterValues { d -> d.ownerAuthored }.keys })
    }

    @Test fun usesCurrentDesignLogicNewQuestionIsSurfacedForOldDesigns() = runBlocking {
        val d = director(); val old = savedProject(d)
        val p = d.reevaluate(old.copy(decisions = old.decisions - Keys.OTA_UPDATES), settings).project
        // OTA_UPDATES did not exist when old designs were made; the current rules ask about it (when relevant to the platforms)
        val f = com.hotattic.gamedesigner.core.schema.Fields.get(Keys.OTA_UPDATES)!!
        if (f.isRelevant(com.hotattic.gamedesigner.core.schema.Traits(p)) && p.decision(Keys.OTA_UPDATES) == null) assertTrue(p.reeval!!.items.any { it.key == Keys.OTA_UPDATES && it.kind == "NEEDS_DECISION" })
    }
}

class ReevaluationIssuesTest {
    private val concept = "A top-down action roguelike for my Android phone about surviving a dangerous city. I fight with a pistol and flares."

    private suspend fun brokenOldProject(d: Director): Project {
        val (p0, ready) = driveToReady(d, newProject(), concept)
        assertTrue(ready)
        var p = SpecVersioning.createVersion(p0, VersionKind.INITIAL, 1L, "2026-10-01")
        // the physical-test state: the owner said "cc0 only" but "original only" is recorded, an uploaded splash is ignored, and the project sits in playtest mode
        p = p.copy(decisions = p.decisions + (Keys.ASSET_POLICY to Decision("original_only", DecisionSource.USER, provenance = Provenance.OWNER_EXPLICIT, rawAnswer = "cc0 only", updatedAt = 5L))
            + (Keys.BRAND_STUDIO to Decision("skip", DecisionSource.USER, provenance = Provenance.OWNER_EXPLICIT, updatedAt = 5L)),
            branding = p.branding + ("studio_splash" to com.hotattic.gamedesigner.core.model.BrandingAsset("studio_splash", com.hotattic.gamedesigner.core.model.BrandingMode.UPLOADED, "branding/s.png", "s.png", "abc", 10, 10, 1L)),
            mode = com.hotattic.gamedesigner.core.model.ProjectMode.PLAYTEST_CONTINUE)
        return p
    }

    @Test fun reevaluatingAPlaytestModeProjectRunsInDesignModeAndNeverAsksWhatYouWantToDoWithTheGame() = runBlocking {
        val d = director(); val old = brokenOldProject(d)
        val p = d.reevaluate(old, settings).project
        assertEquals(com.hotattic.gamedesigner.core.model.ProjectMode.NEW_GAME, p.mode)
        assertTrue(p.pendingFieldKey != Keys.CONTINUATION_GOAL)
        val back = d.discardReevaluation(p)
        assertEquals(com.hotattic.gamedesigner.core.model.ProjectMode.PLAYTEST_CONTINUE, back.mode)
    }

    @Test fun blockingContradictionsAreAskedOneAtATimeLikeIntakeQuestions() = runBlocking {
        val d = director(); val old = brokenOldProject(d)
        var p = d.reevaluate(old, settings).project
        // 1st issue: the asset policy is asked again, with its real choices
        assertEquals(Keys.ASSET_POLICY, p.pendingFieldKey)
        assertEquals(Keys.ASSET_POLICY, p.messages.last().question?.fieldKey)
        assertTrue(p.messages.any { it.text.contains("doesn't add up") })
        p = d.handleUserMessage(p, settings, "cc0 only").project
        assertEquals("cc0_default", p.value(Keys.ASSET_POLICY))
        // 2nd issue: the ignored upload, as its own question
        assertTrue(p.pendingFieldKey!!.startsWith("__issue:uploaded_asset_ignored"), p.pendingFieldKey)
        assertTrue(p.messages.last().quickReplies.any { it.label == "Use my uploaded file" })
        p = d.handleUserMessage(p, settings, "use my uploaded file").project
        assertEquals("upload", p.value(Keys.BRAND_STUDIO))
        // nothing contradictory is left, and the flow continues to the normal review/ready steps instead of blocking
        assertTrue(com.hotattic.gamedesigner.core.engine.ConsistencyReview.review(p, "").errors.isEmpty(), com.hotattic.gamedesigner.core.engine.ConsistencyReview.review(p, "").errors.joinToString { it.code })
        assertTrue(p.pendingFieldKey?.startsWith("__issue") != true)
    }

    @Test fun declineTheUploadedFileAndTheContradictionIsSettledToo() = runBlocking {
        val d = director(); val old = brokenOldProject(d)
        var p = d.reevaluate(old, settings).project
        p = d.handleUserMessage(p, settings, "cc0 only").project
        p = d.handleUserMessage(p, settings, "don't use it").project
        assertEquals("skip", p.value(Keys.BRAND_STUDIO))
        assertTrue(com.hotattic.gamedesigner.core.engine.ConsistencyReview.review(p, "").errors.isEmpty())
    }

    @Test fun secondOpinionBriefMarksWhoStandsBehindEachDecisionAndPointsAreParsed() = runBlocking {
        val d = director(); val old = brokenOldProject(d)
        val p = d.reevaluate(old, settings).project
        val brief = com.hotattic.gamedesigner.core.engine.SecondOpinion.brief(p)
        assertTrue("OWNER'S ORIGINAL IDEA" in brief && "[OWNER]" in brief)
        assertTrue(brief.length <= 8000)
        val pts = com.hotattic.gamedesigner.core.engine.SecondOpinion.points("Intro\n- The game never says how the player heals.\n- Performance on phones is a guess.\n* Short\n3. Combat feedback is undefined for the creature.")
        assertEquals(3, pts.size)
    }
}

class SaysAllIdiomTest {
    @Test fun idiomsContainingAllAreNotAQuantifier() {
        for (s in listOf("It is a turn-based tactics game after all", "above all it must be fast", "not at all", "all right then")) assertTrue(!com.hotattic.gamedesigner.core.engine.ConsistencyReview.saysAll(s), s)
        for (s in listOf("all of them", "everything", "I want all the menus", "all")) assertTrue(com.hotattic.gamedesigner.core.engine.ConsistencyReview.saysAll(s), s)
    }
}
