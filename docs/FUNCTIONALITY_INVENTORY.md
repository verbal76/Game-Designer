# H. Independently discovered functionality and I. Complete functionality inventory

Source of truth: the repository at `a4bed68` (branch `ccr-6330e20f-dm7zhm`), read-only. Evidence classes: **T** = covered by JVM tests (core/otakit), **CI** = compiled/linted/unit-tested by Android CI only, **D** = needs a device (not verified by me), **I** = inferred from code/docs, not exercised. The original audit prompt (sections A-G) and the "question-interface redesign" proposal were not in this session, so "redesign risk" below means: would a change to the question cards, `QuestionSpec`/quick replies, the pending-question states, the chat input or Back navigation touch it.

## H. Independently discovered functionality and forgotten capabilities

### H1. Conversation engine and AI behaviour
1. **Dual interpretation (rules + optional LLM, cross-checked).** `LocalInterpreter`, `LlmInterpreter`, `Director.mergeInterpretations`. Functional (T). Internal. Free text must keep working beside any new question UI. Preserve (critical).
2. **Pending pseudo-questions** (`__proposals__`, `__conflict:<id>__`, `__asset_plan__`, `__review__`, `__ready__`, reference-aspects). They are the dialogue state machine, not schema fields. Functional (T). Internal but drives the UI cards. Highest redesign risk. Preserve (critical).
3. **Hidden chat commands**: `status`, `generate`, `refine` (optional polish questions), "choose everything remaining for me" (bulk delegate), `keep my choice`, `back to designing`, `advanced mode` / `beginner mode`, `explain more`. Some have no button anywhere. Functional (T). Preserve; consider exposing "Refine" and "Choose the rest for me" as visible controls.
4. **Back / answer trail** (`goBack`, `answerTrail`) retracts the last answer and derived values and re-asks. Functional (T/CI). Redesign risk: high. Preserve.
5. **Crash-safe message queue** (`PendingTurn`, `queueTurn`, `retryPending`, turn ids make replays no-ops). Functional (T). Preserve.
6. **Conversation repair** (`MetaConversation`): "I already uploaded it" / "I already answered that" never become requirements. Functional (T). Preserve.
7. **Design facts with retraction, rejected genres/tags, provenance ranking** (DEFAULT < inference < accepted < explicit < correction). Latest word wins; weaker sources never override. Functional (T). Preserve (critical).
8. **Derived defaults** (`DerivedDefaults`): routine engineering decisions recorded with DEFAULT provenance and re-derived when the design changes; "refine" can ask them. Functional (T). Preserve.
9. **Reference-game research and aspects** (`WebResearch`, `ReferenceAspects`): with internet permission, named games are looked up and Bob asks what the owner likes about each; sources recorded. Functional in tests with fakes (T); live behaviour unverified (D/I). Preserve.
10. **Term research** ("what is X?"): Bob can look a term up and states when he could not. Same status as 9.
11. **Toolchain version capture** at generation (`research.toolchainFacts`) when internet is allowed. T with fakes; live unverified.
12. **Design-intelligence layer** (`DesignLenses` + nine reference documents in `docs/game-design-references/`, `DesignModel`, `DesignCoherence`, `Gates`, `AssetStrategy`, `ProceduralAuthorship`, `PlatformPolicy`, `UpdateStrategy`, `StackAdvisor`, `Explainer`, `Acknowledgements`, `DesignStages`, `Exclusions`, `PrototypeLifecycle`). Functional (T). Internal. Preserve (critical).

### H2. Projects, persistence, import/export
13. **Three project modes** (new game, existing game from GitHub, playtest feedback) plus in-project switching. Functional (T/CI). Preserve.
14. **Atomic saves, `.bak` and `.corrupt` recovery, schema-version migration registry, refusal of newer formats** (`FileProjectStore`, `ProjectCodec`, `UnsupportedSchemaException`). Functional (T). The migration registry is empty beyond the current schema: untested against a real older file. Preserve (critical).
15. **Backup and restore** (zip of projects and uploaded images; never keys or tokens). Functional (CI; restore path D). Settings only. Preserve.
16. **Export package zip, and per-document Copy / Share / Save** with a file-naming dialog; sanitized names. Functional (T/CI). Preserve.
17. **Append-only spec version history and side-by-side compare** (`SpecVersioning`, `SpecCompare`, Compare screen with "changed sections only"). Functional (T). Preserve.
18. **Reevaluate** (re-read a saved design with newer intelligence, source audit, baseline, diff, discard; screens `reeval-pick`, `reeval/{id}`). Functional (T). Hidden: only reachable from Home and the chat menu. Preserve; consider promoting.
19. **Second opinion** on a reevaluation (cloud or on-device) with "Raise with Bob" turning a point into a conversation, and **"Review with Claude"** on a spec (cloud). Functional (T with fakes; live D). Preserve.
20. **Per-project Claude plan / usage style** drives scope and resource estimates (`ScopeEngine`: tiers, Low-to-Extreme effort, phases). Functional (T). Preserve.

### H3. Existing-game and playtest modes
21. **Repository inspection** (`RepoInspector`, `GitHubClient.inspect`): engine, build system, verified facts versus assumptions, head SHA, feeding spec section 17. Functional (T with fakes; live D). Preserve.
22. **GitHub write methods exist but nothing calls them**: `createRepository` and `putFile` (tested only with mocks). Dormant. The product text says GitHub write automation is intended. Review with owner before anyone deletes or wires them.
23. **Playtest feedback capture with severity guess and incorporation status** (OPEN / INCORPORATED / DISMISSED). Functional (T). Preserve.
24. **Feedback attachments are modelled (image, video, audio) and rendered into the spec, but no screen or Director path can add one.** Partially implemented / dormant. Needs an owner decision (complete or drop).

### H4. Assets and branding
25. **Branding screen**: three slots (icon, studio splash, game splash), upload, preview, replace, remove, "create original", "generic placeholder", skip. Functional (CI; picker D). Preserve.
26. **Image validation** (`ImageSniffer`: PNG, JPEG, WebP header checks, size limits) and masters never modified; masters included in the export under `branding/master/`. Functional (T). Preserve.
27. **Asset plan** (`AssetPlan`): derived needs with resolutions (external CC0, other license, procedural, generated original, owner-supplied, deferred), provenance and licensing text in `ASSETS.md`. Functional (T). Preserve.
28. **`tools/derive-branding.sh`**: reproduces Android resources from the studio logo (needs ImageMagick); referenced only from docs. Functional (I). Preserve.

### H5. AI providers and models
29. **On-device model** via LiteRT-LM (`LiteRtLocalLlm`), loaded/unloaded, with status. Implemented; device behaviour D.
30. **Model manager** (`ModelManager`, `ModelInstaller`): downloads with resume, SHA-256 verification before use, Hugging Face token for gated models, import of a local `.litertlm` file, keep-screen-on while working, removal. Functional (T/CI; D).
31. **Device-aware recommendation** (`DeviceProfile`, `ModelRecommender`): hides models the phone cannot run and says how many. Functional (T).
32. **Verified model catalog**: bundled fallback (`ModelCatalogData`) plus a catalog published by `model-catalog.yml` from `tools/models/candidates.json` via `discover.py` (pre-release `model-catalog`). Functional (I/CI).
33. **Cloud providers**: Anthropic and any OpenAI-compatible service with a base URL, Test button, "what is and is never sent" statement, Keystore-encrypted secrets (`SecretStore`). Functional (T with fakes; live D).
34. **Legacy provider setting** (`cloudProvider` enum kept next to the newer `llmProviderId`) for rollback safety. Superseded but must stay.

### H6. Settings, onboarding, identity
35. **Onboarding** (director name, experience, permissions up front: internet research, GitHub; Claude plan and usage; on-device AI) and **splash** with the Hot Attic logo. Functional (CI; D).
36. **Dormant settings**: `studioName` is stored but nothing reads it (package IDs hard-code `com.hotatticgames`); `defaultExperience` only affects NEW projects (in-project mode is separate). Review.
37. **About / diagnostics** screens (versions, runtime fingerprint, layer source, last update check, rejected builds). Functional (CI).

### H7. Update system (OTA) beyond what was asked
38. **Signed, verified, staged updates with trial start, health marking, crash rollback, bad-version list, high-water rollback protection, runtime-fingerprint gate** (`otakit`, `OtaManager`). Functional (T).
39. **Two lines**: dev (automatic, "Update ready - Restart now" banner) and stable (pick any of the last 10 builds, applies only on restart), plus pin, "Use bundled layer", "Check for update". Functional (T/CI; D).
40. **Layer identity naming** (`VersionIdentity`: APK major + layer minor from OTA sequence). Functional (T/CI). v5 re-based it to sequence 16.

### H8. Build, release and signing
41. **Workflows**: `android.yml` (manual or ready PRs; now also uploads the debug APK), `ota-publish.yml` (only when `ota/publish-request.json` changes), `release-v4.yml` (one-shot, left in place; cannot re-publish), `release-v5.yml`, `model-catalog.yml`. Functional (CI). Do not edit a `release-*.yml` casually: pushing publishes.
42. **Signing keys live in a PRIVATE DRAFT release named `gd-signing-keys`** (OTA key pair, Android keystore and password), loaded by `tools/ota/ensure-keys.sh`. Surprising dependency: deleting or publishing that draft changes the app's signing identity. Critical to preserve.
43. **Guards**: 16 KB page-size check (`tools/check-16kb.py`), same-certificate and higher-version-code upgrade check, exact-source-SHA release notes. Functional (CI).
44. **Hot Attic platform policy** (Android target API 36, OTA decision with Mote reference) enforced in generated documents. Functional (T).

### H9. Surprising findings, hidden dependencies, loss risks in a redesign
- The pending pseudo-states and `QuickReply`/`QuestionSpec` are the only bridge between engine and screen; a redesigned question UI that ignores `recommended`, `canDelegate`, `canSkip` or the pseudo-states would silently drop conflict handling, asset-plan approval and the design-review gate.
- "Choose for me", "Skip", "Ask me later", "Keep my choice", "Back" and "Explain more" exist as engine commands first, buttons second.
- Documentation drift: `docs/CONVERSATION.md` still describes the retired `ota_scope` follow-up; the README is one sentence. Most capabilities above are documented only in code and tests.
- Superseded but required for loading old projects: `ota_scope`, legacy OTA content ids (`gameplay_code`, `assets`, `data`, `features` expand to the finer ids), `cloudProvider`.
- Dormant: GitHub write calls, feedback attachments, `studioName`.
- Nothing above was changed, removed or redesigned by this audit.

## I. Complete functionality inventory (reference checklist)

Priority: **C** critical (loss breaks the product or data), **H** high, **M** medium, **L** low. Status: **V** verified by tests/CI, **U** implemented, device/live behaviour unverified, **P** partial, **D** dormant (code exists, no entry point), **S** superseded but kept.

| # | Capability | Facing | Status | Priority |
|---|---|---|---|---|
| 1 | Conversational interview, adaptive questions, one at a time | user | V | C |
| 2 | Rules + LLM interpretation with fallback | internal | V | C |
| 3 | Pending pseudo-question state machine | internal | V | C |
| 4 | Choose for me / skip / ask me later / bulk delegate | user | V | C |
| 5 | Back (retract and re-ask) | user | V | H |
| 6 | Provenance ranking, facts, retractions, rejections | internal | V | C |
| 7 | Derived defaults and Refine | both | V | H |
| 8 | Conflict detection, explain, alternatives, informed override | user | V | C |
| 9 | Completeness and nine-stage progress | user | V | H |
| 10 | Plain-English teaching cards, Explain more | user | V (render CI/D) | H |
| 11 | Beginner / Advanced selector and chat commands | user | V (UI D) | H |
| 12 | Bob's acknowledgements, calm conflict tone | user | V | M |
| 13 | Platform policy (API 36), OTA requirement model, stack advisor | both | V | C |
| 14 | Multiplayer discovery and network plan | user | V | H |
| 15 | Explicit exclusions kept out of all documents | both | V | C |
| 16 | Design review and approval gate | user | V | C |
| 17 | CLAUDE.md, master prompt, 14 handoff documents | user | V | C |
| 18 | Honest generation stages, in-app Documents tab | user | V (UI D) | H |
| 19 | Spec versions, compare | user | V | H |
| 20 | Audit, consistency review, coherence checks | internal | V | C |
| 21 | Scope and resource estimate | user | V | M |
| 22 | New / existing / playtest project modes | user | V | H |
| 23 | Repository inspection | user | U | H |
| 24 | GitHub write (create repo, put file) | internal | D | M |
| 25 | Playtest feedback text capture | user | V | M |
| 26 | Feedback attachments (image/video/audio) | internal | D/P | M |
| 27 | Reevaluate, second opinion, raise with Bob | user | V (live U) | M |
| 28 | Review spec with Claude (cloud) | user | U | M |
| 29 | Branding uploads, previews, generate/placeholder/skip | user | V/CI | H |
| 30 | Image sniffing and untouched masters | internal | V | H |
| 31 | Asset plan, strategy, provenance, licensing | both | V | H |
| 32 | Internet research (references, terms, toolchain) | both | U | M |
| 33 | On-device LLM runtime | user | U | H |
| 34 | Model download/verify/resume/import, device recommendation | user | V/U | H |
| 35 | Verified model catalog + workflow | both | U | M |
| 36 | Cloud providers (Anthropic, OpenAI-compatible), Test, privacy text | user | V (live U) | H |
| 37 | Encrypted secret storage | internal | U | C |
| 38 | Onboarding and splash | user | U | M |
| 39 | Settings (name, defaults, toggles) | user | V/CI | M |
| 40 | `studioName` setting | internal | D | L |
| 41 | Project list, cards, rename, delete, per-project prefs | user | V/CI | H |
| 42 | Atomic save, backup copies, corrupt-file recovery, migrations | internal | V | C |
| 43 | Backup / restore zip | user | U | H |
| 44 | Export package, copy/share/save | user | V/CI | C |
| 45 | Signed OTA with trial, health, rollback, fingerprint gate | both | V | C |
| 46 | Dev/stable lines, stable picker, pin, reset, diagnostics | user | V/CI | H |
| 47 | Legacy ids and settings (`ota_scope`, `cloudProvider`, coarse OTA ids) | internal | S | C |
| 48 | CI workflows (Android, OTA publish, release, catalog) | internal | V | C |
| 49 | Signing material in draft release `gd-signing-keys` | internal | V | C |
| 50 | Release guards (16 KB, same cert, version code) | internal | V | C |
| 51 | `tools/derive-branding.sh` | internal | U | L |
| 52 | Reference documents powering the design lenses | internal | V | H |
| 53 | Accessibility: reduced motion, labels, theme contrast test | user | CI/D | M |
