package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.engine.BuildObjective
import com.hotattic.gamedesigner.core.engine.MetaConversation
import com.hotattic.gamedesigner.core.engine.ProjectObjective
import com.hotattic.gamedesigner.core.engine.ResourceLevel
import com.hotattic.gamedesigner.core.engine.ScopeEngine
import com.hotattic.gamedesigner.core.model.BrandingMode
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.FeedbackStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.UsageStyle
import com.hotattic.gamedesigner.core.schema.DimId
import com.hotattic.gamedesigner.core.schema.DimensionLexicon
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits

/**
 * The execution prompt a coding agent is given. It carries the concrete, consequential content of the design (creative target,
 * playable scope, gameplay that must work, what does not count, how to verify) and points at CLAUDE.md for the full authoritative
 * record. Sections with nothing to say are omitted; there is no boilerplate for its own sake.
 */
object MasterPromptGenerator {

    fun generate(project0: Project, versionNumber: Int): String {
        val project = com.hotattic.gamedesigner.core.engine.DerivedDefaults.apply(project0)
        val t = Traits(project)
        val title = project.value(Keys.DISPLAY_NAME) ?: project.name
        val scope = ScopeEngine.recommend(project)
        val prototype = ProjectObjective.of(project) == BuildObjective.PROTOTYPE
        val repo = project.repo?.let { "${it.owner}/${it.repo}" }
        val openFeedback = project.feedback.count { it.status == FeedbackStatus.OPEN }
        fun v(k: String) = project.decision(k)?.takeIf { it.status == DecisionStatus.CONFIRMED && it.value.isNotBlank() && (Fields.get(k)?.isRelevant(t) != false) }?.value
        fun rec(k: String) = if (project.decision(k)?.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION) " (accepted recommendation)" else ""
        fun lab(k: String) = v(k)?.let { PlainLabels.of(project, k) + rec(k) }
        val usage = when (project.prefs.usageStyle) {
            UsageStyle.CONSERVATIVE -> "The owner shares one usage allowance across many projects: work economically and stop cleanly at a checkpoint rather than burning the whole window."
            UsageStyle.BALANCED -> "Work at a steady, economical pace; checkpoint often so a context reset costs nothing."
            UsageStyle.AGGRESSIVE -> "The owner wants this finished quickly: use the allowance as needed, but still checkpoint at every stable milestone."
        }
        val heavy = if (scope.resources.level >= ResourceLevel.HEAVY) " This is a ${scope.resources.level.label} effort: expect many sessions; keep HANDOFF.md current." else ""

        return buildString {
            fun section(name: String, body: List<String>) { if (body.isEmpty()) return; appendLine("## $name"); body.forEach { appendLine(it) }; appendLine() }
            appendLine("You are Claude Code, the autonomous lead engineer for \"$title\" (spec v$versionNumber).")
            appendLine()

            val mission = when (project.mode) {
                ProjectMode.NEW_GAME -> if (prototype) "Build the fully functional PROTOTYPE of \"$title\" described below and in `CLAUDE.md`: the intended game and core loop genuinely playable, with only the content needed to prove the design. Not a mockup or gray-box, and not a full production game."
                    else "Build the complete, genuinely playable first version of \"$title\" described below and in `CLAUDE.md`. Not a prototype, mockup or gray-box."
                ProjectMode.EXISTING_GAME -> "Continue the existing game as described in `CLAUDE.md` section 17. Inspect the repository first, separate verified facts from assumptions, create a rollback tag/branch, preserve working systems, then implement the plan."
                ProjectMode.PLAYTEST_CONTINUE -> "Apply the playtest feedback in `CLAUDE.md` section 17 ($openFeedback open item(s)). Reproduce each issue first, fix root causes, verify, and keep everything that already works."
            }
            section("MISSION / DELIVERABLE", listOf(mission,
                "Repository: ${repo ?: "the current repository"}. Work on the designated branch; commit and push stable checkpoints.",
                "FIRST ACTION: read the repository-root `CLAUDE.md` in full. It is the authoritative specification and Part A (the owner's requirements, corrections and must-not-change constraints) overrides everything else. If it is missing, tell the owner it must be added at the repository root before you proceed.",
                "Deliverable: ${t.platforms.joinToString { Platforms.labels[it] ?: it }.ifEmpty { "the target platform" }} build, source, assets, README with exact install/launch steps and controls, and `docs/VERIFICATION.md` (what you actually exercised, what you could not, known issues)."))

            val conceptLines = MetaConversation.designSentences(project.originalConcept.ifBlank { project.value(Keys.CONCEPT).orEmpty() }, 8).take(6)
            section("CREATIVE TARGET", listOfNotNull(
                conceptLines.takeIf { it.isNotEmpty() }?.let { "The owner's concept, in their words: \"${it.joinToString(" ")}\"" },
                v(Keys.CORE_FANTASY)?.let { "Core fantasy: $it" },
                if (t.genresKnown) "Genre: ${t.genres.joinToString(" + ") { it.label }}. ${v(Keys.DIMENSION) ?: ""} ${lab(Keys.PERSPECTIVE)?.let { "View: $it." } ?: ""}".trim() else null))

            section("PLAYER EXPERIENCE", listOfNotNull(v(Keys.PLAYER_FEELING)?.let { "How it should feel: $it" }, lab(Keys.SESSION_STRUCTURE)?.let { "Typical session: $it" },
                DimensionLexicon.sentencesFor(project, DimId.FEELING).firstOrNull()?.takeIf { it != v(Keys.PLAYER_FEELING) }?.let { "In the owner's words: \"$it\"" }))

            section("PLAYABLE FIRST-BUILD SCOPE", listOfNotNull(
                (v(Keys.FIRST_SLICE)?.let { it + rec(Keys.FIRST_SLICE) } ?: "Build the smallest polished slice that fully demonstrates the concept."),
                if (prototype) "This is a PROTOTYPE by the owner's own definition: real gameplay, representative presentation, a beginning-to-end slice, real controls and core loop. It is not required to contain full production content (every eventual level, enemy, boss or final art)."
                else "Content volume: see CLAUDE.md section 5 (sizing is a recommendation, not a requirement, unless the owner stated numbers).",
                "Smaller and polished beats larger and unfinished. Do not invent content counts the owner did not set."))

            section("CORE LOOP", listOfNotNull(v(Keys.CORE_LOOP)?.let { it + rec(Keys.CORE_LOOP) }))

            val must = listOfNotNull(
                v(Keys.MOVEMENT_CAMERA)?.let { "Movement and camera: $it${rec(Keys.MOVEMENT_CAMERA)}" },
                lab(Keys.COMBAT_MODEL)?.let { "Combat: $it" },
                v(Keys.CHARACTERS)?.let { "Characters: ${lab(Keys.CHARACTERS)}" },
                v(Keys.ECONOMY)?.let { "Economy: $it" }, v(Keys.SURVIVAL_CRAFTING)?.let { "Survival and crafting: $it" }, v(Keys.AUTOMATION_SIM)?.let { "Simulation: $it" },
            ) + project.activeFacts().map { it.text }.filter { f -> DimensionLexicon.classify(f).let { c -> DimId.MOVEMENT in c || DimId.INTERACTION in c } }.take(4).map { "Owner said: \"$it\"" }
            section("GAMEPLAY THAT MUST WORK", must.distinct())

            section("WORLD / LEVEL STRUCTURE", listOfNotNull(
                when (v(Keys.WORLD_STRUCTURE)) {
                    "vertical_shaft" -> "ONE continuous vertical world (a single shaft). It is traversed as one connected space. Do NOT convert it into separate levels, rooms or a level-select."
                    "open_map" -> "One large connected map, traversed continuously."
                    "single_arena" -> "A single arena."
                    "authored_levels" -> "A set of hand-authored levels."
                    "procedural_stages" -> "Procedurally generated stages."
                    "hub_missions" -> "A hub with missions."
                    else -> lab(Keys.WORLD_STRUCTURE)
                }))

            section("PROGRESSION / FAILURE / COMPLETION", listOfNotNull(
                lab(Keys.PROGRESSION)?.let { "Progression: $it" }, lab(Keys.DIFFICULTY_FAILURE)?.let { "Failure and recovery: $it" },
                v(Keys.WIN_LOSS)?.let { "Win and loss: $it${rec(Keys.WIN_LOSS)}" }, v(Keys.DONE)?.let { "The first build is complete when: $it${rec(Keys.DONE)}" }))

            section("VISUAL QUALITY IS PART OF COMPLETION", listOfNotNull(
                lab(Keys.ART_DIRECTION)?.let { "Art direction: $it" }, v(Keys.COLOR_MOOD)?.let { "Colour and mood: $it" },
                "The very first view the player sees (title or opening scene) must look intentional: run the game, capture it, inspect the screenshot critically and fix what looks default, flat or unreadable before moving on.",
                "Visual quality is part of done. A constrained scope is fine; ugly, engine-default, placeholder-looking, incoherent or unreadable output is not.",
                "What does NOT count:") + AntiSlop.derive(project).map { "- $it" })

            section("AUDIO / PRESENTATION", listOfNotNull(lab(Keys.AUDIO)?.let { "Audio: $it" }, lab(Keys.VFX)?.let { "Effects and feel: $it" }, v(Keys.HUD_UI)?.let { "HUD and interface: $it" }))

            section("CONTROLS / PLATFORM", listOfNotNull(
                "Platforms: ${t.platforms.joinToString { Platforms.labels[it] ?: it }.ifEmpty { "not set" }}.",
                lab(Keys.INPUT_METHODS)?.let { "Input: $it. Map the game's semantic actions to these inputs yourself; the owner did not specify every button." },
                lab(Keys.ORIENTATION)?.let { "Orientation: $it" }))

            val ups = project.branding.values.filter { it.mode == BrandingMode.UPLOADED }
            section("SUPPLIED ASSETS / ASSET POLICY", listOfNotNull(
                lab(Keys.ASSET_POLICY)?.let { "Asset policy: $it. Verify every external asset's license on its own page, log provenance in `ASSETS.md`, never assume 'free to download' is a license." },
            ) + ups.map { "Owner-supplied ${it.slot.replace('_', ' ')}: `${it.originalName}` (${it.width}x${it.height}), included in the package under `branding/master/`. Use it as-is; never replace or regenerate it. Keep the master untouched and derive every size from it." })

            val inv = v(Keys.MUST_NOT_CHANGE)?.takeIf { it.trim().lowercase() != "none" }
            val implied = com.hotattic.gamedesigner.core.schema.SliceSuggester.invariants(t).value.takeIf { it != "none" }
            section("MUST-NOT-CHANGE CONSTRAINTS", listOfNotNull(inv?.let { "- ${it.trim()}" }, implied?.takeIf { it != inv }?.let { "- $it" }))

            section("AUTONOMOUS WORKFLOW", listOf(
                "The owner defines the vision; you solve the engineering. Work autonomously: implement rather than merely plan, make reasonable reversible engineering decisions yourself and record them in `docs/DECISIONS.md`, and never stop to ask the owner anything `CLAUDE.md` already answers.",
                "Escalate only a genuine creative decision not covered, credentials or permissions only the owner can supply, or a verified blocker with no engineering alternative.",
                "Start by inspecting the repository, the owner-supplied assets and the tools actually installed; never assume them. Build the smallest vertical slice that proves the riskiest assumption (movement feel, the world structure, the render path, the build pipeline) before widening, and fix a failing assumption immediately instead of building on it.",
                "The deliverable is the working game, not a tutorial, a plan or a pile of source. If a playable build was requested, do not finish with source only.",
                "Do not stop at the first compile or the first screenshot. Do not claim success without actually playing the game. Research current facts (versions, SDK requirements, licenses) when they matter and record sources; do not repeat settled research.",
                "Never commit secrets. Never force-push or rewrite shared history. $usage Use subagents only when their value clearly exceeds their cost.$heavy",
                "Commit stable, tested milestones; when a context nears its limit or a phase ends, update `HANDOFF.md` with branch + SHA, what is implemented, test/build status, known defects and next work."))

            section("CORE-FIRST IMPLEMENTATION STRATEGY", coreFirst(project, t))

            section("VERIFY BEFORE FINISHING", VerificationPlan.steps(project).map { "- $it" })

            section("DELIVER", listOf(
                "Finish only when `CLAUDE.md` Part A is satisfied, the first-build scope is playable beginning to end, automated validation is green, the installable/runnable build exists, and `docs/VERIFICATION.md` is honest.",
                "Hand off to the owner only when the remaining meaningful validation genuinely needs a human; list exactly what to test."))

            val open = unresolved(project, t)
            section("KNOWN UNRESOLVED ITEMS", open)
        }.trimEnd() + "\n"
    }

    private fun unresolved(p: Project, t: Traits): List<String> {
        val deferred = p.decisions.filter { it.value.status == DecisionStatus.DEFERRED && it.key != Keys.FIVE_MINUTES && Fields.get(it.key)?.required == true }.keys.map { "- ${Fields.get(it)?.title ?: it}: deliberately deferred by the owner; do not build it." }
        val delegated = p.decisions.any { (_, d) -> d.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION }
        return deferred + listOfNotNull(if (delegated) "- Where the owner delegated a decision to Bob's recommendation, it is recorded in `CLAUDE.md` Part B; follow it unless Part A says otherwise." else null)
    }

    /** First prove the riskiest mechanic, then a complete loop, then content, then presentation, then validation - ordered for THIS game. */
    private fun coreFirst(p: Project, t: Traits): List<String> {
        val corpus = (listOf(p.originalConcept) + p.activeFacts().map { it.text }).joinToString(" ").lowercase()
        val risky = when {
            Regex("gravity|spherical|planet").containsMatchIn(corpus) -> "FIRST prove the custom gravity/traversal and movement on its world shape, with real collision, before anything else."
            p.value(Keys.WORLD_STRUCTURE) == "vertical_shaft" -> "FIRST prove continuous traversal of the whole vertical world (movement, camera, collision and world streaming at the full depth) before building content."
            t.has(Tag.COMBAT) && t.has(Tag.MOVEMENT) -> "FIRST prove movement + aiming/attacking + one weapon or ability + one enemy, feeling right, in one small space (a rough test space is acceptable for this step only)."
            t.has(Tag.MOVEMENT) -> "FIRST prove the movement and camera feel in a small test space (rough is acceptable for this step only)."
            t.has(Tag.BUILDING) || t.has(Tag.SIMULATION) -> "FIRST prove the simulation tick and one complete production/placement chain."
            else -> "FIRST prove the core loop once, end to end, with one unit of content."
        }
        return listOf("- $risky",
            "- THEN establish the complete playable loop from start to completion (including failure and restart).",
            "- THEN expand to the representative content of the first-build scope.",
            "- THEN raise the visual and audio presentation to the standard above.",
            "- THEN validate beginning to end, repair, and re-validate.")
    }
}
