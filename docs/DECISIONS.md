# Engineering decisions (settled - do not re-research)

Authoritative product spec: `/CLAUDE.md`. This file records *engineering* decisions and the evidence behind them.
Each entry: decision, date verified, reasoning, how to revisit.

## D1. Android stack (verified 2026-10-04)
- **AGP 9.4.1**, **Gradle 9.8.0** (AGP 9.4 requires Gradle >= 9.6.0; 9.8.0 is the current release per services.gradle.org), **JDK 17+** (CI uses 17; the cloud authoring sandbox has 21).
- **Kotlin 2.4.20**. NOTE: AGP (root buildscript classpath, by id without version in :app) and the Kotlin plugins must share the root classloader or AGP 9 built-in Kotlin fails with NoClassDefFoundError: BaseVariant; changing the Kotlin version does NOT fix that with the Compose compiler plugin and kotlinx.serialization plugin at the same version. AGP 9 has built-in Kotlin, so the `kotlin-android` plugin is NOT applied in `:app`.
- **compileSdk/targetSdk 36**, **minSdk 28** (Android 9; covers essentially all active phones; needed for modern Keystore + file APIs). AGP 9.4 supports API 37; we stay on 36 until we have a device to verify behavior changes of 37.
- **Jetpack Compose** (BOM), Material 3, Navigation Compose, `androidx.core:core-splashscreen`.
- Versions live in `gradle/libs.versions.toml`. The cloud sandbox cannot reach `dl.google.com` (Google Maven + Android SDK), so androidx/AGP/litertlm versions in the catalog were taken from web search + release notes and are validated by **CI**, not locally. If CI reports a missing artifact version, fix it in the catalog.

## D2. Module layout
- `:core` - pure Kotlin/JVM (no Android APIs): domain model, interview schema, completeness/conflict/scope/asset/audit engines, Director, generators, persistence (file-based JSON with migrations), HTTP-based research/GitHub/cloud-LLM clients. 100% unit-testable on any JVM and the place nearly all logic lives.
- `:app` - Android only: Compose UI, ViewModels, Android Keystore secret store, on-device LLM runtime adapter (LiteRT-LM), file pickers, share/export.
- `settings.gradle.kts` includes `:app` unless `-PcoreOnly` is passed (used in environments without the Android SDK).

## D3. Persistence = JSON files, not Room
Reasons: projects are document-shaped, must be exportable/backup-able as-is, need versioned migrations on raw JSON, and avoiding KSP/Room keeps the build lean and verifiable. One directory per project (`projects/<id>/project.json`, atomic write via temp+rename, `.bak` of previous good copy, `.corrupt` evidence kept). Migrations: `MigrationRegistry` over raw JSON, schema version in `Model.kt` (`PROJECT_SCHEMA_VERSION`, currently 1).

## D4. On-device LLM: LiteRT-LM behind `LlmProvider` (verified 2026-10-04)
- Runtime: **LiteRT-LM** (`com.google.ai.edge.litertlm:litertlm-android`, v0.16.0 at verification; Kotlin API `Engine`/`EngineConfig`/`Conversation`, `.litertlm` model files, CPU/GPU/NPU backends). Google's recommended successor to the now maintenance-only MediaPipe LLM Inference API. Apache-2.0.
- Model: not bundled (multi-GB). Downloadable/importable model package. Default catalog entry: Google **Gemma** family in `.litertlm` form from the `litert-community` Hugging Face org (E4B confirmed to exist in the LiteRT-LM README; smaller variants follow the same naming). Qwen3 1.7B (Apache-2.0) is the documented alternative if a `.litertlm` build is available. Users can also import any `.litertlm` file from device storage - this is the verified-working path regardless of catalog drift.
- Hugging Face is **not reachable from the authoring sandbox**, so exact repo/file names in the in-app catalog must be verified on a real network (flagged `verified=false` in code until then).
- The Director never *depends* on the model: interview order, state changes, conflicts and generation are deterministic. The model improves free-text extraction and answers free-form questions. With no model installed the app is fully functional and says so honestly in the UI.

## D5. Cloud LLM
`LlmProvider` boundary; first implementation is the **Anthropic Messages API** (user-supplied key stored with Android Keystore AES-GCM; never in source). Used for hard tasks (final spec audit/synthesis, large repo analysis). Additional providers plug into the same interface.

## D6. Research
`ResearchProvider` boundary. Implementation uses only open, ToS-friendly sources: Wikipedia REST (reference-game summaries, CC BY-SA, attribution stored) and official version endpoints (Gradle, Maven Central metadata, GitHub releases API). Every result is stored as a `ResearchNote`/`ReferenceGame` with source URL + retrieval time. Respect for site terms: no scraping of asset marketplaces; asset sources are a curated registry (Kenney, Quaternius, Poly Haven, ambientCG = blanket CC0; OpenGameArt/itch.io/Freesound = per-asset, CC0 filter, verify each).

## D7. GitHub
Optional layer. Auth via user-supplied fine-grained personal access token stored in the Keystore-backed secret store (no OAuth client secret can be shipped in an app; a GitHub device-flow client ID can be added later without architecture change). Read-only inspection first; writes (create repo/commit) require explicit confirmation in UI.

## D8. Branding
`Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png` (repo root, 1536x1024 RGBA) is the authoritative studio logo and is **never modified**. App resources are derived by `tools/derive-branding.sh` (reproducible, ImageMagick). The Game Designer launcher icon is an original vector (flame + blueprint grid) consistent with the logo's palette.

## D9. Build verification reality
No Android SDK in the authoring sandbox -> `:app` is compiled and the APK is built by **GitHub Actions** (`.github/workflows/android.yml`), which also runs `:core` tests and uploads the debug APK as a workflow artifact. Never claim a device/emulator test that was not run.
