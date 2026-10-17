# v4 project-save investigation (read-only)

Method: git history (tags v1..v4, HEAD), manifests, Gradle files, `FileProjectStore`, the backup/restore code, release workflows, and the one project export available in this environment. No device was accessible and nothing was modified.

## Findings
| Question | Answer | Evidence |
|---|---|---|
| Application ID | `com.hotatticgames.gamedesigner`, identical at v1, v2, v3, v4 and now | `app/build.gradle.kts` at each tag |
| Code namespace | `com.hotattic.gamedesigner` (v2+ layer: `...applayer`). Namespace is not the install identity | same |
| Storage | Private app storage: `<filesDir>/projects/<id>/project.json`, plus `project.json.bak` (previous good copy), `project.json.corrupt` (quarantine), `assets/` (uploaded masters). Settings in `<filesDir>/settings.json`. API keys in Android Keystore-backed prefs (`gd_secrets`), never exported | `FileProjectStore.kt` (unchanged in layout v1..HEAD; HEAD only adds project-card summary fields) |
| Android path | `/data/data/com.hotatticgames.gamedesigner/files/projects/<uuid>/project.json` | `filesDir` in `GameDesignerApp` (v1) and `AppContainer` (v4) |
| Format | One pretty-printed JSON file per project, kotlinx.serialization, `schemaVersion` 1 (still 1 at HEAD, so every old project is readable by current code) | `ProjectCodec`, `PROJECT_SCHEMA_VERSION` at v4 and HEAD |
| Android backup | **Disabled**: `android:allowBackup="false"` at every tag. No Auto Backup, no `adb backup` | `AndroidManifest.xml` v1..HEAD |
| Shared/external storage | Not used. Nothing is written outside private storage except what the owner explicitly saves/exports through a file picker | grep for external storage APIs: none |
| Export 1: Backup all | Settings > backup writes a ZIP with `projects/<id>/project.json` (+ .bak, assets), no keys. Restore unzips into `projects/`, with a zip-slip guard, **overwriting a same-id project** | `AppViewModel.backupAll/restoreBackup` (v1..HEAD) |
| Export 2: Spec package | `ExportPackage` ZIP: CLAUDE.md, MASTER_PROMPT.md, ASSETS.md, docs/, **`game-designer/project.json` (the full project)**, and uploaded branding. **A spec package is therefore a complete project backup**, not only a spec | `ExportPackage.files` (v1, v4, HEAD) |
| In-place update keeps data | Yes, when the new APK has the same application ID and signing key: Android keeps private storage. Not through uninstall | Android behaviour; OTA only swaps code (`filesDir/ota`), never touches `projects/` |

## What most likely caused the loss (inference; not provable from the repo)
- Release APKs (v2 onward) are signed with the persistent key from the `gd-signing-keys` draft release. HANDOFF.md already records that v1 to v2 cannot update in place because of a different key.
- CI builds of `android.yml` (including the review builds, such as the `game-designer-debug-apk-*` artifacts) are signed with the runner's throw-away **debug key**, which differs from the release key (and per run). Installing one over a release build is refused by Android; the only way forward is uninstalling, which deletes `filesDir` including every project. `docs/BEGINNER_UX.md` warned about this.
- Application ID did not change, so the loss was an uninstall forced by a signature mismatch, not by a package rename.
- I cannot see the device, so I cannot establish which APK was installed when.

## Likelihood the original data survived an uninstall
- Private `filesDir`: **removed** at uninstall. Auto Backup disabled. No shared-storage copy was ever made automatically. Chance it survived on the device: essentially nil, unless the owner had a spec package / "Backup all" ZIP, or an installed copy still exists on another device or profile.
- No root, no forensic recovery (excluded by your rules).

## Recovery sources
| Source | Status |
|---|---|
| `Climb_up_spec_v1.zip` (uploaded in this session, sha256 `3b18d6ca...3b54`, 2,886,005 bytes) | **Inspected read-only on a copy.** Contains `game-designer/project.json` for project "Climb up" (schema 1, 45 decisions of which 22 owner-authored, 28 facts, 67 messages / 28 owner messages, 1 spec version) plus CLAUDE.md, MASTER_PROMPT.md, ASSETS.md and the studio splash master. It decodes with the current code and round-trips equal. **Climb Up appears fully recoverable** by import. |
| Songs of Syx | No export in this environment (git history, uploads, /tmp searched). Only synthetic regression fixtures exist, which are not the owner's data. |
| Either Character | Same: no export. Only a synthetic regression fixture (since replaced with an invented project). |
| Untitled Game | Nothing found. |
| Device files, Android backup, shared storage, other copies | Not accessible from this session. Owner action needed: check Downloads / Files app / Drive / email / chat for any `*_spec_*.zip`, `*.zip` exported from Bob, or a "Backup all" ZIP; check whether another phone/profile still has the app installed. |

## Recommended recovery procedure (needs your approval)
1. Climb Up: import the original `project.json` byte-for-byte into a new project directory using a collision-safe importer (new id if it already exists, never overwrite). Verify it opens, decisions/provenance equal the file, and the original ZIP stays untouched. Classify: fully recovered, if that holds.
2. Songs of Syx, Either Character, Untitled Game: search any further ZIPs you can supply. Without a file, they stay **unrecoverable**; I will not reconstruct them from memory. Reconstruction from the chat or synthetic fixtures is possible only as a clearly labelled "Reconstructed" new project with your approval of each decision.
3. Do not touch the device. You perform installs. Preventive step I propose in Phase 4 anyway: make the importer accept a spec package or a backup, warn before anything destructive, and add an in-app reminder that uninstalling deletes projects.

## Stopped here
Per your instructions, no restoration has been attempted. Waiting for approval of the procedure above.
