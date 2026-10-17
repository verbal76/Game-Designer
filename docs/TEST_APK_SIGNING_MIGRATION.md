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

## Option B in practice: the side-by-side "Game Designer TEST 2" app
The migration build is `com.hotatticgames.gamedesigner.test2`, label **Game Designer TEST 2**, version `5.0.0-test2`, signed with the persistent TEST key. It is a different application from the installed TEST app (`...gamedesigner.test`) and from production Bob (`...gamedesigner`): its own private storage, nothing shared. Installing it does not touch, update, replace or remove either of them. It is built by dispatching `android.yml` with `test_variant = test2`; the normal run (empty input) still builds the usual TEST app. Only those two values are accepted, and production's id and signing are unchanged.

### Import and compare (you do this; nothing is removed automatically)
1. **Back up in the old TEST app** (the one that holds your projects): Settings > Your projects: backup and recovery > Back up all projects. Wait for "verified". Save to Downloads and copy the ZIP to Drive or a computer. Then Settings > Check a backup file on it: "This file is intact".
2. **Install TEST 2.** Download the CI artifact, unzip it, open `app-debug.apk`. It appears as a second icon, "Game Designer TEST 2". The old TEST app stays.
3. **Import into TEST 2:** open TEST 2 > Home > Import or recover projects > pick the backup ZIP. The preview must say "Complete backup ... checksums verified" and list your projects. Tap Import.
4. **Compare every project** (old app vs TEST 2), one at a time: the name, the conversation (first and last messages), the decisions and exclusions, the approvals and spec versions, the project count. TEST 2 holds exact copies; imported projects are never renamed unless the same id already exists there.
5. If a project looks different or missing, stop: the old TEST app still holds the original and the ZIP is intact. Tell me which one and I will investigate from the file.
6. Only when you are satisfied, remove the old TEST app yourself. TEST 2 then remains your test app; its projects are not affected by removing the other app. Later TEST 2 builds update it in place (same key and id). Removing "TEST 2" at any time deletes only its own data.

### What was verified here
- Automated (core, JVM, synthetic projects): a backup moves into a completely separate store with every project identical, including a field this build does not know; a damaged project is recovered from its good copy and the backup is reported as INCOMPLETE rather than clean; importing twice never overwrites (`MigrationPortabilityTest`, `TransferControllerTest`, `ProjectTransferTest`, `DataArchitectureAuditTest`).
- CI: the TEST 2 APK's id, version, label and certificate are checked against the requested variant and the recorded persistent key.
- Not verified until you try it: installing on a phone, and importing your own backup.
