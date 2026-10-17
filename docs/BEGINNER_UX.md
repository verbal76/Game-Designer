# Beginner UX completion and private Android test build

Built source: `26cd2bfc345762a29b33f5b3adf5329ad223dcbd` (Android CI run 38349180231, success: lint, debug APK, layer unit tests, 16 KB alignment).

## What changed
- **Mode selector** (`ui/Lesson.kt` `ModeSelector`, shown on the chat screen of a design project): Beginner / Advanced chips, current mode selected, one-line explanation ("Bob explains the choices and recommends the technical details." / "Show additional technical options and give me more control.") and "Both modes produce the same complete development plan." Switching uses `Director.switchMode`, the same path as the chat commands, so no answer, exclusion or approved decision changes; the mode is stored in the project and survives reopening.
- **Lesson cards** (`ui/Lesson.kt` `LessonCard`, `director/LessonParser.kt`): Bob's teaching text is split into a card. Always visible: what it is, why it matters, Bob's recommendation, advantages ("Good"), disadvantages ("Watch out") and a "Hard to change later" badge. Folded behind "Show details": how it works, what happens if you choose differently, long-term effects. "Explain more" asks for deeper detail. One column, wrapped text, theme font sizes, no sideways scrolling.
- Bob teaches the engine in the design review for Beginners (the engine is Bob's call there), and names the saving and controls choices in plain words with a way to change them.
- Defects found and fixed on the way: the review text could be mistaken for a lesson card; an owner's "no skill trees" became the game's title; online play planned for later was wrongly called a contradiction with an offline first build.

## Evidence
- Automated (JVM): 269+ tests including `BeginnerUxTest` (selector logic, switching, persistence, card parsing for every topic at every depth) and `BeginnerWalkthroughTest` (scripted knight-game session, 12 checks, two mode switches).
- Static inspection: the Compose source is checked for layout rules (no horizontal scroll, no fixed widths, no tiny fonts). It is not rendered.
- CI: the Android modules compile, lint and the layer unit tests pass.
- NOT verified: how any of this looks or feels on a physical phone; a real first-time user.

## Installing the private test APK
The CI artifact (`game-designer-debug-apk-<commit>`, a ZIP containing `app-debug.apk`) is **Game Designer TEST**: application id `com.hotatticgames.gamedesigner.test`, version name ending `-test`, its own private storage. It installs **next to** production Bob and cannot replace, update or delete it, so it is safe to install without uninstalling anything. Production Bob (`com.hotatticgames.gamedesigner`) is only ever built by the `release-vN.yml` workflows, signed with the permanent release key. See `docs/ANDROID_SIGNING_AND_TESTING.md` for signing and storage details and `docs/PROJECT_TRANSFER.md` for backup and import.
