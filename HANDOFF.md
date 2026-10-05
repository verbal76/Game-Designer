# HANDOFF (read this first in a new context)

Branch `ccr-6330e20f-dm7zhm` (PR #1, draft). Spec: `CLAUDE.md`. Decisions: `docs/DECISIONS.md`. Architecture: `docs/ARCHITECTURE.md`. **OTA: `docs/OTA.md`.**

## Releases
- `v1` Physical Test Baseline 1 (commit 2b53d80, debug-signed with a throwaway CI key). Untouched.
- `v2` **OTA-capable native baseline** (latest): `Game-Designer-v2.apk`, signed with the persistent Game Designer release key, versionName 2.0.0.
- `ota-dev` prerelease: the dev OTA channel (`manifest.json`, `manifest.sig`, `bundle.zip`), currently bundle v2 `ota-proof-2`.
- `gd-signing-keys` is a PRIVATE DRAFT release holding the OTA signing key + APK keystore. Never publish or delete it (deleting = new signing identity: reinstall + new APK for OTA trust).

## Structure
`:core` (logic, OTA-able) / `:applayer` (UI+ViewModel, OTA-able) / `:shellapi` (stable contract) / `:otakit` (update engine, JVM) / `:app` (native shell: LiteRT-LM, secrets, loader, OTA manager) / `:otabundle` (packaging only). Fallback layer is compiled into the APK.

## Verified
CI green: core + otakit JVM tests, lint, theme-contrast test, bundle build/sign/verify. v2 workflow verified the real APK (apksigner, aapt2 badging, bundled layer + OTA runtime present) and ran a live OTA lifecycle against the published channel (discover, verify, stage, trial, commit, restart persistence, no loop, corruption rollback).

## NOT verified
Nothing has run on a device or emulator. Unverified on hardware: dex loading of the OTA bundle, first-frame health commit, the contrast fix visually, splash, Keystore, LiteRT-LM, pickers. The privacy of the draft key release rests on GitHub's documented behavior (confirm by viewing the Releases page logged out: `gd-signing-keys` must not appear).

## Publishing OTA / APK
OTA: edit `ota/publish-request.json` (channel, version > current, label; stable needs `confirm: PUBLISH-STABLE`) and push; ordinary pushes never publish. New APK (native/permission/dependency/resource/runtime change): add a new release workflow like `release-v2.yml`. Changing `shellApi`/Kotlin/Compose/AGP versions changes the runtime fingerprint and needs a new APK.

## Install note
v1 -> v2 cannot update in place (different signing key): export backup in Settings, uninstall v1, install v2, restore. Later APKs signed with the persistent key update over v2.

## Next (after owner's physical findings)
Reference-game fork gap; asset search; emulator smoke test in CI; consider moving signing material to repository secrets.
