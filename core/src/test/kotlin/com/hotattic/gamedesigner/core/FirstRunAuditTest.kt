package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.ConversationView
import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.GuidedPanel
import com.hotattic.gamedesigner.core.model.Experience
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Role
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Audit of the first-run interview and its interaction with mode switching and reevaluation, using synthetic ideas only. Each test states the
 * owner-visible rule it protects: a question is asked once, answered questions are not replayed, switching mode or reevaluating never re-asks or
 * rewrites what the owner already decided.
 */
class FirstRunAuditTest {
    private fun answer(k: String?) = when {
        k == "__proposals__" -> "yes"; k == "__asset_plan__" -> "looks good"; k == "__review__" -> "looks right"
        k?.startsWith("__conflict") == true -> "use alternative 1"; k == "__ready__" -> "generate"; else -> "choose for me"
    }
    private suspend fun start(mode: Experience? = null): Pair<Director, Project> {
        val d = director(); var p = d.start(newProject(), settings)
        if (mode != null) p = d.switchMode(p, settings, mode)
        return d to d.handleUserMessage(p, settings, "A cosy 2D deck-building game for Android about running a tea shop.").project
    }
    private fun questionKeys(p: Project) = p.messages.filter { it.role == Role.DIRECTOR }.mapNotNull { it.question?.fieldKey ?: it.fieldKey }

    @Test fun noQuestionIsAskedTwiceAndAnsweredKeysAreNotReaskedInAFullInterview() = runBlocking {
        for (mode in listOf(Experience.BEGINNER, Experience.EXPERT)) {
            val (d, p0) = start(mode); var p = p0; val order = mutableListOf<String>()
            repeat(80) { val k = p.pendingFieldKey ?: return@repeat; if (order.lastOrNull() != k) order += k; p = d.handleUserMessage(p, settings, answer(k)).project }
            val dups = order.filter { !it.startsWith("__") }.groupBy { it }.filter { it.value.size > 1 }.keys
            assertTrue(dups.isEmpty(), "$mode asked these questions more than once: $dups")
            assertTrue(order.size > 10, "$mode interview reached enough questions: ${order.size}")
        }
    }

    @Test fun switchingModeBeforeTheFirstAnswerStillAsksEachQuestionOnce() = runBlocking {
        val (d, p0) = start(Experience.BEGINNER); var p = d.switchMode(p0, settings, Experience.EXPERT); p = d.switchMode(p, settings, Experience.BEGINNER)
        val before = p.messages.size; val key = p.pendingFieldKey
        p = d.handleUserMessage(p, settings, answer(key)).project
        assertTrue(p.messages.size > before)
        val m = GuidedPanel.model(p)
        val h = ConversationView.history(p, m).map { it.text }
        if (m != null) assertEquals(1, (h + m.title).count { it == m.title }, "'${m.title}' shown once")
    }

    @Test fun switchingModeAfterConfirmingNeverChangesRecordedDecisions() = runBlocking {
        val (d, p0) = start(Experience.BEGINNER); var p = p0
        repeat(8) { p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project }
        val recorded = p.decisions
        p = d.switchMode(p, settings, Experience.EXPERT)
        recorded.forEach { (k, v) -> assertEquals(v.value, p.decisions[k]?.value, "$k unchanged by mode switch") }
        p = d.switchMode(p, settings, Experience.BEGINNER)
        recorded.forEach { (k, v) -> assertEquals(v.value, p.decisions[k]?.value, "$k unchanged by switching back") }
    }

    @Test fun reevaluationDoesNotReplayConfirmedQuestionsOrChangeDecisions() = runBlocking {
        val (d, p0) = start(Experience.BEGINNER); var p = p0
        repeat(60) { if (p.pendingFieldKey == null) return@repeat; p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project }
        val decided = p.decisions; val askedBefore = questionKeys(p).toSet(); val msgs = p.messages.size
        val r = d.reevaluate(p, settings).project
        val newQuestions = questionKeys(r).drop(questionKeys(p).size)
        assertTrue(newQuestions.none { it in decided && decided.getValue(it).ownerAuthored }, "reevaluation re-asked decided keys: $newQuestions")
        assertTrue(askedBefore.isNotEmpty() && r.messages.size >= msgs)
        decided.forEach { (k, v) -> assertEquals(v.value, r.decisions[k]?.value, "$k unchanged by reevaluation") }
    }

    @Test fun switchingModeDuringAnOpenReevaluationKeepsTheSameFindingsAndDecisions() = runBlocking {
        val (d, p0) = start(Experience.BEGINNER); var p = p0
        repeat(60) { if (p.pendingFieldKey == null) return@repeat; p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project }
        var r = d.reevaluate(p, settings).project
        val status = r.reeval?.status; val decisions = r.decisions
        r = d.switchMode(r, settings, Experience.EXPERT)
        assertEquals(status, r.reeval?.status, "the reevaluation stays as it was"); assertEquals(decisions, r.decisions)
        r = d.switchMode(r, settings, Experience.BEGINNER)
        assertEquals(status, r.reeval?.status); assertEquals(decisions, r.decisions)
    }
}
