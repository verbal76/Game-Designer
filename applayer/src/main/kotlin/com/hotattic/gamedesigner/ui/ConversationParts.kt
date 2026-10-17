package com.hotattic.gamedesigner.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hotattic.gamedesigner.core.director.ConversationScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.runtime.withFrameNanos

/**
 * Pieces of the question-and-answer screen that are free of the ViewModel, so the real components can be rendered and tested on their own.
 * The decisions (when to follow, whether Enter submits, which controls exist) are in core: ConversationScroll and ComposerPolicy.
 */

/** Closes the soft keyboard and drops input focus so it cannot reopen by itself. Called the moment an answer is confirmed. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun rememberKeyboardDismiss(): () -> Unit {
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    return remember(focus, keyboard) { { focus.clearFocus(force = true); keyboard?.hide(); Unit } }
}

/**
 * The conversation: ONE scrolling list with, below it and outside it, a reserved strip for the return-to-latest control. The control therefore never
 * covers conversation text; it takes real layout space, and only while newer content is below the visible area.
 */
@Composable
fun ConversationViewport(
    listState: LazyListState,
    returnLabel: String,
    onReturnToLatest: () -> Unit,
    modifier: Modifier = Modifier,
    content: LazyListScope.() -> Unit,
) {
    // Shown only once content has stayed below for a moment, so a reply that is about to be followed does not flash the strip. Hidden at once at the bottom.
    val showReturn by produceState(false, listState) {
        snapshotFlow { ConversationScroll.showReturnControl(listState.canScrollForward) }.collectLatest { below -> if (below) { delay(160); value = true } else value = false }
    }
    Column(modifier) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag("conversation-list"), state = listState,
            contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content,
        )
        if (showReturn) ReturnToLatestBar(returnLabel, onReturnToLatest, Modifier.testTag("return-to-latest"))
    }
}

@Composable
fun ReturnToLatestBar(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().animateContentSize(), color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 2.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick, Modifier.heightIn(min = 44.dp), colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSecondaryContainer)) {
                Text(label, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The one answer box. [showSend] is false in a staged question (CONFIRM is the single submit action) and true only for free messages. The Enter key
 * never submits: the IME action stays "default", so Enter starts a new line.
 */
@Composable
fun AnswerComposer(
    text: String, onText: (String) -> Unit, placeholder: String, compact: Boolean, focusRequester: FocusRequester,
    showSend: Boolean, sendEnabled: Boolean, onSend: () -> Unit, modifier: Modifier = Modifier,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        OutlinedTextField(
            text, onText,
            Modifier.weight(1f).heightIn(max = if (compact) 96.dp else 168.dp).focusRequester(focusRequester).testTag("answer-field"),
            placeholder = { Text(placeholder) },
            minLines = 1, maxLines = if (compact) 3 else 6,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Default),
            shape = RoundedCornerShape(20.dp),
        )
        if (showSend) FilledIconButton(onSend, Modifier.padding(start = 8.dp), enabled = sendEnabled) { Icon(Icons.AutoMirrored.Filled.Send, "Send your message") }
    }
}

/**
 * A staged question's answer area: the answer box (no send arrow) above BACK | CONFIRM | NEXT. Confirming closes the keyboard immediately, before Bob
 * has answered, and clears focus so the keyboard does not come back.
 */
@Composable
fun QuestionAnswerBar(
    text: String, onText: (String) -> Unit, placeholder: String, compact: Boolean, focusRequester: FocusRequester,
    hint: String?, canBack: Boolean, onBack: () -> Unit,
    confirmLabel: String, confirmEnabled: Boolean, onConfirm: () -> Unit,
    nextEnabled: Boolean, onNext: () -> Unit,
) {
    val dismiss = rememberKeyboardDismiss()
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AnswerComposer(text, onText, placeholder, compact, focusRequester, showSend = false, sendEnabled = false, onSend = {})
        ActionBar(hint, canBack, onBack, confirmLabel, confirmEnabled, { onConfirm(); dismiss() }, nextEnabled, onNext, compact)
    }
}

/**
 * Waits until the viewport, the number of list items and the keyboard inset have stopped changing, so a scroll is never computed from stale sizes.
 * Gives up after a short time rather than leaving the owner without a scroll.
 */
suspend fun awaitLayoutSettled(listState: LazyListState, imeBottom: () -> Int, expectedItems: () -> Int) {
    withTimeoutOrNull(900) {
        var last: Triple<Int, Int, Int>? = null
        var stable = 0
        while (stable < 3) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val now = Triple(imeBottom(), info.totalItemsCount, info.viewportEndOffset - info.viewportStartOffset)
            stable = if (now == last && now.second >= expectedItems()) stable + 1 else 0
            last = now
        }
    }
}

/** One pending scroll, performed after the layout has settled. */
internal data class ScrollRequest(val seq: Int, val action: ConversationScroll.Action, val animate: Boolean)

/** Holds the follow state of one conversation list. Decisions come from [ConversationScroll]; this class only records them and queues the scroll. */
class ConversationFollower internal constructor(val listState: LazyListState) {
    var state by mutableStateOf(ConversationScroll.State())
        private set
    internal var request by mutableStateOf<ScrollRequest?>(null)
        private set
    internal var itemsBefore = 0
    private var seq = 0

    fun report(e: ConversationScroll.Event) {
        val st = ConversationScroll.step(state, e)
        state = st.state
        if (st.action != ConversationScroll.Action.NONE) { seq += 1; request = ScrollRequest(seq, st.action, st.animate) }
    }
}

/**
 * Keeps the conversation following new content, without fighting the owner. [itemCount] is the number of list items, [contentKey] changes whenever
 * the content changes (a reply, the next question), [imeBottom] is the keyboard inset, [projectKey] identifies the conversation (null while loading).
 *  - the first time a conversation is shown, and after it is restored, the newest content is shown once, instantly;
 *  - new content is followed only while the owner is at the bottom, otherwise the owner's position is kept and [ConversationFollower.state] says it is unseen;
 *  - the keyboard opening/closing keeps a bottom reader at the bottom and never moves a reader who scrolled up;
 *  - scrolling happens only after the layout (items, viewport, keyboard) has stopped changing, so it never uses stale sizes.
 */
@Composable
fun rememberConversationFollower(listState: LazyListState, itemCount: Int, imeBottom: Int, contentKey: Any?, projectKey: String?): ConversationFollower {
    val f = remember(listState) { ConversationFollower(listState) }
    val itemsNow by rememberUpdatedState(itemCount)
    val imeNow by rememberUpdatedState(imeBottom)
    var seenProject by remember { mutableStateOf<String?>(null) }
    var seenCount by remember { mutableStateOf(0) }

    LaunchedEffect(contentKey, projectKey) {
        val pid = projectKey ?: return@LaunchedEffect
        if (seenProject != pid) { seenProject = pid; seenCount = itemsNow; f.itemsBefore = 0; f.report(ConversationScroll.Event.RESTORED) }
        else { f.itemsBefore = seenCount; seenCount = itemsNow; f.report(ConversationScroll.Event.NEW_CONTENT) }
    }
    var imeSeen by remember { mutableStateOf(false) }
    LaunchedEffect(imeBottom > 0) { if (imeSeen) f.report(ConversationScroll.Event.KEYBOARD_CHANGED) else imeSeen = true }
    // The owner's own scrolling: dragging away from the bottom stops the following; coming back to the bottom resumes it.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { i ->
            if (i is DragInteraction.Stop || i is DragInteraction.Cancel) {
                snapshotFlow { listState.isScrollInProgress }.first { !it }
                f.report(if (listState.canScrollForward) ConversationScroll.Event.USER_SCROLLED_AWAY else ConversationScroll.Event.USER_REACHED_BOTTOM)
            }
        }
    }
    LaunchedEffect(f.request) {
        val r = f.request ?: return@LaunchedEffect
        awaitLayoutSettled(listState, { imeNow }, { itemsNow })
        if (itemsNow == 0) return@LaunchedEffect
        val last = itemsNow - 1
        if (r.action == ConversationScroll.Action.SHOW_NEW_CONTENT) {
            // The first new item goes to the top: a tall message is read from its start, a short tail simply ends at the bottom.
            val target = f.itemsBefore.coerceIn(0, last)
            if (r.animate) listState.animateScrollToItem(target) else listState.scrollToItem(target)
        } else if (r.animate) listState.animateScrollToItem(last)
        else { listState.scrollToItem(last); listState.scrollBy(10_000_000f) }   // exactly the end, e.g. while the viewport resizes
    }
    return f
}
