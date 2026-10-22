# Prototype lifecycle and handoff

Game Designer is a temporary launch tool. Its generated `CLAUDE.md` starts with `<!-- GAME-DESIGNER-LIFECYCLE: PROTOTYPE -->` and a section "0a. Lifecycle and handoff"; the master prompt repeats the rule. In any fresh Claude conversation, without the app, the marker says whether prototype-era scope controls still apply.

- **Handoff triggers:** owner accepts the prototype; starts post-prototype work; or gives an explicit instruction beyond the prototype scope.
- **At handoff** the marker becomes `OWNER_DIRECTED`, `docs/HANDOFF.md` gets a dated entry (`PrototypeLifecycle.handoff` implements the same edit and is unit-tested).
- **Expires:** items tagged `[prototype-only]`, scope controls, designer recommendations/assumptions, the prototype workflow. **Survives:** owner-set constraints, technical docs, real security/licensing/platform/save-compatibility limits.
- Scope controls mean "not required for this prototype", never "forbidden". Questions are not instructions.

Related behavior added in the same change: primary-platform question for multi-platform projects (all platforms retained; shared core plus per-platform layers), multiplayer discovery (`multiplayer_intent/timing/mode`), requirement-driven stack rationale (`StackAdvisor`), art-style fidelity rule, and a single-source-of-truth working model for agents.

Evidence: `PrototypeHandoffTest` proves the generated files contain these rules and that the lifecycle transformation works. How a live model behaves when reading them is not proven by tests.
