# Interview and reevaluation UX repair

Based on device testing of the `bc77a5f` TEST build (that build is the baseline and is not changed by this work).

## Root causes

| Symptom | Cause | Repair |
|---|---|---|
| Question text with earlier chat glued into it; question shown twice | The chat drew every message, including the active question, and the guided panel drew it again | `ConversationView.history` hides/compacts the active question; Bob's lead-in becomes a single note on the card |
| Raw `**bold**` | Message text was drawn as plain text | `SimpleMarkdown` (pure parser) + `MarkdownText` adapter |
| Choices hidden below explanation, nested scrolling, buttons covering content | The panel was a height-capped scroller inside the chat scroller, with buttons inside it | One `LazyColumn`; the question is a card inside it; Back/Confirm/Next live in a fixed `ActionBar` outside it; "More below" chip |
| Unclear what is selected, Next vs Confirm | Selection and answer were one tap; no draft | `AnswerDraft` (pick/type exclusive, per-question), Confirm names what it records, Next never answers |
| Reevaluation picker gave no feedback, allowed overlapping runs | No run state | `ReevalRunTracker` (Idle/Running/Done/Failed), highlighted card, per-card progress, Cancel, Try again |
| Reevaluation results hard to read | Raw finding kinds and counts | `ReevalExplainer`: what was reviewed, unchanged, recommended, needs attention (with why/relation/choices/what changes/deferrable), completeness explained, labelled actions |
| Mode switch replayed or duplicated text | Long switch message and re-asked question | Short note; open question stays open, nothing re-asked |

## Where the logic lives

All decisions are in `:core` pure classes with JVM tests; the Compose files are thin adapters (`QuestionPanel.kt`, `Chat.kt`, `MarkdownText.kt`, `Reevaluate.kt`).

## Automated coverage

`InterviewViewTest`, `FirstRunAuditTest`, `ReevalRunTrackerTest`, `ReevalExplainerTest`, `ReevaluationTest`, `DataArchitectureAuditTest`, plus source-layout tests (`BeginnerUxTest`, `InteractionPolishTest`, `IntegrationValidationTest`). There is no Compose UI test infrastructure (no Android SDK offline); layout is verified by the checklist below.

## Manual device checklist (needs the next TEST build)

1. Start a project; confirm each question appears once, with no earlier chat inside it, and no `**` anywhere.
2. On a long question, confirm the choices are visible without hunting; "More below" appears when content is off-screen and scrolls when tapped.
3. Open the keyboard: stage strip/mode selector hide, question and composer stay usable; Back/Confirm/Next stay on screen.
4. Rotate during a question with a picked option and a half-typed answer: both survive.
5. Largest system font size: nothing clipped, buttons still reachable.
6. Pick an option: it is clearly highlighted; Confirm is enabled and names the answer; Next is disabled until confirmed; Back keeps the draft.
7. Type your own answer: picking is cleared; Confirm records exactly the typed text.
8. Switch Beginner/Advanced mid-question and after confirming: same question stays, nothing replayed.
9. Reevaluate: tapped card highlights with progress; other cards say Waiting; rapid taps start one run; Cancel and back-navigation behave; a failure shows Try again.
10. Results: read Summary and Needs attention in plain language; the primary button label says what it does; Discard leaves the saved design unchanged.
11. Android+iOS project with an added "Windows PC primary" fact: the finding explains the conflict and does not replace Android/iOS.
