package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A confirmed owner decision is never flipped by a passing remark; the owner is asked. Evidence: AUTOMATED, synthetic, rules only. */
class ContradictionAcrossTurnsTest {
    private fun ans(k: String?) = when {
        k == "__proposals__" -> "yes"; k == "__asset_plan__" -> "looks good"; k == "__review__" -> "looks right"
        k?.startsWith("__conflict") == true -> "use alternative 1"; k == "__ready__" -> "generate"
        k == Keys.FIVE_MINUTES || k == Keys.CORE_FANTASY -> "Playing it should feel calm and rewarding."
        else -> "choose for me"
    }

    /** An RPG where the owner ruled combat out in the opening message, stopped at some later plain question. */
    private suspend fun noCombatThenLaterQuestion(): Pair<Director, Project> {
        val d = director(); var p = d.handleUserMessage(d.start(newProject(), settings), settings, "A pixel art RPG about running a village. No combat.").project
        repeat(60) { if (p.pendingFieldKey != null && !p.pendingFieldKey!!.startsWith("__") && p.pendingFieldKey != Keys.HAS_COMBAT && p.answerTrail.size >= 2) return d to p; p = d.handleUserMessage(p, settings, ans(p.pendingFieldKey)).project }
        error("no later question; pending=${p.pendingFieldKey}")
    }

    @Test fun aLaterDescriptiveMentionDoesNotFlipAConfirmedExclusion() = runBlocking {
        val (d, p) = noCombatThenLaterQuestion()
        assertEquals("no", p.value(Keys.HAS_COMBAT))
        val q = d.handleUserMessage(p, settings, "The villagers fight goblins with swords when raiders come.").project
        assertEquals("no", q.value(Keys.HAS_COMBAT), "a passing remark must not overwrite the owner's decision: ${q.decision(Keys.HAS_COMBAT)}")
        assertTrue(ConflictEngine.open(q).any { it.id == "fact_contradicts_gate" }, "the contradiction is raised: ${ConflictEngine.open(q).map { it.id }}")
    }

    @Test fun theOwnerIsAskedAndEitherAnswerSettlesIt() = runBlocking {
        val (d, p) = noCombatThenLaterQuestion()
        val q = d.handleUserMessage(p, settings, "The villagers fight goblins with swords when raiders come.").project
        var r = q
        repeat(20) { if (r.pendingFieldKey?.startsWith("__conflict:fact_contradicts_gate") != true) r = d.handleUserMessage(r, settings, ans(r.pendingFieldKey)).project }
        val conflict = assertNotNull(ConflictEngine.open(r).firstOrNull { it.id == "fact_contradicts_gate" })
        assertEquals(2, conflict.alternatives.size)
        val keep = d.handleUserMessage(r, settings, "use alternative 1").project
        assertEquals("no", keep.value(Keys.HAS_COMBAT)); assertTrue(ConflictEngine.open(keep).none { it.id == "fact_contradicts_gate" })
        val add = d.handleUserMessage(r, settings, "use alternative 2").project
        assertEquals("yes", add.value(Keys.HAS_COMBAT)); assertTrue(ConflictEngine.open(add).none { it.id == "fact_contradicts_gate" })
    }

    @Test fun anExplicitChangeOfMindIsAppliedAndSaid() = runBlocking {
        val (d, p) = noCombatThenLaterQuestion()
        val q = d.handleUserMessage(p, settings, "Actually, I want combat after all.").project
        assertEquals("yes", q.value(Keys.HAS_COMBAT))
        assertTrue(q.decision(Keys.HAS_COMBAT)!!.prov.ownerAuthored)
        assertTrue(ConflictEngine.open(q).none { it.id == "fact_contradicts_gate" })
    }

    @Test fun repeatingTheSameRemarkDoesNotDuplicateTheConflict() = runBlocking {
        val (d, p) = noCombatThenLaterQuestion()
        var q = d.handleUserMessage(p, settings, "The villagers fight goblins with swords when raiders come.").project
        q = d.handleUserMessage(q, settings, "The villagers fight goblins with swords when raiders come.").project
        assertEquals(1, ConflictEngine.open(q).count { it.id == "fact_contradicts_gate" })
        assertFalse(q.activeFacts().groupBy { it.text }.any { it.value.size > 1 })
    }
}
