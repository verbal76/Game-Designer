package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.AnswerDraft
import com.hotattic.gamedesigner.core.director.ComposerPolicy
import com.hotattic.gamedesigner.core.director.ConversationScroll as S
import com.hotattic.gamedesigner.core.director.ConversationScroll.Action
import com.hotattic.gamedesigner.core.director.ConversationScroll.Event
import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.GuidedPanel
import com.hotattic.gamedesigner.core.director.PanelAction
import com.hotattic.gamedesigner.core.director.SubmitGuard
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The answer box, keyboard and conversation-following rules, as pure logic. The drawing is covered by the Robolectric Compose tests in :applayer. */
class ConversationWorkflowTest {
    // ---- one answer-submitting control ---------------------------------------------------------------------------------------------------------

    @Test fun theSendArrowExistsOnlyForFreeMessagesNeverBesideAnOpenQuestion() {
        assertFalse(ComposerPolicy.showSendArrow(questionActive = true), "CONFIRM is the single submit action in a staged question")
        assertTrue(ComposerPolicy.showSendArrow(questionActive = false), "free messages (no open question) still need their send action")
    }

    @Test fun theEnterKeyNeverSubmits() {
        assertFalse(ComposerPolicy.enterSubmits(true)); assertFalse(ComposerPolicy.enterSubmits(false))
    }

    @Test fun theSameTurnIsNotSubmittedTwiceByADoubleTapButARetryLaterIsAllowed() {
        val g = SubmitGuard(1500)
        assertTrue(g.begin("t1", 1000)); assertFalse(g.begin("t1", 1100), "second tap in the same moment")
        assertTrue(g.begin("t2", 1200), "a different turn is independent")
        assertTrue(g.begin("t1", 5000), "a deliberate retry after a failure goes through")
    }

    // ---- submission semantics are unchanged -----------------------------------------------------------------------------------------------------

    private fun answer(k: String?) = when {
        k == "__proposals__" -> "yes"; k == "__asset_plan__" -> "looks good"; k == "__review__" -> "looks right"
        k?.startsWith("__conflict") == true -> "use alternative 1"; k == "__ready__" -> "generate"; else -> "choose for me"
    }

    @Test fun writtenAndChosenAnswersConfirmEmptyOnesDoNot() = runBlocking {
        val d = director(); var p = d.handleUserMessage(d.start(newProject(), settings), settings, "A calm 2D puzzle game for Android about restoring a garden.").project
        var sawChoice = false; var sawText = false
        repeat(40) {
            val m = GuidedPanel.model(p)
            if (m != null) {
                assertNull(GuidedPanel.confirm(m, emptySet(), ""), "an empty answer is never confirmed")
                assertNull(GuidedPanel.confirm(m, emptySet(), "   "))
                val typed = GuidedPanel.confirm(m, emptySet(), "my own answer")
                assertTrue(typed is PanelAction.Say && typed.text == "my own answer"); sawText = true
                if (m.options.isNotEmpty()) {
                    val pick = GuidedPanel.confirm(m, setOf(m.options.first().id), "")
                    assertNotNull(pick); sawChoice = true
                    assertEquals(pick, GuidedPanel.confirm(m, setOf(m.options.first().id), ""), "the same answer has the same turn id, so the engine can ignore a duplicate")
                }
            }
            p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project
        }
        assertTrue(sawChoice && sawText)
    }

    @Test fun aDraftSurvivesTheKeyboardBeingDismissedWithoutConfirming() {
        // Dismissing the keyboard only clears focus; the typed answer lives in the per-question draft, which only a recorded answer clears.
        val d = AnswerDraft().type("half an ans")
        assertEquals("half an ans", d.text); assertFalse(d.isEmpty)
        assertEquals(d, AnswerDraft.decode(AnswerDraft.encode(mapOf("k" to d)))["k"], "and it survives rotation / process restore")
    }

    @Test fun nothingOpensTheKeyboardOnItsOwn() {
        assertFalse(ComposerPolicy.FOCUS_ON_NEW_QUESTION); assertTrue(ComposerPolicy.DISMISS_KEYBOARD_ON_CONFIRM)
    }

    // ---- following the conversation -------------------------------------------------------------------------------------------------------------

    private fun run(vararg events: Event, from: S.State = S.State()): List<S.Step> {
        var s = from; return events.map { e -> S.step(s, e).also { s = it.state } }
    }

    @Test fun confirmingAndAdvancingAlwaysReturnToTheNewestContent() {
        for (e in listOf(Event.CONFIRMED, Event.ADVANCED, Event.RETURN_TO_LATEST)) {
            val away = S.State(following = false, unseen = true)
            val st = S.step(away, e)
            assertEquals(Action.SHOW_BOTTOM, st.action, "$e"); assertTrue(st.state.following && !st.state.unseen, "$e resumes following")
        }
    }

    @Test fun newContentIsFollowedWhileAtTheBottomAndStartsAtItsFirstItem() {
        val st = S.step(S.State(following = true), Event.NEW_CONTENT)
        assertEquals(Action.SHOW_NEW_CONTENT, st.action)
    }

    @Test fun anOwnerWhoScrolledUpIsNotPulledDownAndIsToldThereIsNewContent() {
        val steps = run(Event.USER_SCROLLED_AWAY, Event.NEW_CONTENT, Event.NEW_CONTENT, Event.KEYBOARD_CHANGED)
        assertTrue(steps.drop(1).all { it.action == Action.NONE }, "no forced scrolling while reviewing history")
        assertTrue(steps.last().state.unseen && !steps.last().state.following)
        assertEquals("New message below ↓", S.returnLabel(steps.last().state))
        assertEquals("More below ↓", S.returnLabel(S.State()))
    }

    @Test fun theKeyboardKeepsABottomReaderAtTheBottomButNeverMovesAReviewer() {
        assertEquals(Action.SHOW_BOTTOM, S.step(S.State(following = true), Event.KEYBOARD_CHANGED).action)
        assertFalse(S.step(S.State(following = true), Event.KEYBOARD_CHANGED).animate, "resizing is followed without an animated jump")
        assertEquals(Action.NONE, S.step(S.State(following = false), Event.KEYBOARD_CHANGED).action)
    }

    @Test fun returningToTheBottomByHandResumesFollowing() {
        val st = run(Event.USER_SCROLLED_AWAY, Event.NEW_CONTENT, Event.USER_REACHED_BOTTOM).last()
        assertTrue(st.state.following && !st.state.unseen); assertEquals(Action.NONE, st.action)
        assertEquals(Action.SHOW_NEW_CONTENT, S.step(st.state, Event.NEW_CONTENT).action)
    }

    @Test fun restoringAConversationShowsTheNewestContentOnceAndInstantly() {
        val st = S.step(S.State(following = false, unseen = true), Event.RESTORED)
        assertEquals(Action.SHOW_BOTTOM, st.action); assertFalse(st.animate)
    }

    @Test fun theReturnControlIsShownOnlyWhileThereIsContentBelow() {
        assertTrue(S.showReturnControl(true)); assertFalse(S.showReturnControl(false))
    }
}
