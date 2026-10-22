# Guided question panel (BACK | CONFIRM | NEXT)

Bob's open question is now shown in a focused panel docked above the conversation box instead of the old answer card. It is a **view over engine state**: `core/director/GuidedPanel.kt` derives the panel model from `Project` (the open question key, the last Director message and its `QuestionSpec` or quick replies); `applayer/ui/QuestionPanel.kt` draws it. No decision, pending state or sequence lives in the UI. The screen keeps only the draft selection, saved across rotation and tied to the message that asked the question.

## What the owner sees
Question, a one-line "Why Bob asks", Bob's recommendation (marked in words, never pre-selected, with a "Pick Bob's recommendation" button that only selects), the choices (radio rows, check boxes for multi-select), "Something else - type my own answer", "Choose for me" and "Skip / Ask me later", an "Explain this to me" fold that shows Bob's lesson card, a line saying what is selected and that it is **not confirmed yet**, and BACK | CONFIRM | NEXT. "Hide" folds the panel to one line without losing the question. The conversation box stays below at all times.

## The three buttons
- **CONFIRM** is disabled until something is selected or typed; it never answers for the owner. It calls the existing pipelines: `submitSelection(fieldKey, ids, turnId)` for structured questions, `send(text, turnId)` for typed answers and for quick-reply questions (conflicts, review wording, Yes / Not quite...). The turn id is stable for the same draft on the same question message (`panel:<messageId>:<hash of draft>`), so a repeated tap or replay is a no-op in the engine (`processedTurns`). Delegate and skip have their own stable ids.
- After the engine accepts it the panel says **Confirmed**, with what was recorded and anything Bob noted (the corrections he applied, for example). It is not shown if the engine asked the same question again because it could not use the answer.
- **NEXT** only reveals the question the engine already asked (that is just the panel model of the project as it stands). It is enabled only after a confirmation, and says why when blocked: "Confirm your answer first", "This needs a decision first" (conflicts), "Bob is still working", "your last message is saved but not answered".
- **BACK** is the engine's existing undo of the last answer (`goBack`): it clears that answer and what Bob inferred from it and asks it again. Earlier answers are kept. It is offered only when there is something to undo. To change anything older, type the change; the G6 correction system applies it.

## Replaced and preserved
| Old control | Now |
|---|---|
| Option chips (single tap answers) | Radio rows + CONFIRM (a deliberate extra tap, by design) |
| Multi-select chips, Select all / Clear / Continue | Check boxes, Select all / Clear, CONFIRM (n) |
| Yes / No chips | Yes / No options |
| Choose for me, Skip, Ask me later | same buttons, same command texts |
| Quick-reply chips for conflicts, proposals, review, ready, asset plan, references | options of the panel; the same text is sent; outside an open question the old chip row is still used |
| "Back to previous question" | BACK (the old text link remains when no panel is open) |
| Lesson card in the chat bubble | unchanged in the history, and in the panel's fold |
| Stage strip, mode selector, status, rename, preferences | untouched |
| Free text in the conversation box | untouched; typing while the panel is open goes through the engine and the panel follows |

## Tests
`GuidedPanelTest` (22) drives the real Director through the calls the panel makes: every question kind, recommendation never selected, CONFIRM through both pipelines, unconfirmed selection changes nothing, multi-select, free text on a structured question, duplicate and replayed turn ids, NEXT blocking and revealing, accepted-state expiry, BACK keeping earlier answers, a typed G6 correction while the panel is open, primary platform, conflict resolution, review, delegate and skip, state restoration through the project codec, and that no capability of the old card was lost. The two source-scan tests that asserted the old card in `Chat.kt` now assert the panel (generic rendering, no special-cased fields, recommendation in words, NEXT/BACK/CONFIRM present, 48 dp targets, no nested popups).

## Not verifiable here
Compose rendering, rotation, landscape, the software keyboard, haptics and touch feel need the CI build and a device. The layout keeps BACK | CONFIRM | NEXT outside the scrolling area, caps the panel at 50% of the usable height (62% in landscape or with the keyboard open, where it also drops the longer text), and applies the keyboard inset once on the parent.
