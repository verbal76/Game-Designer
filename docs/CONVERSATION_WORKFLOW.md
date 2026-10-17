# Question-and-answer workflow: one confirm, keyboard, following the conversation

Intended flow: read Bob's question, select or type an answer, tap **Confirm my answer**, the keyboard closes, the conversation expands, and the newest response is in view. Nothing covers the text, and scrolling back to read history is never fought.

## Root causes (from the code and from the real renders)
| Problem | Cause | Repair |
|---|---|---|
| Two answer buttons | The send arrow beside the answer box and CONFIRM both submitted: in a staged question the arrow simply called the same confirm. It did nothing unique there. In free chat (no question open) it is the only send action | `ComposerPolicy.showSendArrow(questionActive)`: no arrow while a question is open; kept for free messages, where it is the only way to send |
| Keyboard stayed open | Confirm never dismissed it or cleared focus | `rememberKeyboardDismiss()` (clear focus + hide) runs the moment an answer is submitted, before Bob replies; nothing requests focus on a new question or stage |
| Text hidden / screen in the wrong place | The scroll was fired from a `LaunchedEffect` when state changed, before the keyboard and list had finished resizing, so it used stale sizes; it also ran on unrelated changes and ignored where the owner was reading | `ConversationScroll` (core, pure) decides; `rememberConversationFollower` waits until items, viewport and keyboard inset stop changing (3 stable frames) and then scrolls. New content is followed only at the bottom; explicit actions always return to the newest content; the keyboard keeps a bottom reader at the bottom and never moves a reader who scrolled up |
| "More below" covered text | It was a floating chip drawn on top of the list | `ConversationViewport`: the control is a reserved strip below the list (real layout space), high-contrast, shown only while content is below (after a short settle delay so it does not flash), hidden at the bottom; it returns to the newest content |
| Confirm label cut off (found in the real render) | The Confirm button had a 2:1:1 width split and `maxLines = 1`: "Confirm my answer" showed as "Confirm my" on a 360 dp phone | Confirm takes 3/5 of the width and may use two lines; Back/Next use tighter padding; tests check no label is visually truncated at 320 dp |
| Enter might submit | Not the case: the answer box is multi-line with the default IME action | Made explicit (`ImeAction.Default`) and pinned by a test; Enter starts a new line |
| Double submission | Nothing refused a second tap before the screen updated | `SubmitGuard` ignores the same turn within 1.5 s; the engine's stable turn id also makes a repeat a no-op; Confirm is disabled while Bob is working |

## Where things live
- `core/director/ConversationScroll.kt`, `ComposerPolicy.kt` (decisions, JVM-tested).
- `applayer/ui/ConversationParts.kt`: `ConversationViewport`, `ReturnToLatestBar`, `AnswerComposer`, `QuestionAnswerBar`, `rememberKeyboardDismiss`, `rememberConversationFollower`.
- `Chat.kt` wires them to the ViewModel; the question engine, drafts, saved data and OTA/production identity are unchanged.

## Tests
- JVM (`ConversationWorkflowTest`, 13): send-arrow rule, Enter, double-submit guard, empty vs typed vs chosen answers, drafts survive, follow rules, restore.
- Robolectric, real Compose components (`ConversationUiTest`, in `:applayer`): no send arrow in a staged question; Confirm submits once, hides the keyboard (fake controller) and clears focus; a disabled Confirm does nothing; the typed answer is kept; the return strip is below the list, appears only when needed, disappears at the bottom and returns to the latest; opening shows the newest content; new content is followed at the bottom and not while reviewing history (and the owner is told); the keyboard resize keeps a bottom reader and does not move a reviewer; Confirm labels are not clipped at 320 dp; eight renders of the real components.
- Renders are produced in CI (`ui-renders-*` artifact and printed to the job log as base64). Robolectric has no soft keyboard, so "keyboard open" is a shorter window (what `adjustResize` leaves), and keyboard dismissal is checked with a fake controller plus the focus state. This is **not** a physical-device verification.

## Device checklist (needs the next TEST 2 build)
1. Answer by tapping a choice, tap **Confirm my answer**: one tap, one submission; the conversation lands on Bob's reply.
2. Type an answer (keyboard open), tap Confirm: the keyboard closes immediately and does not reopen when the next question appears or the stage changes.
3. Type half an answer, dismiss the keyboard with Back: nothing is submitted, the text is still there.
4. Press Enter in the answer box: a new line, not a submission.
5. Scroll up to old messages: the screen does not jump when Bob's reply arrives; "New message below" appears in its own strip; tapping it returns to the newest message.
6. Open and close the keyboard while reading at the bottom: you stay at the bottom; while reading old messages: nothing moves.
7. A long Bob message: you see its beginning, not its middle.
8. Rotate; largest font; 3-button vs gesture navigation: nothing is hidden behind the keyboard, composer or navigation bar; Confirm reads in full.
9. Free chat (no open question): the send arrow is present and sends.
10. Reopen a project: it opens at the newest content once, without jumping afterwards.
