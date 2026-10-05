package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.engine.AssetPlan
import com.hotattic.gamedesigner.core.engine.AuditEngine
import com.hotattic.gamedesigner.core.engine.AuditReport
import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.engine.ConsistencyReview
import com.hotattic.gamedesigner.core.engine.ScopeEngine
import com.hotattic.gamedesigner.core.model.AssetResolution
import com.hotattic.gamedesigner.core.model.BrandingMode
import com.hotattic.gamedesigner.core.model.BrandingSlot
import com.hotattic.gamedesigner.core.model.DecisionSource
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.FactStatus
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.FeedbackStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.ResearchKind
import com.hotattic.gamedesigner.core.schema.Dependencies
import com.hotattic.gamedesigner.core.schema.EngineCatalog
import com.hotattic.gamedesigner.core.schema.Field
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits

/**
 * Deterministically composes the authoritative target-game CLAUDE.md from structured project state. It never emits
 * TBD/TODO placeholders: every field is either resolved, derived from a rule, or listed as an explicit exclusion.
 */
object ClaudeMdGenerator {

    fun generate(project: Project, versionNumber: Int, versionLabel: String, nowIso: String, audit: AuditReport = AuditEngine.audit(project)): String {
        val t = Traits(project)
        val scope = ScopeEngine.recommend(project)
        val title = project.value(Keys.DISPLAY_NAME) ?: project.name
        val sb = StringBuilder()
        fun h1(s: String) = sb.append("# ").append(s).append("\n\n")
        fun h2(s: String) = sb.append("## ").append(s).append("\n\n")
        fun h3(s: String) = sb.append("### ").append(s).append("\n\n")
        fun p(s: String) = sb.append(s).append("\n\n")
        fun bullets(items: Collection<String>) { if (items.isNotEmpty()) { items.forEach { sb.append("- ").append(it).append('\n') }; sb.append('\n') } }
        // Only confirmed, still-relevant decisions are rendered; proposals and stale values never reach the spec.
        fun active(key: String) = project.decision(key)?.takeIf { it.status == DecisionStatus.CONFIRMED && it.value.isNotBlank() && (Fields.get(key)?.isRelevant(t) != false) }
        fun v(key: String) = active(key)?.value
        fun label(key: String, value: String?): String = value?.let { raw -> raw.split("|").joinToString(", ") { optionLabel(t, key, it) } } ?: "(not specified)"

        h1("$title - AUTHORITATIVE BUILD SPECIFICATION")
        p("Spec version $versionNumber ($versionLabel) - generated $nowIso by Game Designer. This file supersedes earlier versions; earlier versions are preserved in the Game Designer project history and must not be re-derived from memory.")

        // 0. Authority
        h2("0. Authority and how to use this file")
        p("This file is the authoritative specification for **$title**. Read it completely before making decisions. Preserve the owner's intent over convenience. " +
            "If repository reality conflicts with this document, investigate, preserve recoverable working state, and take the safest path that satisfies this specification. " +
            "Never silently reduce scope to a prototype, toy, mockup or gray-box. Where this file is explicit, follow it; where it is silent on a non-creative engineering detail, make the best reversible decision and record it in `docs/DECISIONS.md`.")
        p("The owner is Kevin. Creative and product decisions belong to the owner; engineering decisions belong to you.")
        if (project.mode != ProjectMode.NEW_GAME) {
            h3("Project mode")
            p(if (project.mode == ProjectMode.EXISTING_GAME) "EXISTING GAME: this specification continues an existing repository. Inspect it first; see section 17." else "PLAYTEST CONTINUATION: this specification updates an existing build after human playtesting; see section 17.")
        }

        // PART A / B: who said what.
        sb.append("# PART A - OWNER REQUIREMENTS (authoritative: implement exactly; nothing in Part B or C may contradict this)\n\n")
        h2("A1. The owner's concept, in their own words")
        val concept = project.originalConcept.ifBlank { project.value(Keys.CONCEPT).orEmpty() }
        sb.append(ConsistencyReview.VERBATIM_OPEN).append('\n')
        sb.append(concept.ifBlank { title }.lines().joinToString("\n") { "> $it" }).append('\n')
        sb.append(ConsistencyReview.VERBATIM_CLOSE).append("\n\n")
        val ownerFacts = project.activeFacts()
        val ownerDecisions = project.decisions.filter { (k, d) -> d.ownerAuthored && d.status == DecisionStatus.CONFIRMED && d.value.isNotBlank() && active(k) != null && k != Keys.CONCEPT }
        h2("A2. What the owner has specified")
        if (ownerFacts.isEmpty() && ownerDecisions.isEmpty()) p("The owner specified nothing beyond the concept above.")
        bullets(ownerFacts.map { it.text })
        bullets(ownerDecisions.map { (k, d) ->
            val corr = if (d.prov == Provenance.OWNER_CORRECTION) " [owner correction - supersedes anything earlier]" else ""
            "**${Fields.get(k)?.title ?: k}:** ${label(k, d.value)}$corr" + if (d.rawAnswer.isNotBlank() && d.rawAnswer.length < 160) " (owner said: \"${d.rawAnswer.trim()}\")" else ""
        })
        val retracted = project.facts.filter { it.status == FactStatus.RETRACTED }
        val rejectedLines = project.rejected.flatMap { (k, ids) -> ids.map { id -> k to id } }.map { (k, id) ->
            when (k) {
                Dependencies.TAG -> "Rejected by the owner: ${id.lowercase().replace('_', '-')} design"
                Keys.GENRE, "genre" -> "Rejected by the owner: genre ${GenreKnowledge.resolve(id).label}"
                else -> "Rejected by the owner: ${Fields.get(k)?.title ?: k} = ${optionLabel(t, k, id)}"
            }
        }.distinct()
        if (retracted.isNotEmpty() || rejectedLines.isNotEmpty()) {
            h2("A3. Withdrawn or rejected by the owner (do NOT implement)")
            bullets(retracted.map { "WITHDRAWN (do not implement): ${it.text}" } + rejectedLines)
        }
        val overrides = project.decisions.filter { it.value.source == DecisionSource.OVERRIDE }
        if (overrides.isNotEmpty()) {
            h2("A4. Informed overrides")
            p("The owner was informed of the tradeoffs and knowingly chose the following. Implement them as stated; do not reopen them.")
            bullets(overrides.map { (k, d) -> "**${Fields.get(k)?.title ?: k}** = ${label(k, d.value)} (Director recommended: ${d.overrides ?: "an alternative"})." })
        }
        val acknowledged = ConflictEngine.acknowledged(project)
        if (acknowledged.isNotEmpty()) {
            p("Acknowledged risks (owner proceeded after being warned):")
            bullets(acknowledged.map { "${it.title}: ${it.message}" })
        }

        sb.append("# PART B - ACCEPTED RECOMMENDATIONS (Game Designer's suggestions the owner accepted or delegated; keep unless there is a strong engineering reason)\n\n")
        val accepted = project.decisions.filter { (k, d) -> !d.ownerAuthored && d.status == DecisionStatus.CONFIRMED && d.value.isNotBlank() && active(k) != null && k != Keys.CONCEPT }
        if (accepted.isEmpty()) p("None.")
        else bullets(accepted.map { (k, d) -> "**${Fields.get(k)?.title ?: k}:** ${label(k, d.value)}" + (d.note.takeIf { it.isNotBlank() }?.let { " - $it" } ?: "") })

        sb.append("# PART C - IMPLEMENTATION GUIDANCE (how to build it; derived from Parts A and B plus engineering practice)\n\n")
        p("Where anything in this part appears to conflict with Part A, Part A wins.")

        // 2. Vision
        h2("2. Vision and non-negotiables")
        p("The owner's concept is quoted verbatim in section A1.")
        v(Keys.CORE_FANTASY)?.let { p("**Core fantasy:** $it") }
        v(Keys.PLAYER_FEELING)?.let { p("**Intended player feeling:** $it") }
        if (t.genresKnown) p("**Genre:** ${t.genres.joinToString(" + ") { it.label }}. **Dimension:** ${v(Keys.DIMENSION) ?: "n/a"}. **Perspective:** ${label(Keys.PERSPECTIVE, v(Keys.PERSPECTIVE))}.")
        if (project.references.isNotEmpty()) {
            h3("Reference games")
            p("References inform design characteristics only. Never copy characters, art, maps, music, writing, names or any proprietary asset from them.")
            v(Keys.REFERENCE_ASPECTS)?.let { p("What to take from them: $it") }
            bullets(project.references.map { r ->
                buildString {
                    append("**${r.name}**")
                    if (r.aspects.isNotEmpty()) append(" - take: ${r.aspects.joinToString("; ")}")
                    if (r.summary.isNotBlank()) append(". Research summary: ${r.summary}")
                    if (r.sources.isNotEmpty()) append(" (sources: ${r.sources.joinToString(", ") { it.url }})")
                }
            })
        }
        h3("Non-negotiables")
        bullets(listOf(
            "The first build is a genuinely playable, complete game - the full intended core loop with real visuals, input, audio (if specified), menus, settings, saves, win/failure/progression - not a prototype.",
            "No unfinished-work markers, placeholder gameplay or stubbed systems ship in the first build. Anything deferred is listed in section 16 and nowhere else.",
            "Everything in this file is implemented, validated by automation, repaired and polished before asking the owner to playtest.",
            "No secrets (API keys, tokens, signing keys, passwords) are ever committed.",
            "All external assets satisfy the license policy in section 8 and are logged with provenance.",
        ))

        // 3. Player experience
        h2("3. Player experience")
        bullets(listOfNotNull(
            v(Keys.CORE_LOOP)?.let { "**Core loop:** $it" },
            v(Keys.SESSION_STRUCTURE)?.let { "**Session structure:** ${label(Keys.SESSION_STRUCTURE, it)}" },
            v(Keys.WORLD_STRUCTURE)?.let { "**World/level structure:** ${label(Keys.WORLD_STRUCTURE, it)}" },
            v(Keys.WIN_LOSS)?.let { "**Win/loss:** $it" },
            v(Keys.DIFFICULTY_FAILURE)?.let { "**Difficulty and failure:** ${label(Keys.DIFFICULTY_FAILURE, it)}" },
            v(Keys.TUTORIAL)?.let { "**Tutorial/onboarding:** ${label(Keys.TUTORIAL, it)}" },
            v(Keys.STORY)?.let { "**Story:** ${label(Keys.STORY, it)}" },
            v(Keys.MOVEMENT_CAMERA)?.let { "**Movement and camera feel:** $it" },
        ))

        // 4. Systems
        h2("4. Gameplay and system requirements")
        p("Every system the owner specified or accepted below is required in the first build and must be reachable and exercised during normal play. Genre checklist items apply wherever they fit this game's actual design; if one does not fit, adapt or omit it and note why in `docs/DECISIONS.md` (Part A always wins).")
        val specifics = listOfNotNull(
            v(Keys.COMBAT_MODEL)?.let { "**Combat:** ${label(Keys.COMBAT_MODEL, it)}" },
            v(Keys.ENEMIES_BOSSES)?.let { "**Enemies and bosses:** $it" },
            v(Keys.CHARACTERS)?.let { "**Characters:** ${label(Keys.CHARACTERS, it)}" },
            v(Keys.PROGRESSION)?.let { "**Progression:** ${label(Keys.PROGRESSION, it)}" },
            v(Keys.ECONOMY)?.let { "**Economy:** $it" },
            v(Keys.SURVIVAL_CRAFTING)?.let { "**Survival and crafting:** $it" },
            v(Keys.AUTOMATION_SIM)?.let { "**Simulation/building:** $it" },
        )
        if (specifics.isNotEmpty()) { h3("Specified design (who decided each item is in Parts A and B)"); bullets(specifics) }
        val systems = t.genres.flatMap { g -> g.systems.map { g to it } }.distinctBy { it.second.id }
        if (systems.isNotEmpty()) {
            h3("Required systems (genre completeness checklist - adapt each item to this game's real design)")
            bullets(systems.map { (g, s) -> "**${s.name}** (${g.label.substringBefore(" (").substringBefore(" /")}): ${s.detail}" })
        }
        h3("Cross-cutting systems that must exist")
        bullets(listOfNotNull(
            "Pause behavior: the game can be paused/backgrounded at any time without loss or exploit; resuming restores exact state.",
            "Death/failure/restart: defined flow from failure to retry or menu with no dead ends or soft-locks.",
            "Save/load: ${label(Keys.SAVE_SYSTEM, v(Keys.SAVE_SYSTEM))}. See section 11.",
            "Main loop must never reach an unwinnable or stuck state; automated tests assert this where feasible.",
            if (t.has(Tag.CRAFTING) || t.hasGenre("rpg")) "Inventory: stacking, capacity, drop/pickup, full-inventory handling, sorting/filtering; no item can be lost silently." else null,
            if (t.has(Tag.CRAFTING) || t.has(Tag.ECONOMY)) "Resource regeneration/respawn and sinks are defined in data and balanced by headless simulation." else null,
        ))

        // 5. Scope
        h2("5. Content scope (recommended sizing)")
        p("Scope tier: **${scope.effectiveTier.label}** (recommended: ${scope.recommendedTier.label}). ${scope.rationale.joinToString(" ")}")
        p("These counts are Game Designer's UPPER-BOUND EFFORT HEURISTICS, not owner requirements, unless the owner stated numbers in Part A. Do not pad content to reach them. Map each unit onto this game's real structure (for example depth zones instead of levels in a descent game) and size content so the intended loop is complete and replayable. Author content as data (tables/resources), validated by automated checks; no content slot may be an empty stub.")
        bullets(scope.targets.map { "${it.label}: **${it.count}**" })

        // 6. Controls
        h2("6. Controls, camera and orientation")
        bullets(listOfNotNull(
            "Input methods: ${v(Keys.INPUT_METHODS)?.split("|")?.joinToString { label(Keys.INPUT_METHODS, it) } ?: "per platform defaults"}",
            v(Keys.TOUCH_SCHEME)?.let { "Touch scheme: ${label(Keys.TOUCH_SCHEME, it)}. Touch targets at least 48dp; controls must not obscure critical play; layout adapts to both thumbs and to phone/tablet." },
            v(Keys.INPUT_REMAP)?.let { "Remapping: ${label(Keys.INPUT_REMAP, it)}" },
            v(Keys.ORIENTATION)?.let { "Orientation: ${label(Keys.ORIENTATION, it)}; layouts must handle notches, cutouts, safe areas and aspect ratios from 16:9 to 21:9 (and tablets)." },
            "Camera and movement feel follow section 3 and must be tunable from a single data file.",
        ))

        // 7. Art / audio / UI
        h2("7. Art, audio and interface direction")
        bullets(listOfNotNull(
            v(Keys.ART_DIRECTION)?.let { "Art direction: ${label(Keys.ART_DIRECTION, it)}" },
            v(Keys.COLOR_MOOD)?.let { "Color and mood: $it" },
            v(Keys.VFX)?.let { "Effects/game feel: ${label(Keys.VFX, it)} (all screen shake/flash effects respect the reduced-motion setting)" },
            v(Keys.AUDIO)?.let { "Audio: ${label(Keys.AUDIO, it)}" },
            v(Keys.HUD_UI)?.let { "HUD/UI: $it" },
            v(Keys.MENUS_SETTINGS)?.let { "Menus and settings: ${it.split("|").joinToString { m -> label(Keys.MENUS_SETTINGS, m) }}" },
        ))

        // 8. Assets
        h2("8. Assets, provenance and branding")
        val policy = v(Keys.ASSET_POLICY) ?: "cc0_default"
        p("License policy: **${label(Keys.ASSET_POLICY, policy)}**. 'Free to download' is not a license. For every external asset: verify the license on the asset's own page, save a copy of the license text under `assets/licenses/`, and add an entry to `ASSETS.md` with file, source URL, creator, license, and download date. The in-game credits screen lists all of them. Do not scrape or redistribute assets against a site's terms.")
        p("If no coherent, appropriately licensed set exists for a need, build the original procedural replacement described below. Never leave an asset need unresolved or as a placeholder.")
        val needs = AssetPlan.needs(t)
        val recs = project.assets.associateBy { it.needId }
        for (n in needs) {
            val r = recs[n.id]
            h3(n.label)
            p(n.detail)
            if (r == null) p("Resolution: original procedural construction. ${AssetPlan.proceduralFallback(n)}")
            else bullets(listOfNotNull(
                "Resolution: ${resolutionText(r.resolution)}",
                r.source.takeIf { it.isNotBlank() }?.let { "Candidate sources: $it" },
                r.license.takeIf { it.isNotBlank() }?.let { "License: $it" },
                r.creator.takeIf { it.isNotBlank() }?.let { "Creator: $it" },
                r.url.takeIf { it.isNotBlank() }?.let { "URL: $it" },
                r.notes.takeIf { it.isNotBlank() }?.let { "Instructions: $it" },
            ))
        }
        h3("Game branding and identity")
        for (slot in BrandingSlot.CORE) {
            val b = project.branding[slot]
            val key = Keys.brandingKeyForSlot.getValue(slot)
            val nice = when (slot) { BrandingSlot.ICON -> "Game/app icon"; BrandingSlot.STUDIO_SPLASH -> "Studio/developer splash"; else -> "Game splash / title screen" }
            val choice = v(key)
            val line = when {
                b?.mode == BrandingMode.UPLOADED -> "uploaded master at `branding/master/${b.localFile?.substringAfterLast('/')}` (${b.originalName}, ${b.width}x${b.height}, sha256 ${b.sha256.take(12)}...). Never modify or overwrite the master; derive every size from it."
                choice == "generate_original" -> "create an original asset in keeping with the art direction and the game's identity."
                choice == "generic_temporary" -> "use a clean generic temporary asset; mark it as replaceable branding (not a gameplay placeholder) in `ASSETS.md`."
                choice == "skip" -> "intentionally omitted by the owner; do not create one."
                else -> "create an original asset."
            }
            sb.append("- **$nice:** $line\n")
        }
        sb.append('\n')
        p("Derive all required platform sizes, formats, adaptive-icon foreground/background and safe-zone variants automatically from the master artwork (for Android: mipmap densities, adaptive icon layers with the monochrome layer, Play Store 512px icon; splash via the platform splash API plus an in-game splash screen). Keep the master file untouched.")

        // 9. Architecture / toolchain
        h2("9. Architecture, toolchain and platforms")
        val e = t.engine
        if (e != null) {
            bullets(listOf(
                "Engine/framework: **${e.name}** (${e.language}). License: ${e.licenseNote}.",
                "Why: ${e.strengths}",
                "Known caveats to design around: ${e.caveats}",
                "Headless validation: ${e.headlessValidation}",
                "Build artifacts: ${e.buildArtifactNotes}",
            ))
        }
        val pins = project.research.filter { it.kind == ResearchKind.TOOLCHAIN_VERSION }
        if (pins.isNotEmpty()) {
            p("Verified toolchain facts (captured with sources by Game Designer; re-verify only if a build fails because of them):")
            bullets(pins.map { n -> "${n.topic}: ${n.summary} (${n.sources.joinToString { it.url }})" })
        } else {
            p("Toolchain versions have not been captured from live sources. Before writing code, verify the current stable versions of the engine, language runtime, build tools and every dependency you select (${e?.baselineVersionNote ?: "verify current stable"}), pin them exactly in the repository (lockfiles / version catalogs / export presets) and record the reasons and sources in `docs/DECISIONS.md`.")
        }
        bullets(listOfNotNull(
            "Target platforms: ${t.platforms.joinToString { Platforms.labels[it] ?: it }.ifEmpty { "not specified" }}",
            v(Keys.PERFORMANCE)?.let { "Performance target: ${label(Keys.PERFORMANCE, it)}" },
            v(Keys.MIN_HARDWARE)?.let { "Minimum hardware: $it" },
            v(Keys.NETWORK_POLICY)?.let { "Network policy: ${label(Keys.NETWORK_POLICY, it)}. ${if (it == "fully_offline") "The build must request no INTERNET permission and make no network calls." else "Network features degrade gracefully when offline."}" },
            v(Keys.TOOLCHAIN_PREFS)?.let { "Owner toolchain preferences: $it" },
        ))
        h3("Repository layout and rules")
        bullets(listOf(
            "Keep game logic separate from rendering/engine glue so rules, simulation and data validation are unit-testable headlessly.",
            "All balance/content numbers live in data files, never scattered in code.",
            "Docs: `docs/DECISIONS.md` (engineering decisions), `docs/ARCHITECTURE.md`, `HANDOFF.md` (current state for the next context), `ASSETS.md` (provenance).",
            "Commit at every stable, tested checkpoint with a clear message; never leave `main` unbuildable. Never force-push or rewrite history on shared branches.",
        ))
        h3("Build pipeline")
        p(pipelineText(t, v(Keys.CI_BUILD)))

        // 10. Performance
        h2("10. Performance requirements")
        bullets(listOfNotNull(
            "Hold the target frame rate (${label(Keys.PERFORMANCE, v(Keys.PERFORMANCE))}) on the minimum hardware in normal play, with graceful quality degradation instead of stutter.",
            if (t.hasGenre("survivors_like") || t.has(Tag.COMBAT)) "Set and enforce a documented maximum on-screen entity count; use object pooling and avoid per-frame allocations." else null,
            if (t.has(Tag.SIMULATION) || t.has(Tag.BUILDING)) "Simulation runs at a fixed timestep with a documented per-tick budget and a stress test at the maximum supported scale." else null,
            "Cold start under 4 seconds on minimum hardware to the main menu; no ANRs; memory stays within a documented ceiling; battery-friendly background behavior.",
        ))

        // 11. Persistence
        h2("11. Persistence and save requirements")
        bullets(listOfNotNull(
            "Save model: ${label(Keys.SAVE_SYSTEM, v(Keys.SAVE_SYSTEM))}.",
            "Saves are versioned with explicit migrations and tested with fixture saves from every previous version.",
            "Writes are atomic (write temp then rename); corrupt saves are detected, backed up and recovered from without crashing.",
            "The app can be killed at any moment on a phone; autosave on pause/background and at safe points.",
            "Settings persist separately from game saves. A 'reset data' option exists if listed in the menus.",
        ))

        // 12. Accessibility
        h2("12. Accessibility")
        val acc = project.list(Keys.ACCESSIBILITY)
        if (acc.isEmpty()) p("No accessibility features beyond platform defaults were specified.") else {
            p("Implement and test each of the following; all must be reachable from the settings screen and apply immediately:")
            bullets(acc.map { accessibilityText(it) })
        }

        // 13. Testing / validation
        h2("13. Testing and validation")
        v(Keys.TESTING)?.let { p(it) }
        h3("Validation ladder (run in this order; repair failures before proceeding)")
        bullets(listOf(
            "Static checks: lint/format/type checks for the chosen toolchain.",
            "Unit tests for all rules, simulations, data validation (content tables complete, references resolve, no negative or infinite values).",
            "Persistence tests: save/load round trip, corrupt save recovery, migration from fixtures.",
            "Headless smoke playtest(s): " + (t.genres.flatMap { it.smokeChecks }.distinct().joinToString(" ").ifBlank { "scripted playthrough of the core loop completes without crash or soft-lock." }),
            "Build the real artifact for every target platform in CI and confirm it installs/launches (emulator or headless where available). Report only what you actually ran.",
            "UI inspection: capture screenshots of every screen at phone and tablet aspect ratios and review them for clipping, overlap and unreadable text.",
        ))
        h3("Definition of done for the first build")
        p(v(Keys.DONE) ?: "The complete core loop is playable start to finish; all systems in this file are implemented; automated validation is green; installable artifact produced.")

        // 14. Release
        h2("14. Packaging, identity and release")
        bullets(listOfNotNull(
            v(Keys.DISPLAY_NAME)?.let { "Display name: **$it**" },
            v(Keys.PACKAGE_ID)?.let { "Application/package ID: `$it` (never change after first release)" },
            v(Keys.VERSION_STRATEGY)?.let { "Versioning: ${label(Keys.VERSION_STRATEGY, it)}; the CI sets the build code from the run number." },
            v(Keys.STORE_PLAN)?.let { "Distribution: ${label(Keys.STORE_PLAN, it)}" },
            v(Keys.MONETIZATION)?.let { "Monetization: ${label(Keys.MONETIZATION, it)}" },
            v(Keys.PRIVACY)?.let { "Privacy: ${label(Keys.PRIVACY, it)}" },
            v(Keys.SIGNING)?.let { "Signing: ${label(Keys.SIGNING, it)}. Keys and keystores are never in the repository; debug-signed builds are acceptable for owner playtests." },
            "Request only the permissions the game actually needs and document each in `docs/DECISIONS.md`.",
        ))

        // 15. Execution & resources
        h2("15. Execution plan and resource rules")
        p("Resource estimate: **${scope.resources.level.label}**. ${scope.resources.explanation}")
        p("Claude environment expectation: ${project.prefs.claudePlan.label}; usage style: ${project.prefs.usageStyle.label}.")
        h3("Phases")
        bullets(scope.phases)
        h3("Resource discipline")
        bullets(scope.resources.strategy + listOf(
            "Do not research settled questions repeatedly; persist findings in `docs/DECISIONS.md`.",
            "Do not sacrifice product quality merely to save tokens; do shrink process overhead instead.",
            "Do not ask the owner anything already answered in this file. Escalate only genuine creative decisions not covered here, credentials/permissions only the owner can supply, or verified blockers with no engineering alternative.",
        ))

        // 16. Exclusions
        h2("16. Explicit exclusions and deferred features")
        val ex = v(Keys.EXCLUSIONS)
        if (ex != null) p(ex) else p("No exclusions were declared beyond: online multiplayer, accounts and cloud services (not part of this build).")

        // 17. Continuation
        if (project.mode != ProjectMode.NEW_GAME) {
            h2("17. Repository reality and continuation plan")
            val r = project.repo
            val ins = r?.inspection
            if (r != null) p("Repository: ${r.owner}/${r.repo}${if (r.branch.isNotBlank()) " (branch ${r.branch})" else ""}.")
            if (ins != null) {
                p("Inspected ${ins.inspectedAt}: engine **${ins.detectedEngine.ifBlank { "unknown" }}** ${ins.detectedEngineVersion}, build system **${ins.buildSystem.ifBlank { "unknown" }}**, head ${ins.headSha.take(10)}.")
                if (ins.verifiedFacts.isNotEmpty()) { p("Verified facts (observed in the repository):"); bullets(ins.verifiedFacts) }
                if (ins.assumptions.isNotEmpty()) { p("Assumptions (unverified - confirm by inspection before relying on them):"); bullets(ins.assumptions) }
            } else p("No repository inspection is attached. Inspect the repository yourself first and distinguish verified facts from assumptions.")
            bullets(listOfNotNull(
                v(Keys.CONTINUATION_GOAL)?.let { "Goal: ${label(Keys.CONTINUATION_GOAL, it)}" },
                v(Keys.PRESERVE_SYSTEMS)?.let { "Must preserve: $it" },
                v(Keys.KNOWN_ISSUES)?.let { "Known problems: $it" },
                "Create a rollback tag/branch before changing anything. Do not replace working systems merely because a rewrite is easier. Modernize without destabilizing the current playable line.",
            ))
            val open = project.feedback.filter { it.status == FeedbackStatus.OPEN }
            if (open.isNotEmpty()) {
                h3("Open playtest feedback (against spec v${open.maxOf { it.againstVersion }})")
                p("Reproduce each item first (write a failing test or scripted scenario where feasible), fix the root cause, then verify.")
                bullets(open.map { fb ->
                    "[${fb.severity.name}] ${fb.text}" + if (fb.attachments.isNotEmpty()) " (attachments: ${fb.attachments.joinToString { it.name }})" else ""
                })
            }
        }

        // PART D
        sb.append("# PART D - UNRESOLVED QUESTIONS\n\n")
        val unresolved = Fields.all.filter { f -> f.isRelevant(t) && f.required && f.key != Keys.CONCEPT && project.decision(f.key)?.let { it.status == DecisionStatus.CONFIRMED && it.value.isNotBlank() } != true }
            .map { f -> if (project.decision(f.key)?.status == DecisionStatus.PROPOSED) "${f.title}: inferred but not confirmed by the owner (${label(f.key, project.value(f.key))}); treat as a recommendation and make the least surprising reversible choice." else "${f.title}: not yet decided (${f.prompt})" }
        val deferred = project.decisions.filter { it.value.status == DecisionStatus.DEFERRED }.keys.map { "${Fields.get(it)?.title ?: it}: deliberately deferred by the owner; do not build it." }
        if (unresolved.isEmpty() && deferred.isEmpty()) p("None. Every required decision is resolved.") else bullets(unresolved + deferred)

        // 18. Human-only
        h2(if (project.mode == ProjectMode.NEW_GAME) "17. Human-only steps" else "18. Human-only steps")
        p("Only these steps genuinely need the owner. Do everything else autonomously.")
        bullets(audit.humanOnlyTasks)

        if (audit.findings.any { it.level != com.hotattic.gamedesigner.core.engine.AuditLevel.INFO }) {
            h2("Audit notes at generation time")
            p("The Game Designer audit reported the following when this version was generated. Treat blocking items as owner-visible risks and resolve or work around them.")
            bullets(audit.findings.filter { it.level != com.hotattic.gamedesigner.core.engine.AuditLevel.INFO }.map { "[${it.level.name}] ${it.message}" })
        }
        return sb.toString().trimEnd() + "\n"
    }

    private fun resolutionText(r: AssetResolution) = when (r) {
        AssetResolution.EXTERNAL_CC0 -> "use a CC0/public-domain set from external sources"
        AssetResolution.EXTERNAL_OTHER_LICENSE -> "external asset under a non-CC0 license (attribution rules apply)"
        AssetResolution.PROCEDURAL -> "original, generated procedurally by project code"
        AssetResolution.GENERATED_ORIGINAL -> "original, generated"
        AssetResolution.USER_SUPPLIED -> "supplied by the owner"
        AssetResolution.DEFERRED_BY_OWNER -> "deferred by the owner"
    }

    private fun optionLabel(t: Traits, key: String, value: String): String {
        val f: Field = Fields.get(key) ?: return value
        if (f.kind == com.hotattic.gamedesigner.core.schema.FieldKind.TEXT) return value
        return f.options(t).firstOrNull { it.id == value }?.label ?: when (key) {
            Keys.GENRE -> GenreKnowledge.resolve(value).label
            Keys.PLATFORMS -> Platforms.labels[value] ?: value
            else -> value
        }
    }

    private fun accessibilityText(id: String): String = when (id) {
        "text_scaling" -> "Text size: at least 3 steps (100/130/160%) applied to all text, with layouts that reflow without clipping."
        "colorblind" -> "Colorblind safety: never convey information by color alone; provide shape/pattern/icon redundancy and a palette option verified against deuteranopia/protanopia/tritanopia simulations."
        "reduced_motion" -> "Reduced motion: disables screen shake, flashes, heavy parallax and camera sway."
        "subtitles" -> "Subtitles/captions for all speech and meaningful audio cues, with size and background options."
        "haptics_control" -> "Vibration/haptics: global on/off and intensity control."
        "large_touch_targets" -> "Large touch targets: minimum 48dp, 56dp for primary actions, adequate spacing."
        "difficulty_assists" -> "Difficulty assists: adjustable damage, speed, aim assist and similar, changeable at any time without penalty."
        "remappable_controls" -> "Fully remappable keyboard/gamepad controls with conflict detection and reset to defaults."
        else -> id
    }

    private fun pipelineText(t: Traits, ci: String?): String {
        val e = t.engine
        val base = when (ci) {
            "github_actions" -> "Use GitHub Actions to build the installable artifact on every push to the working branch and on tags, and upload it as a downloadable workflow artifact. Claude writes and maintains the workflow, reads the run results, and repairs failures until green. "
            "claude_environment" -> "Build in the Claude workspace. First check whether the full toolchain can actually be installed there (network allowlists can block SDK hosts); if not, fall back to GitHub Actions. "
            "local_machine" -> "The owner builds locally; provide an exact, tested `docs/BUILD.md` and a single-command build script. "
            else -> "Provide a reproducible command-line build that produces the installable artifact. "
        }
        val engineSpecific = when (e?.id) {
            "godot" -> "Godot: pin the engine version, check export templates in, commit export presets, and run headless imports/tests with the pinned binary; Android needs JDK, Android SDK and a debug keystore for installable test builds."
            "unity" -> "Unity: use the pinned editor in batchmode via GameCI; activation uses a license secret in CI."
            "libgdx", "android_native" -> "Gradle: wrapper pinned, Android Gradle Plugin and compile/target SDK pinned to a verified compatible set; `assembleDebug` for owner playtests and `bundleRelease` for stores."
            "web_phaser", "web_three" -> "Web: Vite production build deployed as static files; wrap with Capacitor for Android/iOS packages if those platforms are targeted."
            "flutter_flame" -> "Flutter: pin the stable channel version; `flutter build apk --debug/--release`."
            "bevy" -> "Rust: pin toolchain via rust-toolchain.toml; commit Cargo.lock; cross-compile per platform in CI."
            else -> "Pin all tool versions in the repository."
        }
        val ios = if (Platforms.IOS in t.platforms) " iOS builds run on a macOS runner and require the owner's Apple signing assets (human step)." else ""
        return base + engineSpecific + ios
    }
}
