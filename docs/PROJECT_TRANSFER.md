# Backing up, importing and recovering projects

Personal projects are never part of the app, the repository, a test, a CI artifact or an update. Bob only ever reads a file you pick on your phone, when you pick it.

## What survives what
| Event | Projects |
|---|---|
| Updating Bob over the installed app (same app id and signing key), including OTA updates | **Kept.** They live in `files/projects/<id>/project.json` in private storage, which updates do not touch |
| Installing a build signed with a different key (for example a CI review build over a release build) | Android refuses it. The only way forward is uninstalling |
| Uninstalling Bob, or "Clear data" | **Deleted.** Android keeps no copy (Auto Backup is off on purpose: no cloud sync without your say-so) |
| A backup you saved to Downloads, Drive or a computer | **Survives** an uninstall |

## Back up (do this before any uninstall)
Settings > Your projects: backup and recovery > **Back up all projects**. Choose where to save (Downloads, Google Drive, a memory card). Bob writes the ZIP, reads it back, checks every checksum, and tells you it was verified. It holds every project (conversation, decisions, exclusions, approvals, spec versions, history) and uploaded images. It never holds API keys or tokens. Home shows a reminder when there has been no backup for a week.

### When a project is damaged
A backup never drops a project silently. Bob reads your project files without changing them. If a project's file is damaged but a recent good copy (`project.json.bak`) exists, the backup holds that good copy, keeps the damaged file under `recovery/` inside the backup, and tells you; if the project came from a newer Bob it is kept as it is; if no readable copy exists its raw files are kept under `unreadable/`. In all three cases the dialog says **"Backup needs attention"**, names the project, and the backup is not counted as a clean backup (the reminder stays). Nothing on the phone is changed or deleted.

### Size limits
Imports and backups are read in bounded pieces: a file over 64 MB is refused, a package that unpacks to more than 128 MB (or one entry over 48 MB, or far larger than it weighs) is refused as a possible decompression bomb. A normal backup is a few MB.

## Import or recover (after a reinstall, or to bring a file from elsewhere)
1. Open Bob. On the Home screen tap **Import or recover projects** (or Settings > **Import projects from files**).
2. Android's file picker opens. Pick one or several files: a Bob backup ZIP, a spec package ZIP exported from Bob, or a `project.json`. Browse to Downloads, Drive, or wherever you saved them.
3. Bob shows what each file is **before saving anything**:
   - *Complete backup*: lists the projects and says whether checksums were verified.
   - *Spec package that includes the complete project*: its conversation, decisions and spec versions can be imported.
   - *Generated specification only*: no project data inside, so it cannot become a project.
   - *Not recognised*, or a damaged or too-new file, with the reason.
4. Tap **Import**. Each project is added as a new project. Nothing you already have is overwritten: if a project with the same id or name exists, the imported one is saved as a separate copy (named "... (imported)").
5. Open the project from Home. Your original messages, decisions, exclusions, approvals and history are exactly as they were in the file.

**Check a backup file** (Settings) tests a file for damage without importing it.

Old backups made by earlier versions (no checksum list) still import; Bob says they are older backups it could read but not checksum.

## What this does not do
- It cannot recover a project that was never exported. A spec ZIP or a backup is the only source.
- It never reconstructs a project from memory or chat history.
