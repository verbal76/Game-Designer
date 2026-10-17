# Bob's Game-Design Knowledge Library

Advisory reference material that Bob can show when the owner asks for advice, examples or alternatives. It is never a source of decisions.

## What is in it
- **103 entries covering 100 distinct games**, extracted from one source (*The 100 Games That Taught Me Game Design*, Game Maker's Toolkit, video). More than 100 because a few games support two separate principles. Nothing is capped or padded to 100.
- Each entry: id, game, source timestamp range, tags, derived topic concepts, problem, mechanic, effect, tradeoffs (only when the source states one), `inference` (extraction-time analysis, always shown labelled "My own analysis (not from the source)"), a suggested owner question, related ids, confidence, extraction status `VALIDATED`, `reviewedBy` "none (machine-validated only; owner review pending)", content version.
- **14 entries are active (pilot)**, chosen across difficulty, exploration, simulation/automation, choice, emergence, onboarding, accessibility, touch input, scope/loop and a no-combat edge case. The other 89 are validated and inert until listed in `docs/knowledge-library/pilot.json`.

## Three kinds of content, kept apart
1. **Source-supported**: `problem`, `mechanic`, `effect`, `tradeoffs`: what the source says, in original wording.
2. **Claude's analysis**: `inference`, labelled in the UI.
3. **Anything else** (general knowledge) is not in the library. The existing `Explainer` and `DesignLenses` remain separate.

## Copyright safeguards
No transcript text exists in the repository, in generated Kotlin, in cards or in specs. The schema has no field for source wording; provenance is a timestamp range. `tools/knowledge/validate.py <transcript>` (the transcript is read from a path and never committed) fails on any run of 5 or more consecutive words shared with the transcript (after removing game titles, numbers and an allow-list of ordinary design terms), checks every timestamp, checks that the game is spoken within its range, caps field lengths and reports uncovered transcript ranges. Last run: 103 entries, 100 games, 0 errors, no uncovered ranges. Entries were also reviewed by hand to remove the narrator's imagery and analogies. The validator measures overlap; it cannot prove a paraphrase is good, so owner review is still recorded as pending.

## Retrieval (deterministic, offline)
`KnowledgeLibrary.retrieve`: topic concepts are found from the owner's words (phrases in `tags.json`) and from the open question's field. An entry needs a topic hit plus a second signal (another topic or shared content word) to reach the minimum score of 4, so vague requests return nothing. Results are the best at most **2**, and the second must score at least 60% of the first. No embeddings, no cloud, no model call.

Suppressed entries: ones the owner rejected in this project; ones that depend on a mechanic the owner excluded (excluded reference aspects, confirmed "no combat"/"no progression", and the owner's own "no X / without X / do not want X", wherever the engine filed it). When in doubt it leaves an example out.

## Entry points
1. The owner asks ("give me examples of...", "how do other games handle...", "I need help with difficulty progression").
2. Optional **Show an example** button on a guided question, shown only when a topic applies. Never injected.

## Owner authority
- Showing a card changes no decision, fact, approval or completeness figure (tested against decisions, rejected, references, facts, approval, review fingerprint and percent).
- "Interested in X" records interest only (`Project.knowledge`, status INTERESTED), then Bob asks which specific part the owner wants. The owner's answer in their own words goes through the ordinary answer pipeline. Only if that changes a decision is the entry marked APPLIED.
- "None of these fit" / "none of it" records REJECTED and the entries are never offered again in that project.
- The library never enters an interpretation prompt, `applyStatement`, reevaluation, or the spec/handoff generators (`MetaConversation` treats example requests as non-design talk).
- Old projects open unchanged: `Project.knowledge`, `ChatMessage.knowledgeEntryIds` and `knowledgeStage` are additive with defaults. No schema bump.

## Adding a source later
Add JSON files under `docs/knowledge-library/entries/`, extend `tags.json` if needed, run `validate.py`, list ids in `pilot.json`, run `python3 tools/knowledge/generate.py` (use `--check` to verify the committed Kotlin is current).

## Engine changes made along the way
- A reference's possessive is no longer part of its name ("Pac-Man's" is now "Pac-Man").

## Verified and not verified
Verified: 17 JVM tests in `KnowledgeLibraryTest` (the 12 scenarios, view-changes-nothing, Show-an-example offered only where relevant, no library text in a recording model's prompts, unknown ledger ids, library integrity). With the earlier corpus tests the mandatory brief cases ("Songs of Syx ... no combat", Pac-Man without power-ups, "Maybe Windows later, Android first", "No, not for a general audience") are covered.
Not verified: behaviour with the real on-device model (none is runnable here; the model path is tested only with a scripted recording provider, which proves library text is not sent to it), the Compose "Show an example" button (compiled only in CI), and any device behaviour.

## Second source: Karhulahti (2024), "Vitality structures in 'addictive' game design"
Open Research Europe 4:47, version 2, 14 Oct 2024, doi:10.12688/openreseurope.17177.2. Added as `entries/part-05-karhulahti2024.json` (9 entries, all active, all **explicit-only**: retrieved only when the owner asks, never by the "Show an example" button).

| Entry | Classification | Section |
|---|---|---|
| CLIMB | SOURCE_CONSTRUCT | Analysis: CLIMB |
| FINAL STRETCH | SOURCE_CONSTRUCT | Analysis: FINAL STRETCH |
| ALERT | SOURCE_CONSTRUCT | Analysis: ALERT |
| Micro, meso and macro time scales | SOURCE_FRAMEWORK | Analysis |
| Self, avatar or object perspective (meta-spatial) | SOURCE_FRAMEWORK | Analysis |
| Past, present and future perspective (meta-temporal) | SOURCE_FRAMEWORK | Analysis |
| Design and experience as a pair | SOURCE_FRAMEWORK | Vitality Structures |
| Signal density and session length | SOURCE_OBSERVATION | Analysis: ALERT |
| What the source does and does not establish | SOURCE_CAVEAT | Methods |

How it differs from the video source: provenance is a section name, not a timestamp (`sources.json` lists valid sections). Every entry must carry tradeoffs and a `Bob's reading` inference, kept apart from the paper's content. Every card from this source adds "Source status": the framework is a hypothesis-stage proposal from the author's own recollection and does not establish a cause of problem gaming, and mentioning a construct is not a request for any mechanic. Cards label the tradeoff line "Risks and limits".

Guards: the concepts have their own topic (`engagement_loops`) that no other entry shares, so ordinary requests (difficulty, exploration, onboarding, progression) never surface them. A message that describes a feature ("I want ranked ladders and notifications") is not a request for reference material. They are never added to decisions, the interpretation prompt, reevaluation, or the spec. Interest, rejection and suppression behave as for the video entries.

Deliberately excluded because the paper does not support them as design knowledge or they are not constructs it proposes: causal claims about addiction; the author's clinical hypotheses (links to ADHD, autism, OCD, impulsivity, anxiety); the gambling-design material (near-miss, illusion of control) which the paper reviews rather than proposes; Stern's vitality-forms theory, affordance and ontology discussion; the "prime vitality structure" idea (speculative); per-game rank numbers; and the 27/18/6 matrix counts (covered qualitatively by the dimension entries).

Validation limit: the validator compared the entries against the ~1,300 words of the supplied text that the entries draw from (definitions, dimensions, status and limits), not the full ~19,000-word paper. A planted copied sentence is caught; paraphrase quality beyond overlap is not machine-verifiable, so review stays pending.
