package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.engine.CompletenessEngine
import com.hotattic.gamedesigner.core.engine.DesignDimensions
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A scripted on-device model: the reply is a function of the prompt, so each test states exactly what a real model would say. */
private class Scripted(var reply: (LlmRequest) -> String) : com.hotattic.gamedesigner.core.llm.LlmProvider {
    var calls = 0
    override val id = "scripted"; override val displayName = "Scripted local model"
    override val tier = com.hotattic.gamedesigner.core.llm.LlmTier.LOCAL_SMALL; override val isLocal = true
    override suspend fun isReady() = true
    override suspend fun complete(request: LlmRequest): LlmResult { calls++; return LlmResult.Ok(reply(request)) }
}

class AdaptiveQuestionsTest {
    private val climbing = "A 2.5D platformer where you climb a giant mountain by hand over hand, every grip is a gamble."
    private fun last(p: Project) = p.messages.last { it.role == Role.DIRECTOR }.text
    private fun asked(p: Project) = p.messages.filter { it.role == Role.DIRECTOR }.mapNotNull { it.question?.fieldKey }
    private val combatDetail = setOf(Keys.COMBAT_MODEL, Keys.ENEMIES_BOSSES)

    private suspend fun askUntil(d: Director, start: Project, key: String, reply: String = "choose for me", max: Int = 60): Project {
        var p = start
        repeat(max) {
            if (p.pendingFieldKey == key) return p
            val pending = p.pendingFieldKey
            val r = when {
                pending == "__proposals__" -> "yes"
                pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__asset_plan__" -> "looks good"
                else -> reply
            }
            p = d.handleUserMessage(p, settings, r).project
        }
        return p
    }

    // ---- combat as a gate -------------------------------------------------------------------------------------------

    @Test fun uncertainCombatCollapsesToAYesNoAndNoRemovesTheWholeBranch() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        p = askUntil(d, p, Keys.HAS_COMBAT)
        assertEquals(Keys.HAS_COMBAT, p.pendingFieldKey, "the short gate comes before any combat detail")
        assertTrue("Yes or no" in last(p) && "combat" in last(p).lowercase())
        assertEquals(null, p.decision(Keys.COMBAT_MODEL))
        p = d.handleUserMessage(p, settings, "No!").project
        assertEquals("no", p.value(Keys.HAS_COMBAT))
        assertFalse(Fields.get(Keys.COMBAT_MODEL)!!.isRelevant(Traits(p)))
        val (done, ready) = driveToReady(d, p, "")
        assertTrue(ready)
        assertTrue(asked(done).none { it in combatDetail }, "no combat detail ever: ${asked(done)}")
    }

    @Test fun aPeacefulClimbingGameIsNeverAskedAboutCombatAtAll() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A peaceful procedural climbing game. You climb generated stages and try to reach the top.").project
        val (done, ready) = driveToReady(d, p, "")
        assertTrue(ready)
        assertEquals("no", done.value(Keys.HAS_COMBAT))
        assertTrue(asked(done).none { it == Keys.HAS_COMBAT || it in combatDetail })
    }

    @Test fun anOwnerWhoDescribesCombatGetsCombatQuestionsWordedForTheirGame() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "$climbing There are skeleton enemies and boss fights on the way up.").project
        assertEquals("yes", p.value(Keys.HAS_COMBAT), "described combat opens the branch without a gate question")
        p = askUntil(d, p, Keys.COMBAT_MODEL)
        assertEquals(Keys.COMBAT_MODEL, p.pendingFieldKey)
        assertTrue("You want combat" in last(p), last(p))
    }

    @Test fun changingYourMindAboutCombatReopensTheBranch() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "$climbing There is no combat at all.").project
        p = askUntil(d, p, Keys.WIN_LOSS)
        assertEquals("no", p.value(Keys.HAS_COMBAT))
        p = d.handleUserMessage(p, settings, "Actually, add enemies and boss fights.").project
        assertEquals("yes", p.value(Keys.HAS_COMBAT))
        assertEquals(Provenance.OWNER_CORRECTION, p.decision(Keys.HAS_COMBAT)!!.prov)
        assertTrue(Fields.get(Keys.COMBAT_MODEL)!!.isRelevant(Traits(p)))
    }

    @Test fun combatCoreGenresAreNotAskedWhetherTheyHaveCombat() {
        val p = ProjectOps.setDecision(newProject(), Keys.GENRE, "survivors_like", Provenance.OWNER_EXPLICIT, 1L)
        assertFalse(Fields.get(Keys.HAS_COMBAT)!!.isRelevant(Traits(p)))
        assertTrue(Fields.get(Keys.COMBAT_MODEL)!!.isRelevant(Traits(p)))
    }

    @Test fun backOnTheGateReopensTheDecisionAndTheBranchFollowsTheNewAnswer() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        p = askUntil(d, p, Keys.HAS_COMBAT)
        p = d.handleUserMessage(p, settings, "no").project
        assertFalse(Traits(p).has(Tag.COMBAT))
        val back = d.goBack(p)!!
        assertEquals(Keys.HAS_COMBAT, back.pendingFieldKey)
        assertEquals(null, back.decision(Keys.HAS_COMBAT))
        val yes = d.handleUserMessage(back, settings, "yes").project
        assertTrue(Traits(yes).has(Tag.COMBAT))
        assertTrue(Fields.get(Keys.COMBAT_MODEL)!!.isRelevant(Traits(yes)))
    }

    // ---- open answers -----------------------------------------------------------------------------------------------

    @Test fun luckyIsAMeaningfulAnswerWithOrWithoutAModelAndWithOrWithoutThePunctuation() = runBlocking {
        for (word in listOf("Lucky!", "lucky", "LUCKY!!!")) {
            val d = director()
            var p = d.start(newProject(), settings)
            p = d.handleUserMessage(p, settings, climbing).project
            p = d.askAbout(p, Keys.PLAYER_FEELING)
            p = d.handleUserMessage(p, settings, word).project
            assertTrue(p.value(Keys.PLAYER_FEELING).orEmpty().lowercase().startsWith("lucky"), "$word -> ${p.value(Keys.PLAYER_FEELING)}")
            assertTrue(p.pendingFieldKey != Keys.PLAYER_FEELING, "not asked again")
        }
    }

    @Test fun aModelsReadingOfAColourfulAnswerIsKeptNextToTheOwnersWords() = runBlocking {
        val llm = Scripted { """{"intent":"unclear","gloss":"The thrill of barely making an improbable jump or recovery"}""" }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        p = d.askAbout(p, Keys.PLAYER_FEELING)
        p = d.handleUserMessage(p, settings, "Lucky!").project
        val v = p.value(Keys.PLAYER_FEELING).orEmpty()
        assertTrue(v.startsWith("Lucky") && "improbable" in v, v)
        assertTrue(p.pendingFieldKey != Keys.PLAYER_FEELING)
    }

    @Test fun anOpenTextAnswerIsKeptEvenWhenTheModelIsUnsureAndDictationSlipsSurvive() = runBlocking {
        val llm = Scripted { """{"intent":"unclear"}""" }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        p = d.askAbout(p, Keys.WIN_LOSS)
        p = d.handleUserMessage(p, settings, "The wom by reaching the top, the fail by failing to do so").project
        assertTrue(p.value(Keys.WIN_LOSS).orEmpty().contains("reaching the top"))
        assertTrue(p.pendingFieldKey != Keys.WIN_LOSS)
    }

    @Test fun anAnswerThatMatchesNoOptionIsNeverRepeatedTwice() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        p = d.askAbout(p, Keys.DIMENSION)
        p = d.handleUserMessage(p, settings, "blorp").project
        assertEquals(Keys.DIMENSION, p.pendingFieldKey, "first miss asks once more")
        p = d.handleUserMessage(p, settings, "zonk").project
        assertTrue(p.pendingFieldKey != Keys.DIMENSION, "second miss: Bob decides and says so: ${last(p)}")
        assertTrue(p.messages.takeLast(3).any { it.role == Role.DIRECTOR && "recommendation" in it.text })
    }

    // ---- one pass: inference, follow-up, rich answers -----------------------------------------------------------------

    @Test fun oneRichAnswerResolvesFutureQuestionsAndTheyAreNotAskedAgain() = runBlocking {
        val llm = Scripted { r ->
            if (r.messages.last().content.contains("OWNER'S LATEST MESSAGE")) """{"intent":"freeform","inferences":[
                {"key":"win_loss_conditions","value":"Win by reaching the top of the generated climb; fail by falling and restarting the stage.","confidence":"high","evidence":"try to reach the top"},
                {"key":"has_combat","value":"no","confidence":"high","evidence":"climb procedurally generated stages"}]}""" else "{}"
        }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "I want a game where you climb procedurally generated stages and try to reach the top.").project
        assertEquals("no", p.value(Keys.HAS_COMBAT))
        assertEquals(DecisionStatus.CONFIRMED, p.decision(Keys.HAS_COMBAT)!!.status)
        assertTrue(p.value(Keys.WIN_LOSS).orEmpty().contains("reaching the top"))
        assertEquals(Provenance.SYSTEM_INFERENCE, p.decision(Keys.WIN_LOSS)!!.prov)
        val (done, ready) = driveToReady(d, p, "")
        assertTrue(ready)
        assertTrue(asked(done).none { it == Keys.WIN_LOSS || it == Keys.HAS_COMBAT || it in combatDetail }, asked(done).toString())
    }

    @Test fun inferencesNeedAQuoteFromTheOwnerAndNeverOverrideTheOwner() = runBlocking {
        val llm = Scripted { """{"intent":"freeform","inferences":[
            {"key":"has_combat","value":"no","confidence":"high","evidence":"wizards cast spells at dragons"},
            {"key":"win_loss_conditions","value":"Defeat the dragon","confidence":"high","evidence":"climb a giant mountain"}]}""" }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        assertEquals(null, p.decision(Keys.HAS_COMBAT), "invented evidence is ignored")
        // an owner decision is never replaced by a later inference
        p = ProjectOps.setDecision(p, Keys.WIN_LOSS, "Win by finishing the last climb.", Provenance.OWNER_EXPLICIT, 5L)
        p = d.handleUserMessage(p, settings, "The mountain is huge and I want it to feel enormous.").project
        assertEquals("Win by finishing the last climb.", p.value(Keys.WIN_LOSS))
    }

    @Test fun mediumConfidenceInferencesAreHeldForConfirmationNotRecordedAsFact() = runBlocking {
        val llm = Scripted { """{"intent":"freeform","inferences":[{"key":"has_combat","value":"no","confidence":"medium","evidence":"climb a giant mountain"},{"key":"win_loss_conditions","value":"Reach the summit","confidence":"low","evidence":"climb a giant mountain"}]}""" }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        assertEquals(DecisionStatus.PROPOSED, p.decision(Keys.HAS_COMBAT)?.status)
        assertEquals(null, p.decision(Keys.WIN_LOSS), "low confidence is only a reason to ask")
    }

    @Test fun anInterestingAnswerGeneratesOneContextualFollowUpInTheSameModelCall() = runBlocking {
        var n = 0
        val llm = Scripted { r -> if (r.messages.last().content.contains("OWNER'S LATEST MESSAGE") && n++ == 0) """{"intent":"freeform","follow_up":"What happens when your hands slip - do you fall to the bottom or to a ledge?"}""" else "{}" }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        assertEquals(1, llm.calls, "reading the answer and choosing the next question took ONE model call")
        assertTrue("hands slip" in last(p), last(p))
        assertEquals("__followup__", p.pendingFieldKey)
        p = d.handleUserMessage(p, settings, "You fall back to the last ledge you rested on.").project
        assertTrue(p.pendingFieldKey != "__followup__")
        assertTrue(p.activeFacts().any { "ledge" in it.text })
    }

    @Test fun followUpsAreCappedAndNeverRepeated() = runBlocking {
        val llm = Scripted { r -> if (r.messages.last().content.contains("OWNER'S LATEST MESSAGE")) """{"intent":"freeform","follow_up":"What makes the climbing hard - grip, stamina or hazards?"}""" else "{}" }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        var follows = 0
        repeat(12) {
            if (p.pendingFieldKey == "__followup__") follows++
            p = d.handleUserMessage(p, settings, if (p.pendingFieldKey == "__proposals__") "yes" else "choose for me").project
        }
        assertEquals(1, follows, "the identical question is never asked twice")
    }

    @Test fun ordinaryAnswersCostOneModelCallAndTrivialRepliesCostNone() = runBlocking {
        val llm = Scripted { "{}" }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        val afterConcept = llm.calls
        p = askUntil(d, p, Keys.HAS_COMBAT)
        val before = llm.calls
        p = d.handleUserMessage(p, settings, "no").project
        assertEquals(before, llm.calls, "a bare yes/no needs no model")
        assertTrue(afterConcept <= 2)
    }

    // ---- choose for me ----------------------------------------------------------------------------------------------

    @Test fun chooseForMeOnCombatForAClimbingGameSaysNoAndWhy() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        p = askUntil(d, p, Keys.HAS_COMBAT)
        p = d.handleUserMessage(p, settings, "choose for me").project
        assertEquals("no", p.value(Keys.HAS_COMBAT))
        assertTrue("Nothing you've described needs combat" in p.decision(Keys.HAS_COMBAT)!!.note, p.decision(Keys.HAS_COMBAT)!!.note)
        assertFalse(Fields.get(Keys.COMBAT_MODEL)!!.isRelevant(Traits(p)))
    }

    @Test fun chooseForMeUsesTheModelOnTheWholeDesignAndRejectsInvalidChoices() = runBlocking {
        var value = "landscape"
        val llm = Scripted { r -> if (r.messages.last().content.contains("Choose what best fits")) """{"value":"$value","why":"Hand-over-hand climbing reads best with a wide view of the wall."}""" else "{}" }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        val q = d.askAbout(p, Keys.ORIENTATION)
        val ok = d.handleUserMessage(q, settings, "choose for me").project
        assertEquals("landscape", ok.value(Keys.ORIENTATION))
        assertTrue(ok.messages.takeLast(3).any { "wide view" in it.text })
        value = "diagonal"   // not a real option: the canned suggestion applies instead
        val bad = d.handleUserMessage(q, settings, "choose for me").project
        assertNotNull(bad.value(Keys.ORIENTATION)); assertTrue(bad.value(Keys.ORIENTATION) != "diagonal")
    }

    // ---- progress ---------------------------------------------------------------------------------------------------

    @Test fun skippingAnIrrelevantBranchDoesNotCostProgressAndAnOpenGateHoldsItBack() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        val open = DesignDimensions.percent(p)
        val no = ProjectOps.setDecision(p, Keys.HAS_COMBAT, "no", Provenance.OWNER_EXPLICIT, 9L)
        val yes = ProjectOps.setDecision(p, Keys.HAS_COMBAT, "yes", Provenance.OWNER_EXPLICIT, 9L)
        assertTrue(DesignDimensions.percent(no) > open, "settling the gate is progress: $open -> ${DesignDimensions.percent(no)}")
        assertTrue(DesignDimensions.percent(no) > DesignDimensions.percent(yes), "a 'yes' opens real combat design work that still has to be done")
        val c = CompletenessEngine.compute(no)
        assertTrue(c.missingRequired.none { it.key in combatDetail })
    }

    @Test fun inferredFactsCountAsProgress() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        val before = DesignDimensions.percent(p)
        val inferred = ProjectOps.setDecision(p, Keys.WIN_LOSS, "Win by reaching the top; fail by falling.", Provenance.SYSTEM_INFERENCE, 9L, DecisionStatus.CONFIRMED)
        assertTrue(DesignDimensions.percent(inferred) > before)
    }

    // ---- back and inferences -------------------------------------------------------------------------------------------

    @Test fun backTakesBackWhatWasInferredFromThatAnswerButNotOwnerDecisions() = runBlocking {
        val llm = Scripted { r ->
            val t = r.messages.last().content
            if (t.contains("OWNER'S LATEST MESSAGE: Lucky")) """{"intent":"freeform","inferences":[{"key":"difficulty_failure","value":"checkpoint_retry","confidence":"high","evidence":"Lucky"}]}""" else "{}"
        }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, climbing).project
        p = d.askAbout(p, Keys.PLAYER_FEELING)
        p = d.handleUserMessage(p, settings, "Lucky!").project
        assertEquals("checkpoint_retry", p.value(Keys.DIFFICULTY_FAILURE))
        val back = d.goBack(p)!!
        assertEquals(null, back.decision(Keys.PLAYER_FEELING))
        assertEquals(null, back.decision(Keys.DIFFICULTY_FAILURE), "the inference went back with the answer")
    }
}
