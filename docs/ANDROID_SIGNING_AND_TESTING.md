# Android signing audit and side-by-side testing

Verified = read from the repository or checked by a test. Assumed = not checkable from here.

## Current arrangement (verified from source)
| Build | Application id | Signed with | Evidence |
|---|---|---|---|
| Production release (`release-vN.yml`) | `com.hotatticgames.gamedesigner` | The persistent release keystore from the private draft release `gd-signing-keys` (alias `gamedesigner`). The workflow checks the new APK's certificate against the previous release's and stops on a mismatch | `app/build.gradle.kts`, `release-v5.yml` |
| OTA bundles | n/a (dex only) | A separate OTA key pair (also in `gd-signing-keys`); the public half is baked into release APKs as `assets/ota/trusted_keys.txt` | `ota-publish.yml`, `GameDesignerApp.readTrustedKeys` |
| CI debug build (`android.yml`) before this change | the **production** id | The runner's default debug key, generated fresh on every CI run (no step ever kept it) | `android.yml` had no keystore step |
| Local `assembleRelease` without the env keystore | production id | **Now refuses** (before this change it fell back to the debug key) | guard in `app/build.gradle.kts` |

Consequences (verified by reasoning from the above): every earlier CI debug APK had a different signature from the release app and from every other CI debug APK, yet the same application id. Android refuses to install such an APK over the installed app, and the only way forward was an uninstall, which deletes private project data. This is the most likely cause of the earlier loss.

Not verified: the certificate fingerprint of the Bob currently installed on the phone, and of the keystore in `gd-signing-keys` (not readable from this environment). Do not assume they match any CI debug build; they do not.

## Recommended signing strategy
1. **Production.** Keep one release key, only for `release-vN.yml`. Back up the keystore and its password outside GitHub (a password manager), because losing it means every installed user must uninstall. Prefer moving it to GitHub Actions secrets later (an owner action; not done here). Never sign a debug or test build with it. A release build without the key now **fails** instead of falling back to the debug key (`app/build.gradle.kts` guard, exercised by a CI step and checked by `ReleaseSafetyTest`).
2. **Test builds.** Use a different application id and a different key, so a test build can neither replace nor conflict with production. Done: the debug build type now uses `applicationIdSuffix ".test"`.
3. **Test key stability.** The test key is the CI debug key, now kept in the Actions cache (`gd-test-debug-keystore-v1`) so successive test APKs update in place. It is a throw-away key for the test app only. If GitHub evicts the cache (unused for a week) the next test APK has a new signature; only the test app, which should hold synthetic data or a backup, then needs reinstalling. Production is unaffected.
4. **Never rotate silently.** Any change of production key needs an explicit owner decision and a migration plan (export a backup, install the new app under a new key, import).

## Side-by-side test build (verified in CI by the identity step)
- Package `com.hotatticgames.gamedesigner.test`, version name `5.0.0-test`, launcher label **Game Designer TEST**. Android treats it as a different app: separate private storage (projects, secrets, OTA state), separate permissions.
- It cannot update, overwrite or delete the installed production app, and installing or removing it does not touch production data.
- OTA is off in it: the APK carries no trusted OTA key (`GameDesignerApp` treats a missing `trusted_keys.txt` as "OTA off").
- Production behaviour is unchanged: only the `debug` build type changed; `release` keeps the production id and key.
- Not verified: behaviour on a device (nothing was installed).

## Installing the test APK (you do this; nothing was installed)
1. Download the CI artifact ZIP from the run page and extract `app-debug.apk`.
2. Open the APK on the phone and allow installation from that source if asked. It installs as a second app named "Game Designer TEST" next to Bob. Do not uninstall Bob.
3. In the test app: Home > Import or recover projects, then pick your backup or spec files. Projects go into the test app's own storage only.
4. Use Settings > Back up all projects to save a copy to Downloads or Drive. A backup is the only thing that survives removing the test app.
