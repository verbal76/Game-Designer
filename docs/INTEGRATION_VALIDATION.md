# Integration validation of the architectural repair (baseline 46c3862)

Evidence classes: **AUTOMATED** (deterministic assertions), **SIMULATED** (scripted replay through the Director, rule-based interpretation, no LLM), **UI-CONTRACT** (the `QuestionSpec` payload the Chat screen renders). Not available here: Android SDK/emulator, a live model, a physical device.

## Defects found and repaired
1. A long opening message containing "later" (e.g. "...with multiplayer later") was read as "ask me later" and the whole description was discarded (`AnswerParser.isPostpone`). Postponement now needs a short deferral.
2. The old note "Game Designer's first version doesn't design online multiplayer" still fired and contradicted multiplayer discovery. Removed (`ConflictEngine`).
3. Multiplayer, timing and mode options accepted only their exact labels ("online" was "not caught"). Natural-phrase aliases added; the multiplayer suggestion respects a multiplayer mention.
4. Working title was built from planning words ("Multiplayer Later"). Planning/platform words are excluded.
5. OTA conflated technical capability with store policy and silently softened unmet requests. Each platform now states capability and policy separately; `UpdateStrategy.unmet` lists requests that cannot be met as asked; the spec must say so (`ota_downgrade_silent` is a blocking error); assumed distribution channels are labelled.
6. The closest-alternative explanation was vague; it now names the concrete tradeoff and 3D weakness.
7. Lifecycle text did not state that questions, hypotheticals and proposed features are not handoff triggers; added to CLAUDE.md 0a and the master prompt.
8. The review the owner approves now includes the technology choice, its closest alternative, platform limits and OTA constraints.

## Tests
`IntegrationValidationTest` (8) and `PrototypeHandoffTest` (14) plus the existing suite: see the final report for counts.

## Needs physical or live-model validation
Rendering of the new chips on a device; that a live Claude, given the two files in a fresh conversation, honours the marker and the non-trigger rule; real store-policy wording (Apple/Google) at implementation time; Windows/iOS builds.
