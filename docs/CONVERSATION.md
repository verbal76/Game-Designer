# Conversation architecture (v3)

owner language -> interpreter (LLM, else rules) -> structured design state -> deterministic validation -> CLAUDE.md + MASTER_PROMPT

## Interpretation
- `Director.interpret` always runs the deterministic `LocalInterpreter` (OptionResolver + negation-aware `DecisionExtractor`). If a provider is ready it also calls `LlmInterpreter` (cloud first, then on-device) and merges: the model decides intent and selection, deterministic extraction wins on explicit tokens (e.g. "2D"), negations from both apply.
- The model gets a compact state only: original concept, compact decisions with provenance, owner rejections, recent facts, the current question with option ids, last 4 turns, the latest message. Its JSON is validated: unknown option ids, fields, tags and genres are dropped; unusable output falls back to rules. Nothing is applied without schema validation.
- Offline / no provider: `InterpreterKind.RULES`. The chat shows a banner ("Conversational interpretation is unavailable"); tappable options and multi-select still work.
- Privacy: see `ProviderFactory.PRIVACY_SENT/NOT_SENT` (shown in Settings). No assets, files, repo contents or keys are sent. Keys live in the Android Keystore store (`SecretStore`), one secret name per provider.

## Providers
`LlmProvider` boundary. `ProviderFactory` registry: Anthropic, OpenAI-compatible (https base URL). Selection is stored in additive `AppSettings.llmProviderId` / `llmBaseUrl` (no new enum values, so a rollback layer still reads settings).

## Cardinality
`FieldKind {TEXT, SINGLE, MULTI, BOOLEAN, NUMBER}` -> `QuestionSpec` on the director message -> `QuestionCard` (single: tap submits; multi: toggles, Select all, Clear, Continue(n), Choose for me, Skip/Ask later; no submit on first tap). `Director.submitSelection` records structured answers; typed text goes through the resolver ("option three", "30 to 60 minutes", "all", "everything except language", "1, 3 and 5", "first three").

## Authority
`Provenance`: DEFAULT < SYSTEM_INFERENCE < OWNER_ACCEPTED_RECOMMENDATION < OWNER_EXPLICIT < OWNER_CORRECTION. `ProjectOps.setDecision` never lets a lower rank replace a confirmed higher one; an owner statement that changes an owner value becomes OWNER_CORRECTION. Owner rejections (`Project.rejected`: genres, options, tags) block system re-inference; an owner statement lifts them (reversal). "You choose" records OWNER_ACCEPTED_RECOMMENDATION (or accepts an existing inference).

## Invalidation
`Reconciler.reconcile(before, after)`: decisions derived from a changed root (genre, dimension, platforms, tag, ...) by the system or an accepted recommendation are cleared if no longer valid, to a fixpoint; owner-stated decisions are kept and surface as conflicts (e.g. `combat_turn_based_vs_real_time`). The Director announces what was dropped.

## Export gate
`SpecVersioning.generate`: revalidate -> `ConsistencyReview.resolve` (settle by owner precedence, e.g. "all" must hold every option) -> generate -> `ConsistencyReview.review` (turn-based/grid language in a real-time design, rejected genres/options present, truncated multi-select, menus truncated). Errors block the export and go back to the owner. The owner's verbatim concept is exempt from scanning.

## Generated spec
Part A OWNER REQUIREMENTS (verbatim concept, facts, owner decisions with their words, withdrawn/rejected items), Part B ACCEPTED RECOMMENDATIONS, Part C IMPLEMENTATION GUIDANCE (scope counts are labelled upper-bound heuristics, never requirements), Part D UNRESOLVED QUESTIONS. Part A wins on any conflict.

## Rollback safety
All model additions are optional with defaults; `DecisionSource` is unchanged and written alongside `Provenance`; no schema-version bump. Tested by `CompatAndProviderTest`.

## v4: design intelligence (OTA-delivered)

### Completeness is dimensions, not questions
`DesignDimensions` weighs 17 build-critical dimensions (vision, feeling, loop, movement, interaction, world topology, failure, progression, session, first playable slice, visual identity, audio, controls/platform, asset policy, must-not-change, completion, distribution). A dimension is DECIDED (owner), DELEGATED (accepted recommendation / deferred), DISCRETION (Bob's routine default) or UNRESOLVED. `percent` is the weighted resolved share, capped at 99 until no blocking conflict exists and the owner approved (or delegated approval of) the design review. Different projects reach 100 with different numbers of questions.

### Ask better, not more
- `Tiers.derive`: routine engineering (saves, menus, accessibility, engine, CI, testing, package id, orientation, audio default...) is derived by `DerivedDefaults` with DEFAULT provenance and listed in CLAUDE.md Part C. "refine" can still open any of them.
- `DesignSeeder`: what the owner already said (their facts) seeds loop, movement feel, mood, win/loss, world topology, failure model, progression, first-build scope, and what completes it - each owner sentence is filed under ONE dimension (`DimensionLexicon.primary`). Seeds are the owner's own words (OWNER_EXPLICIT) and never replace a decision.
- "A great five minutes" is asked only when three or more core dimensions are still unspoken; the answer is extracted into structure, not just stored.
- `MUST_NOT_CHANGE` and `FIRST_SLICE` are new questions; both have drafts for "choose for me" built only from what the owner already fixed.

### Review gate
`DesignReview.compose` renders a plain-English "here's the game I think we're making". `ReviewGate` binds approval to a fingerprint of everything the owner can see; any later change makes the approval stale. `SpecVersioning.generate` refuses to export an unapproved design. Section edits ("change the world"), natural-language corrections and "you decide" (delegated approval) are supported; a negation is never an approval.

### Contradiction checks run over CLAUDE.md, MASTER_PROMPT.md and ASSETS.md
prototype vs complete, CC0-only vs original-only, owner logo vs generated logo, continuous world vs levels, no combat vs boss fights, phone-only vs keyboard-only, no inventory vs inventory progression, truncated multi-select, answer disagreeing with the owner's words, meta-conversation recorded as a requirement.

### Spec and prompt
CLAUDE.md: A (vision, requirements, corrections, must-not-change, first build scope, completion, assets), B (accepted recommendations only), C (guidance: Bob's engineering defaults, anti-slop, game-specific verification, deliverable contract), D (unresolved/delegated). MASTER_PROMPT: mission, creative target, experience, first-build scope, loop, must-work, world, progression/failure/completion, visual quality (with what does NOT count), audio, controls, assets, invariants, autonomous workflow, core-first strategy derived per game, verify-before-finishing, deliver, unresolved. Empty sections are omitted; no invented counts.

### Attachments and exactly-once turns
`AttachmentIngest` reads the picked URI to EOF immediately (document providers return pipes with unknown length), validates PNG/JPEG/WebP by header, writes temp+fsync+atomic rename into app storage, verifies SHA-256, and only then does `Director.attachmentReceived` record the asset, satisfy the question and advance once. Failures are typed and shown; nothing is claimed before durable ingestion. `ProjectSession` is the single writer (mutations apply to the freshest state under one lock, never a stale snapshot held across IO/LLM awaits), and every owner action carries a turn id persisted in `Project.processedTurns`, so duplicated callbacks, retries and restored UI cannot re-commit a turn. Delegation phrases ("you choose") only count in short replies, never inside a long description.


## Reevaluate (v4 line)
A fourth primary workflow (home screen and the project menu share one implementation: `Director.reevaluate`) runs a SAVED design through the current design intelligence without repeating the interview.
- **What is stored (audited, never invented):** every chat message verbatim (owner and Bob), `originalConcept` (empty in older projects; then recovered from the first stored owner message and marked), each decision with provenance (older decisions have none: their authority is inferred from the legacy source and marked uncertain, never upgraded) and the owner's own words (`rawAnswer`, only where recorded), rejections, facts and retractions, uploaded-asset associations, and every spec version with its decision snapshot.
- **Pipeline:** `Reevaluation.audit` -> re-read the owner's stored words with the current interpreter (safe additions only; model proposals never overwrite owner decisions) -> reconsider only what old Bob inferred or defaulted (owner words, corrections, deferrals and accepted recommendations stay; a newer differing recommendation is listed beside the kept one) -> current seeding, derived defaults, reconciliation -> contradiction check and completeness -> `ReevalRecord` with the report (NEW / CHANGED / REMOVED / PRESERVED / KEPT_REC / NEEDS_DECISION / CONTRADICTION) and the baseline.
- **Questions:** only genuinely missing build-important decisions are asked, through the normal conversation. Design approval is reset, so the plain-English review must be approved again.
- **Versioning:** starting a reevaluation changes no spec version. Approving generates the next version labelled "Reevaluation"; earlier versions stay byte-identical and can be compared in Spec -> Changes. Discard restores the saved design exactly.
- **Schema:** purely additive (`Project.reeval`); no migration step and no schema version bump.

### Reevaluate: side-by-side comparison
- `ReevalCompare.rows` builds a Before | After table from the stored baseline and the working design: every decision (and the owner's statements) with SAME / CHANGED / NEW / REMOVED and who stands behind each side (You, You (correction), Accepted from Bob, Bob). The Reevaluation screen shows it (Changes only / Everything / Your decisions), with a separate "Needs attention" tab (new questions, contradictions, newer recommendations beside kept ones) and a "What was stored" tab.
- `SpecCompare` compares any two spec versions section by section (matched by heading) with aligned lines, so only real edits light up; Spec screen -> "Compare with the previous version" and Reevaluation -> "Compare specs" open it. Versions are read-only history.

### Reevaluate: design mode, issue intake, second opinion
- A reevaluation always runs in design mode. A project that was left in playtest-feedback mode is switched to design mode for the reevaluation (the original mode is kept in the baseline and restored on discard); the continuation-goal question ("What do you want to do with the existing game?") never appears for a design that has no game yet.
- Blocking structural contradictions are raised one at a time like intake questions (`Director.nextIssue/askIssue`): a mismatch on a question is taken back and that question is asked again with its real choices; an ignored upload gets "Use my uploaded file / Don't use it"; the rest ask for the owner's words and are absorbed normally. Each contradiction is raised once, so none can loop; contradictions that the owner-precedence pass settles by itself (`multi_select_truncated`) are not raised.
- "Second opinion" asks a different AI (the configured cloud provider, or the on-device model) to critique the design from a brief that marks who stands behind every decision (OWNER, ACCEPTED-FROM-BOB, BOB-GUESS). Its points can be raised with Bob as normal questions. Advice only: nothing changes by itself.

## Adaptive interview (Bob drives; the question bank is a coverage checklist)

- **One model pass per answer.** `LlmInterpreter` reads the owner's message together with the design so far and returns, in one JSON object: the answer, volunteered decisions, **inferences** (`key`, `value`, `confidence` high/medium/low, a quote of the owner's words), one optional **follow-up**, and a **gloss** (the design intent behind a colourful answer such as "Lucky!"). Bare "yes"/"no"/exact short picks need no model call at all. Everything else is deterministic.
- **Inference is graded.** HIGH is recorded (`SYSTEM_INFERENCE`, confirmed) and not asked again; MEDIUM becomes a proposal Bob confirms ("Here's what I understood"); LOW is dropped (a reason to ask). Every inference needs evidence grounded in the owner's words, never overrides an owner decision, and is tagged with the question it came from so **Back** takes it away with that answer.
- **Gates.** Combat, economy and crafting are gated by a short yes/no (`has_combat`, ...) when the genre only sometimes has them. The gate is settled without asking when the owner's words rule it in or out ("peaceful", "no combat", "add boss fights"; `Gates`, applied by `DesignSeeder` and, whenever the owner says it, `Gates.applyOwnerText`, where the latest explicit word wins), or when a model inference with evidence settles it. "No" removes the system's tag and takes back its detail answers. "Choose for me" on a gate reads the description first.
- **Wording adapts.** `Messages.promptFor` rewrites a question for the game ("Does your platformer have combat...?", "How should aiming and shooting feel?"). Examples in a question are only examples; open-text answers are kept whatever their wording and the model's reading is stored beside them.
- **Follow-ups** come from the same pass, at most three per project, never repeated, only when the planner would otherwise ask a field.
- **Never loops.** A second unmatched reply to the same question makes Bob decide (his recommendation, said out loud). "Choose for me" asks the model to choose from the *whole* design (validated against the real options) and falls back to the trait-aware suggestion.
- **Progress** is the weighted share of build-critical design dimensions that are settled, with partial credit inside a dimension: inferred facts count, irrelevant (gated-out) systems do not exist for the percentage, and an open gate holds the Interaction dimension back.

## Reconciled state and the export (Climb up lessons)

- **Gates are generalised.** Combat, economy, crafting and *player power progression* (`has_progression`) are each established first (by the owner's words, a grounded model inference, or one short yes/no), then their detail questions open or vanish. A "no" removes the detail answers; a later explicit owner statement ("actually, add enemies") re-settles the gate (`OWNER_CORRECTION`) and the Interaction/Progression branches follow.
- **A statement that answers the open question answers it.** If the owner is asked how the player gets stronger and says the player doesn't, the gate closes, their words are kept as facts, and Bob moves on instead of asking again. "Choose for me" can no longer re-introduce something the owner ruled out (gate suggestions read their description first).
- **Open text is the owner's own words.** A model's tidier summary never replaces it (the old "lucky" lost "this is a skill based game"). Long answers to the core loop and the first-playable questions are mined for facts like the concept is.
- **Supplied assets are a policy.** `asset_policy` has `supplied_cc0`, `supplied_original` and `supplied_only`; "use the asset packs that I give it" is seeded from the owner's words, the question is not asked again, and the export (CLAUDE.md A7/section 8, MASTER_PROMPT, ASSETS.md) tells the builder to inspect the packs first. Voxel/3D looks plan 3D models, not sprites, and pick a 3D-capable engine.
- **Export reads the reconciled state.** Content counts, the genre system checklist, anti-slop rules, verification steps and the accessibility text drop what the owner ruled out (no enemy or boss counts without combat, no progression check or power-ups without progression, no hand-built level set or level select with procedural stages). Bob's routine defaults (session length, side view) are not presented as design facts when the owner described the thing themselves.
- **OTA yes opens one follow-up** (`ota_scope`: content-only vs. content and sandboxed logic); how/when/rollback stay engineering defaults.
- `docs/CLIMB_UP_GROUND_TRUTH.md` is the oracle; `ClimbUpRegressionTest` replays the real interview and branches (combat yes, progression yes, OTA no) against it.
