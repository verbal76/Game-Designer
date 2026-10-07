package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Traits
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class Scripted(var reply: (LlmRequest) -> String) : com.hotattic.gamedesigner.core.llm.LlmProvider {
    val prompts = mutableListOf<String>()
    override val id = "scripted"; override val displayName = "Scripted local model"
    override val tier = com.hotattic.gamedesigner.core.llm.LlmTier.LOCAL_SMALL; override val isLocal = true
    override suspend fun isReady() = true
    override suspend fun complete(request: LlmRequest): LlmResult { prompts += request.messages.last().content; return LlmResult.Ok(reply(request)) }
}

class AdaptiveQuestionsTest {
    private val concept = "A 2.5D platformer where you climb a giant mountain by hand over hand, every grip is a gamble."
    private fun last(p: Project) = p.messages.last { it.role == Role.DIRECTOR }.text

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

    @Test fun combatIsAYesNoGateBeforeAnyCombatDetailAndNoSkipsAllOfIt() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        p = askUntil(d, p, Keys.HAS_COMBAT)
        assertEquals(Keys.HAS_COMBAT, p.pendingFieldKey, "the combat gate is asked before the combat details")
        assertTrue("Yes or no" in last(p))
        assertEquals(null, p.decision(Keys.COMBAT_MODEL))
        p = d.handleUserMessage(p, settings, "No!").project
        assertEquals("no", p.value(Keys.HAS_COMBAT))
        assertFalse(Traits(p).has(com.hotattic.gamedesigner.core.schema.Tag.COMBAT))
        assertFalse(Fields.get(Keys.COMBAT_MODEL)!!.isRelevant(Traits(p)))
        // and it never comes up, however far the interview goes
        val (done, ready) = driveToReady(d, p, "")
        assertTrue(ready)
        assertTrue(done.messages.none { it.role == Role.DIRECTOR && it.question?.fieldKey in setOf(Keys.COMBAT_MODEL, Keys.ENEMIES_BOSSES) })
    }

    @Test fun anOwnerWhoRulesCombatOutNeverGetsAskedAboutIt() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "$concept There is no combat at all, it is purely about climbing.").project
        val (done, ready) = driveToReady(d, p, "")
        assertTrue(ready)
        assertEquals("no", done.value(Keys.HAS_COMBAT))
        assertTrue(done.messages.none { it.question?.fieldKey in setOf(Keys.HAS_COMBAT, Keys.COMBAT_MODEL, Keys.ENEMIES_BOSSES) })
    }

    @Test fun combatCoreGenresAreNotAskedWhetherTheyHaveCombat() {
        val p = newProject().let { com.hotattic.gamedesigner.core.engine.ProjectOps.setDecision(it, Keys.GENRE, "survivors_like", com.hotattic.gamedesigner.core.model.Provenance.OWNER_EXPLICIT, 1L) }
        assertFalse(Fields.get(Keys.HAS_COMBAT)!!.isRelevant(Traits(p)))
        assertTrue(Fields.get(Keys.COMBAT_MODEL)!!.isRelevant(Traits(p)))
    }

    @Test fun lookAheadSettlesGroundedGatesAndRejectsInventedEvidence() = runBlocking {
        val llm = Scripted { """{"systems":{"combat":{"need":"no","evidence":"purely about climbing and grip"}}}""" }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "$concept It is purely about climbing and grip.").project
        assertEquals("no", p.value(Keys.HAS_COMBAT), "the model read it from the owner's words")
        assertEquals(com.hotattic.gamedesigner.core.model.DecisionStatus.PROPOSED, p.decision(Keys.HAS_COMBAT)!!.status)
        // invented evidence is ignored
        val liar = Scripted { """{"systems":{"combat":{"need":"no","evidence":"wizards cast spells at dragons"}}}""" }
        val d2 = director(DirectorDeps(local = liar, clock = FakeClock()))
        var q = d2.start(newProject(), settings)
        q = d2.handleUserMessage(q, settings, concept).project
        assertEquals(null, q.decision(Keys.HAS_COMBAT))
    }

    @Test fun aModelFollowUpIsAskedOnceAndTheAnswerIsKept() = runBlocking {
        var n = 0
        val llm = Scripted { r -> if (r.messages.last().content.contains("Upcoming questions") && n++ == 0) """{"follow_up":"What happens when your hands slip - do you fall to the bottom or a ledge?"}""" else "{}" }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        val q = p.messages.last { it.role == Role.DIRECTOR }
        assertTrue("hands slip" in q.text, "follow-up shown: ${q.text}")
        assertEquals("__followup__", p.pendingFieldKey)
        p = d.handleUserMessage(p, settings, "You fall back to the last ledge you rested on.").project
        assertNotNull(p.pendingFieldKey)
        assertTrue(p.pendingFieldKey != "__followup__")
        assertTrue(p.activeFacts().any { "ledge" in it.text })
    }

    @Test fun anOpenTextAnswerIsKeptEvenWhenTheModelIsUnsureWhatItMeans() = runBlocking {
        val llm = Scripted { """{"intent":"unclear"}""" }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        p = d.askAbout(p, Keys.WIN_LOSS)
        p = d.handleUserMessage(p, settings, "You win by reaching the top and you fail by falling off before you do!").project
        assertTrue(p.value(Keys.WIN_LOSS).orEmpty().contains("reaching the top"))
        assertTrue(p.pendingFieldKey != Keys.WIN_LOSS)
    }
}
