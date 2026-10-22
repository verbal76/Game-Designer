package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.engine.DecisionExtractor
import com.hotattic.gamedesigner.core.engine.DesignDimensions
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.ReviewGate
import com.hotattic.gamedesigner.core.engine.Severity
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.ReferenceGame
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.research.ResearchOutcome
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Regression tests for the five engine corrections of docs/FUNCTIONAL_REDISCOVERY_A-G.md (G6, tests from G8).
 * Evidence: AUTOMATED on synthetic projects (rules only, no model, no network). The original Songs of Syx export was not available,
 * so nothing here claims to validate it.
 */
private class RecordingResearch : com.hotattic.gamedesigner.core.research.ResearchProvider {
    val names = mutableListOf<String>()
    override suspend fun researchReferenceGame(name: String): ResearchOutcome<ReferenceGame> { names += name; return ResearchOutcome.NotFound("test") }
    override suspend fun toolchainFacts(engineId: String): ResearchOutcome<List<com.hotattic.gamedesigner.core.model.ResearchNote>> = ResearchOutcome.Unavailable("test")
}

class EngineRepairG6Test {
    private val concept = "A medieval fantasy city builder like Songs of Syx with professions, relationships and an interconnected economy. Android and iPhone."

    private fun answer(pending: String?): String = when {
        pending == "__proposals__" -> "yes"
        pending == "__asset_plan__" -> "looks good"
        pending?.startsWith("__conflict:") == true -> "use alternative 1"
        pending == "__ready__" -> "generate"
        pending == "__review__" -> "looks right"
        pending == Keys.FIVE_MINUTES || pending == Keys.CORE_FANTASY -> "Playing it should feel calm and rewarding."
        else -> "choose for me"
    }

    /** Drives the interview with delegation until [stop] holds. Returns the director and the project at that moment. */
    private suspend fun reach(d: Director = director(), research: Boolean = false, stop: (Project) -> Boolean): Pair<Director, Project> {
        val s = settings.copy(internetResearchAllowed = research)
        var p = d.start(newProject(), s)
        p = d.handleUserMessage(p, s, concept).project
        repeat(220) {
            if (stop(p)) return d to p
            p = d.handleUserMessage(p, s, answer(p.pendingFieldKey)).project
        }
        error("never reached the requested state; pending=${p.pendingFieldKey}")
    }

    private fun plats(p: Project) = p.list(Keys.PLATFORMS).toSet()

    private fun plainField(p: Project): Boolean {
        val k = p.pendingFieldKey ?: return false
        return !k.startsWith("__") && k !in setOf(Keys.PLATFORMS, Keys.PRIMARY_PLATFORM, Keys.REFERENCES)
    }

    // ---- 1 + 5: owner corrections and primary platform ---------------------------------------------------------------

    @Test fun forgetAndroidItIsAPcGameNowReplacesThePlatforms() = runBlocking {
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        assertEquals(setOf(Platforms.ANDROID, Platforms.IOS), plats(ready))
        assertTrue(ReviewGate.approved(ready))
        val p = d.handleUserMessage(ready, settings, "Actually, forget Android. It is a PC game now.").project
        assertEquals(setOf(Platforms.WINDOWS), plats(p), "the decision itself changed, not only a fact")
        assertEquals(Provenance.OWNER_CORRECTION, p.decision(Keys.PLATFORMS)!!.prov)
        assertFalse(ReviewGate.approved(p), "a material correction invalidates the earlier approval")
        assertTrue(p.activeFacts().any { "pc game" in it.text.lowercase() }, "the owner's words are still recorded")
        assertEquals(emptyList(), ConflictEngine.open(p).filter { it.id == "fact_contradicts_decision" })
        assertTrue(p.messages.last { it.role == Role.DIRECTOR }.text.isNotBlank())
    }

    @Test fun addPcAsThePrimaryPlatformKeepsTheOthersAndSetsPrimary() = runBlocking {
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        val p = d.handleUserMessage(ready, settings, "Add PC as the primary platform.").project
        assertEquals(setOf(Platforms.ANDROID, Platforms.IOS, Platforms.WINDOWS), plats(p))
        assertEquals(Platforms.WINDOWS, p.value(Keys.PRIMARY_PLATFORM))
        assertTrue(p.references.none { it.name.startsWith("Add", true) }, "an instruction is never a reference game: ${p.references}")
        assertFalse(ReviewGate.approved(p))
    }

    @Test fun makePcPrimaryKeepAndroidSecondary() = runBlocking {
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        val p = d.handleUserMessage(ready, settings, "Make PC primary, keep Android secondary.").project
        assertTrue(Platforms.WINDOWS in plats(p) && Platforms.ANDROID in plats(p), "Android stays: ${plats(p)}")
        assertEquals(Platforms.WINDOWS, p.value(Keys.PRIMARY_PLATFORM))
    }

    @Test fun primaryPlatformIsRecordedThroughTheNormalDecisionSystem() = runBlocking {
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        val p = d.handleUserMessage(ready, settings, "I want the iPhone version built first.").project
        val dec = p.decision(Keys.PRIMARY_PLATFORM)!!
        assertEquals(Platforms.IOS, dec.value)
        assertTrue(dec.prov.ownerAuthored)
        assertEquals(setOf(Platforms.ANDROID, Platforms.IOS), plats(p), "naming a primary never drops the other platforms")
    }

    @Test fun removingAPrimaryPlatformDropsThePrimaryChoiceToo() = runBlocking {
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        val a = d.handleUserMessage(ready, settings, "Add PC as the primary platform.").project
        val b = d.handleUserMessage(a, settings, "Drop PC, I don't want it.").project
        assertFalse(Platforms.WINDOWS in plats(b))
        assertTrue(b.value(Keys.PRIMARY_PLATFORM) != Platforms.WINDOWS, "primary must not point at a removed platform")
    }

    @Test fun ordinaryPlatformStatementsStillWork() = runBlocking {
        // Existing behaviour: a plain mention is not an instruction and never changes a confirmed decision.
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        val p = d.handleUserMessage(ready, settings, "I think players on Android will love the economy part.").project
        assertEquals(setOf(Platforms.ANDROID, Platforms.IOS), plats(p))
    }

    // ---- 2: the same instruction at every pending state --------------------------------------------------------------

    @Test fun theSameInstructionWorksAtEveryPendingState() = runBlocking {
        val instruction = "Switch to PC only, forget Android and iPhone."
        val states = listOf<(Project) -> Boolean>(
            { it.pendingFieldKey == "__review__" }, { it.pendingFieldKey == "__ready__" }, { plainField(it) },
        )
        for (stop in states) {
            val (d, at) = reach(stop = stop)
            val p = d.handleUserMessage(at, settings, instruction).project
            assertEquals(setOf(Platforms.WINDOWS), plats(p), "state ${at.pendingFieldKey}")
        }
        // No pending question, and a conflict question that no longer exists.
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        for (pending in listOf(null, "__conflict:gone__", "__proposals__")) {
            val p = d.handleUserMessage(ProjectOps.setPending(ready, pending), settings, instruction).project
            assertEquals(setOf(Platforms.WINDOWS), plats(p), "state $pending")
        }
    }

    @Test fun anInstructionAtAFieldQuestionIsNotMistakenForItsAnswer() = runBlocking {
        val (d, at) = reach { plainField(it) }
        val key = at.pendingFieldKey!!
        val p = d.handleUserMessage(at, settings, "Make PC the main platform.").project
        assertEquals(Platforms.WINDOWS, p.value(Keys.PRIMARY_PLATFORM))
        assertTrue(p.decision(key)?.rawAnswer.orEmpty().let { "PC" !in it }, "the platform instruction must not be stored as the answer to $key")
    }

    // ---- idempotence / replay ----------------------------------------------------------------------------------------

    @Test fun aReplayedTurnIdDoesNotApplyACorrectionTwice() = runBlocking {
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        val once = d.handleUserMessage(ready, settings, "Add PC as the primary platform.", turnId = "t-1")
        assertFalse(once.duplicate)
        val again = d.handleUserMessage(once.project, settings, "Add PC as the primary platform.", turnId = "t-1")
        assertTrue(again.duplicate)
        assertEquals(once.project.decisions, again.project.decisions)
        assertEquals(once.project.facts, again.project.facts)
        assertEquals(once.project.messages.size, again.project.messages.size)
    }

    @Test fun repeatingTheSameInstructionWithANewTurnIsIdempotentInState() = runBlocking {
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        val a = d.handleUserMessage(ready, settings, "Add PC as the primary platform.").project
        val b = d.handleUserMessage(a, settings, "Add PC as the primary platform.").project
        assertEquals(plats(a), plats(b))
        assertEquals(a.value(Keys.PRIMARY_PLATFORM), b.value(Keys.PRIMARY_PLATFORM))
        assertEquals(a.activeFacts().size, b.activeFacts().size, "the same sentence is not recorded twice")
    }

    @Test fun backAfterAConfirmedFreeTextCorrectionStillUndoesTheLastFieldAnswer() = runBlocking {
        val (d, at) = reach { plainField(it) }
        val key = at.pendingFieldKey!!
        val corrected = d.handleUserMessage(at, settings, "Make PC the main platform.").project
        val answered = d.handleUserMessage(corrected, settings, "choose for me").project
        val back = assertNotNull(d.goBack(answered))
        assertTrue(back.pendingFieldKey != null)
        assertEquals(Platforms.WINDOWS, back.value(Keys.PRIMARY_PLATFORM), "Back undoes the answer, not the owner's correction")
        assertNotNull(back.messages.lastOrNull())
        assertTrue(key.isNotBlank())
    }

    // ---- 3: reference guard ------------------------------------------------------------------------------------------

    @Test fun instructionSentencesPlatformsAndFragmentsAreNeverReferenceGames() {
        val bad = listOf(
            "Add PC as the primary platform.", "Add PC a", "Switch to PC only.", "Make Android the main platform.",
            "Use Unity for it.", "Forget Android. It is a PC game now.", "Change it to Windows and Linux.",
            "Remove Steam Deck support.", "Let Godot handle the build.", "Set the target to iPhone.",
        )
        for (s in bad) assertEquals(emptyList(), DecisionExtractor.referenceGames(s), "\"$s\" must not yield references")
        for (name in listOf("Add PC a", "Add PC", "PC", "Android", "Switch to PC", "Unity", "Make Android", "Godot Engine", "Steam Deck", "Windows PC", "Risk of the", "A"))
            assertFalse(com.hotattic.gamedesigner.core.engine.ReferenceGuard.isPlausibleTitle(name), "\"$name\"")
    }

    @Test fun realReferenceTitlesStillWork() {
        assertEquals(listOf("Songs of Syx"), DecisionExtractor.referenceGames(concept))
        assertEquals(listOf("Risk of Rain 2", "Vampire Survivors"), DecisionExtractor.referenceGames("I want Risk of Rain 2 mixed with Vampire Survivors, but I'm a wizard."))
        for (name in listOf("Songs of Syx", "Risk of Rain 2", "Vampire Survivors", "Make Way", "Dwarf Fortress", "Stardew Valley", "Minecraft"))
            assertTrue(com.hotattic.gamedesigner.core.engine.ReferenceGuard.isPlausibleTitle(name), "\"$name\"")
    }

    @Test fun addReferencesRejectsFragmentsAtTheSharedEntryPoint() {
        val p = ProjectOps.addReferences(newProject(), listOf("Add PC a", "Songs of Syx", "Unity"), 1L)
        assertEquals(listOf("Songs of Syx"), p.references.map { it.name })
    }

    @Test fun noResearchIsMadeForAnInstructionFragment() = runBlocking {
        val research = RecordingResearch()
        val d = director(DirectorDeps(research = research, clock = FakeClock()))
        val s = settings.copy(internetResearchAllowed = true)
        var p = d.start(newProject(), s)
        p = d.handleUserMessage(p, s, concept).project
        // A reference that was stored by an older build (the original failure) is never researched either.
        val legacy = p.copy(references = p.references + ReferenceGame(name = "Add PC a"))
        d.handleUserMessage(legacy, s, "Add PC as the primary platform.")
        assertTrue(research.names.isNotEmpty() && research.names.all { it == "Songs of Syx" }, "only the real reference is researched: ${research.names}")
    }

    @Test fun anExplicitReferenceAnswerStillGetsResearched() = runBlocking {
        val research = FakeResearch(ResearchOutcome.NotFound("x"))
        val d = director(DirectorDeps(research = research, clock = FakeClock()))
        val s = settings.copy(internetResearchAllowed = true)
        var p = d.start(newProject(), s)
        p = d.handleUserMessage(p, s, "I want a game like Dwarf Fortress but cute.").project
        assertTrue(research.calls >= 1)
        assertTrue(p.references.any { it.name == "Dwarf Fortress" })
    }

    // ---- 4: facts versus decisions -----------------------------------------------------------------------------------

    private fun contradictory(): Project {
        val ready = runBlocking { reach { it.pendingFieldKey == "__ready__" }.second }
        // Reproduces the original failure: the instruction exists as a fact, newer than the decision, and was never applied.
        return ProjectOps.addFacts(ready, listOf("Add PC as the primary platform."), "owner", Provenance.OWNER_EXPLICIT, ready.updatedAt + 10_000)
    }

    @Test fun aFactThatContradictsThePlatformDecisionIsABlockingConflict() {
        val p = contradictory()
        val c = ConflictEngine.open(p).firstOrNull { it.id == "fact_contradicts_decision" }
        assertNotNull(c, "conflicts: ${ConflictEngine.open(p).map { it.id }}")
        assertEquals(Severity.BLOCKER, c.severity)
        assertTrue(c.message.contains("PC", true) || c.message.contains("Windows", true))
    }

    @Test fun aContradictoryProjectNeverGeneratesAndNeverReportsOneHundredPercent() {
        val p = contradictory()
        // Even with the owner's approval re-recorded on exactly this state, consistency alone must keep readiness below 100.
        val approved = ReviewGate.approve(p, p.updatedAt + 1, "owner")
        assertTrue(ReviewGate.approved(approved))
        assertTrue(DesignDimensions.percent(approved) < 100)
        val g = SpecVersioning.generate(approved, VersionKind.INITIAL, 1L, "2026-10-17T00:00:00Z")
        assertTrue(g.blocked, "generation must be blocked")
        assertTrue(g.review.findings.any { it.code == "fact_contradicts_decision" }, g.review.findings.joinToString { it.code })
        assertEquals(0, g.project.versions.size)
    }

    @Test fun anOlderInstructionThatALaterOwnerDecisionSupersededIsNotAContradiction() = runBlocking {
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        val a = d.handleUserMessage(ready, settings, "Add PC as the primary platform.").project
        // The owner later changes the platforms with an explicit decision; the older sentence must not resurrect a conflict.
        val b = ProjectOps.setDecision(a, Keys.PLATFORMS, "android${com.hotattic.gamedesigner.core.model.Decision.LIST_SEPARATOR}ios", Provenance.OWNER_EXPLICIT, a.updatedAt + 5_000)
            .let { ProjectOps.clearDecision(it, Keys.PRIMARY_PLATFORM, a.updatedAt + 5_000) }
        assertTrue(ConflictEngine.open(b).none { it.id == "fact_contradicts_decision" })
    }

    @Test fun anAppliedInstructionLeavesAConsistentSpecification() = runBlocking {
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        var p = d.handleUserMessage(ready, settings, "Switch to PC only, forget Android and iPhone.").project
        repeat(80) {
            if (p.pendingFieldKey == null && p.versions.isNotEmpty()) return@repeat
            p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project
        }
        assertTrue(ConflictEngine.open(p).none { it.id == "fact_contradicts_decision" })
        val md = ClaudeMdGenerator.generate(p, 1, "Initial", "2026-10-17T00:00:00Z")
        assertTrue("Windows" in md)
        assertFalse(Regex("Target platforms:[^\\n]*Android").containsMatchIn(md), "the spec must not list a platform the owner removed")
    }

    // ---- 5: readiness and approval -----------------------------------------------------------------------------------

    @Test fun changingReferencesInvalidatesTheApproval() = runBlocking {
        val (_, ready) = reach { it.pendingFieldKey == "__ready__" }
        assertTrue(ReviewGate.approved(ready))
        val changed = ProjectOps.addReferences(ready, listOf("Dwarf Fortress"), ready.updatedAt + 1)
        assertFalse(ReviewGate.approved(changed), "a new reference is part of what the owner approved")
        val unchanged = ProjectOps.addReferences(ready, listOf("Add PC a"), ready.updatedAt + 1)
        assertTrue(ReviewGate.approved(unchanged), "a rejected fragment changes nothing, so approval stays")
    }

    @Test fun aCleanApprovedDesignStillReachesOneHundredPercent() = runBlocking {
        val (_, ready) = reach { it.pendingFieldKey == "__ready__" }
        assertEquals(100, DesignDimensions.percent(ready))
    }

    @Test fun statusAndReviewMentionAnUnappliedInstruction() {
        val p = contradictory()
        val status = com.hotattic.gamedesigner.core.director.Messages.status(p, "Bob")
        assertTrue("PC" in status && ("did not apply" in status || "not applied" in status || "contradict" in status.lowercase()), status)
        val review = com.hotattic.gamedesigner.core.generate.DesignReview.compose(p)
        assertTrue("PC" in review && ("did not apply" in review || "not applied" in review || "contradict" in review.lowercase()), review)
    }

    // ---- reevaluation ------------------------------------------------------------------------------------------------

    @Test fun reevaluationKeepsOwnerDecisionsAndNeverResearchesFragments() = runBlocking {
        val research = RecordingResearch()
        val d = director(DirectorDeps(research = research, clock = FakeClock()))
        val (_, ready) = reach(d) { it.pendingFieldKey == "__ready__" }
        val polluted = ready.copy(references = ready.references + ReferenceGame(name = "Add PC a"))
        val turn = d.reevaluate(polluted, settings.copy(internetResearchAllowed = true))
        assertEquals(plats(ready), plats(turn.project), "owner decisions are kept")
        assertTrue("Add PC a" !in research.names, "never researches a fragment: ${research.names}")
    }

    // ---- the deterministic parser stays conservative ------------------------------------------------------------------

    @Test fun plainPlatformMentionsAreNeverInstructions() {
        for (t in listOf(
            "I think players on Android will love the economy part.", "Android and iPhone.", "Only touch controls, for Android.", "It should feel like a first-person game on Windows.",
            "The first level teaches the economy.", "A medieval fantasy city builder like Songs of Syx.", "I play it on my iPhone every day.", "Players trade goods on the web of roads.",
        )) assertEquals(null, com.hotattic.gamedesigner.core.engine.PlatformInstructions.parse(t), "\"$t\"")
    }

    @Test fun instructionsAreParsedIntoOrderedSteps() {
        val cur = listOf(Platforms.ANDROID, Platforms.IOS)
        fun run(t: String) = com.hotattic.gamedesigner.core.engine.PlatformInstructions.parse(t)!!.applyTo(cur, null)
        assertEquals(listOf(Platforms.WINDOWS), run("Actually, forget Android. It is a PC game now.").platforms)
        assertEquals(listOf(Platforms.ANDROID, Platforms.IOS, Platforms.WINDOWS), run("Also add Windows.").platforms)
        assertEquals(listOf(Platforms.IOS), run("Drop Android.").platforms)
        assertEquals(listOf(Platforms.WINDOWS), run("PC instead of Android and iPhone.").platforms)
        assertEquals(Platforms.IOS, run("Build the iPhone version first.").primary)
        assertEquals(Platforms.WINDOWS, run("PC should be the main platform.").primary)
        // Removing everything is refused, not applied.
        assertFalse(com.hotattic.gamedesigner.core.engine.PlatformInstructions.parse("Forget Android and iPhone.")!!.applyTo(cur, null).valid)
    }

    // ---- existing behaviour that must keep working --------------------------------------------------------------------

    @Test fun everyQuickReplyStillWorksAtEveryPendingState() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        var checked = 0
        repeat(120) {
            if (p.pendingFieldKey == null) return@repeat
            val quick = p.messages.lastOrNull { it.role == Role.DIRECTOR }?.quickReplies.orEmpty()
            val pending = p.pendingFieldKey
            for (q in quick.take(4)) {
                val t = d.handleUserMessage(p, settings, q.send)
                assertTrue(t.project.messages.size > p.messages.size, "\"${q.send}\" at $pending produced no reply")
                if (p.decision(Keys.PLATFORMS)?.status == com.hotattic.gamedesigner.core.model.DecisionStatus.CONFIRMED && pending != Keys.PLATFORMS && pending != Keys.PRIMARY_PLATFORM && q.send.lowercase() !in setOf("android", "ios", "windows", "web"))
                    assertEquals(plats(p), plats(t.project), "quick reply \"${q.send}\" at $pending changed the platforms")
                checked++
            }
            p = d.handleUserMessage(p, settings, answer(pending)).project
        }
        assertTrue(checked >= 20, "exercised $checked quick replies")
    }

    @Test fun delegateAndSkipStillSettleTheOpenQuestion() = runBlocking {
        val (d, at) = reach { plainField(it) }
        val key = at.pendingFieldKey!!
        val chosen = d.handleUserMessage(at, settings, "choose for me").project
        assertTrue(chosen.decision(key) != null || chosen.pendingFieldKey != key, "delegating answers or moves past $key")
        val (d2, at2) = reach { plainField(it) && it.messages.lastOrNull { m -> m.role == Role.DIRECTOR }?.question?.canSkip == true }
        val k2 = at2.pendingFieldKey!!
        val skipped = d2.handleUserMessage(at2, settings, "skip").project
        assertTrue(skipped.pendingFieldKey != k2, "skipping moves past $k2")
    }

    @Test fun lessonCardsAndTheStageStripSurviveACorrection() = runBlocking {
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        val p = d.handleUserMessage(ready, settings, "Make PC primary, keep Android secondary.").project
        val stages = com.hotattic.gamedesigner.core.engine.DesignStages.of(p).stages
        assertTrue(stages.any { it.id == "platforms" } && stages.any { it.id == "review" })
        val review = stages.first { it.id == "review" }
        assertTrue(review.state != com.hotattic.gamedesigner.core.engine.StageState.COMPLETE, "the changed design needs review again")
        // A question asked after the correction still renders as a card where it has a lesson.
        val (d2, at) = reach { plainField(it) }
        val after = d2.handleUserMessage(at, settings, "Make PC the main platform.").project
        val lastQuestion = after.messages.lastOrNull { it.role == Role.DIRECTOR && it.question != null }
        assertNotNull(lastQuestion)
        val parts = com.hotattic.gamedesigner.core.director.LessonParser.split(lastQuestion.text)
        assertTrue(parts.lesson != null || parts.before.isNotBlank() || lastQuestion.text.isNotBlank())
    }

    @Test fun anInstructionDoesNotReplaceTheAnswerEvenWhenItFollowsTheQuestionAfterIt() = runBlocking {
        val (d, at) = reach { plainField(it) }
        val key = at.pendingFieldKey!!
        val p = d.handleUserMessage(at, settings, "Make PC the main platform.").project
        assertEquals(key, p.pendingFieldKey, "the question that was open is asked again")
    }

    @Test fun aLegacyProjectWithAnUnappliedInstructionIsRepairedThroughTheConflictQuestion() = runBlocking {
        val d = director()
        val p = contradictory()
        val status = d.handleUserMessage(p, settings, "status").project.messages.last().text
        assertTrue("did not apply" in status && "PC" in status, status)
        val asked = d.askNext(ProjectOps.setPending(p, null), settings)
        assertTrue(asked.pendingFieldKey?.startsWith("__conflict:fact_contradicts_decision") == true, "pending=${asked.pendingFieldKey}")
        val fixed = d.handleUserMessage(asked, settings, "use alternative 1").project
        assertEquals(Platforms.WINDOWS, fixed.value(Keys.PRIMARY_PLATFORM))
        assertTrue(Platforms.WINDOWS in plats(fixed))
        assertTrue(ConflictEngine.open(fixed).none { it.id == "fact_contradicts_decision" })
    }

    @Test fun aCorrectedDesignStillGeneratesACompleteSpecification() = runBlocking {
        val (d, ready) = reach { it.pendingFieldKey == "__ready__" }
        var p = d.handleUserMessage(ready, settings, "Add PC as the primary platform.").project
        var requested = false
        repeat(80) {
            if (requested) return@repeat
            val t = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)); p = t.project
            if (t.action == com.hotattic.gamedesigner.core.director.DirectorAction.GenerateSpec) requested = true
        }
        assertTrue(requested, "the interview reaches generation again")
        val g = SpecVersioning.generate(p, VersionKind.INITIAL, p.updatedAt + 1, "2026-10-17T00:00:00Z")
        assertFalse(g.blocked, g.review.findings.joinToString { "${it.code}: ${it.message}" })
        val md = g.project.versions.last().claudeMd
        assertTrue("Windows" in md && "Android" in md)
    }
}
