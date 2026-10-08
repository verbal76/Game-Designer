# Hot Attic Games platform policy (baked into every generated package)

Two permanent rules. They are Game Designer rules, not per-game design choices.

## 1. Android Target API 36 (automatic)
- When Android is a selected platform, `android_target_api = "36"` is derived (a derived `Field`, never asked, never offered in "refine"; `DerivedDefaults.apply`). If Android is removed, `DesignCoherence.reconcile` drops it.
- `engine/PlatformPolicy.kt` holds the wording. It appears in CLAUDE.md section **A0** (before the owner's requirements, so it cannot be missed), in MASTER_PROMPT.md under **PLATFORM POLICY**, and as a verification step.
- It tells the builder to select a compatible set (SDK, AGP, Gradle, JDK, AndroidX, engine Android components), never to keep an older target because a dependency/template uses it, never to pin older dependencies solely to preserve an obsolete target, and to report a genuine incompatibility instead of lowering the target.
- No Android text is emitted when Android is not a target; the coherence pass errors if it leaks.

## 2. OTA is an explicit decision; Mote is the reference
- Asked as `ota_updates` (applicable when the project targets any non-web platform): `none` (ABSENT), `content_ota` (PRESENT, kept id for older projects), unanswered (UNKNOWN). `DesignModel` exposes it as `SystemId.OTA_UPDATES` (`PRESENT / ABSENT / UNKNOWN / NOT_APPLICABLE`). Postponing does not resolve it: the planner brings it back and `DesignCoherence.check` raises `ota_unresolved` if generation is attempted while it is open. Only an explicit answer (or an explicit "choose for me", recorded as an accepted recommendation) settles it; an inference never overrides the owner's answer.
- **YES** selects the Hot Attic Games **Mote** OTA architecture: the builder inspects/follows the actual Mote implementation where reachable and carries forward the shell/runtime boundary, OTA-safe versus native-change classification, compatibility fingerprint, channel pointer, verified artifacts, staging, safe restart, rollback, update-loop protection, owner-controlled publication and exact-source release discipline. OTA does not mean every change is OTA-deliverable. If Mote is genuinely incompatible with the stack, the builder names the incompatibility, preserves as many Mote properties as possible, reports the deviation and gets the owner's approval; no silent substitution. EAS Update, CodePush and other hosted OTA products are never the default.
- **NO** emits one line: OTA is ABSENT, add no infrastructure. The old "what may updates change" question is retired (Mote classifies OTA-safe changes); older projects still load.
- Coherence errors: Android without API 36 in CLAUDE.md or MASTER_PROMPT.md; API 36 text without Android; OTA yes without Mote named; Mote/OTA infrastructure when OTA is no or not applicable; a hosted OTA product named as a default; OTA unresolved.
