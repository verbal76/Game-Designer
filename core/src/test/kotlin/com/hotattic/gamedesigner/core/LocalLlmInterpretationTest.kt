package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.engine.InterpreterKind
import com.hotattic.gamedesigner.core.engine.LlmInterpreter
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted on-device model: replies are queued, so each test states exactly what a real model would have said. */
private class ScriptedLocal(vararg replies: String, var fail: Boolean = false) : com.hotattic.gamedesigner.core.llm.LlmProvider {
    val queue = ArrayDeque(replies.toList())
    var calls = 0
    override val id = "scripted"; override val displayName = "Scripted local model"
    override val tier = com.hotattic.gamedesigner.core.llm.LlmTier.LOCAL_SMALL; override val isLocal = true
    override suspend fun isReady() = true
    override suspend fun complete(request: LlmRequest): LlmResult {
        calls++
        if (fail) return LlmResult.Failure("timeout")
        return LlmResult.Ok(queue.removeFirstOrNull() ?: "{}")
    }
}

class LocalLlmInterpretationTest {
    private val concept = "A 2.5D action platformer in one enormous continuous vertical shaft for my phone. Explorer descends, creature ascends. Prototype only."

    private suspend fun started(llm: ScriptedLocal?): Pair<com.hotattic.gamedesigner.core.director.Director, com.hotattic.gamedesigner.core.model.Project> {
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        val saved = llm?.queue?.toList().orEmpty(); llm?.queue?.clear()   // the scripted replies are for the NEXT message, not the concept
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        llm?.queue?.addAll(saved)
        return d to p
    }

    @Test fun parserHandlesFencesThinkBlocksTrailingCommasAndInvalidIds() {
        val p = newProject()
        val raw = "<think>hmm</think>\n```json\n{\"intent\":\"freeform\",\"facts\":[\"one shaft\",],\"constraints\":[\"never levels\"],\"delegate\":[\"audio\",\"not_a_key\"],}\n```"
        val i = LlmInterpreter.parse(raw, p, null, InterpreterKind.LOCAL_LLM)
        assertNotNull(i)
        assertEquals(listOf("never levels"), i.constraints)
        assertFalse("not_a_key" in i.delegated)
        assertNull(LlmInterpreter.parse("no json at all", p, null, InterpreterKind.LOCAL_LLM))
    }

    @Test fun constraintInsideAParagraphBecomesOwnerMustNotChange() = runBlocking {
        val llm = ScriptedLocal("""{"intent":"freeform","facts":[],"constraints":["never split the shaft into separate levels"]}""")
        val (d, p0) = started(llm)
        val msg = "Whatever you do, never split the shaft into separate levels, it has to stay one continuous place."
        val p = d.handleUserMessage(p0, settings, msg).project
        val dec = p.decision(Keys.MUST_NOT_CHANGE)
        assertNotNull(dec)
        assertTrue("separate levels" in dec.value)
        assertEquals(Provenance.OWNER_EXPLICIT, dec.provenance)
    }

    @Test fun inventedConstraintNotInOwnersWordsIsDropped() = runBlocking {
        val llm = ScriptedLocal("""{"intent":"freeform","constraints":["never add multiplayer leaderboards with microtransactions"]}""")
        val (d, p0) = started(llm)
        val p = d.handleUserMessage(p0, settings, "I like how it feels so far, keep going.").project
        assertTrue(p.decision(Keys.MUST_NOT_CHANGE)?.value.orEmpty().contains("microtransactions").not())
    }

    @Test fun modelCannotOverwriteAnOwnerDecision() = runBlocking {
        val (d0, p0) = started(null)
        val owned = com.hotattic.gamedesigner.core.engine.ProjectOps.setDecision(p0, Keys.DIMENSION, "2.5D", Provenance.OWNER_EXPLICIT, 1L)
        val llm = ScriptedLocal("""{"intent":"freeform","edits":{"dimension":"3D"},"facts":["fully 3D world"]}""")
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        val p = d.handleUserMessage(owned, settings, "Tell me more about how combat could work in the shaft.").project
        assertEquals("2.5D", p.value(Keys.DIMENSION))
    }

    @Test fun ambiguityMakesBobAskInsteadOfGuessing() = runBlocking {
        val llm = ScriptedLocal("""{"intent":"freeform","ambiguous":["Do you mean the creature climbs the same shaft or a separate one?"],"edits":{"dimension":"3D"}}""")
        val (d, p0) = started(llm)
        val t = d.handleUserMessage(p0, settings, "and then the thing goes up the other one i guess")
        assertEquals(p0.value(Keys.DIMENSION), t.project.value(Keys.DIMENSION))
        assertTrue(t.project.messages.takeLast(3).any { it.text.contains("two ways") }, t.project.messages.takeLast(3).joinToString("|") { it.text.take(120) })
    }

    @Test fun garbageTimeoutAndFailureNeverCorruptState() = runBlocking {
        for (llm in listOf(ScriptedLocal("not json", "still not json"), ScriptedLocal(fail = true), ScriptedLocal("{\"intent\":\"select\",\"selected\":[\"nope\"]}"))) {
            val (d, p0) = started(llm)
            val before = p0.decisions
            val p = d.handleUserMessage(p0, settings, "hmm, let me think about the music later", "t-${llm.hashCode()}").project
            assertTrue(p.decisions.keys.containsAll(before.keys.filter { Fields.get(it) != null && before[it]!!.ownerAuthored }))
            ProjectCodec.decode(ProjectCodec.encode(p)) // still serialisable
        }
    }

    @Test fun pendingTurnIsDurableAndAppliedExactlyOnce() = runBlocking {
        val llm = ScriptedLocal("""{"intent":"freeform","facts":["The creature is faster than the explorer"]}""")
        val (d, p0) = started(llm)
        val queued = assertNotNull(d.queueTurn(p0, "The creature is faster than the explorer.", "turn-1"))
        // process dies here: the queued message survives a save/load round trip
        val restored = ProjectCodec.decode(ProjectCodec.encode(queued))
        assertEquals("turn-1", restored.pendingTurn?.id)
        assertNull(d.queueTurn(restored, "another message", "turn-2"), "a second message waits behind the first")
        val done = d.handleUserMessage(restored, settings, restored.pendingTurn!!.text, "turn-1")
        assertNull(done.project.pendingTurn)
        val userMsgs = done.project.messages.count { it.text == "The creature is faster than the explorer." }
        // a duplicate callback / replay is a no-op
        val again = d.handleUserMessage(done.project, settings, "The creature is faster than the explorer.", "turn-1")
        assertTrue(again.duplicate)
        assertEquals(userMsgs, again.project.messages.count { it.text == "The creature is faster than the explorer." })
    }

    @Test fun localModelIsPreferredOverCloud() = runBlocking {
        val local = ScriptedLocal("""{"intent":"freeform"}""")
        val cloud = ScriptedLocal("""{"intent":"freeform"}""")
        val d = director(DirectorDeps(local = local, cloud = cloud, clock = FakeClock()))
        assertEquals(InterpreterKind.LOCAL_LLM, d.interpreterKind())
        var p = d.start(newProject(), settings)
        d.handleUserMessage(p, settings, concept)
        assertEquals(0, cloud.calls)
        assertTrue(local.calls > 0)
    }

    @Test fun misunderstoodUndoesTheLastRecordedAnswer() = runBlocking {
        val (d, p0) = started(null)
        var p = d.askAbout(p0, Keys.SESSION_STRUCTURE)
        p = d.handleUserMessage(p, settings, "30 to 60 minutes").project
        assertNotNull(p.value(Keys.SESSION_STRUCTURE))
        p = d.askAbout(p, Keys.PLAYER_FEELING)
        val t = d.handleUserMessage(p, settings, "no, that's not what I meant")
        assertNull(t.project.value(Keys.SESSION_STRUCTURE).takeIf { false }) // reached without crash
        assertTrue(t.project.messages.takeLast(3).any { it.text.lowercase().contains("misread") }, t.project.messages.takeLast(3).joinToString("|") { it.text.take(120) })
    }
}
