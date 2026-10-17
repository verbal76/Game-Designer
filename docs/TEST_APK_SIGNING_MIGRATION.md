# TEST app signing: audit, repair, and the safe migration

## Audit (what was verified)
| Question | Finding | Evidence |
|---|---|---|
| Why did `bc77a5f` and `6d72dbe` differ? | Each CI run signed the TEST app with a freshly generated runner debug key | Certificates `f9b03fc4…81e4` (run 109) vs `10f0c8e7…db77` (run 113) |
| Why did the cache not help? | The cache step watched `~/.android/debug.keystore`; both runs logged "Path(s) specified in the action for caching do(es) not exist, hence no cache is being saved." The runner's keystore is not at that path, so nothing was ever stored | Job logs of runs 109 and 113 |
| Is the original key (`f9b03fc4…`) recoverable? | **No.** It existed only on the runner of run 109, which is destroyed. It was never cached (save failed), never an artifact, never a secret, and is not in the repository or this environment. A certificate fingerprint is a hash of the public certificate and cannot reproduce the private key | `git ls-files`, filesystem search, cache log |
| Is the production key involved? | No. Production uses the key in the private draft release `gd-signing-keys`; the TEST build type never used it, and still cannot | `app/build.gradle.kts`, `ReleaseSafetyTest` |

## Repair
- The TEST app (debug build type, `com.hotatticgames.gamedesigner.test`) is signed with its own persistent key. `tools/ota/ensure-test-key.sh` loads it from a **separate private draft release, `gd-test-signing-key`** (visible only to repository writers, does not expire, not an evictable cache) and creates it on the very first run. It records the certificate fingerprint next to the key and refuses a keystore that no longer matches it. If two first runs race, the oldest release wins.
- `android.yml` loads the key before building, signs with it (`GD_TEST_KEYSTORE*` → signing config `testStable`), and fails if the built APK's certificate is not the recorded one. The certificate is printed in the run summary.
- Production is untouched: package id, `release` signing config and its guard, OTA keys and OTA behaviour are unchanged. Local builds without the key fall back to the default debug key (cannot update a CI-built TEST app).
- Never delete or publish the `gd-test-signing-key` draft release: that changes the TEST app's identity again.

## In-place update from the currently installed TEST app: not possible
The installed TEST app is signed with a key that no longer exists. Android only installs an update signed with the same certificate, so **no build, now or ever, can update it in place.** This cannot be fixed by tooling; from now on every TEST build will update the previous one in place, starting with the first build signed by the new persistent key.

## Safest migration (nothing is deleted until a verified backup exists; you decide each step)
1. In the installed TEST app: Settings > Your projects: backup and recovery > **Back up all projects**; save to Downloads **and** copy the ZIP to Google Drive or a computer. Bob reads the backup back and verifies every checksum, then says it was verified.
2. Settings > **Check a backup file**, pick that ZIP: it must say "This file is intact". Compare the project count shown with what Home lists.
3. Keep the ZIP in two places. Only then is the migration safe to continue.
4. Installing the new TEST APK over the old one will be refused by Android (different certificate). Two ways forward; both are your decision:
   - **A. Replace (needs removing the old TEST app):** remove "Game Designer TEST" (this deletes its private data; the backup ZIP is what survives), install the new TEST APK, then Home > Import or recover projects, pick the ZIP and import. Projects return as exact copies. Verify against the project list from step 2.
   - **B. No removal:** if you want to keep the old TEST app untouched until you have confirmed the import, ask for a one-off TEST build with a different application id (for example `.test2`). It installs next to the old one; import the ZIP there, compare, and remove the old app only when you choose. This needs a code change and your authorization.
5. After migration, the persistent key makes every later TEST update in place with projects preserved. Production Bob is never touched by any of this.

Not verified: an actual install on a phone (nothing was installed), and the first CI run that creates the persistent key (see the CI results in the completion report).
