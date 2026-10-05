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
