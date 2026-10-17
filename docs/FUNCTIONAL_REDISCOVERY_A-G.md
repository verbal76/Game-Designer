# Functional rediscovery A-G (companion to FUNCTIONALITY_INVENTORY.md, which keeps sections H and I)

Read-only investigation of branch `ccr-6330e20f-dm7zhm` at `79f0460`. No application code, pipeline or release was changed. Evidence classes: **code** (read), **test** (an existing JVM test asserts it), **probe** (I ran a throw-away test against the current code and then deleted it; results quoted below), **inferred** (reasoned, not exercised), **device** (not verified; none of this was run on a phone). Passing JVM tests are not treated as proof of end-to-end or on-device behaviour.

## Executive summary

**Recommendation: do NOT begin the interface redesign yet. Begin only after the five engine corrections in G6 and their regression tests (G8) are done. The redesign itself is feasible without a second state machine (F), but it would put a friendlier face on a correction path that currently loses owner instructions.**

Prioritized findings

| # | Priority | Finding | Evidence |
|---|---|---|---|
| 1 | **P0** | An owner instruction that changes an already-confirmed decision, typed as free text outside the open question, is recorded only as a fact. The decision is not changed and Bob does not say so. Probe: "Actually, forget Android. It is a PC game now." left `platforms=android` (OWNER_EXPLICIT); spec then listed "Actually, forget Android." and "It is a PC game now." as owner requirements AND "Target platforms: Android". | probe; `Director.applyStatement`, `ProjectOps.setDecision` |
| 2 | **P0** | Nothing detects a contradiction between owner facts and decisions, so the export, the review and readiness are all clean while the document contradicts itself. Probe: `generate` returned `blocked=false`, no errors. | probe; `DesignCoherence`, `ConsistencyReview` check systems, not platforms |
| 3 | **P1** | Any capitalised word run in free text can become a reference game, and research is then run on it. Probe: "Add PC as the primary platform." created reference **"Add PC a"**. The guard that prevents this exists only for the Game-name answer and for `rereadOwnerWords`, not for `applyStatement`. | probe; `DecisionExtractor.referenceGames`, `Director.applyStatement`, `researchNewReferences` |
| 4 | **P1** | "100% ready" means: decision share is complete, no BLOCKER conflict, design approved. It does not mean the design is consistent. The approval fingerprint ignores references. | code; `DesignDimensions.percent`, `ReviewGate.fingerprint`; probe (`refs=false`) |
| 5 | **P1** | "Primary platform" cannot be stated in chat: there is no rule-based extraction for it (only the question or a model inference), and platform words only ever propose; a proposal never overrides a confirmed decision. Probe: "Make PC the primary platform and keep Android and iOS as secondary." extracted `platforms=android|ios` (windows missed) and nothing for primary. | probe; `DecisionExtractor.platformWords` ("pc" alone is not a keyword) |
| 6 | P2 | Working title is taken from the tail of the concept: "Interconnected Economy", "Ropes Jumps" were shown in the review. | probe; `TitleSuggester` |
| 7 | P2 | Owner-authored dependents are never invalidated when a root changes (by design), so a platform change leaves earlier owner-answered controls/orientation unchanged without a warning. | code; `Reconciler.reconcile` skips `ownerAuthored` |
| 8 | P2 | The "combat-focused roguelite" emphasis in the Songs of Syx export was NOT reproduced. Plausible but unproven sources: the researched summary of a wrongly identified reference copied verbatim into the spec, or genre keywords. | probe (negative); inferred |
| 9 | P3 | Hidden overloaded state: `announcedConflicts` doubles as a once-only registry (`ack:`, `followup:`, `luck:`, conflict ids). A UI that resets or replays state must preserve it. | code |

The Songs of Syx export itself was **not available in this session** (only `Climb_up_spec_v1.zip` exists in the uploads); everything about it below is mechanism-level reproduction against synthetic projects.

---

## A. Original purpose and design intent

Intent evidence: README (one sentence: collect aggregated input, build a `claude.md` and a master Claude prompt "to start game development with a working build"); the owner's specification in repository-root `CLAUDE.md` (sections 1-3, 21-24, 29-30: conversational, adaptive, completeness-driven, final audit, versioned CLAUDE.md plus master prompt, repository-aware continuation, playtest loop); `docs/ARCHITECTURE.md`, `docs/CONVERSATION.md`, `docs/DESIGN_INTELLIGENCE.md`, `docs/PLATFORM_POLICY.md`, `docs/PROTOTYPE_HANDOFF.md`, `docs/MASTER_UPGRADE.md`.

| Intended | Implemented (code) | Verified |
|---|---|---|
| Conversation, not a form; Bob infers, asks only what is unresolved | `Director`, `InterviewPlanner`, `DesignSeeder`, `applyInferences`, `LlmInterpreter` | JVM tests with scripted answers/providers (`AdaptiveQuestionsTest`, `DesignerIntelligenceTest`, `TowerClimbRegressionTest`); no real model, no device |
| Suggest choices, explain tradeoffs | `Field.suggest`, `Explainer`, `Acknowledgements`, `ConflictEngine` | tests; card rendering on device unverified |
| Identify missing requirements and contradictions | `CompletenessEngine`, `ConflictEngine`, `ConsistencyReview`, `DesignCoherence` | tests, **but only for contradictions between decisions and systems, not between owner facts and decisions** (finding 2) |
| Apply the owner's later corrections | partially: option answers and gate/tag/genre corrections apply; free-text decision changes do not (finding 1) | no test for platform/decision changes by chat |
| Development-ready spec | `SpecVersioning.generate`, `ClaudeMdGenerator`, `MasterPromptGenerator`, `HandoffDocs` | tests and Android CI compile; no real coding-agent run |
| Repository-aware continuation, playtest loop | `GitHubClient.inspect`, `RepoInspector`, `playtestTurn`, `Reevaluation` | fakes only; live GitHub unverified; feedback attachments missing (H3 #24) |

Missing or incomplete against intent: primary-platform and decision changes by chat; fact/decision coherence; live research and GitHub unverified; feedback attachments; GitHub write dormant.

## B. Interview and recommendation workflow

### B1. How one message is processed (`Director.handleUserMessageOnce`)
1. Append the USER message. 2. `Gates.applyOwnerText`: a passing "no combat"/"add enemies" settles its gate whenever said (and, if it answers the open question, advances). 3. Playtest mode short-circuits to `playtestTurn`. 4. Global commands (below). 5. Conversation repair (`MetaConversation.isMeta`). 6. A `when(pending)` switch handles the pending state (B3). 7. Otherwise `answerField` -> `interpret` (rules, then optional model, merged by `mergeInterpretations`) -> `answerFieldWith` -> `commitValue`. 8. `applyInferences` applies model/rule implications (HIGH confirmed, MEDIUM proposed, LOW dropped; each needs a grounded quote and never overrides an owner decision). 9. `advance` -> `askNext`: batch-confirm proposals, announce at most one new conflict, then `InterviewPlanner.next` (open required field, proposals, uploads, asset plan, open conflicts, advanced controls, review, ready). 10. `askField` composes the question, an acknowledgement of the previous meaningful answer, a lesson card (first time per topic), options, and the suggestion, and attaches a `QuestionSpec`.

### B2. The `QuestionSpec` contract
`QuestionSpec(fieldKey, kind, options, canDelegate, canSkip, recommended)` (`model/Model.kt`), built by `Director.specFor`. `kind` is `SINGLE`, `MULTI`, `TEXT`, `BOOLEAN` or `ASSET_UPLOAD` (branding slots); `canDelegate = field.suggest(t) != null`; `canSkip = !field.required`; `recommended` = the suggestion ids that exist among the options. Quick replies (`QuickReply(label, send)`) carry the same actions as text. The Android chat shows the card only when the last message is the Director's, `busy == null` and `question.fieldKey == pendingFieldKey` (`Chat.kt`). Pseudo-state questions use the same class with synthetic keys (`reviewSpec`, `assetPlanSpec`, reference aspects).

### B3. The pending-state machine (`project.pendingFieldKey`)
| State | Entered when | Accepts | Leaves when | Deterministic / model / research |
|---|---|---|---|---|
| field key | `askField` | chip/text answer, delegate, skip, postpone, question | committed, postponed or skipped | rules; model optional (`interpret`) |
| `__proposals__` | `askNext` finds freshly inferred proposals | yes / no / any correction text | confirmed all, cleared, or re-absorbed | rules; model optional |
| `__conflict:<id>__` | one new non-NOTE conflict per turn | alternative n, "keep my choice", text | resolved or acknowledged (`ConflictAck`) | deterministic (`ConflictEngine`) |
| `__asset_plan__` | unresolved asset needs | looks good / strategy text | plan resolved | deterministic (`AssetPlan`, `AssetStrategy.parse`) |
| `__review__` | everything resolved, not approved | approval, delegate, "change <section>", free change text | `ReviewGate.approve` | deterministic; text changes go through `absorbInto` |
| `__ready__` | approved, audit clean | "generate"/affirm; other text is absorbed | `generateRequest` -> `GenerateSpec` | deterministic |
| reference aspects / more references | named references | picks, delegate, skip, done | aspects recorded | research optional (`WebResearch`), rules/model for options |
| follow-up (`PENDING_FOLLOWUP`) | at most `MAX_FOLLOWUPS` model follow-ups, or the one "lucky" clarification | any text | absorbed | model (follow-up), rules (luck) |
| issue (`__issue:code:key__`) | uploaded-asset ignored etc. | text | resolved | deterministic |

### B4. Commands and how they hit the engine
| Command | Entry | Effect |
|---|---|---|
| Back | `Director.goBack` (UI: link under the card) | clears the last answered field, removes what Bob inferred from it (decisions whose note starts `INFERRED_PREFIX(key)`), keeps owner decisions, re-runs `reconciled`, re-asks. Not read-only navigation. `answerTrail` capped at 30 |
| Skip | `AnswerParser` Skip; `canSkip` | optional field only; required becomes postpone |
| Ask me later | postpone phrases / button | adds to `project.postponed`; required fields return after everything else (`InterviewPlanner`) |
| Choose for me | `AnswerParser`/`OptionResolver` Delegate; `canDelegate` | `commitValue` with `field.suggest`, provenance accepted-recommendation |
| Choose everything remaining | `isBulkDelegate` | `bulkDelegate`, stops at creative input (`BoundaryTest`) |
| Keep my choice | `resolveConflictReply` | records a `ConflictAck(OVERRIDDEN)` for overridable conflicts only |
| Explain more / any question | `Messages.wantsMore`, `answerQuestion` | lesson card with more detail; model reply if ready; pending field unchanged |
| status, refine, advanced/beginner mode, generate | global commands | read-only, optional questions, `switchMode`, `generateRequest` |

### B5. What is deterministic, model-assisted, research-dependent
Deterministic: planner ordering, gates, completeness, conflicts, coherence, derived defaults, spec/handoff generation, review gate, Back, command parsing, `OptionResolver`/`AnswerParser`, all state transitions. Model-assisted (optional, validated against the schema, rules always the fallback): `LlmInterpreter` (single pass: intent, selected, edits, inferences, follow-up, gloss), `answerQuestion` replies, reference aspect options, second opinion. Research-dependent (internet permission): reference game summaries/traits (`researchNewReferences`), term lookup, toolchain versions.

## C. End-to-end workflow and how each stage feeds the next

1. **Create** (Home): project with `ProjectPrefs` from settings. 2. **Concept**: first message becomes `originalConcept`, facts, `DesignSeeder` seeds, `DecisionExtractor` proposes genre/dimension/platforms/art/references. 3. **Proposals** confirmed in one batch. 4. **Interview**: `InterviewPlanner` by priority (genre 10s, audience/length 34-35, multiplayer 36-38, platforms 40, primary 41, ... engine 150, update 156-158, store 192). Gates remove irrelevant branches; derived defaults fill engineering fields. 5. **Conflicts** announced one per turn. 6. **Asset plan**, branding uploads. 7. **Design review** (plain-English summary incl. platform notes, engine card for Beginners, saving/controls) -> approval fingerprint. 8. **Generate**: reconcile, coherence, audit, CLAUDE.md, master prompt, 14 handoff docs; blocked on errors. 9. **Export / copy / share**. 10. **Playtest / reevaluate / revise**: new versions append; reevaluation re-reads words, keeps owner decisions.

Influence chain actually implemented: concept -> genre/tags -> relevant fields and system checklist -> engine ranking (needs dimension, platforms, multiplayer, update needs) -> derived defaults (network, orientation, input) -> conflicts -> review -> spec. Reconciler/derived defaults re-derive non-owner decisions when roots change (`Reconciler.rootSnapshot`: genre, dimension, platforms, tags, input, store, monetization, network, audio, concept, name, references). Probe: switching multiplayer to online-in-prototype re-derived `network_policy` (fully_offline -> online_required); adding Windows re-derived input methods and made the primary-platform question relevant.

Premature or missing follow-ups
- Primary platform is asked only after platforms are chosen; it is **not asked again when platforms change after approval** unless the field is empty (probe: `primary=null`, approval invalidated, question becomes relevant: this part works).
- A free-text platform change never reaches that path (finding 1/5).
- Engine is derived as soon as platforms+dimension exist and re-derived later (`derivedFrom` now includes update and multiplayer answers); before that an interim engine default exists but is not shown to the owner in Beginner mode.
- Budget, game length and audience are asked before technology; hosting model before engine: good. Distribution (`STORE_PLAN`) stays a derived default in Beginner mode even when OTA code updates depend on it (handled only by the "assumed channel" warning).
- Reevaluation does not re-ask questions whose answers the owner changed in conversation after approval (there is no signal that they changed).

## D. Project state, corrections and dependencies

### D1. What is recorded
`Project` (`model/Model.kt`): `decisions: Map<key, Decision(value, source, status, note, overrides, updatedAt, provenance, rawAnswer)>`; `facts: List<DesignFact>` (owner statements, ACTIVE or RETRACTED); `rejected` (genres/tags the owner ruled out); `references`; `messages`; `answerTrail`, `lastAnsweredKey`, `postponed`, `announcedConflicts`, `conflictAcks`; `pendingTurn`, `processedTurns` (last 64); `designApproval(fingerprint)`; `versions`; `reeval`.

Provenance rank: DEFAULT 1 < SYSTEM_INFERENCE 2 < OWNER_ACCEPTED_RECOMMENDATION 3 < OWNER_EXPLICIT 4 < OWNER_CORRECTION 5. `ProjectOps.setDecision`: a weaker source never replaces a CONFIRMED stronger one; an owner write over an earlier owner write becomes OWNER_CORRECTION; owner writes lift rejections, system writes cannot reintroduce rejected values.

### D2. Correction handling: recorded versus applied
| Owner input | Recorded in history | Applied to authoritative state? |
|---|---|---|
| Chip/text answer to the open question | message | **Yes**, `commitValue` (OWNER_EXPLICIT; OWNER_CORRECTION if it changes an owner value) |
| Same, via Back | message | Yes: the answer and Bob's inferences from it are cleared and re-asked |
| "not turn based", "no combat", "remove <genre>" (rules recognise rejected tags/genres/retractions) | message + correction facts | **Yes**: rejection, dependent genres dropped, retracted facts, `Gates` |
| Volunteered decision words inside any message ("on PC") | message + fact | **Only as a proposal** (`SYSTEM_INFERENCE`, PROPOSED), and **silently ignored if a confirmed decision exists** (`applyStatement`: `corrected` is true only when the message rejects a tag/genre or retracts a fact) |
| "Actually, forget Android. It is a PC game now." | message + two facts | **No** (probe) |
| "Add PC as the primary platform." | message + fact + wrong reference "Add PC a" | **No** (probe) |
| Reevaluation re-read | messages unchanged | Only SYSTEM_INFERENCE proposals; owner decisions never altered (by design, `Director.rereadOwnerWords`, `ReevaluationTest`) |

### D3. Dependency invalidation
Works (code + probe): non-owner decisions whose sources changed are dropped and re-derived (`Reconciler.reconcile`, `DerivedDefaults.apply`, `Dependencies.sourcesOf`, `Field.derivedFrom`; approval invalidated by decision or fact changes via `ReviewGate.fingerprint`). Gaps: owner-authored dependents are intentionally left alone but there is no warning that they may now conflict; references do not enter the approval fingerprint; facts enter it (so an unapplied instruction invalidates approval, but the re-asked review shows the unchanged platforms).

### D4. Readiness
`CompletenessEngine.compute` (missing required, proposals, uploads, assets); `DesignDimensions.percent` = weighted dimension share, capped at 99 until no BLOCKER conflict and `ReviewGate.approved`; `generate` additionally runs `ConsistencyReview`, `DesignCoherence.check`, `AuditEngine`. None of them compares facts with decisions.

## E. Songs of Syx reevaluation investigation

**Artifact status: not available.** Searched uploads, `/tmp`, the repository and git history; the only uploaded export is `Climb_up_spec_v1.zip`. "Songs of Syx" appears only in synthetic test data (`NewGenresTest.kt` lines 86-123, `ReevaluationTest.kt` line 28). I cannot inspect that project's saved state, versions, generated documents or reevaluation records, and I did not reproduce the exact export.

What I did instead (probe, current code, synthetic project: "A medieval fantasy city builder like Songs of Syx ... professions, relationships ... interconnected economy. Android and iPhone." through approval and generation, rules-only, internet off):

| Lead | Result | Origin | Why tests missed it |
|---|---|---|---|
| 1. "Add PC as primary" recorded, targets still Android/iOS | **Mechanism reproduced.** Fact stored; `platforms=android|ios`, `primary=android` unchanged; spec shows "Add PC as the primary platform." beside "Target platforms: Android, iPhone / iPad" | Correction handling (`applyStatement` proposal-only path) + natural-language interpretation (no primary-platform extraction; "pc" not a platform word) | Provenance tests assert weaker writes are blocked, never that a volunteered change is surfaced or applied; no chat test changes platforms; the primary-platform field is newer than most tests |
| 2. Part of the instruction classified as a reference | **Reproduced** (`references=[Songs of Syx, Add PC a]`) | Entity classification (`DecisionExtractor.referenceGames`: any capitalised run passes when it has two capitalised words; the tech-tag filter checks the LAST word and "a" is lower-case) and `applyStatement` accepting references from any free text | Reference tests use trigger phrases ("like X") and title answers; none sends an instruction sentence |
| 3. Research on the wrong reference | **Mechanism in code, not exercised** (internet disabled in the probe; `researchNewReferences` researches every reference with no summary when internet is allowed, and the summary is written verbatim into the spec's "Research summary") | Entity classification -> research | Research is tested with fakes and correct names |
| 4. Economy concept vs combat/roguelite instructions | **Not reproduced.** The probe's spec had no combat or roguelite requirements (`combat=NOT_APPLICABLE`). Unverified candidates: wrong-reference summary text; genre keywords in the real concept; an older generator version | unknown | `DesignCoherence` checks forbidden systems only when a system is ABSENT; a city builder with combat text is "not applicable", not "absent" |
| 5. Reported 100% ready with contradictions | **Reproduced**: percent 100, `all 9 stages complete`, approval true after the instruction, regeneration `blocked=false` | Readiness scoring (counts decisions) + design validation (no fact/decision check) | Readiness tests assert counts and conflict gates, not coherence of the finished document |

Not determined without the export: whether the export predates the primary-platform field and the current extractor, which exact sentences the owner typed, whether internet research ran, and the real reevaluation record.

## F. Interface redesign risk and safe implementation

Approved future interface: one active question in a dedicated dialog; contextual suggestions; single/multiple selection; free text; BACK | CONFIRM | NEXT; NEXT disabled until the answer is confirmed; the scrolling chat stays as history.

### F1. Safe strategy (no second state machine)
- The dialog is a **view of engine state**, not state: active question = `project.pendingFieldKey` + the last Director message's `QuestionSpec`. The dialog keeps only a local draft (selected ids / text) that the engine never sees until CONFIRM.
- **CONFIRM** = exactly what the card does today: `vm.submitSelection(fieldKey, ids)` for chips, `vm.send(text, turnId)` for text, with the turn id created once when the draft is created (not per recomposition), so crash-safe `queueTurn`/`processedTurns`/Retry keep working.
- **BACK** = `vm.goBack()` (`Director.goBack`), unchanged semantics; the dialog must say it undoes the last answer and what Bob inferred from it.
- **Special states** map to dialog templates keyed by pending state: `__proposals__` (list with Yes / Not quite), `__conflict:*__` (alternatives + Keep my choice), `__asset_plan__`, `__review__` (Looks right / Change something + section picker), `__ready__` (Generate), reference aspects, follow-up. They must send the same texts/commands the quick replies send today.
- **Delegation and skipping** stay as secondary actions from `QuestionSpec.canDelegate/canSkip` (Choose for me, Skip or Ask me later), not as the three primary buttons.
- **Suggestions** = `recommended` plus options; any new dynamically generated suggestion must be produced by the engine/LLM, validated against the schema, and arrive as `QuestionSpec` data, never invented in the UI.
- **History** = the existing message list, unchanged.

### F2. Conflicts between the proposed UI and engine semantics, with adjustments
1. **NEXT after CONFIRM vs engine auto-advance.** One owner message is one transaction that commits the answer AND appends the next question (`handleUserMessage` -> `advance` -> `askNext`). Adjustment: NEXT is "show the question the engine already asked", enabled when the confirm turn completed (`pendingTurn == null`, `busy == null`, `answerTrail` gained the key or the pending state changed). The UI must not call a separate "advance".
2. **BACK is an undo, not navigation.** It clears answers and derived values and is limited to 30 steps. Adjustment: label it "Undo last answer"/"Back", and add separate read-only "see my answer" access via history; do not offer BACK to arbitrary earlier questions without retraction (it is not supported).
3. **CONFIRM can change more than the current field** (volunteered decisions, corrections, gates, ambiguity questions, follow-ups). Adjustment: after CONFIRM, show Bob's "what I understood" (the engine's `note`/announcement messages) before NEXT enables; if the engine asks an ambiguity or follow-up, NEXT lands on that, not the planned field.
4. **Interleaved non-field questions** (`__conflict`, follow-up, issue) appear between fields; NEXT cannot assume a field sequence or a fixed "n of N".
5. **Free text outside the open question** is a normal engine input (finding 1). The dialog must keep a way to type anything; until finding 1 is fixed it will silently drop decision changes.
6. **Multi-select and "all except"** already commit only on Continue; CONFIRM should be that Continue, not a second step.
7. **Proposals confirm many keys at once** (`__proposals__`): CONFIRM means confirm all; individual edits need free text.
8. **Latency**: model interpretation can take up to the timeout (`MODEL_TIMEOUT_MS`); CONFIRM needs a visible in-progress state and Retry from `retryPending`.
9. **Progress**: stage/strip is derived (`DesignStages`); do not add a question counter.

## G. Final findings and implementation readiness

### G1. Actual end-to-end workflow
See C. Strongest property: provenance and a single transactional message pipeline. Weakest: the pipeline distinguishes "answer to the open question" from "anything else", and "anything else" cannot change confirmed decisions.

### G2. Verified strengths (test or code; not device)
Provenance-ranked decisions and correction semantics for option answers; rejected tags/genres; single-transaction turns with dedupe and crash-safe queue; Back with inference rollback; conflict announcement one at a time; derived-default re-derivation (probe); approval fingerprint invalidation on decision/fact change (probe); deterministic generation with coherence checks for forbidden systems, OTA/platform policy, exclusions; persistence with atomic writes and recovery; 287+ JVM tests.

### G3. Missing or incomplete capabilities
Chat-driven change of an existing decision (platforms, primary platform, others); primary-platform extraction; fact/decision coherence check; reference-entity validation; feedback attachments; GitHub write; live verification of research, GitHub, on-device model, cloud providers; migration of genuinely old project files.

### G4. Significant bugs and risks
Findings 1-9 in the summary table. Additional: `applyStatement` accepts references from any free text; title suggester; owner-authored dependents silently stale; approval ignores references.

### G5. Songs of Syx
See E (artifact missing; mechanisms 1, 2, 5 reproduced, 3 present in code, 4 not reproduced).

### G6. Required corrections before the redesign (engine only; none implemented here)
1. **Apply or ask.** When a free-text message changes a value for a field that has a CONFIRMED owner decision, either apply it as OWNER_CORRECTION (when the message is unmistakably an instruction: "make/switch/change/add ... platform", "forget X", "it is a PC game now") or ask one confirmation question; never drop it silently. Applies to MULTI fields as add/replace/remove.
2. **Primary platform and platform vocabulary**: rule extraction for "PC/Windows/desktop ... primary/main/first" and for removals ("forget Android"); "pc" as a platform word in platform sentences.
3. **Reference entity validation**: references only from trigger phrases, quoted titles, an explicit reference question, or a model; reject instruction sentences, platform/engine/tool names and fragments; never research an entity that was not confirmed.
4. **Coherence between facts and decisions** as a blocking check: platform words in facts must agree with `platforms`/`primary_platform`; a fact that negates a selected platform or system is a conflict; the check must run in `SpecVersioning.generate` and in the review.
5. **Readiness semantics**: 100% only when the coherence check passes; include references in the approval fingerprint; show unresolved instructions ("I recorded but did not apply: ...") in the review and status.
Secondary: title suggester; warn when a root change makes an owner-authored dependent questionable; reference research summaries should not be copied verbatim into the spec without owner confirmation.

### G7. Safe interface implementation strategy
F1, after G6. Build the dialog as a stateless view over `pendingFieldKey` + `QuestionSpec` + pending-state templates; keep all commands as buttons that send the same strings; add engine-side metadata only (never UI-side logic): for example a typed `PendingKind` and a list of "what I understood" lines on the turn result.

### G8. Regression tests required before implementation
1. Chat change of platforms ("forget Android, it is a PC game now"; "add PC as the primary platform"; "make PC primary, keep Android secondary"): decisions, primary, spec and review all agree, or Bob asked and nothing was lost.
2. The same instruction at each pending state (field, `__review__`, `__ready__`, none, `__conflict__`).
3. Reference validation: instruction sentences, platform names and fragments never become references; no research call is made for them (counting fake research provider).
4. Fact/decision coherence: a spec whose Part A contradicts "Target platforms" must be blocked; same for a city-builder economy with combat requirements when combat is not applicable and not described.
5. Readiness: 100% impossible while an unapplied instruction or coherence error exists; approval invalidates when references change.
6. Reevaluation: re-run after an unapplied instruction keeps owner decisions, reports the instruction as pending, and never researches fragments.
7. Back semantics unchanged (existing `AdaptiveQuestionsTest`, `TowerClimbRegressionTest`) plus Back after a confirmed free-text correction.
8. Turn handling: duplicate CONFIRM (same turn id) is a no-op; CONFIRM then process death resumes once; Retry after a model timeout.
9. Each pending state accepts exactly the texts the quick replies send (contract test over `QuickReply.send`).
10. NEXT enablement rule as a pure function over `Project` + `busy`, tested for: committed, model timeout, ambiguity question, follow-up, conflict interleave.
11. Title suggester never returns an owner-excluded or trailing-fragment title.
Do not rely on these passing as proof of device behaviour; each UI piece still needs a device pass.

### G9. Additional functionality beyond the H/I inventory
- "That's not what I meant" undo (`Interpretation.misunderstood`, `lastAnsweredKey`): clears the last recorded answer and re-asks.
- `Gates.applyOwnerText` settles gate questions from passing remarks and can answer the open question.
- `announcedConflicts` as a once-only registry (acknowledgements, follow-up counter, luck clarification, conflict ids).
- Ambiguity questions: statements that could mean two games hold back their volunteered decisions and ask first.
- `MAX_FOLLOWUPS` cap and `processedTurns` ring (64) bound loops and replays.
- `DesignSeeder` seeds text answers from the concept (dedicated sentences) and clear multiplayer words.
- Review sections map words to questions (`DesignReview.sections`), e.g. "change the loop".
- `ProjectSession` serialises every mutation under one lock and persists before publishing.
- `Interpretation.constraints/preferences/scope/delegated/stale`: typed proposals applied deterministically with grounding.
