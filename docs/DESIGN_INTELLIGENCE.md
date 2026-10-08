# Design intelligence: how Bob uses the reference library

The curated notes in `docs/game-design-references/` are *lenses*, not scripts. This file documents the operational system; it does not repeat the papers.

## 1. The design model (`engine/DesignModel.kt`)
A **read-only view derived from the stored decisions, facts and provenance**; nothing new is persisted, so old projects need no migration. It exposes:
- **Optional systems** as `PRESENT / ABSENT / UNKNOWN / NOT_APPLICABLE`: combat, economy, crafting, character power progression, narrative, multiplayer, procedural generation, OTA, supplied assets. `ABSENT` is a complete answer. Only `UNKNOWN` is a gap. `intendedAbsences` lists what the owner ruled out.
- **Experience reading** (`ExperienceLens`): "lucky" + skill/near-miss context = skilled near-miss dynamics, never "random rewards".
- **Progression kinds**: player mastery vs. game-state progress vs. character power. When character power is `ABSENT`, mastery *is* the progression (`masteryNote`).
- **Owner requirements / accepted recommendations / inferences / defaults** sorted by provenance, and `unresolvedMaterialDecisions` (required, relevant, still open).
- `ProceduralInvariants` derives what a generator must preserve (completable routes, readability, fairness, challenge/recovery rhythm, topology, the traversal grammar the owner named) and what may vary.

## 2. Decision precedence
latest explicit owner decision > earlier owner decision > owner-accepted recommendation > grounded high-confidence inference > default (`Provenance.rank`). `ProjectOps.setDecision` never lets a weaker source replace a stronger one. `Gates.applyOwnerText` re-settles a gate from anything the owner says, whenever they say it (`OWNER_CORRECTION`). `DesignCoherence.reconcile` runs before every export: a dependent decision that a "no" gate outranks is dropped; a gate that is only an inference loses to a stronger owner choice and is withdrawn. Defaults are re-derived whenever the design they depended on changed (`DerivedDefaults.apply`).

## 3. Intentionally absent systems
Gates (`schema/Gates.kt`: combat, economy, crafting, character power) are settled by, in order: the owner's words (`DesignSeeder`, `Gates.applyOwnerText`: "there isn't any fighting", "he doesn't get stronger"), a grounded model inference, or one short yes/no. A "no" closes the whole branch, takes back its detail answers, never counts against progress, and is never resurrected by the export. Genre never opens a gate: genres where a system is the point of the game (`core`) are not asked, every other genre is asked, and genre tags do not authorise features.

## 4. Lenses (`schema/DesignLenses.kt`)
One `Lens` per reference note: `id`, `source` file, 1-3 sentences of distilled guidance, and a trigger over a `LensContext` (project, current field, purpose). `select` returns at most **3** lenses; `guidance` adds a two-sentence baseline (owner intent outranks genre/defaults/recommendations; absence is a complete answer). That text, a few hundred characters, is all a model call receives (interpretation: appended to the existing single prompt; choose-for-me: its own prompt). Nothing is retrieved at run time and the papers never enter a prompt.

| Lens | Affects |
|---|---|
| MDA | feeling/fantasy/camera answers; "lucky" asks one clarification if its meaning would change the mechanics; export gets a "design reading" of the feeling |
| Objective/Challenge/Reward | loop, win/loss, slice, failure questions; reward never defaults to XP/loot |
| Rules of play / genre | system questions and choose-for-me; a system can be absent; genre is vocabulary only |
| Mastery | progression gate; mastery is progression when power is absent; no upgrade questions after a rejection |
| Procedural constraints + pattern PCG | world-structure answers; export gains "Procedural generation constraints" and a traversal grammar; authored games get none of it |
| Player-centered | reconciliation / playtest: separate bad concept, implementation, tuning, readability |
| AI-human | choose-for-me and follow-ups: Bob proposes, never overwrites |

**Add a lens:** put the note in `docs/game-design-references/`, add one `Lens(...)` entry. `DesignIntelligenceTest` fails until each note has exactly one lens and the guidance stays bounded.

## 5. Question selection
The question bank is a coverage checklist. Before anything is shown: already answered? settled by a gate or the owner's words? relevant to this genre/design (`Field.relevant`, gates)? derivable by Bob (derived defaults)? Candidates that fail are skipped. One model call per answer reads the message (`LlmInterpreter`) and may return inferences (confidence + quote), a follow-up and a gloss; bare yes/no and exact short picks need no call. A rich answer is mined for facts (concept, five minutes, core loop, first slice) and settles gates and objectives so redundant gates are never asked. Bob-generated follow-ups: at most three per project, never repeated; "lucky" has a deterministic one.

## 6. Choose for me
Acts as the designer on the whole design: the model (when ready) gets the design so far plus choose-lens guidance and may answer `none`; omission is recorded as a deferred/absent decision. Without a model, gate and story suggestions read the owner's description first ("Nothing you described needs ..."). The result is `OWNER_ACCEPTED_RECOMMENDATION`, distinguishable from an owner quote.

## 7. Completeness
Weighted share of build-critical dimensions with partial credit inside a dimension (`DesignDimensions`). Absent and not-applicable systems count as resolved; an unasked gate holds the Interaction/Progression dimensions back; inferred facts count.

## 8. Final reconciliation (`engine/DesignCoherence.kt`, run by `SpecVersioning.generate`)
`reconcile` (precedence, stale dependents) then `check` over CLAUDE.md, MASTER_PROMPT.md and ASSETS.md. Errors block the export: a system the owner ruled out reappearing; absent character power without the mastery statement; a procedural world without generation constraints; supplied asset packs not told to the builder first. Warnings: a never-established system appearing, an experience contradicted by random mechanics, a core loop with no objective. Plus the existing `ConsistencyReview`.
