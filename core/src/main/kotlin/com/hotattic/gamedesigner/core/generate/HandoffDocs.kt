package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.engine.BuildObjective
import com.hotattic.gamedesigner.core.engine.DesignModel
import com.hotattic.gamedesigner.core.engine.Exclusions
import com.hotattic.gamedesigner.core.engine.NetworkPlan
import com.hotattic.gamedesigner.core.engine.PlatformCompat
import com.hotattic.gamedesigner.core.engine.PlatformPolicy
import com.hotattic.gamedesigner.core.engine.ProjectObjective
import com.hotattic.gamedesigner.core.engine.StackAdvisor
import com.hotattic.gamedesigner.core.engine.SystemId
import com.hotattic.gamedesigner.core.engine.SystemState
import com.hotattic.gamedesigner.core.engine.UpdateStrategy
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Traits

/**
 * One policy, stated once, rendered everywhere. CLAUDE.md, the master prompt and the handoff documents all read these rules from here, so they cannot
 * disagree: milestones, failure escalation, who may authorize what, how progress is reported, and how protected gameplay is guarded.
 */
object HandoffPolicy {

    class Milestone(val id: String, val title: String, val deliverable: String, val evidence: String)

    fun milestones(p: Project): List<Milestone> {
        val t = Traits(p)
        val primary = t.primaryPlatform?.let { Platforms.labels[it] ?: it } ?: "the target platform"
        val all = listOf(
            Milestone("M1", "Foundation and first playable build", "An installable build on $primary (the primary platform) that the owner can try, where the core verb works with the real renderer, real camera and real input. It may be incomplete but must say honestly what works.", "Screenshots from the real renderer; a build the owner can install; a plain-English list of what works and what does not."),
            Milestone("M2", "Complete core gameplay loop", "The core loop plays from start to finish, including failure and restart, with real controls and feedback.", "A recorded playthrough of the loop on the real build; automated checks for loop state."),
            Milestone("M3", "Required mechanics and systems", "Every mechanic and system in Part A of CLAUDE.md is implemented and wired into the loop. Nothing is only a button or a menu entry.", "Each requirement in the ledger moves to Complete and verified or is explained."),
            Milestone("M4", "Complete content and end-to-end playthrough", "All content the approved scope and game length call for exists, and the whole game can be played from the first moment to the completion condition.", "A full playthrough on the primary platform; automated route and completion checks where content is generated."),
            Milestone("M5", "Polish, performance and regression testing", "Visuals, audio and feel are polished to the approved identity; performance targets are met; protected gameplay is guarded by regression checks.", "Measured performance on representative hardware; regression results; screenshots checked against GAMEPLAY_INVARIANTS."),
            Milestone("M6", "Release readiness", "Signed, versioned builds for every selected platform, update pipeline verified if requested, documentation complete, and a handoff report. Nothing is published without the owner's authorization.", "Verified artifacts with exact source SHA and build number; the owner-authorization checklist completed."),
        )
        return if (ProjectObjective.of(p) == BuildObjective.PROTOTYPE) all.take(3) + Milestone("P", "Prototype acceptance and handoff", "The owner plays the prototype and either accepts it or gives direction. Milestones M4-M6 are production work the owner directs after acceptance.", "The owner's decision, recorded in docs/HANDOFF.md.")
        else all
    }

    val failureEscalation = listOf(
        "Keep a failure history in `docs/FAILURE_LOG.md`: the defect, what was tried, the expected and actual result, and why the check did or did not catch it.",
        "After TWO failed attempts at the same defect: stop repeating the same fix. Review what earlier attempts changed, compare expected with actual results, work out why the earlier checks did not detect the problem, find the root cause, and choose a different diagnostic strategy.",
        "After THREE failed attempts: write an escalation report (what was tried, what the evidence shows, the leading hypotheses, the options) and stop for the owner before another expensive repair cycle.",
        "Prefer small, measurable fixes. Do not rerun an identical test or build without a reason, and avoid unnecessary full rebuilds and endless rendering loops.",
        "Track repeated build failures and wasted builds in the failure log. Do not weaken, skip or delete a test to make it pass; repair the cause.",
        "Ship playable builds over impressive but unverified reports.",
    )

    fun authorization(p: Project): List<String> = authorizationAll.filter { "Over-the-air" !in it || UpdateStrategy.wanted(p) }

    /** Compact forms for the master prompt, which points at docs/ for the full policy. */
    fun milestoneLine(p: Project) = milestones(p).joinToString("; ") { "${it.id} ${it.title}" }
    val escalationShort = "After 2 failed attempts at the same defect, stop repeating the fix: review earlier changes, compare expected with actual, find why the checks missed it, change diagnostic strategy. After 3, write an escalation report and stop for the owner. Keep `docs/FAILURE_LOG.md`; never weaken a test; avoid reruns without a reason."
    fun authorizationShort(p: Project) = "Local builds are fine. Test releases, " + (if (UpdateStrategy.wanted(p)) "over-the-air publication, " else "") + "store publication, merging to main and any change to signing identity or package IDs each need the owner's explicit authorization, separately; one never implies another. Nothing is published automatically."
    const val progressShort = "Report in plain language: what works, what changed, what remains, what needs approval, what can be tested now, whether the build is playable. Name the evidence class (static inspection, unit test, simulation, production rendering, desktop runtime, physical device). No invented percentages or dates."

    private val authorizationAll = listOf(
        "Local build: allowed without asking.",
        "Build-system (CI) artifact: allowed when the build system is part of the approved plan; keep builds to what the work needs.",
        "GitHub test release (pre-release with files attached): only when the owner chose that delivery method; it is publicly downloadable if the repository is public.",
        "Over-the-air update publication: only on the owner's explicit authorization, separate from ordinary builds and from store releases.",
        "Google Play, Steam and Apple App Store publication: only on the owner's explicit authorization, and each is separate.",
        "Merging into the main branch, deleting history, or changing signing identity or package identifiers: only on explicit authorization. Preserve the signing identity and package identifiers.",
        "Never publish automatically, and never treat one kind of release as permission for another.",
    )

    val progressReporting = listOf(
        "Write reports a non-programmer understands: what works now, what changed, what remains, what needs the owner's approval, what can be tested now, and whether the current build is playable. Example: \"Your character can now run, jump and climb. The Android test build is ready.\"",
        "Keep technical detail (commits, checks, logs) in an optional section for those who want it.",
        "Never invent completion percentages or delivery dates. Never call a feature complete without evidence of the kind the ledger names.",
        "State the evidence class for every claim: static inspection, unit test, simulation, production rendering, desktop runtime, or physical device. Do not claim physical-device verification without a physical device.",
    )

    fun invariantRules(p: Project) = listOf(
        "Once the owner approves a playable baseline, the characteristics recorded in `docs/GAMEPLAY_INVARIANTS.md` are protected.",
        "Repairs are narrowly scoped: if one animation is wrong, fix that animation. Do not change jumping, climbing, physics, the camera or character geometry without a demonstrated need and the owner's approval.",
        "Every protected system has a regression check; run it before and after any change that could touch it, and do not alter protected timing or input response for performance reasons.",
    )

    fun buildNames(p: Project): List<String> {
        val title = p.value(Keys.DISPLAY_NAME) ?: p.name.ifBlank { "Game Title" }
        return Traits(p).platforms.mapNotNull { pl ->
            when (pl) {
                Platforms.ANDROID -> "$title Build NN APK"
                Platforms.WINDOWS -> "$title Build NN Windows"
                Platforms.LINUX, Platforms.STEAM_DECK -> "$title Build NN Linux"
                Platforms.MAC -> "$title Build NN macOS"
                Platforms.IOS -> "$title Build NN iOS (needs a Mac and Apple signing)"
                Platforms.WEB -> "$title Build NN Web"
                else -> null
            }
        }.distinct()
    }
}

/** The handoff package beyond CLAUDE.md and the master prompt: plain files a coding agent (and the owner) can read, all derived from the same project state. */
object HandoffDocs {

    fun files(p0: Project): LinkedHashMap<String, String> {
        val p = com.hotattic.gamedesigner.core.engine.DerivedDefaults.apply(p0)
        val out = linkedMapOf<String, String>()
        out["docs/GAME_DESIGN.md"] = gdd(p)
        out["docs/REQUIREMENTS_LEDGER.md"] = ledger(p)
        out["docs/EXCLUSIONS.md"] = exclusions(p)
        out["docs/ARCHITECTURE_DECISIONS.md"] = adr(p)
        out["docs/TECH_STACK.md"] = techStack(p)
        out["docs/ASSET_PLAN.md"] = assetPlan(p)
        out["docs/BUILD_AND_RELEASE.md"] = buildAndRelease(p)
        out["docs/TEST_PLAN.md"] = testPlan(p)
        out["docs/GAMEPLAY_INVARIANTS.md"] = invariants(p)
        out["docs/MILESTONES.md"] = milestones(p)
        out["docs/COMPLETION_CRITERIA.md"] = completion(p)
        out["docs/OWNER_AUTHORIZATION.md"] = "# Owner authorization policy\n\nThe owner stays in control of creative decisions and of every publication.\n\n" + HandoffPolicy.authorization(p).joinToString("\n") { "- $it" } + "\n"
        out["docs/FAILURE_ESCALATION.md"] = "# Failure escalation and AI budget control\n\n" + HandoffPolicy.failureEscalation.joinToString("\n") { "- $it" } + "\n"
        out["docs/PROGRESS_REPORTING.md"] = "# Progress reporting\n\n" + HandoffPolicy.progressReporting.joinToString("\n") { "- $it" } + "\n"
        return out
    }

    private fun title(p: Project) = p.value(Keys.DISPLAY_NAME) ?: p.name.ifBlank { "The game" }
    private fun label(p: Project, k: String) = PlainLabels.of(p, k)
    private fun src(p: Project, k: String): String = when (p.decision(k)?.prov) {
        Provenance.OWNER_CORRECTION -> "Owner (correction)"
        Provenance.OWNER_EXPLICIT -> "Owner (own words)"
        Provenance.OWNER_ACCEPTED_RECOMMENDATION -> "Owner accepted Bob's recommendation"
        Provenance.SYSTEM_INFERENCE -> "Inferred from the owner's words"
        else -> "Bob's default"
    }
    private fun priority(p: Project, k: String) = when (p.decision(k)?.prov) { Provenance.OWNER_CORRECTION, Provenance.OWNER_EXPLICIT -> "Must"; Provenance.OWNER_ACCEPTED_RECOMMENDATION -> "Should"; else -> "Could" }
    private fun cell(s: String) = s.replace("|", "/").replace("\n", " ").trim()

    // ---------------------------------------------------------------- game design document

    private fun gdd(p: Project): String = buildString {
        appendLine("# Game design document: ${title(p)}")
        appendLine()
        appendLine("This is the plain-English design the owner approved. `CLAUDE.md` is the authoritative specification; this document explains the same design for people.")
        appendLine()
        appendLine(DesignReview.compose(p))
        appendLine()
        val dm = DesignModel.of(p)
        appendLine("## Systems in this game")
        appendLine()
        SystemId.values().forEach { appendLine("- ${it.label}: ${dm.state(it).name.lowercase().replace('_', ' ')}") }
        appendLine()
        listOf(Keys.AUDIENCE to "Audience", Keys.GAME_LENGTH to "Expected length", Keys.BUDGET to "Budget", Keys.PLAYER_FEELING to "Intended feeling", Keys.CORE_LOOP to "Core loop", Keys.WIN_LOSS to "Win and loss", Keys.DONE to "Finished means").forEach { (k, n) -> label(p, k)?.let { appendLine("- **$n:** $it") } }
    }

    // ---------------------------------------------------------------- requirements ledger

    private data class Row(val text: String, val priority: String, val deps: String, val source: String, val validation: String)

    private fun ledgerRows(p: Project): List<Row> {
        val t = Traits(p)
        val rows = mutableListOf<Row>()
        val primary = t.primaryPlatform?.let { Platforms.labels[it] ?: it } ?: "the target platform"
        fun add(text: String, k: String?, deps: String, validation: String) { rows += Row(text, k?.let { priority(p, it) } ?: "Must", deps, k?.let { src(p, it) } ?: "Required by the approved design", validation) }
        label(p, Keys.PLATFORMS)?.let { add("The game runs on: $it. $primary is built and tested first; the others stay supported targets.", Keys.PLATFORMS, "-", "Production build on $primary (physical device where the platform is a phone); compatibility notes for the others") }
        p.value(Keys.DIMENSION)?.let { add("Presentation is $it${label(p, Keys.ART_DIRECTION)?.let { a -> " in the $a art style" } ?: ""}; the style is kept as chosen.", Keys.DIMENSION, "-", "Screenshots from the real renderer compared with GAMEPLAY_INVARIANTS") }
        label(p, Keys.CORE_LOOP)?.let { add("Core loop: $it", Keys.CORE_LOOP, "-", "Playthrough of the loop on the real build") }
        label(p, Keys.MOVEMENT_CAMERA)?.let { add("Movement and camera: $it", Keys.MOVEMENT_CAMERA, "-", "Input-to-motion checks and gameplay screenshots from the real camera") }
        label(p, Keys.WIN_LOSS)?.let { add("Win and loss: $it", Keys.WIN_LOSS, "Core loop", "Reach the completion state and a failure state on the real build") }
        label(p, Keys.DIFFICULTY_FAILURE)?.let { add("Failure and recovery: $it", Keys.DIFFICULTY_FAILURE, "Core loop", "Fail on purpose; confirm recovery cannot soft-lock") }
        t.systems().forEach { (_, s) -> rows += Row("${s.name}: ${s.detail}", "Should", "Core loop", "Bob's genre checklist", "Exercise the system end to end on the real build") }
        p.activeFacts().forEach { f ->
            val r = com.hotattic.gamedesigner.core.engine.FactStance.of(p, f)
            when (r.stance) {
                com.hotattic.gamedesigner.core.engine.Stance.REQUEST -> rows += Row("Owner statement: ${f.text.take(240)}", "Must", "-", "Owner (own words)", "Verify the statement is true in the running game")
                com.hotattic.gamedesigner.core.engine.Stance.REJECTION -> rows += Row("Owner exclusion: ${f.text.take(240)}", "Exclusion", "-", "Owner (own words)", "Verify nothing in the build contradicts the exclusion")
                else -> rows += Row("Owner statement (${r.note}): ${f.text.take(240)}", "Not required", "-", "Owner (own words)", "None: this is not a requirement")
            }
        }
        if (t.has(com.hotattic.gamedesigner.core.schema.Tag.PROCGEN) || p.value(Keys.WORLD_STRUCTURE) == "procedural_stages") add("Generated content is always completable, fair and reproducible from a seed.", Keys.WORLD_STRUCTURE, "Core loop", "Automated reachability and completion check over many seeds")
        add("Progress saves safely (versioned schema, safe writes, recovery) and survives closing and reopening the game.", Keys.SAVE_SYSTEM, "Core loop", "Save, close the game, reopen, load; corrupt-file recovery test")
        label(p, Keys.PERFORMANCE)?.let { add("Performance: $it", Keys.PERFORMANCE, "-", "Measured frame rate on representative hardware") }
        label(p, Keys.AUDIO)?.let { add("Audio: $it", Keys.AUDIO, "-", "Listen to actual playback; files existing is not proof") }
        label(p, Keys.INPUT_METHODS)?.let { add("Input: $it", Keys.INPUT_METHODS, "-", "Exercise every input on the real build") }
        if (t.multiplayer != null && t.multiplayer != "single_player") NetworkPlan.lines(p).forEach { add("Multiplayer: $it", Keys.MULTIPLAYER_INTENT, "Core loop", if (p.value(Keys.MULTIPLAYER_TIMING) == "prototype") "Two-instance synchronization and disconnection tests" else "Deterministic, replayable game state") }
        if (UpdateStrategy.wanted(p)) {
            add("After install, these can be updated over the air: ${UpdateStrategy.contentLabels(p)}.", Keys.OTA_CONTENT, "Engine and structure", "Update pipeline test: valid applies, tampered and corrupt rejected, rollback works, saves survive")
            UpdateStrategy.unmet(p).forEach { rows += Row("Cannot be satisfied as asked: $it", "Must", "OTA", "Documented limitation", "Owner informed; closest compliant mechanism built") }
        }
        if (PlatformPolicy.androidSelected(p)) add("Android Target API ${PlatformPolicy.ANDROID_TARGET_API} with a compatible toolchain set.", Keys.ANDROID_TARGET_API, "-", "Built artifact's targetSdkVersion read from the package")
        p.branding.values.forEach { b -> rows += Row("Branding asset: ${b.slot.replace('_', ' ')}", "Must", "-", "Owner", "Used as supplied; derived sizes generated") }
        label(p, Keys.ASSET_POLICY)?.let { add("Asset strategy: $it. Every asset is logged in ASSETS.md with its license.", Keys.ASSET_POLICY, "-", "Provenance ledger audit") }
        rows += Row("A playable build is delivered early and at every milestone, with an honest list of what works.", "Must", "-", "Approved handoff policy", "Milestone evidence in docs/MILESTONES.md")
        return rows
    }

    private fun ledger(p: Project): String = buildString {
        appendLine("# Requirements ledger: ${title(p)}")
        appendLine()
        appendLine("Every requirement has an ID, a source, and a status. The coding agent updates this file as work proceeds; a status moves forward only with the evidence named in the Validation column.")
        appendLine()
        appendLine("Statuses: Complete and verified | Implemented but unverified | Partially implemented | Placeholder | Missing | Blocked.")
        appendLine("A button or menu entry is not proof that a feature works. A successful compile is not proof that the game is playable. A passing test is not proof that the whole workflow works.")
        appendLine()
        appendLine("| ID | Requirement | Priority | Depends on | Source of decision | Status | Source files | Validation method | Test evidence | Remaining work | Owner acceptance |")
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|")
        ledgerRows(p).forEachIndexed { i, r -> appendLine("| REQ-%03d | %s | %s | %s | %s | Missing | - | %s | - | Not started | Pending |".format(i + 1, cell(r.text), r.priority, cell(r.deps), cell(r.source), cell(r.validation))) }
        appendLine()
        appendLine("Explicit exclusions are tracked separately in `docs/EXCLUSIONS.md`.")
    }

    // ---------------------------------------------------------------- exclusions

    private fun exclusions(p: Project): String = buildString {
        appendLine("# Explicit exclusions: ${title(p)}")
        appendLine()
        appendLine("These were decided by the owner. They stay out of the game until the owner says otherwise, and no document or milestone may reintroduce them.")
        appendLine()
        val dm = DesignModel.of(p)
        val named = Exclusions.fromOwner(p)
        val items = named.mapIndexed { i, e -> "- EXC-%03d **%s** - the owner said: \"%s\"".format(i + 1, e.label, e.evidence) } +
            SystemId.values().filter { dm.state(it) == SystemState.ABSENT }.mapIndexed { i, s -> "- EXC-%03d **%s** - ruled out by the owner's design decisions".format(named.size + i + 1, s.label) }
        p.value(Keys.EXCLUSIONS)?.let { appendLine("Owner's note: $it"); appendLine() }
        if (items.isEmpty()) appendLine("The owner has excluded nothing explicitly. Systems the owner did not describe are simply not required.") else items.forEach { appendLine(it) }
    }

    // ---------------------------------------------------------------- architecture decisions

    private fun adr(p: Project): String = buildString {
        val t = Traits(p)
        appendLine("# Architecture decision record: ${title(p)}")
        appendLine()
        appendLine("Each entry: the decision, why, the closest alternative, and what it costs to change later. Record any new engineering decision here too.")
        appendLine()
        var n = 0
        fun entry(name: String, decision: String, why: String, alt: String, change: String, status: String) {
            n++; appendLine("## ADR-%03d %s".format(n, name)); appendLine("- Decision: $decision"); appendLine("- Why: $why"); appendLine("- Closest alternative: $alt"); appendLine("- Cost of changing later: $change"); appendLine("- Status: $status"); appendLine()
        }
        t.engine?.let { e -> entry("Engine", e.name, StackAdvisor.considered(p).joinToString(" "), StackAdvisor.comparison(p).getOrNull(1)?.substringAfter("Closest viable alternative: ") ?: "none that also meets every requirement", "Very high: most of the game would be rebuilt.", src(p, Keys.ENGINE)) }
        entry("Platforms and primary platform", "${t.platforms.joinToString { Platforms.labels[it] ?: it }}; ${t.primaryPlatform?.let { Platforms.labels[it] ?: it } ?: "single platform"} first", "One shared gameplay core plus a thin integration layer per platform, one source line, one project.", "Separate projects per platform (rejected: they drift apart)", "Medium: controls and layouts differ per platform.", src(p, Keys.PLATFORMS))
        t.multiplayer?.let { m -> entry("Multiplayer", m.replace('_', ' ') + (p.value(Keys.MULTIPLAYER_TIMING)?.let { ", $it" } ?: ""), NetworkPlan.lines(p).joinToString(" ").ifBlank { "Single-player keeps the build focused." }, "The other hosting models, described in the interview.", "Very high if networking is not designed in from the start.", src(p, Keys.MULTIPLAYER_INTENT)) }
        if (UpdateStrategy.wanted(p)) entry("Updates after install", "Updateable: ${UpdateStrategy.contentLabels(p)}", UpdateStrategy.perPlatform(p).joinToString(" ") { "${Platforms.labels[it.platform] ?: it.platform} via ${it.channel}: ${it.mechanism}" }, "Content-only updates with every code change shipped as a new install.", "High: the game's structure depends on it.", src(p, Keys.OTA_CONTENT))
        label(p, Keys.SAVE_SYSTEM)?.let { entry("Saving", it, "Versioned schema, safe writes, backup and recovery.", "No saving", "Medium: formats must stay readable.", src(p, Keys.SAVE_SYSTEM)) }
        label(p, Keys.CI_BUILD)?.let { entry("Build pipeline", it, "Reproducible builds tied to an exact source SHA.", "Local builds only", "Low", src(p, Keys.CI_BUILD)) }
    }

    private fun techStack(p: Project): String = buildString {
        val t = Traits(p)
        appendLine("# Recommended technology stack: ${title(p)}")
        appendLine()
        t.engine?.let { appendLine("**Engine:** ${it.name} (${it.language}). License: ${it.licenseNote}."); appendLine() }
        appendLine("## What the choice was based on"); StackAdvisor.considered(p).forEach { appendLine("- $it") }
        appendLine(); appendLine("## Compared with the closest alternative"); StackAdvisor.comparison(p).forEach { appendLine("- $it") }
        appendLine(); appendLine("## Limits you should know about"); PlatformCompat.notes(p).forEach { appendLine("- $it") }
        StackAdvisor.incompatibilities(p).forEach { appendLine("- INCOMPATIBLE: $it") }
        appendLine(); appendLine("Toolchain versions are verified from live sources by the coding agent before pinning and recorded in `docs/DECISIONS.md`.")
    }

    private fun assetPlan(p: Project): String = buildString {
        appendLine("# Asset and licensing plan: ${title(p)}")
        appendLine()
        appendLine(label(p, Keys.ASSET_POLICY)?.let { "Strategy: $it." } ?: "Strategy: free assets with verified licenses, then original or procedural work.")
        appendLine()
        appendLine("## License classes (record the class for every asset in ASSETS.md)")
        appendLine("- **CC0 / public domain:** use, change and redistribute freely.")
        appendLine("- **Attribution required:** allowed with visible credit; list the credit text.")
        appendLine("- **Free for personal use only:** not allowed in a game that will be released.")
        appendLine("- **Free for commercial use:** allowed in the game; check separately whether the raw files may be redistributed (often they may not).")
        appendLine("- **Modification and redistribution rights** are separate questions; read the license on the asset's own page and do not invent restrictions that are not in it.")
        appendLine()
        appendLine("Use clearly marked placeholders when a suitable asset is not found quickly; do not stall development searching for a perfect asset. Search permitted sources (for example itch.io) only where the owner allowed outside research.")
    }

    private fun buildAndRelease(p: Project): String = buildString {
        val t = Traits(p)
        appendLine("# Build and release plan: ${title(p)}")
        appendLine()
        appendLine("## Source management")
        appendLine("- One authoritative source line and one game project, with a platform-specific build configuration for each target. No independent branches or agents per platform.")
        appendLine("- Reproducible builds, tied to an exact source SHA and a consistent build number. Clear artifact names, controlled promotion between stages.")
        appendLine()
        appendLine("## Artifacts")
        HandoffPolicy.buildNames(p).forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Test delivery")
        appendLine("- " + when (p.value(Keys.TEST_DELIVERY)) {
            "github_prerelease" -> "The owner chose GitHub pre-releases. Attach the actual files directly to the release (not only a zipped build-system download). A pre-release is publicly downloadable if the repository is public. Create it only on the owner's authorization."
            "local_only" -> "The owner builds locally; provide an exact, tested build document and a one-command build script."
            else -> "Private download from the build system. A GitHub release is not created unless the owner asks for one."
        })
        appendLine("- A GitHub release is not publication to Steam or Google Play, and neither is an over-the-air update.")
        appendLine()
        if (Platforms.STEAM_DECK in t.platforms) {
            appendLine("## Steam Deck")
            appendLine("- Controller-only navigation, 1280x800 support, readable text, no clipped UI, on-screen keyboard compatibility, suspend and resume, appropriate save handling.")
            appendLine("- Steam Deck compatibility in practice is not the same as Valve's official Verified status, which is a separate review.")
            appendLine()
        }
        appendLine("## Permissions")
        HandoffPolicy.authorization(p).forEach { appendLine("- $it") }
        if (PlatformPolicy.androidSelected(p)) { appendLine(); appendLine("## Android"); PlatformPolicy.androidRequirements.forEach { appendLine("- $it") } }
        if (UpdateStrategy.wanted(p)) { appendLine(); appendLine("## Updates after install"); appendLine("- Keep update publication separate from normal builds and store releases; manual publication by default."); UpdateStrategy.limitations(p).forEach { appendLine("- $it") } }
    }

    private fun testPlan(p: Project): String = buildString {
        appendLine("# Test and validation plan: ${title(p)}")
        appendLine()
        appendLine("Validate with the real game: production assets, the production renderer and animation system, the real gameplay camera and state, representative screen sizes, and real hardware where it matters. A diagnostic render is not production gameplay.")
        appendLine()
        appendLine("## Evidence classes (state which one every result is)")
        listOf("Static inspection", "Unit tests", "Simulation", "Production rendering", "Desktop runtime", "Physical device").forEach { appendLine("- $it") }
        appendLine()
        appendLine("## What to exercise")
        VerificationPlan.steps(p).forEach { appendLine("- $it") }
    }

    private fun invariants(p: Project): String = buildString {
        val t = Traits(p)
        appendLine("# Gameplay invariants: ${title(p)}")
        appendLine()
        appendLine("The identity and feel of the game. Fill every measured value in at Milestone 1 (when the owner first approves a playable baseline) and keep this file current.")
        appendLine()
        HandoffPolicy.invariantRules(p).forEach { appendLine("- $it") }
        appendLine()
        appendLine("## Owner-set characteristics")
        listOf(Keys.MOVEMENT_CAMERA to "Movement and camera", Keys.PERSPECTIVE to "Camera perspective", Keys.ART_DIRECTION to "Visual identity", Keys.AUDIO to "Audio identity", Keys.DIFFICULTY_FAILURE to "Difficulty and failure", Keys.PERFORMANCE to "Performance target", Keys.INPUT_METHODS to "Input")
            .forEach { (k, n) -> label(p, k)?.let { appendLine("- **$n:** $it") } }
        p.value(Keys.MUST_NOT_CHANGE)?.takeIf { it.trim().lowercase() != "none" }?.let { appendLine("- **Owner's must-not-change:** $it") }
        appendLine()
        appendLine("## Measured at the approved baseline (record the numbers)")
        listOf("Movement speeds", "Acceleration", "Jump behavior", "Physics", "Camera", "Input responsiveness", "Animation timing", "Combat timing", "Character proportions").forEach { appendLine("- $it: measured value, how it was measured, and the regression check that guards it") }
        if (t.has(com.hotattic.gamedesigner.core.schema.Tag.COMBAT).not()) appendLine("- Combat timing: not applicable; this game has no combat.")
        appendLine()
        appendLine("## Regression checks for protected systems")
        appendLine("- Each protected system above has an automated or scripted check that fails if its behavior changes; list the checks here when they exist.")
    }

    private fun milestones(p: Project): String = buildString {
        appendLine("# Milestone plan: ${title(p)}")
        appendLine()
        appendLine("Milestones adapt to the game, but a playable build comes EARLY and the plan ends at the finished game the owner approved${if (ProjectObjective.of(p) == BuildObjective.PROTOTYPE) " (here: a prototype, because the owner asked for one)" else ""}. Do not stop at planning, scaffolding or a proof of concept unless the owner asked for exactly that.")
        appendLine()
        HandoffPolicy.milestones(p).forEach { appendLine("## ${it.id} - ${it.title}"); appendLine("- Deliver: ${it.deliverable}"); appendLine("- Evidence: ${it.evidence}"); appendLine() }
        appendLine("The first build may be incomplete, but it must be honest about what works. At every milestone, tell the owner in plain language what they can test now.")
    }

    private fun completion(p: Project): String = buildString {
        appendLine("# Completion criteria: ${title(p)}")
        appendLine()
        appendLine("- ${p.value(Keys.DONE) ?: "The complete core loop is playable start to finish and every requirement in the ledger is Complete and verified or explicitly excluded."}")
        appendLine("- The requirements ledger has no row left at Missing, Placeholder or Blocked without an owner-approved explanation.")
        appendLine("- A playable build exists for the primary platform, with exact source SHA and build number, and `docs/TEST_PLAN.md` has been exercised with honest evidence classes.")
        appendLine("- Protected gameplay is unchanged from the approved baseline (regression checks pass).")
        appendLine("- Nothing was published without the owner's authorization.")
    }
}
