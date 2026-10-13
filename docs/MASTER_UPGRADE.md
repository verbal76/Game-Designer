# Master upgrade: beginner-friendly planning, plain-English teaching, complete handoff

Baseline audited: b648663. Evidence classes used below: **automated** (deterministic tests), **simulated** (scripted owner replies through the real Director, rule-based interpretation, no model), **unverified** (needs a device, a live model, a network or a store).

## Audit of the baseline (before this change)

| Capability | State at baseline | State now |
|---|---|---|
| Source architecture (core / applayer / shell / otakit, one branch) | Complete and verified (automated) | unchanged |
| Questionnaire | Partially complete: no multiplayer follow-ups, audience, length, budget, test delivery, Steam Deck | Complete and verified (simulated) |
| Beginner interface (Compose UI) | Implemented but unverified (no Android SDK or device here) | unchanged; new content flows through the existing question cards |
| Beginner / Advanced | Experience level existed as a per-project preference; no in-project switch; Advanced asked nothing extra | Complete and verified (simulated): chat commands switch mode without changing answers; Advanced asks the technical controls |
| Plain-English explanations | Partial: one-line "why" per question | Complete and verified (automated): seven-part teaching cards, "Explain more", acronyms defined |
| Project persistence and migration | Complete and verified (automated) | legacy OTA ids expand; lossless round trip tested |
| Decision logic, conflicts, coherence | Complete and verified | extended (see below) |
| Technology recommendation | Partial: ignored update and multiplayer needs | Complete and verified (automated): ranks on platforms, dimension, OTA code needs, multiplayer; Steam Deck via Linux |
| Platform selection and primary platform | Complete | Steam Deck added |
| Multiplayer | Partial: intent, timing, mode only | Complete: players, local layout, hosting, sessions, discovery, cross-play, offline |
| OTA | Partial: five coarse content classes, platform strategy | Granular content classes, never-OTA classes, engine code-update fit, capability versus policy |
| Asset sourcing and licensing | Strategy complete; external research implemented but unverified live | licensing classes documented in `docs/ASSET_PLAN.md` |
| Optional internet research, GitHub, local model | Implemented but unverified live | unchanged |
| Generated CLAUDE.md and master prompt | Complete and verified | adds milestones, early build, escalation, release permissions, progress rules, exclusions |
| Handoff package | Missing (only CLAUDE.md, prompt, ASSETS.md, research, history) | Complete: 14 documents from one policy source |
| Requirements ledger | Missing | Complete and verified (automated) |
| Gameplay invariants / failure escalation / release permissions | Missing | Complete (generated text); effectiveness with a live agent is unverified |
| Open defects | postponement misread, stale multiplayer note, title words (fixed earlier) | none known; UI rendering on a device is unverified |

## What changed

- `engine/Explainer.kt`: teaching cards (what it is, how it works, why it matters for this game, advantages, disadvantages, Bob's recommendation, what happens if you choose differently, long-term consequence, hard-to-reverse flag). Shown the first time a topic comes up; "explain more" gives depth.
- Discovery: multiplayer follow-ups, audience, game length, budget, test delivery; explicit exclusions are recorded in the owner's words and checked in every generated document; the latest word wins.
- OTA: text, levels, scenes, models, textures, audio, interface, scripts, gameplay logic, engine behavior, native code; engine behavior and native code are never over-the-air; engines are rated for gameplay-code updates; unmet requests are stated, never downgraded; a content-only updater is never described as a complete OTA solution.
- Conflicts added: online play with an offline-only architecture, code updates with an engine that cannot update code, a phone game without touch, Steam Deck without a controller, servers with no budget, store limits on downloaded code.
- Handoff documents (`docs/`): game design, requirements ledger, exclusions, architecture decisions, tech stack, asset plan, build and release, test plan, gameplay invariants, milestones, completion criteria, owner authorization, failure escalation, progress reporting.
- Tests: `UpgradeScenariosTest` covers scenarios A to H, mode switching, migration and engine ranking (11 tests); the earlier suites still pass.

## Not verified

The Compose screens on a device; any live model; real beginners; live web research and GitHub; Apple and Google policy wording at implementation time (the generated spec tells the builder to re-verify); a real coding agent following the generated documents.
