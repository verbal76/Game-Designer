# Architecture

```
:app (Android)                         :core (pure Kotlin/JVM)
 Compose UI  ──ViewModels──►  Director ─► engines (Completeness, Planner, Conflict, Scope, AssetPlan, Audit)
 Splash/Onboarding/Chat/             │         ▲
 Projects/Spec/Branding/Settings     │         │ Traits (derived view of decisions) + Fields (interview schema)
 Secrets (Keystore)                  ├─► LlmProvider  ◄── LiteRtLmProvider (app) / AnthropicProvider (core) / fakes (tests)
 LiteRT-LM runtime adapter           ├─► ResearchProvider ◄── WikipediaResearch + VersionResearch (core, HTTP)
 File pickers / share / export       └─► ProjectOps (pure state transitions on immutable Project)
                                     Generators: ClaudeMdGenerator, MasterPromptGenerator, SpecVersioning, ExportPackage
                                     Persistence: ProjectCodec + MigrationRegistry + FileProjectStore
                                     GitHub: GitHubClient + RepoInspector
```

## Key ideas
- **Immutable `Project`** is the single source of truth; every change goes through `ProjectOps` and is persisted by the host.
- **Schema-driven interview** (`schema/Fields.kt`): each `Field` has a relevance predicate over `Traits`, options, a validator and a "choose for me" suggestion with rationale. Relevance is dynamic (e.g. combat fields only for combat genres).
- **Completeness is derived** from real required relevant fields + asset needs + pending uploads - never a cosmetic percentage.
- **Conflict engine** is a list of deterministic rules. Verified impossibilities (engine cannot target platform) are non-overridable blockers; everything else is explain -> recommend -> allow informed override (recorded, never repeated).
- **Director** (`director/Director.kt`) = deterministic conversation controller. Models only assist extraction/Q&A through `LlmProvider`.
- **Generators are deterministic** composition of structured state + genre knowledge, so output is reproducible and testable (no TBD/TODO emitted; test enforces it).
- **Versions are append-only** (`SpecVersioning`). Playtest feedback is incorporated into a new version of kind `PLAYTEST_REPAIR`.

## Future-proofing for the AI Game Director (data model hooks already present)
`RepoLink/RepoInspection` (repository-aware continuation), `PlaytestFeedback.attachments` (screenshots/video/audio), `SpecVersion` (build lineage), `ProjectMode` (new/existing/playtest), `LlmTier` (local vs strong cloud routing), `BrandingSlot` string keys (new slots without migrations), `ResearchNote.kind`.

## Testing
`./gradlew -PcoreOnly :core:test` (no Android SDK needed). Includes a full simulated conversation from the owner's example idea to a generated spec (`EndToEndTest`). `core/src/test/.../SampleDumpTest` writes a sample package to `core/build/sample/` for manual review.
