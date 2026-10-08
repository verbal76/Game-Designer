# Over-the-air (OTA) updates

Game Designer is split into a **native shell** (installed with the APK) and an **application layer** (replaceable over the air).
The application layer is the same code twice: compiled into the APK as the **known-good fallback**, and shipped in signed OTA bundles.

## What is OTA-updatable (no new APK)
Everything compiled from these modules, delivered as dex code in `bundle.zip`:
- `:applayer` - all screens/UI (Compose), navigation, theme/colors, ViewModel, app container, Settings and diagnostics UI.
- `:core` - the Director, interview schema and genre knowledge, conflict/scope/asset/audit engines, `CLAUDE.md` and master-prompt generators, persistence format and migrations, research/GitHub/Anthropic clients.
So: UI fixes, copy/wording, theme changes, new or changed questions, generator output, scope/conflict rules, bug fixes in any of that.

## What still requires a new APK
- `:shellapi` (the shell<->layer contract), `:otakit` (the update engine itself), the shell classes (`MainActivity`, loader, OTA manager, LiteRT-LM adapter).
- Native code and native dependencies (LiteRT-LM runtime), any new third-party library or a changed version of Compose/AndroidX/Kotlin/AGP/serialization/coroutines (the layer runs against the libraries the shell shipped with).
- Android resources and manifest: drawables, the Hot Attic Games logo, launcher icon, themes in `res/`, **Android permissions**, components, SDK levels.
- Signing changes, `applicationId`, anything that changes the **runtime fingerprint** or **shell API level** (below).
A bundle that depends on any of these is rejected on the device by the compatibility gate, so it cannot break a running app.

## Trust and safety model
| Concern | Mechanism |
|---|---|
| Authenticity | `manifest.json` is signed with ECDSA P-256/SHA-256. The APK pins the public key (`assets/ota/trusted_keys.txt`, written by CI). No key in the APK (e.g. a local build) = OTA off. |
| Integrity | The signed manifest carries the bundle's SHA-256 and size; the download is hashed while streaming; mismatch = rejected and deleted. Re-verified from disk at every start. |
| Compatibility gate | Manifest must match the app's **shell API level** and **runtime fingerprint** (`k<kotlin>-c<composeBom>-a<agp>-s<shellApi>`), and `minShellVersionCode`. Otherwise "needs a new APK". |
| Anti-rollback | A bundle must be newer than the bundled layer, the active/pending/trial bundle and the highest ever committed version. A bundle that failed on this device is never re-offered. |
| Atomic activation | Verified download -> `versions/<n>/` (read-only, as Android 14+ requires) -> atomic `state.json` write (temp+rename) marks it *pending*. The running layer is never touched. |
| Activation timing | Only at the **next cold start**; no mid-session swap, no restart prompt, no launch flash, no loop. Automatic checks run in the background >=20 s after launch and at most every 6 h; "Check for update" in Settings is manual. |
| Trial and rollback | A newly activated bundle is a *trial* until the layer composes and stays alive 1.5 s, then it is *committed*. A recorded crash during the trial rolls back at the next start immediately; silent deaths get 3 starts. Falls back to the last committed OTA layer, else the bundled layer. |
| Load failure | Any class-loading/instantiation error falls back to the bundled layer in the same start and blacklists the bundle. |
| Corruption | Corrupt `state.json`, missing/modified bundle files -> bundled layer. Nothing in OTA state can prevent startup. |
| Secrets | The signing key never enters the APK or repo. See "Keys". |

Loader: `ChildFirstDexClassLoader` loads `com.hotattic.gamedesigner.*` from the bundle first (except `shellapi`, `shell`, `otakit`), everything else from the shell.

## Update lines (channels)
- **dev - automatic.** GitHub prerelease `ota-dev`. The app checks on every launch and every return to it (at most every 15 minutes), downloads the newest build, verifies it and schedules it for the next start, all without any action. An **"Update ready - Restart now"** banner appears so it can be applied immediately; it never restarts by itself. This build defaults to `dev` because it is a physical-test build.
- **stable - not automatic.** GitHub prerelease `ota-stable`, publishing requires `confirm: PUBLISH-STABLE`. Settings -> Diagnostics and updates shows a **drop-down of the last 10 published builds** (name, date, source SHA, downloaded/running/chosen). Choosing one downloads and verifies it if needed and schedules exactly that build for the next restart (the Restart banner appears). While on stable **nothing is ever scheduled automatically**, but newer builds keep downloading into the local cache so they are ready to choose. Choosing an older build is allowed (the owner's explicit choice bypasses anti-rollback; automatic updates never can).
- **off** - no checks.
Ordinary source pushes **never** publish. Publishing happens only when `ota/publish-request.json` is changed on a pushed branch (or the `Publish OTA bundle` workflow is dispatched manually): set `channel`, a `version` higher than the channel's current one, and a `label`. CI runs tests, builds the bundle, signs, verifies, then publishes.

### Channel contents (history)
Each channel release keeps the latest files older apps read (`manifest.json`, `manifest.sig`, `bundle.zip`) plus, per recent build, `manifest-<N>.json`, `manifest-<N>.sig`, `bundle-<N>.zip`, and an `index.json` listing the newest 10 builds **per runtime generation**. `index.json` is a discovery hint only: every build is verified against its own signed manifest (signature, hash, compatibility gate) on the phone. Builds for another runtime generation are never shown. If a channel has no index, its latest build is still listed.

## Keys
CI cannot use repository secrets here (the authoring environment cannot create them), so persistent key material lives in a **private draft release named `gd-signing-keys`** (visible only to repository writers; created on first use by `tools/ota/ensure-keys.sh`): the OTA signing key pair and the Android release keystore. **Do not publish or delete that draft**: deleting it changes the APK signing identity (forces reinstall) and the OTA trust key (forces a new APK). Moving the same files into repository secrets later is recommended hardening.

## Diagnostics
Settings -> *Diagnostics and updates* shows: native version/build, shell API level, runtime fingerprint, running layer (BUNDLED or OTA, version, label), the code identity compiled into the running layer, channel, trusted-key presence, last check and result, pending update, startup/rollback notes and rejected versions. Buttons: check now, switch channel, use bundled layer.

## Layer identity
Bundled layer: version `1`, label `v2-bundled`. OTA bundles are built with `-PlayerVersion=<n> -PlayerLabel=<name>`. The first dev bundle is version `2`, label `ota-proof-2`: identical source, different identity, so a successful OTA is visible in diagnostics without shipping any product change.

## Verified vs not verified
Verified by automated tests (`:otakit:test`) and the CI live proof against the published channel: signature, hash, compatibility gate, anti-rollback, staging, trial/commit/restart persistence, crash rollback, corruption fallback, no re-download loop.
**Not verifiable off-device:** `DexClassLoader` loading the bundle on a real phone and the first-frame health commit. The owner confirms this by installing v2, opening Settings -> Diagnostics, tapping *Check for update*, restarting the app and seeing `OTA v2 (ota-proof-2)`.
