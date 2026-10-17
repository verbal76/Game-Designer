# G6 engine repair (companion to FUNCTIONAL_REDISCOVERY_A-G.md)

Scope: the five engine corrections from G6, no interface change, no new state machine. Evidence is AUTOMATED on synthetic projects (rules only, no model, no network); the original Songs of Syx export was not available and is **not** claimed as validated. Nothing here was run on a device.

| # | Correction | Where | Result |
|---|---|---|---|
| 1 | Owner corrections change the real decision | `PlatformInstructions` (parser), `Director.applyPlatformInstruction` (hook before the pending-state switch), `Director.applyStatement` (generic change cue `OwnerChange`) | "Forget Android, it is a PC game now" replaces the platforms (OWNER_CORRECTION), clears or sets the primary, keeps the owner's sentence as a fact, invalidates approval, re-asks the open question. Works at a field question, `__review__`, `__ready__`, `__proposals__`, a conflict question and with no question. A confirmed decision that a passing mention disagrees with is kept and Bob says so; an unmistakable change cue ("actually", "make it", "switch to") applies it. |
| 2 | Facts versus decisions | `FactCoherence`, conflict `fact_contradicts_decision` (BLOCKER, not overridable, one alternative that applies the instruction) | A newer owner fact that disagrees with the platform decisions blocks readiness, blocks `SpecVersioning.generate`, appears in the status and the review ("I recorded but did not apply: ..."), and is repaired through the normal conflict question. An older instruction that a later owner decision superseded is not a contradiction. Existing projects that carry the original unapplied sentence are repaired the same way. |
| 3 | Reference guard | `ReferenceGuard.isPlausibleTitle` used by `DecisionExtractor.referenceGames`, `ProjectOps.addReferences` (every entry point), `researchNewReferences`, `SpecVersioning.generate` (`purge`), the approval fingerprint | Instruction sentences, platform/engine/tool names and fragments ("Add PC a", "Switch to PC", "Windows and Linux", "Use Unity for") are never references and never researched; real titles still pass. |
| 4 | Readiness | the same conflict is a BLOCKER, so `DesignDimensions.percent` stays at 99; generation reports it as an error | A contradictory project cannot report 100% or generate, even if the approval is re-recorded on exactly that state. |
| 5 | Primary platform | `PlatformInstructions` (also used by `DecisionExtractor.extract`) | "PC/Windows/desktop ... primary/main/first", "build the iPhone version first", "make PC primary, keep Android secondary" record `primary_platform` through `ProjectOps.setDecision`; removing a platform also drops a primary that pointed at it; naming a primary never drops the other platforms. |

Approval fingerprint now includes the reference names. **Side effect:** an approval given by an earlier build to a project that has references no longer matches, so that project shows its review once more before generating. Nothing else in the project changes.

## Remaining limitations
- Coherence (correction 2) covers platforms and the primary platform only. A fact that negates a system ("no combat") is still handled by the existing gates and rejected tags; a city-builder spec that describes combat in free text (Songs of Syx lead 4) was not reproduced and is not addressed.
- The parser is deliberately conservative: an unusual phrasing is not applied (the sentence is still recorded and, when it contradicts the decision, the conflict question appears).
- Generic volunteered changes (non-platform fields) use a cue list; without a cue the value is kept and Bob says so, with no confirmation question.
- Title suggester, owner-authored stale dependents, the overloaded `announcedConflicts` registry and verbatim research summaries (P2/P3) are untouched.
- Repeated research of a reference that returned NotFound still happens on each absorb (existing behaviour).
- Compose UI, `:applayer` and the Android build were not rebuilt locally; no core API they use changed.
