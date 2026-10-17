# Data architecture audit (before the final APK)

Evidence: code reading plus AUTOMATED tests on synthetic projects (`DataArchitectureAuditTest`, `ProjectTransferTest`, `PersonalDataLeakGuardTest`). No device, no real model, no owner data.

## 1. What is distributed, and what each file is
The user-facing files, by evidence in the release workflows:
1. **The native APK** (`Game-Designer-vN.apk`, published as a GitHub release). The installed shell: Android manifest, Keystore access, model runtime, OTA loader, and the application layer as built.
2. **The OTA bundle** (`bundle.zip` containing only `classes*.dex`, plus `manifest.json` and `manifest.sig`, published to the `ota-dev` or `ota-stable` release). The replaceable application layer, signed.
Neither is a project file. They contain code, bundled assets (including the generated knowledge library) and trusted OTA keys, and no owner project; `PersonalDataLeakGuardTest` enforces that.
Separately, each designed game yields generated documents (`CLAUDE.md`, `MASTER_PROMPT.md`, `docs/...`) and, in spec packages, `game-designer/project.json` (the complete project). Those are per-owner outputs, never shipped with the app.

## 2. Storage
`<filesDir>/projects/<id>/project.json` (+ `.bak`, `.corrupt`, `assets/`). One JSON file per project, `schemaVersion` 1, additive fields (no schema bump) so older layers read newer files. Atomic temp-file write; the previous good copy is kept; a corrupt file is quarantined.

## 3. Path from owner words to specification and back
| Stage | Where it lives | Preserved? |
|---|---|---|
| Owner statements | `messages` (role USER, verbatim), `originalConcept`, `facts` (own words, ACTIVE/RETRACTED/SUPERSEDED, never deleted) | Yes. Tested byte-exact across save, load, backup, import, spec package and two reevaluations |
| Interpretation | `Decision.provenance` (DEFAULT < SYSTEM_INFERENCE < OWNER_ACCEPTED_RECOMMENDATION < OWNER_EXPLICIT < OWNER_CORRECTION), `rawAnswer` | Yes |
| Approvals | `designApproval` bound to a fingerprint of the reviewed state; any later change invalidates it | Yes (tested) |
| Rejections | `rejected` map | Yes |
| Exclusions | `ReferenceGame.excluded` (per reference) and gate decisions ("no combat") | Yes |
| Unresolved questions | Computed from the schema (`CompletenessEngine`), not stored as decisions | By design |
| Specifications | `versions` (append-only, each with decisions snapshot) | Yes; reevaluation leaves them byte-equal |
| Reference library | Separate ledger `knowledge` and library data; never in decisions, facts, prompts or specs | Yes (tested) |
| Reevaluation | Re-reads stored USER messages and facts and the stored decisions; ignores generated spec text (tested by poisoning the saved spec) | Owner decisions, rejections, references, facts, versions unchanged over three runs |

## 4. Weaknesses found
| # | Finding | Severity | Action |
|---|---|---|---|
| 1 | **Superseded decisions were overwritten.** `setDecision`, `clearDecision`, `reject`, gate removals and reconciliation replaced or dropped the active decision. The earlier owner value survived only inside a spec-version snapshot if a spec happened to be generated in between, or in the chat text | Data loss of owner intent history | **Fixed**: additive `Project.decisionHistory` (`DecisionRevision`: key, old value, provenance, status, timestamps, owner's raw words, replacement). Recorded at the single commit point (`ProjectSession.mutate`) so every mutation path is covered. Old values are never active, never read by the interview, readiness, approval fingerprint, or generators (tested) |
| 2 | **Unknown fields were erased on save.** Additive fields do not bump the schema, so a project saved by a newer layer and then saved by an older layer (an OTA rollback) lost the newer fields (decoder ignores unknown keys, encoder never wrote them back) | Data loss on rollback | **Fixed**: `ProjectCodec.encodePreserving` carries unknown top-level fields over from the stored copy (tested). Nested unknown fields are still dropped; documented limit |
| 3 | The old backup was an unchecked ZIP, restore overwrote same-id projects, and a spec package could not be imported at all | Overwrite risk, no integrity | **Fixed**: `ProjectTransfer` (manifest with SHA-256 per file, verify, collision-safe import, zip-slip and size limits, spec-only detection) |
| 4 | Reevaluation re-stamps Bob's own DEFAULT decisions (`updatedAt`) on every run | Cosmetic | None needed: values, provenance and status are identical across runs; owner decisions are byte-identical |
| 5 | Library cards are stored as Director chat messages inside `project.json` | Low: separate from decisions, flagged by `knowledgeStage`, USER-only reading means they never become facts | None |
| 6 | Interpretation-time "no X" about a reference was not attached to the reference (the Pac-Man case) | Owner-exclusion defect | **Fixed**: `ReferenceAspects.fromOwnerWords` reads liked aspects per clause and a bare "I do not want X" after a liked aspect becomes an exclusion on that reference (tested through the conversation and reevaluation) |
| 7 | Legacy decisions without provenance are inferred from their stored source and marked uncertain, never upgraded | Known limit | None |
| 8 | `docs/CLIMB_UP_GROUND_TRUTH.md` and `ClimbUpRegressionTest` are derived from the owner's real Climb Up answers, committed earlier as a regression oracle | Conflicts with the rule that personal projects stay out of the repository and tests | **Open: needs the owner's decision** (remove, or replace by a synthetic equivalent). The leak guard cannot flag it because it is prose and replayed answers, not a project file |

## 5. Round-trip and stability tests
- Round trip: project -> `FileProjectStore` -> backup ZIP -> import into a fresh store -> equal (`Project ==`) including versions, facts, history, ledger; same through a spec package; an older file without the new fields opens; a file with an unknown field keeps it after a save.
- Repeated reevaluation x3 on a project that went through conversation, approval and spec generation: owner decisions, accepted recommendations, rejections, references, active facts, spec versions and history unchanged; Bob's own decisions unchanged in value, provenance and status; discarding restores the design exactly.
- Migration: existing chain tests (raw-JSON steps, newer schema rejected without modifying the file, missing step is an error) plus the preserving save.

## 6. Verdict
Two defects needed fixing before the APK (1 and 2, plus the Pac-Man exclusion, 6), and are fixed. No storage redesign is needed. One open owner decision (8).
