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
