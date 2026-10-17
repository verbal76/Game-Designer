package com.hotattic.gamedesigner.ui

import android.app.Application
import android.content.ComponentName
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.hotattic.gamedesigner.core.director.Accepted
import com.hotattic.gamedesigner.core.director.AnswerDraft
import com.hotattic.gamedesigner.core.director.ConversationScroll
import com.hotattic.gamedesigner.core.director.NextState
import com.hotattic.gamedesigner.core.director.PanelKind
import com.hotattic.gamedesigner.core.director.PanelModel
import com.hotattic.gamedesigner.core.director.PanelOption
import com.hotattic.gamedesigner.core.model.ChatMessage
import com.hotattic.gamedesigner.core.model.Role
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The real question-and-answer components (ActiveQuestionCard, QuestionAnswerBar, ConversationViewport, the follower) rendered and exercised under
 * Robolectric with synthetic data. This is not a phone: it has no soft keyboard, so the keyboard is represented by a fake controller (hide() calls are
 * counted) and by a shorter window (what adjustResize leaves for the conversation). Renders are written to build/ui-renders and uploaded by CI.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h740dp-xhdpi")
@OptIn(ExperimentalComposeUiApi::class)
class ConversationUiTest {
    @get:Rule val rule = createEmptyComposeRule()

    private fun show(w: Int = 360, h: Int = 740, content: @Composable () -> Unit) {
        RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-xhdpi")
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app.packageName, ComponentActivity::class.java.name))
        val a = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        a.setContent { GameDesignerTheme { content() } }
        rule.waitForIdle()
    }

    private fun render(name: String) {
        rule.mainClock.advanceTimeBy(600); rule.waitForIdle()     // lets the return strip appear where content overflows, as it does on a phone
        val dir = File("build/ui-renders").apply { mkdirs() }
        val bmp = rule.onRoot().captureToImage().asAndroidBitmap()
        FileOutputStream(File(dir, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val options = listOf(
        PanelOption("a", "A calm, cosy world", "Relaxed pacing, no fail state.", true, null),
        PanelOption("b", "Tense and fast", "Short rounds, high stakes.", false, null),
        PanelOption("c", "Somewhere in between", "A steady build with a few spikes.", false, null),
    )
    private fun model(withOptions: Boolean = true, title: String = "**Game feel.** What mood should a session of this game have?") = PanelModel(
        key = "feel", messageId = "m-q", kind = if (withOptions) PanelKind.SINGLE else PanelKind.TEXT, title = title,
        why = "It decides pacing, difficulty and the audio direction.", detail = null, options = if (withOptions) options else emptyList(),
        recommendationLabel = if (withOptions) "A calm, cosy world" else null, recommendationWhy = if (withOptions) "Fits a relaxing puzzle game." else null,
        lesson = null, canDelegate = true, canSkip = false, allowsText = true, currentLabel = null,
    )
    private fun msg(i: Int, mine: Boolean = i % 2 == 1, text: String = "Message number $i. ".repeat(if (i % 7 == 0) 12 else 3)) =
        ChatMessage(id = "msg-$i", role = if (mine) Role.USER else Role.DIRECTOR, text = text, at = i.toLong())

    // ---- one confirm control, keyboard dismissal ------------------------------------------------------------------------------------------------

    @Test fun noSendArrowBesideAStagedQuestionButOneForFreeMessages() {
        show {
            Column {
                QuestionAnswerBar("", {}, "Or type or dictate your own answer...", false, FocusRequester(), "Choose an answer, or type your own.", false, {}, "Confirm my answer", false, {}, false, {})
                AnswerComposer("", {}, "Type a message to Bob...", false, FocusRequester(), showSend = true, sendEnabled = false, onSend = {})
            }
        }
        rule.onAllNodesWithTag("answer-field").assertCountEquals(2)
        rule.onAllNodesWithContentDescription("Send your message").assertCountEquals(1)   // only the free-message composer
        rule.onNodeWithText("Confirm my answer").assertExists()
    }

    @Test fun confirmSubmitsOnceClosesTheKeyboardAndClearsFocus() {
        var confirms = 0; var hides = 0; var text by mutableStateOf("")
        val fake = object : SoftwareKeyboardController { override fun show() {}; override fun hide() { hides++ } }
        show { CompositionLocalProvider(LocalSoftwareKeyboardController provides fake) {
            QuestionAnswerBar(text, { text = it }, "Or type...", false, remember { FocusRequester() }, null, false, {}, "Confirm my answer", text.isNotBlank(), { confirms++ }, false, {})
        } }
        rule.onNodeWithTag("answer-field").performClick(); rule.onNodeWithTag("answer-field").assertIsFocused()
        rule.onNodeWithTag("answer-field").performTextInput("A garden at dusk")
        rule.onNodeWithText("Confirm my answer").performClick(); rule.waitForIdle()
        assertEquals("exactly one submission", 1, confirms); assertTrue("the keyboard was asked to close", hides >= 1)
        rule.onNodeWithTag("answer-field").assertIsNotFocused()
        assertEquals("the typed answer is still there for a retry", "A garden at dusk", text)
    }

    private fun assertConfirmLabelFits(label: String) {
        show(w = 320, h = 640) { Column(Modifier.fillMaxSize()) { ActionBar(null, true, {}, label, true, {}, true, {}, false) } }
        val node = rule.onNodeWithText(label).fetchSemanticsNode()
        val results = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        node.config[androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult].action?.invoke(results)
        val r = results.first()
        assertFalse("'$label' must not be visually truncated", r.hasVisualOverflow)
        assertTrue("'$label' fits in at most two lines", r.lineCount <= 2)
    }
    @Test fun confirmMyAnswerIsNotCutOffOnANarrowPhone() = assertConfirmLabelFits("Confirm my answer")
    @Test fun aLongChoiceLabelOnConfirmIsNotCutOffEither() = assertConfirmLabelFits("Confirm: A calm, cosy world")
    @Test fun aMultiSelectConfirmLabelIsNotCutOff() = assertConfirmLabelFits("Confirm 3 selected")

    @Test fun anUnavailableConfirmDoesNothing() {
        var confirms = 0
        show { QuestionAnswerBar("", {}, "Or type...", false, remember { FocusRequester() }, "Type your answer to enable Confirm.", false, {}, "Confirm my answer", false, { confirms++ }, false, {}) }
        rule.onNodeWithText("Confirm my answer").performClick(); rule.waitForIdle()
        assertEquals(0, confirms)
    }

    @Test fun dismissingTheKeyboardWithoutConfirmingKeepsTheUnfinishedAnswer() {
        var text by mutableStateOf(""); var confirms = 0
        show { QuestionAnswerBar(text, { text = it }, "Or type...", false, remember { FocusRequester() }, null, false, {}, "Confirm my answer", true, { confirms++ }, false, {}) }
        rule.onNodeWithTag("answer-field").performClick(); rule.onNodeWithTag("answer-field").performTextInput("half an answ")
        rule.runOnUiThread { }     // focus is dropped by the system on manual dismissal; nothing is submitted and nothing is cleared
        assertEquals("half an answ", text); assertEquals(0, confirms)
    }

    // ---- the viewport and the return-to-latest strip --------------------------------------------------------------------------------------------

    @Test fun theReturnStripIsNeverOverTextAppearsOnlyWhenNeededAndReturnsToTheLatest() {
        lateinit var state: LazyListState; var returns = 0
        show(h = 520) {
            state = rememberLazyListState()
            ConversationViewport(state, "More below ↓", { returns++ }, Modifier.fillMaxSize()) { items((0 until 40).toList()) { Text("Row $it", Modifier.padding(12.dp)) } }
        }
        rule.waitUntil(3000) { rule.onAllNodesWithTag("return-to-latest").fetchSemanticsNodes().isNotEmpty() }
        val list = rule.onNodeWithTag("conversation-list").getBoundsInRoot(); val bar = rule.onNodeWithTag("return-to-latest").getBoundsInRoot()
        assertTrue("the strip is below the list, not over it ($list vs $bar)", list.bottom <= bar.top + 0.5.dp)
        rule.onNodeWithText("More below ↓").performClick(); assertEquals(1, returns)
        rule.runOnIdle { runBlocking { state.scrollToItem(39); state.scrollBy(10_000_000f) } }
        rule.waitUntil(3000) { rule.onAllNodesWithTag("return-to-latest").fetchSemanticsNodes().isEmpty() }
    }

    @Test fun aShortConversationNeverShowsTheStrip() {
        show { ConversationViewport(rememberLazyListState(), "More below ↓", {}, Modifier.fillMaxSize()) { items((0 until 3).toList()) { Text("Row $it") } } }
        rule.mainClock.advanceTimeBy(1000)
        rule.onAllNodesWithTag("return-to-latest").assertCountEquals(0)
    }

    // ---- following: confirm, new content, manual scrolling, keyboard ----------------------------------------------------------------------------

    private class Harness { var count by mutableStateOf(30); var ime by mutableStateOf(0); var key by mutableStateOf(0); lateinit var f: ConversationFollower; lateinit var state: LazyListState }
    private fun showFollowed(h: Harness, height: Int = 520) = show(h = height) {
        h.state = rememberLazyListState()
        h.f = rememberConversationFollower(h.state, h.count, h.ime, h.key, "project-1")
        ConversationViewport(h.state, ConversationScroll.returnLabel(h.f.state), { h.f.report(ConversationScroll.Event.RETURN_TO_LATEST) }, Modifier.fillMaxSize().height(height.dp)) {
            items((0 until h.count).toList()) { Text("Row $it", Modifier.fillMaxWidth().padding(12.dp)) }
        }
    }
    private fun atBottom(h: Harness) = !h.state.canScrollForward

    @Test fun openingAConversationShowsTheNewestContentOnce() {
        val h = Harness(); showFollowed(h)
        rule.waitUntil(3000) { atBottom(h) }
        assertTrue(h.f.state.following)
    }

    @Test fun newContentIsFollowedAtTheBottomButNotWhileTheOwnerReviewsHistory() {
        val h = Harness(); showFollowed(h); rule.waitUntil(3000) { atBottom(h) }
        h.count = 35; h.key += 1                                  // new content while following
        rule.mainClock.advanceTimeBy(3000); rule.waitForIdle()
        assertEquals("the new items are laid out", 35, h.state.layoutInfo.totalItemsCount)
        assertTrue("new content was followed to the bottom (scrolling=${h.state.isScrollInProgress}, first=${h.state.firstVisibleItemIndex})", atBottom(h))
        rule.runOnUiThread { runBlocking { h.state.scrollToItem(2) } }                   // the owner scrolls up
        h.f.report(ConversationScroll.Event.USER_SCROLLED_AWAY)
        val first = h.state.firstVisibleItemIndex
        h.count = 38; h.key += 1; rule.mainClock.advanceTimeBy(3000); rule.waitForIdle()
        assertEquals("the reading position is kept", first, h.state.firstVisibleItemIndex)
        assertTrue("and the owner is told something is new", h.f.state.unseen)
        h.f.report(ConversationScroll.Event.CONFIRMED)                                   // an explicit action returns to the newest content
        rule.mainClock.advanceTimeBy(3000); rule.waitForIdle()
        assertTrue("confirming returned to the newest content", atBottom(h))
    }

    @Test fun theKeyboardResizingKeepsABottomReaderAtTheBottomAndLeavesAReviewerAlone() {
        val h = Harness(); showFollowed(h); rule.waitUntil(3000) { atBottom(h) }
        h.ime = 300; rule.waitForIdle(); rule.mainClock.advanceTimeBy(1500)
        assertTrue("still at the bottom after the keyboard opened", atBottom(h))
        rule.runOnIdle { runBlocking { h.state.scrollToItem(3) } }; h.f.report(ConversationScroll.Event.USER_SCROLLED_AWAY)
        val first = h.state.firstVisibleItemIndex
        h.ime = 0; rule.waitForIdle(); rule.mainClock.advanceTimeBy(1500)
        assertEquals("a reviewer is not moved by the keyboard", first, h.state.firstVisibleItemIndex)
    }

    // ---- renders of the real components ---------------------------------------------------------------------------------------------------------

    @Composable
    private fun Screen(history: List<ChatMessage>, model: PanelModel?, accepted: Accepted?, text: String, picked: Set<String>, compact: Boolean, state: LazyListState, label: String = "More below ↓") {
        Column(Modifier.fillMaxSize()) {
            ConversationViewport(state, label, {}, Modifier.weight(1f).fillMaxWidth()) {
                items(history, key = { it.id }) { m -> Bubble(m, "Bob") }
                if (accepted != null) item { AcceptedCard(accepted, NextState(true, null)) }
                else if (model != null) item { ActiveQuestionCard(model, AnswerDraft(picked, text), {}, { _, _ -> }, {}, {}) }
            }
            Surface(Modifier.fillMaxWidth(), tonalElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    if (accepted == null && model != null) QuestionAnswerBar(text, {}, "Or type or dictate your own answer...", compact, remember { FocusRequester() },
                        if (text.isBlank() && picked.isEmpty()) "Choose an answer, or type your own." else null, true, {},
                        if (picked.isNotEmpty() || text.isNotBlank()) "Confirm my answer" else "Confirm my answer", picked.isNotEmpty() || text.isNotBlank(), {}, false, {})
                    else ActionBar(null, true, {}, "Recorded", false, {}, true, {}, compact)
                }
            }
        }
    }
    private val hist = (1..4).map { msg(it, text = if (it % 2 == 1) "A cosy puzzle game about restoring a garden, please." else "Great start. I have a few questions to shape it.") }
    private val long = (1..40).map { msg(it) }

    @Test fun render1MultipleChoiceKeyboardClosed() { show { Screen(hist, model(), null, "", emptySet(), false, rememberLazyListState()) }; render("1-multiple-choice-keyboard-closed") }
    @Test fun render2WrittenAnswerKeyboardOpenViewport() { show(h = 430) { Screen(hist, model(false), null, "", emptySet(), true, rememberLazyListState()) }; render("2-written-answer-keyboard-open-viewport") }
    @Test fun render3AnswerReadyToConfirm() { show { Screen(hist, model(), null, "", setOf("a"), false, rememberLazyListState()) }; render("3-ready-to-confirm") }
    @Test fun render4ImmediatelyAfterConfirm() { show { Screen(hist, model(), Accepted("Recorded: A calm, cosy world", listOf("Noted: relaxed pacing."), 5), "", emptySet(), false, rememberLazyListState()) }; render("4-after-confirm") }
    @Test fun render5NextResponse() { show { Screen(hist + msg(5, mine = false, text = "Thanks. Next: how should failure feel?"), model(title = "**Failure.** What happens when the player makes a mistake?"), null, "", emptySet(), false, rememberLazyListState()) }; render("5-next-response") }
    @Test fun render6And7ScrolledUpWithMoreBelow() {
        lateinit var st: LazyListState
        show { st = rememberLazyListState(); Screen(long, model(), null, "", emptySet(), false, st) }
        rule.runOnIdle { runBlocking { st.scrollToItem(2) } }
        rule.waitUntil(3000) { rule.onAllNodesWithTag("return-to-latest").fetchSemanticsNodes().isNotEmpty() }
        render("6-7-scrolled-up-more-below")
    }
    @Test fun render8LongConversationKeyboardOpenViewport() {
        lateinit var st: LazyListState
        show(h = 430) { st = rememberLazyListState(); Screen(long, model(false), null, "Half an answer typed here", emptySet(), true, st) }
        rule.runOnIdle { runBlocking { st.scrollToItem(long.size); st.scrollBy(10_000_000f) } }
        rule.waitForIdle(); render("8-long-conversation-keyboard-open-viewport")
    }
    @Test fun render9Landscape() { show(w = 800, h = 360) { Screen(hist, model(), null, "", setOf("b"), true, rememberLazyListState()) }; render("9-landscape") }
}
