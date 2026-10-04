# HANDOFF (read this first in a new context)

Branch: `ccr-6330e20f-dm7zhm` (PR #1, draft). Authoritative spec: `CLAUDE.md`. Decisions: `docs/DECISIONS.md`. Architecture: `docs/ARCHITECTURE.md`.

## Implemented and verified
- `:core` (pure Kotlin): model, schema (~56 fields), engines, Director, generators, persistence+migrations, web research, Anthropic provider, GitHub client/inspector. **63 JVM tests pass** (`./gradlew -PcoreOnly :core:test`).
- `:app` (Android): Compose UI (HotAttic splash, onboarding, home, chat, spec, branding, settings, repos), Keystore secrets, LiteRT-LM provider, model manager (download/import), backup/restore. **Compiled, linted and packaged by GitHub Actions CI** (`.github/workflows/android.yml`): at commit `d12cc89` both CI jobs were green (core tests, `lintDebug`, `assembleDebug`, app unit tests) and a ~40 MB debug APK artifact `game-designer-debug-apk` was uploaded. Authoring sandbox has no Android SDK, so always check the latest CI run for newer commits.

## NOT verified / known limits
- The CI-built APK has NOT been installed or run on any device or emulator by me. Runtime behavior (splash, UI layout, Keystore, LiteRT-LM engine loading, file pickers) is unverified.
- LiteRT-LM API usage (`Engine`, `EngineConfig`, `ConversationConfig`, `sendMessage(...).toString()`) follows the official Kotlin docs but is unverified against the artifact; model catalog URLs (Hugging Face) are unverified (`verified=false`).
- Sandbox cannot reach dl.google.com / Hugging Face: library versions were verified from release notes/search only.
- Image-generation of "original" branding assets is recorded as a decision and instruction for Claude Code; the app does not itself render generated art yet.

## Next dependency-ordered work
1. Owner: download the `game-designer-debug-apk` artifact from the latest Actions run and install it; report what breaks. Next: add an instrumented/emulator smoke test job to CI.
2. Verify/adjust LiteRT-LM usage and model URLs on a real device with network.
3. Reference traits from research should feed back into questions (e.g. dimension fork between a 3D and a 2D reference).
4. Asset search assistance (itch.io/OpenGameArt listings with license check) behind `ResearchProvider`.
5. Compose UI tests / screenshot checks; tablet layout pass.
6. Release signing config via CI secrets when a store release is wanted.

## Owner decisions genuinely needed
None blocking. Network policy note: allow `dl.google.com` and `huggingface.co` in the cloud environment if Claude should build/test the Android app locally (see docs/DECISIONS.md D9).
