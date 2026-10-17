# Knowledge library: architectural assessment (Phase 1, read-only; no code changed)

**Blocking input:** the transcript "The 100 Games That Taught Me Game Design" was not supplied (only the brief). The brief grounds two entries, Space Invaders [00:02:40-00:03:35] and Pac-Man [00:03:48-00:04:39], and says the transcript is the only source for transcript-derived examples. Nothing from the video will be written from memory. Extraction (Phase 2) waits for the transcript text.

## What exists (actual code)
- **Owner statements / recommendations / rejections**: `Decision.provenance` (DEFAULT < SYSTEM_INFERENCE < OWNER_ACCEPTED_RECOMMENDATION < OWNER_EXPLICIT < OWNER_CORRECTION), `ProjectOps.setDecision`; rejections in `Project.rejected` (options, genres, tags, reference exclusions in `ReferenceGame.excluded`); hedged and rejected sentences labelled by `FactStance`; confirmed exclusions protected by `Gates`, `FactCoherence`, `ConflictEngine`.
- **Existing knowledge mechanisms**: `Explainer` (per-field teaching cards, hand-written), `DesignLenses` (7 distilled paper notes in `docs/game-design-references/`, up to 3 lines of guidance fed to model prompts at `Interpretation.kt:211` and `Director.kt:831`), `GenreKnowledge`, `ReferenceAspects`. None holds game-specific, sourced, timestamped examples.
- **Owner questions**: `AnswerParser` -> `Answer.Question` -> `Director.answerQuestion` (on-device model if ready, else the Explainer card). **Reevaluate**: `Director.reevaluate` + `rereadOwnerWords` only add proposals; it never touches owner decisions. **On-device model**: `LlmProvider` behind `Director.readyProvider`; it interprets owner text and answers questions; its output is validated by `DecisionExtractor.sanitize` and the rule path.
- **Local data pattern**: `ModelCatalogData.kt` (generated Kotlin data in `:core`, OTA-able, no runtime file I/O).

## Where reference knowledge can safely enter
1. **`Director.answerQuestion` / an explicit owner request** ("examples of exploration", "how do I ramp difficulty"): read-only lookup, answer as "one possible approach".
2. **An optional secondary action on a question** in the guided panel (like "Explain this to me"): never auto-inserted into question text.
3. **Never**: `Interpretation`/`applyStatement` (extraction), `Reconciler`, `rereadOwnerWords`, spec generation of unaccepted content, or the model's INTERPRET prompt (the existing lenses already reach it; library records must not).

## Intended approach (smallest that satisfies the brief)
- **Data**: `docs/knowledge-library/*.json` (versioned entries with the brief's schema, `extraction_status`, `reviewed_by`, `content_version`) -> `tools/knowledge/validate.py` (schema, timestamp format, paraphrase length cap, every excerpt found in the supplied transcript, no entry without a status) -> generated `KnowledgeLibraryData.kt` in `:core`. Offline, no network, no embeddings or vector store.
- **Retrieval**: deterministic tag and phrase scoring over the owner's stated problem, the open question's key, and explicit comparison words; hard minimum score, at most 2 results, only REVIEWED entries may carry a citation; entries whose mechanic maps to a system the owner excluded or rejected (combat, enemies, power-ups...) are suppressed. No match returns nothing.
- **Project state (additive, no migration)**: a small ledger on `Project` of entries shown, rejected (suppressed for good) and accepted. Acceptance is an explicit owner action recorded with OWNER_ACCEPTED_RECOMMENDATION provenance as an "accepted design approach" (title + timestamp + the owner's words), never as a decision field; rejection stores the rejection, not a feature.
- **Spec**: only accepted approaches appear, in their own labelled section; no example text, no unaccepted entry.
- **Model**: deterministic first. If the model is later used to phrase an example, library text goes in as explicitly untrusted context and its output is checked against confirmed decisions and exclusions before display.
- **UI**: an optional "Show an example" action and a card with Accept / Not for my game, both going through normal engine commands with stable turn ids.

## Risks and stop conditions
No stop condition is triggered: no decision-model rewrite, no cloud dependency, no spec rewrite. Risks: (a) a thin pilot (2 grounded entries until the transcript arrives); (b) retrieval false positives, controlled by a minimum score and tested with "no suitable reference"; (c) the existing lenses and the library both reaching owners, kept apart by purpose; (d) the model path and Android UI are only verifiable by CI and recorded outputs, not on a device.
