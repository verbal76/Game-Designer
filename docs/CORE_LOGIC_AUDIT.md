# Core logic hardening: audit, semantic-interpretation review and next plan

Evidence labels: **AUTOMATED** = JVM tests on synthetic projects, rules only (no model, no network); **PROBE** = a one-off run of the real engine whose output was read by a person; nothing here was run on a device, with a live model or with live research. The original Songs of Syx export was never available; every Songs of Syx statement below concerns the synthetic sentence in the brief.

## A. Architecture audit: the information lifecycle as implemented

| Stage | Implementation (source) | Safeguards that already existed |
|---|---|---|
| Owner message in | `Director.handleUserMessage` -> `handleUserMessageOnce` (director/Director.kt), turn ids + `processedTurns`, `queueTurn` for crash safety | idempotent turn ids, one transaction per message |
| System settling | `Gates.applyOwnerText` (schema/Gates.kt): yes/no for combat, economy, crafting, progression | latest word wins |
| Platform instructions | `PlatformInstructions`, `Director.applyPlatformInstruction` (G6) | OWNER_CORRECTION, fact vs decision conflict |
| Interpretation | `LocalInterpreter` + `DecisionExtractor` (rules), `LlmInterpreter` (optional), merged in `Director.interpret` | model never overrides rules or owner decisions |
| Intent for the open question | `AnswerParser` -> `OptionResolver` (selection, "all except", positions, numbers) | cardinality, validation |
| Facts | `Director.applyStatement` -> `ProjectOps.addFacts` (owner sentences, ACTIVE/RETRACTED) | retraction instead of deletion |
| Decisions and provenance | `ProjectOps.setDecision` (DEFAULT < SYSTEM_INFERENCE < ACCEPTED_RECOMMENDATION < OWNER_EXPLICIT < OWNER_CORRECTION) | weaker never replaces confirmed stronger |
| References | `DecisionExtractor.referenceGames`, `ProjectOps.addReferences`, `ReferenceGuard`, `researchNewReferences`, `ReferenceAspects` | guard from G6 |
| Conflicts | `ConflictEngine` rules, `FactCoherence` (platform and gate contradictions), `DesignCoherence`, `ConsistencyReview`, `AuditEngine` | blocking severities, alternatives |
| Dependencies | `Dependencies.sourcesOf` + `Field.derivedFrom` + `Reconciler.reconcile` (explicit model; default "broad") | owner-authored never silently dropped |
| Persistence | `FileProjectStore` + `ProjectCodec` (atomic writes, migrations), `ProjectSession` lock | recovery, versioned schema |
| Undo | `Director.goBack` + `answerTrail` (30) | inferences taken back with the answer |
| Readiness | `DesignDimensions.percent`, `CompletenessEngine`, `ReviewGate` fingerprint (decisions, facts, rejections, branding, references) | capped at 99 with a BLOCKER or no approval |
| Specification | `SpecVersioning.generate` -> `ClaudeMdGenerator`, `MasterPromptGenerator`, `HandoffDocs`, mandatory `ConsistencyReview`/`DesignCoherence` | append-only versions, blocking review |
| Guided panel | `GuidedPanel` (pure view over `Project`), `QuestionPanel` | no UI-side decision state |

## B. Failure inventory (reproduced before repair)

| # | Sev | Component | Existing behaviour (reproduced) | Root cause | Fix | Test |
|---|---|---|---|---|---|---|
| 1 | P0 | `Gates` | "I like the population simulation in Songs of Syx, but I don't want its combat system" -> `has_combat = yes`, OWNER_EXPLICIT | denial regex needs the noun right after the verb ("its" breaks it); the affirm regex fires on any mention; `DesignSeeder` records it | clause-scoped negation, questions and reference-attributed mentions say nothing | InterpretationHardeningTest (deniedSystems..., mentioningASystem..., anExcludedReferenceSystem...) |
| 2 | P0 | spec | the generated spec for that sentence was titled "Combat System" and required "Enemies and bosses", "fight enemies", "Bosses: 9" and reported `absent_system_reintroduced` only as a non-blocking finding | #1, plus genre stock text ("fight enemies") ignoring the exclusion | combat-free stock text (`Traits.withoutCombat`), exclusion stated in section 16 | theGeneratedSpecDoesNotReintroduce... |
| 3 | P0 | `OptionResolver` | "No, not a general audience." was recorded as audience = general, OWNER_EXPLICIT | option matching has no negation scope | per-clause negation, rejected options kept in `rejected`, not re-offered or re-recommended (`Field.offered/recommendation`) | RecommendationRejectionTest (5) |
| 4 | P0 (self-inflicted) | `Director.applyStatement` | the G6 change skipped platform edits whenever a sentence parsed as a platform instruction, so the first message "Android is my primary platform" lost its platform and primary | `platformsHandled` ignored that no platform decision existed yet | only skip once platforms are decided | theFirstMessageKeepsItsPlatform... |
| 5 | P1 | `DecisionExtractor` | "I might bring it to Windows later" -> platforms = android\|windows | no hedge scope | `Hedge`: hedged clauses are possibilities; the spec lists them as "Possible later platforms (NOT launch requirements)" | aPlatformMentionedAsPossibleLater..., aLaterPlatform... |
| 6 | P1 | references | "RPG" became a reference game; "Not like Minecraft" added Minecraft | genre words are in the title vocabulary; comparison trigger ignores negation | `ReferenceGuard` generic terms; rejected-comparison check | aGenreNameIsNeverAReferenceGame, aRejectedComparisonIsNotAReference |
| 7 | P1 | references | every reference got "its core loop structure, pacing and the way build choices compound" as a confirmed default | invented default text | `ReferenceAspects.fromOwnerWords` (liked / rejected characteristic), honest default "inspiration only", `ReferenceGame.excluded` | referenceAspectsAreTheOwnersWords..., anUnspecifiedReferenceInheritsNothing |
| 8 | P1 | `ConceptText` | core fantasy = "You don't want its combat system."; package id `...combatsystem` | the sentence after "but" is taken as the fantasy | negated and hedged parts are never the fantasy | theTitleAndCoreFantasy... |
| 9 | P1 | `Dependencies` | "also add Windows" dropped a puzzle game's core loop and win/loss; 3D->2D dropped feeling, win/loss, definition of done, must-not-change | fields without an entry default to depending on genre, dimension and platforms | explicit sources for seven free-text fields | DependencyAndSpecAccuracyTest (2) |
| 10 | P1 | spec | every owner sentence became a "Must" ledger row, including hedged ones, rejections and wording the owner later replaced | no stance on facts | `FactStance`: request / rejection / consideration / superseded, shown in A2 and the ledger | DependencyAndSpecAccuracyTest (2) |
| 11 | P1 | `Gates` | "No combat, but I want boss fights." settled silently (last word wins) | no notion of contradictory signals | mixed verdicts record nothing; the gate question is asked | contradictoryWordsInOneMessage... |
| 12 | P1 | `Gates` | "I'm not sure if I want crafting" read as a request | no hedge scope | hedged clauses say nothing | hedgedWishesAreConsiderations... |
| 13 | P1 | conflicts | a later remark ("the villagers fight goblins") against a confirmed "no combat" was invisible | facts vs gate decisions never compared | `FactCoherence.gateFindings` -> blocking conflict `fact_contradicts_gate` with keep / change alternatives; an explicit change of mind still applies | ContradictionAcrossTurnsTest (4) |
| 14 | P2 | research | a NotFound reference was searched again on every message | no marker | `ReferenceGame.researchTried` (offline failures are still retried) | ReferenceResearchIntegrityTest |
| 15 | P2 | spec | research text appeared as plain "Research summary" | unlabeled | "Background research (NOT a requirement ...)" | same |

Verified without defect (AUTOMATED, LifecycleIntegrityTest): reload after every turn produces the same project as staying open; Back keeps unrelated decisions and exclusions; a conflict found before closing is resolved after reopening; regeneration appends a version and never alters the earlier one; an interrupted message resumes exactly once; readiness cannot reach 100 with a contradiction. Researched traits never became decisions.

## C. Parsing assessment
The deterministic parser is **adequate once its negation, hedge, question and attribution handling is made consistent**. Of 13 human-verified corpus cases across racing, puzzle, city builder, platformer, strategy, narrative, farming and RPG, 10 already passed after the first repair batch and the rest failed for the same reason: each code path decided "negated / hedged / asked / compared" on its own. No failure needed semantic understanding beyond clause-level cues. Remaining limits: unusual phrasing is not understood (it is recorded and, if it contradicts a decision, raised as a conflict); the liked-aspect patterns cover "like the X in Y" and "Y's X" only; the LLM interpreter path was not changed and is untested here.

## D. Semantic-layer paper review (Jia and Anaissi, arXiv:2609.19615v1)

**What it demonstrates.** An LLM-driven pipeline that turns noisy telemetry into a business semantic layer (feature -> node), evaluated on one e-commerce dataset with self-reported figures (human score 51.6 -> 82.3, 74% noise filtered, kappa 0.87 for an LLM judge against 500 annotated mappings). It uses OpenAI inference, Milvus embeddings and Airflow. Its ablation is on its own data; none of the numbers predict anything for Bob.

| Paper component | Purpose | Bob equivalent | Fixes a demonstrated Bob failure? | Simpler deterministic version | Cost |
|---|---|---|---|---|---|
| 1 Normalisation (column importance, mask random strings) | remove noise before retrieval | `MetaConversation` (remarks about the app are not requirements), `ReferenceGuard`, `ConceptText` hygiene | partly: instruction sentences and genre words as references were exactly this noise | rules, as done | low |
| 2 High-level feature identification with an industry taxonomy prior | anchor labels to canonical features | `GenreKnowledge`, `Fields`, `Gates` (the canonical systems) | no: Bob already has a closed taxonomy | none needed | n/a |
| 3 Candidate rule retrieval (dense + BM25) | find candidates per feature | `OptionResolver.lexical`, aliases | no failure came from missing candidates; they came from misreading polarity | none needed | high (embeddings, index) |
| 4 Semantic filtering (hard rules, LLM yes/no) | reject candidates that do not belong | hard filters yes (`ReferenceGuard`, gates); the LLM judge is not needed | **the principle transfers**: validate every candidate against exclusions and confirmed decisions before it enters the model | clause stance + `FactCoherence` + `ProjectOps.setDecision` precedence | low |
| 5 Clustering and canonical naming | merge equivalent variants, one name | `Reconciler`/option ids, `ReferenceGame` name dedupe | no | none needed | high |
| Deterministic output (temp 0, JSON) | reproducible | rules are deterministic; `sanitize` validates model JSON | already satisfied | n/a | n/a |
| LLM-as-judge with kappa vs humans | continuous quality check | `ConsistencyReview`, `DesignCoherence`, the corpus tests | **the principle transfers**: a golden set with human-written expectations | the human-verified corpus in `InterpretationCorpusTest` | low |

**Transfers:** (a) a candidate is checked against policy before commit, (b) a small human-verified golden set drives quality, (c) hierarchy as an inductive bias (Bob's gates and fields already are one). **Does not transfer:** embeddings/vector search (the vocabulary is closed and offline), LLM clustering and naming, telemetry normalisation, the cost profile (OpenAI, Milvus, Airflow: impossible offline on Android and a new privacy surface), and the premise (labelling many events versus reading one sentence's polarity).

## E. Current pipeline versus a structured interpretation pipeline

| Stage of the proposed pipeline | Status in Bob |
|---|---|
| identify individual claims | exists, per path: `MetaConversation.designSentences`, `Gates.verdicts` clauses, `Hedge`, `ReferenceAspects.fromOwnerWords`, `PlatformInstructions` |
| classify intent (mentioned / requested / rejected / considered / asked / recommended / confirmed) | **defective before this batch**: seven separate implementations of clause splitting and negation (`Gates`, `Hedge`, `DecisionExtractor.negatedBefore`, `Exclusions`, `ConceptText`, `OptionResolver`, `FactStance`), so a path that lacked one cue misread. Repaired path by path; still duplicated |
| preserve negation and scope | repaired in gates, options, references, platforms, concept text |
| identify candidate decisions | exists (`Interpretation.edits`, proposals as SYSTEM_INFERENCE/PROPOSED) |
| validate against decisions and exclusions | exists for provenance (`setDecision`); **missing before** for facts vs decisions: now `FactCoherence` (platforms, gates) |
| resolve uncertainty or conflict | exists (ambiguity questions, conflict questions); now also mixed signals and contradictions |
| commit approved decisions | exists (confirmation, proposals batch) |
| verify generated specs | exists (`ConsistencyReview`, `DesignCoherence`); now stance-aware ledger and exclusions in section 16 |

The mention / request / reject / consider / recommend / confirm distinction: *mention* and *question* say nothing; *reject* is an exclusion (gate decision or option rejection or reference exclusion); *consider* is a hedged clause (never a decision); *request* is a plain affirmation; *Bob's recommendation* is data on the question (`recommended`, never selected); *confirmed* is only an owner write through `setDecision`.

## F. Is an intermediate structured representation needed?
For the example sentence the engine now holds the expected structure **in its existing stores**: reference Songs of Syx (`references`), liked characteristic "population simulation" and rejected "combat system" (`ReferenceGame.aspects/excluded`), combat excluded (gate `no`, owner-authored), Android confirmed, Windows only a hedged fact (rendered as "possible later"), nothing inherited from the reference, no authorisation for enemies or bosses (stock text is combat-free). A separate claim store would duplicate the project model. What is justified is a **shared clause reader** (one function returning per-clause stance), not a new store.

## G. Options compared
| | A: harden existing parser | B: add a lightweight shared clause layer using existing stores | C: LLM/retrieval semantic system |
|---|---|---|---|
| Accuracy | enough for the corpus (13/13, plus 45 new tests) | same, with fewer places to forget a cue | unknown; unverified; can hallucinate |
| Unusual concepts | clause cues generalise; vocabulary limits remain | same | potentially better, untested |
| Offline / Android | yes | yes | needs a model or network |
| Runtime cost | negligible | negligible | seconds per message on-device, or cloud cost |
| External services / privacy | none | none | cloud or model download; owner text leaves the device |
| Testing | deterministic | deterministic | needs recorded outputs |
| Maintenance | duplicated regexes (current risk) | one place | prompts, model versions |
| New hallucination risk | none | none | real |
| Guided UX | unchanged | unchanged | unchanged |

**Recommendation: A now (done), B as the next batch (P2, refactor under the corpus as safety net), C only if the corpus grows failures that clause cues cannot express.** Embeddings, vector stores and cloud APIs are not justified by any reproduced failure.

## H. Remaining work and plan
Order and dependencies: (1) shared `ClauseReader` consolidation, behind the existing corpus and hardening tests (complexity: medium, ~1 session; no behaviour change allowed); (2) extend `FactCoherence` from platforms and gates to dimension/art/orientation with conflict questions instead of annotations (medium); (3) owner-visible provenance in the guided panel ("you decided / Bob recommended / default") (small, UI); (4) title suggester tails and `announcedConflicts` registry split (P2/P3, small each); (5) warn when an owner-authored dependent may be stale after a root change (medium); (6) widen liked-aspect patterns and add a confirmation question for research summaries (small); (7) LLM-path hardening: run the corpus through the interpreter with recorded model outputs (medium, needs a model).
Acceptance for each: its own failing-first tests, the whole corpus unchanged, G6/panel contracts intact. The project is ready to move beyond core hardening for platform, exclusion, option, reference and spec accuracy; it is **not** yet ready to claim hardened LLM-assisted interpretation or live research quality.

## I. Unresolved risks
**Verified defects fixed:** items 1-15. **Unresolved, verified:** duplicated clause logic (#E); dimension/art contradictions are annotated, not asked; owner-authored dependents can go stale without a warning; selective-aspect parsing covers two phrasings; a fact whose only contradiction is with a non-platform, non-gate decision is not blocked. **Speculative:** the LLM interpreter may reintroduce the failures fixed here for rule paths; rule cues were tuned on English phrasing from this corpus. **Side effect to know:** projects approved before references entered the fingerprint (G6) show their review once more.
