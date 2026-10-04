# GAME DESIGNER — AUTHORITATIVE PRODUCT AND BUILD SPECIFICATION

## 0. Authority
This file is the authoritative product specification for `verbal76/Game-Designer`.

Claude Code is the implementation agent. Read this entire file before making architectural decisions. Preserve user intent over convenience. If repository reality conflicts with this document, investigate the conflict, preserve recoverable working state, and choose the safest path that satisfies this specification. Do not silently reduce scope to an MVP, toy, mockup, or gray-box demonstration.

The owner is Kevin (also uses Verbal informally). This application is initially for his personal use.

## 1. Product Mission
Build an Android-first application called **Game Designer** that conducts an intelligent conversational game-design process and produces an implementation package that Claude Code can use to build a genuinely playable game from scratch or substantially continue/rebuild an existing game.

This is not a generic prompt generator and not a fixed questionnaire. It is the first version of a long-term **AI Game Director**.

V1 must turn a rough statement such as:

> “I want Risk of Rain 2 mixed with Vampire Survivors, but I’m a wizard defending a moving castle.”

into a researched, internally consistent, technically feasible, implementation-ready game specification. The primary deliverables are:

1. An authoritative generated `CLAUDE.md` for the target game/project.
2. A compact but complete Claude Code master execution prompt that tells Claude how to execute that specification autonomously.
3. Supporting structured project data and research/asset records so the specification can be revised without starting over.

The generated instructions must target a **workable, fully playable first game**, not a block moving across a screen, an intentionally skeletal proof of concept, or a collection of TODOs.

## 2. Long-Term Destination
Architect V1 so it can grow into this workflow without a rewrite:

Idea → conversational design → research → asset acquisition/generation → repository creation/inspection → Claude Code orchestration → autonomous implementation → automated validation → build artifact → human playtest → screenshot/video/voice feedback → repository-aware continuation → release polish.

V1 does not need to autonomously run the entire Claude build loop, but its data model and service boundaries must not prevent that future.

## 3. Product Principles

### 3.1 Conversational, not form-driven
The primary experience is a natural conversation with an AI director. Structured controls may support the conversation, but the user must not be forced through a giant static questionnaire.

The interview dynamically branches based on what is being designed. A factory builder, city builder, 2D platformer, 3D roguelite, survival/crafting game, strategy game, puzzle game, etc. require different follow-up questions.

### 3.2 Beginner-friendly, expert-capable
At onboarding/project creation, ask experience level and technical preferences. A beginner can describe games and feelings without knowing engines, renderers, SDKs, CI, or architecture. The Director figures those out and explains important choices.

Experienced users can specify preferences and override recommendations.

### 3.3 “Choose for me” is always valid
The user must be able to delegate decisions. For ordinary choices, select the best reasonable answer. For meaningful creative forks, offer a small set of materially different choices plus a recommendation when possible.

### 3.4 Challenge bad choices, then respect owner authority
Detect conflicts, incompatible requirements, excessive scope, unsuitable engines/platform combinations, licensing problems, performance problems, etc. Explain the conflict and recommend alternatives.

Once the user understands the tradeoff and explicitly chooses a direction, follow it unless it is genuinely impossible or unsafe. Do not argue indefinitely.

### 3.5 No unresolved implementation holes
The Director actively detects missing systems. Examples include saves, pause behavior, death/restart, input, inventory, crafting UX, resource respawn, accessibility, performance, tutorial/onboarding, UI, audio, win/loss conditions, build identity, and platform packaging.

A generated final specification must not casually contain “developer should add later,” “asset TBD,” or equivalent unresolved requirements unless the user explicitly chose to defer that feature.

### 3.6 Playable means playable
The target first build should contain the coherent intended gameplay loop, sufficient content to evaluate it, real/appropriate visuals, controls, menus/settings/saves where relevant, audio where relevant, win/failure/progression behavior where relevant, validation, and a build artifact for the selected platform(s).

Claude should autonomously implement, test, repair, and polish everything practical before requiring human playtesting.

## 4. V1 Host Platform
Game Designer itself is **Android phone first**.

Design responsive layouts that can later support tablets without rewriting the app. The target game produced by Game Designer can be Android, iPhone/iPad, Windows/PC, web, or other supported targets chosen during that game's interview.

The application should be voice-friendly because the owner primarily uses Android voice-to-text. Do not require a custom speech-recognition stack merely to satisfy V1 if standard Android dictation already provides an excellent text-input path. Ensure long conversational text input works well with voice-to-text, editing, scrolling, and submission.

## 5. Branding for Game Designer
The repository contains the authoritative **Hot Attic Games** studio logo supplied by the owner.

Game Designer must show the Hot Attic Games branding as its initial startup splash. Locate the repository asset rather than redrawing or replacing it. Preserve the source asset untouched and derive platform-specific resources as needed.

Create a professional Game Designer application icon and any secondary launch treatment needed, consistent with the Hot Attic Games identity, unless a suitable authoritative asset already exists in the repository.

Do not confuse Game Designer's own branding with branding assets for games designed inside the app.

## 6. Project Modes
V1 should establish these project types in the data model and UI:

### A. New Game
Start from an idea, references, or a rough description and create a complete build specification.

### B. Existing Game
Optionally connect/select a GitHub repository, inspect its actual code and architecture first, summarize the current reality, then conduct a repository-aware interview for continuation, expansion, modernization, repair, or rebuild. Preserve known working systems and rollback state.

### C. Playtest Feedback / Continue
Store a prior design/build specification and accept feedback after the user plays a build. Support text/voice descriptions and architect for screenshots and gameplay video attachments. Convert feedback into a repository-aware continuation specification/prompt rather than forcing a new project.

If C cannot be fully implemented in the first internal build, its data structures and navigation must still be designed so it is a natural extension rather than a rewrite.

## 7. Onboarding and Permissions
On first use, explain capabilities and request broad permissions/settings up front so the Director does not stop every few minutes asking permission.

The user should be able to grant permission for automatic internet research whenever the Director determines current information is useful.

GitHub integration is **optional**. Game Designer must remain useful without GitHub: the user can manually create repositories/files and copy the generated execution prompt.

If GitHub is enabled, prefer full useful integration: authenticate securely, inspect repositories, create/select repositories when authorized, read trees/files/history, and eventually create branches/files/commits as appropriate. Destructive actions require explicit confirmation. Never hardcode credentials or secrets.

## 8. AI Architecture

### 8.1 Local-first intelligence
Investigate a practical on-device open-weight LLM architecture for modern Android, with Qwen-family or another demonstrably suitable model as a candidate. Do not select a model merely because this specification mentions Qwen. Benchmark/research current viable options and choose based on quality, license, device practicality, context support, runtime support, download size, RAM/storage use, speed, and maintainability.

Prefer a replaceable/downloadable model package over permanently baking a huge model into the APK when practical.

The local model is suitable for tasks such as:
- conversation routing and routine responses,
- extracting structured decisions from user messages,
- deciding the next interview question,
- completeness tracking,
- summarization,
- classification,
- detecting straightforward omissions/conflicts,
- routine recommendations.

### 8.2 Optional stronger cloud reasoning
Architect a provider abstraction so a stronger cloud LLM can be configured for difficult work without coupling the product permanently to one provider. V1 may implement one provider first if that is the most efficient path, but the boundary must support additional providers later.

Potential escalation tasks include complex architecture, large repository analysis, difficult contradiction resolution, final design audit, and final `CLAUDE.md` synthesis/verification.

No API key is committed to source. Store secrets using appropriate Android secure storage. The app must still provide useful functionality when no cloud provider is configured.

### 8.3 Internet research is separate from model memory
With initial user permission, research current information when necessary rather than relying on stale model knowledge. Research may include current engine versions, SDK/platform requirements, library compatibility, asset licenses, build requirements, and reference-game characteristics.

Record sources/provenance in structured project state where appropriate.

## 9. Director Identity
The AI Director has an editable display name. Default: **Bob**.

The user can rename it during onboarding/settings (for example, “Glaxor”). Do not make the name technically significant.

Bob should be knowledgeable, practical, concise enough for phone use, willing to make recommendations, and focused on moving the design toward completion rather than producing endless questions.

## 10. Initial Conversation
A new project should begin with a low-friction question similar in spirit to:

“What game do you want to make? Describe the idea however you want. You can name games you like, describe the feeling you want, or give me only a rough concept.”

Then infer what is already known and ask only useful unresolved questions.

Research named reference games automatically when internet permission is enabled. Extract useful design characteristics without instructing Claude to copy copyrighted characters, art, maps, music, writing, or proprietary assets.

## 11. Design Knowledge / Interview Coverage
The dynamic interview and underlying schema must be capable of resolving, when relevant:

- game genre/subgenre and hybrid genres,
- 2D/2.5D/3D and perspective,
- core fantasy and intended player feeling,
- reference games and which aspects are being referenced,
- core gameplay loop,
- session structure and expected play pattern,
- world/level structure and generation,
- movement and camera feel,
- combat/noncombat interaction,
- enemies/AI/bosses,
- progression/upgrades/economy,
- survival/crafting/inventory/resource systems,
- city/factory/automation/simulation systems,
- characters/classes/loadouts where relevant,
- difficulty and failure/recovery,
- save/load/autosave,
- tutorial/onboarding,
- UI/HUD/menus/settings,
- input methods and remapping,
- touch/controller/keyboard/mouse needs,
- accessibility,
- audio/music/VFX,
- visual/art direction,
- target platforms,
- mobile orientation with recommendation,
- performance target and minimum hardware assumptions,
- engine/framework/toolchain,
- package/application identity,
- versioning,
- store/release preparedness,
- monetization (free/paid/ads/IAP/undecided),
- privacy/network requirements,
- testing/CI/build pipeline,
- asset plan and licensing,
- scope/content quantity,
- definition of done.

Do not ask irrelevant categories just to fill fields. Relevance is dynamic.

## 12. Scope Determination
Infer an appropriate initial game scope deterministically from the design, selected platforms, complexity, available assets, technical constraints, and the user's Claude resource preference.

Then tell the user the recommended scope and ask whether they want to go bigger (or smaller).

Do not automatically collapse ideas into an MVP. The preferred default is the largest coherent first playable game that is practical for autonomous Claude Code execution while maintaining quality.

## 13. Claude Plan / Resource Awareness
During onboarding or project setup, ask what Claude environment/plan the user expects to use and how aggressively they want to consume its allowance.

Do not pretend to know exact future token consumption. Provide useful qualitative estimates such as Low / Moderate / Heavy / Extreme and explain why.

For large designs, recommend an execution strategy that preserves one authoritative specification while allowing Claude to implement in durable phases/checkpoints.

Generated prompts should optimize Claude usage:
- avoid redundant rediscovery,
- preserve decisions in files,
- use focused agents/subagents only when their value exceeds their cost,
- avoid gratuitous parallelism,
- run targeted tests before expensive broad tests when sensible,
- maintain checkpoints/handoffs,
- do not sacrifice product quality merely to save tokens.

## 14. Technology Recommendation
Ask whether the user has engine/IDE/pipeline preferences. If not, recommend the best current workable option for the actual game and target platforms.

Use current research where version choice matters. Explain major tradeoffs in plain language and allow override.

The generated specification must pin or clearly define important toolchain/runtime versions where reproducibility requires it, while avoiding unnecessary version rigidity.

## 15. Offline / Network Policy
For this V1, prefer games designed to work locally/offline unless the game concept inherently requires networking.

Online multiplayer design/orchestration is deferred to a later Game Designer build. The data model should not make future multiplayer support impossible, but V1 does not need the full multiplayer interview tree.

Avoid paid APIs/services by default. If a requested feature would introduce ongoing cost, explain it and require a conscious choice.

## 16. Asset Research and Generation
The default external asset license policy is **CC0/public domain**.

When assets are required:
1. Determine what is needed before finalizing the specification.
2. Search legitimate sources, including itch.io where appropriate.
3. Verify the actual license; “free download” is not sufficient.
4. Record source, creator where applicable, license, and relevant provenance.
5. Prefer coherent asset sets over a visually inconsistent pile of individually acceptable assets.
6. Preview/present meaningful alternatives to the user when there are creative choices.

If no suitable CC0/public-domain asset exists, do not leave the requirement undone. Offer alternatives and, where practical, direct the build to create original/procedural assets using engine primitives, generated meshes/materials/textures/shaders/UI/audio, or other lawful original methods. The system itself should eventually support generation where appropriate.

Do not scrape or redistribute assets in violation of site terms or licenses.

## 17. Branding & Identity for Each Designed Game
During design, include a **Branding & Identity** stage with optional uploads for:

1. Game/app icon image.
2. Studio/developer splash image.
3. Game splash/title-screen image.

Show previews and allow replace/remove.

If an asset is absent, offer sensible choices such as:
- create/generate an appropriate original asset,
- use a clean generic temporary asset,
- skip the element when it is genuinely unnecessary.

The recommended default is automatic creation rather than leaving work undone.

Ask for package/application identifiers and other required release identity fields at the appropriate time. Explain them to beginners and propose valid defaults.

Preserve uploaded master artwork untouched. The generated build specification should instruct Claude to derive all target-platform sizes/formats/safe-zone variants automatically.

Architect the project schema so optional future branding fields (logo/title treatment, store feature graphic, loading artwork, etc.) can be added without migration pain.

## 18. Accessibility
Accessibility is a standard design consideration, not an optional afterthought. Recommend and include relevant features such as subtitles, text sizing, color accessibility, remappable controls, reduced motion, vibration/haptics controls, difficulty assists, readable touch targets, and other genre/platform-appropriate accommodations.

Do not mechanically add irrelevant settings.

## 19. Store / Release Preparedness
Ask progressively whether the user is prepared to define release-facing details. Do not dump a giant store questionnaire at once.

Where appropriate, cover:
- package/application ID,
- display name,
- version/version code strategy,
- icon and splash assets,
- orientation,
- permissions,
- signing-ready configuration (without exposing keys),
- privacy/network implications,
- release build configuration,
- store metadata drafts/feature assets when requested.

Generic functional placeholders are allowed only when explicitly understood as replaceable branding, not as excuses for incomplete gameplay.

## 20. Persistent Local Project State
Local-first storage is the V1 default.

Persist projects across sessions, including:
- conversation-derived decisions,
- normalized structured specification,
- unresolved questions,
- recommendations and explicit overrides,
- references and research summaries,
- asset selections/provenance,
- repository association if any,
- generated `CLAUDE.md` versions,
- generated execution prompts,
- playtest feedback and revisions,
- project status/completeness.

Provide export/backup of project data and generated artifacts. Architect for optional cloud sync later without requiring it now.

## 21. Completeness Engine
Maintain structured completeness based on actual unresolved requirements, not cosmetic percentages.

The UI may present understandable categories such as Gameplay, Content, Art, Technical, Platform, Assets, and Release, but their state must derive from concrete schema requirements and relevance rules.

Before declaring a specification ready, run a final audit for:
- contradictions,
- unresolved required decisions,
- missing gameplay systems,
- platform incompatibilities,
- licensing gaps,
- technical/build holes,
- missing required assets,
- undefined human-only decisions,
- vague requirements likely to cause Claude to improvise incorrectly.

## 22. Generated Target-Game CLAUDE.md
The generated file is the authoritative build specification for the target game. It should be organized for machine/agent execution as well as human readability.

It should include, as relevant:
- authority and owner decisions,
- game vision and non-negotiables,
- player experience,
- complete gameplay/system requirements,
- content scope,
- controls/camera,
- art/audio direction,
- exact selected assets and provenance,
- architecture/toolchain/platform targets,
- repository/branch/release rules,
- performance requirements,
- persistence/save requirements,
- accessibility,
- UI/settings,
- testing/validation,
- packaging/build artifacts,
- definition of done,
- explicit exclusions/deferred features,
- autonomy/resource rules.

The generated file should reduce repeated context consumption in Claude Code by serving as durable project memory.

## 23. Generated Claude Code Master Prompt
Alongside the target-game `CLAUDE.md`, generate a compact master prompt instructing Claude Code to:

- inspect/read the repository and authoritative `CLAUDE.md` first,
- preserve existing working state/rollback points for existing projects,
- research current facts when needed,
- implement the specification rather than a toy prototype,
- work autonomously through implementation, integration, testing, repair, and polish,
- use sensible token/resource discipline,
- avoid asking the owner questions already answered by the specification,
- make reversible reasonable engineering decisions when details are non-creative,
- escalate only genuine owner decisions or hard external blockers,
- validate continuously,
- produce a playable/installable artifact,
- hand off to the owner only when the remaining meaningful validation genuinely requires human playtesting.

## 24. Version History
Every generated specification/prompt package should be versioned in the project so the user can see the evolution, e.g. Initial Build → Expansion → Playtest Repair → Release Polish.

Never silently overwrite the only copy of an earlier authoritative specification.

## 25. Existing Repository Safety
When operating on an existing game:
- inspect before prescribing,
- identify engine/version/build system and current working state,
- preserve rollback checkpoints,
- do not replace functioning systems simply because a greenfield implementation is easier,
- distinguish verified repository facts from assumptions,
- identify modernization requirements without casually destabilizing the current playable line.

## 26. Security and Privacy
- No hardcoded API tokens, GitHub tokens, passwords, signing keys, or secrets.
- Use Android-appropriate secure secret storage.
- Minimize permissions.
- Make network activity understandable.
- Keep project data local by default.
- Treat uploaded private project files appropriately.
- Validate imported files and repository content defensively.

## 27. Engineering Quality for Game Designer Itself
Game Designer must itself be a real, maintainable Android application, not a UI mock.

Claude must:
- select a current supported Android stack after verifying current compatibility,
- use a maintainable modular architecture,
- separate UI, domain/specification logic, persistence, LLM/provider interfaces, research, GitHub, asset management, and export generation,
- make the structured specification deterministic/testable where possible rather than relying entirely on free-form LLM output,
- create migrations/versioning for persisted project schema,
- handle offline/error/loading states,
- write automated tests for deterministic core logic and critical persistence/export behavior,
- lint/typecheck/static-check as appropriate,
- build real Android artifacts,
- test on available emulators/tooling and inspect UI where practical,
- fix discovered defects rather than merely reporting them,
- document architecture and setup.

## 28. Resource / “Slow Boat” Execution Mandate for Building This App
The owner runs many Claude projects concurrently and shares a finite five-hour allowance window. Build Game Designer autonomously, but do not consume resources recklessly.

Rules:
- Prefer sequential focused work over gratuitous swarms of parallel agents.
- Use subagents only when they provide clear leverage.
- Do not repeatedly research or audit the same settled question.
- Persist discoveries and decisions in repository docs.
- Commit durable, tested checkpoints.
- Use targeted tests during development and broader suites at meaningful gates.
- Avoid repeatedly rebuilding expensive artifacts when a cheaper check answers the question.
- Continue useful work without waiting for the owner when no owner decision is required.
- Do not interpret resource conservation as permission to lower quality or stop at an MVP.

The desired behavior is: **slow enough to coexist with other projects, autonomous enough not to require babysitting, ambitious enough to build the real product.**

## 29. Initial Implementation Strategy
Do not blindly follow a rigid phase list if repository reality suggests a better dependency order, but a sensible progression is:

1. Repository archaeology and current-state verification.
2. Architecture/stack decision with current-version research.
3. Android application shell and Hot Attic Games startup branding.
4. Versioned local project/state model.
5. Conversational Director UI and deterministic interview/completeness core.
6. Local LLM runtime/model investigation and integration behind an abstraction.
7. Internet research/source capture layer.
8. Optional cloud LLM provider abstraction/integration.
9. Dynamic design schema/rules and recommendation/conflict engine.
10. Asset research/provenance and branding uploads.
11. `CLAUDE.md` generator and export package.
12. Claude execution-prompt generator.
13. Optional GitHub integration and existing-repository inspection.
14. Playtest/continuation foundations.
15. Full validation, UX polish, performance, backup/export, and installable Android build.

Do not stop after each item for permission. Progress autonomously while keeping stable checkpoints.

## 30. Definition of Done for the First Serious Playtest Build of Game Designer
The owner should not receive Game Designer merely because it launches.

The first serious playtest candidate should, at minimum, allow the owner to:
- launch through the Hot Attic Games splash,
- create and reopen a local project,
- interact conversationally with Bob,
- describe a game in natural language/voice-to-text,
- establish experience/preferences and target platforms,
- receive useful recommendations and resolve conflicts,
- select or delegate major design decisions,
- capture reference games,
- track meaningful design completeness,
- manage the three core game-branding uploads,
- establish Claude-plan/resource preferences,
- generate and locally save/export a substantial target-game `CLAUDE.md`,
- generate and copy/export its accompanying Claude Code master prompt,
- preserve version history,
- survive app restart without losing the project,
- handle no-network/no-cloud-provider states gracefully,
- pass the available automated validation and produce an installable Android artifact.

If some advanced integration (for example full GitHub write automation or a high-quality local model) is blocked by a genuine technical constraint, do not fake it. Build the strongest functional path available, document the verified blocker, and preserve the architecture for completion. However, exhaust reasonable engineering alternatives before declaring a blocker.

## 31. Immediate Owner Intent
This repository is now the permanent home of Game Designer. Begin building from this specification when instructed by the accompanying master prompt. The Hot Attic Games logo already placed in the repository is authoritative for the app startup splash.
