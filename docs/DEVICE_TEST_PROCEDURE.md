# Device test procedure (Game Designer TEST)

What the automated tests cannot show: the Compose screens, Android's file picker and storage providers, real on-device inference, and behaviour across installs. Run these on the phone with the **Game Designer TEST** app (`com.hotatticgames.gamedesigner.test`). Production Bob is never touched. Mark each line pass/fail and note the build (Settings > About shows `5.0.0-test` and the commit).

## A. Import, backup and recovery (use synthetic or copied files, never your only copy)
| # | Do | Expect |
|---|---|---|
| A1 | Home > Import or recover projects, then press Back in the picker | Nothing changes, no dialog |
| A2 | Pick a Bob backup ZIP from Downloads | Preview names the file, says "Complete backup ... checksums verified" and lists the projects; nothing saved until you tap Import |
| A3 | Cancel at the preview, reopen Home | No project was added |
| A4 | Pick the same backup again and Import; repeat once more | Projects appear; the second time as "(imported)" copies; existing ones unchanged |
| A5 | Pick a spec-only ZIP (CLAUDE.md and prompts, no project.json) | Preview says it holds a generated spec only and cannot be imported; no Import button |
| A6 | Pick a text file or a photo | "Not recognised", no import |
| A7 | Pick a file from Google Drive (a provider that streams) | Same preview as a local file |
| A8 | Settings > Back up all projects, save to Downloads | A dialog says "Backup saved" with the verified count; Home's backup reminder disappears |
| A9 | Choose a destination that is read-only or full (SD card removed, a locked Drive folder) | "The backup could not be written", no success message, reminder stays |
| A10 | With a damaged project (see C) take a backup | Dialog titled "Backup needs attention" naming the project and how it was handled; the reminder stays |
| A11 | Settings > Check a backup file on the new backup | "This file is intact" |
| A12 | Reinstall the TEST app only (remove Game Designer TEST, install again), then import the backup | Every project returns with its messages, decisions and spec versions |
| A13 | Install a newer TEST APK over the old one (same signing cache) | Projects are still there |

## B. Side-by-side
| # | Do | Expect |
|---|---|---|
| B1 | Install the TEST APK with production Bob installed | Two apps: "Game Designer" and "Game Designer TEST"; production Bob and its projects are unchanged |
| B2 | Open Settings > About in TEST | Version ends `-test`; OTA reports off or no update channel |
| B3 | Create a project in TEST | It does not appear in production Bob |

## C. Damaged project (optional, needs a file manager with access to app data, or use a debug tool)
Only on a throw-away project: truncate its `project.json` while a `project.json.bak` exists. A backup must still include the project (from the last good copy) and report it as recovered, and must not change either file.

## D. On-device AI (hedged, negated and contradictory owner statements)
Prerequisite: Settings > Set up on-device AI, load a model, "Use the on-device model" on, and confirm the banner reads LOCAL AI. Then, in a new project, send each message on its own turn and compare with the expected result. Record the model name and version with the results. Repeat with the model switched off (RULES-ONLY); the owner-authority results must be identical in both modes.
| # | Owner says (open question in brackets) | Expected | Fails if |
|---|---|---|---|
| D1 | "Songs of Syx population simulation but no combat" | Reference Songs of Syx; combat not asked or kept out; no combat mechanics in the design | combat recorded as wanted |
| D2 | "I like Pac-Man's role reversal but I do not want power-ups" | Reference Pac-Man, liked aspect "role reversal", exclusion "power-ups" shown in the design review | power-ups appear as a feature |
| D3 | "Maybe Windows later, Android first" | Platforms: Android only; Windows shown as a possibility, not a target | Windows is a target |
| D4 | [Who is this game for?] "No, not for a general audience" | Audience is not "general"; the question is asked again | "general" recorded |
| D5 | "Make it 3D. No wait, keep it 2.5D." after confirming 2.5D | Stays 2.5D | becomes 3D |
| D6 | "I might want multiplayer someday, I'm not sure" | No confirmed multiplayer decision | multiplayer confirmed |
| D7 | "There is no combat at all in this game" on a puzzle concept | Combat never switched on | combat on |
What this does and does not establish: the automated tests (`ModelInterpretationValidationTest`) prove the protections hold against a hand-authored model that makes these mistakes; they say nothing about how a real model reads the words. D1 to D7 are the only evidence about a real model, and they are not yet run.
