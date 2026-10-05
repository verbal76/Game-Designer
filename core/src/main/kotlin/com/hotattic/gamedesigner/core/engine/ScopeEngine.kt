package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.ClaudePlan
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.UsageStyle
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Traits

enum class ScopeTier(val label: String) { COMPACT("Compact"), STANDARD("Standard"), LARGE("Large"), EPIC("Epic") }

data class ContentTarget(val label: String, val count: Int)

enum class ResourceLevel(val label: String) { LOW("Low"), MODERATE("Moderate"), HEAVY("Heavy"), EXTREME("Extreme") }

data class ResourceEstimate(val level: ResourceLevel, val explanation: String, val strategy: List<String>)

data class ScopeRecommendation(
    val recommendedTier: ScopeTier,
    val effectiveTier: ScopeTier,
    val complexity: Int,
    val rationale: List<String>,
    val targets: List<ContentTarget>,
    val resources: ResourceEstimate,
    val phases: List<String>,
)

/**
 * Deterministic scope inference. The default is the largest coherent first playable build that is practical for an
 * autonomous agent; it is never collapsed into a bare prototype. The owner may adjust one tier up or down.
 */
object ScopeEngine {

    private val tiers = ScopeTier.values()

    private val genreTargets: Map<String, List<Pair<String, IntArray>>> = mapOf(
        "survivors_like" to listOf("Playable heroes" to ia(2, 3, 5, 7), "Weapons" to ia(6, 10, 16, 24), "Passive upgrades" to ia(6, 10, 14, 20),
            "Enemy types" to ia(8, 12, 18, 26), "Bosses" to ia(1, 2, 3, 5), "Stages / biomes" to ia(1, 2, 3, 4), "Meta unlocks" to ia(10, 20, 35, 50)),
        "action_roguelite" to listOf("Stages / biomes" to ia(2, 3, 4, 6), "Enemy types" to ia(10, 16, 24, 34), "Bosses" to ia(2, 3, 5, 7),
            "Items / relics" to ia(25, 45, 70, 100), "Playable heroes" to ia(1, 2, 4, 6), "Meta unlocks" to ia(10, 20, 30, 45)),
        "platformer" to listOf("Levels" to ia(8, 16, 28, 40), "Worlds / themes" to ia(1, 2, 4, 5), "Enemy types" to ia(5, 8, 12, 16),
            "Bosses" to ia(1, 2, 4, 5), "Power-ups / abilities" to ia(2, 4, 6, 8)),
        "metroidvania" to listOf("Regions" to ia(3, 5, 7, 9), "Abilities" to ia(4, 6, 9, 12), "Enemy types" to ia(12, 20, 30, 40), "Bosses" to ia(4, 6, 9, 12)),
        "shooter" to listOf("Levels / encounters" to ia(5, 8, 12, 18), "Weapons" to ia(5, 8, 12, 16), "Enemy types" to ia(6, 10, 15, 20), "Bosses" to ia(1, 2, 4, 6)),
        "city_builder" to listOf("Building types" to ia(20, 35, 55, 80), "Resource types" to ia(5, 8, 12, 16), "Scenarios" to ia(2, 4, 6, 9), "Events" to ia(8, 15, 25, 40)),
        "factory_automation" to listOf("Items" to ia(25, 45, 80, 120), "Machines" to ia(10, 16, 24, 32), "Resource types" to ia(5, 8, 12, 16),
            "Tech tree nodes" to ia(20, 35, 60, 90), "Maps / scenarios" to ia(1, 2, 3, 5)),
        "survival_crafting" to listOf("Gatherable resources" to ia(12, 20, 30, 45), "Recipes" to ia(30, 60, 100, 150), "Biomes" to ia(2, 3, 5, 7),
            "Creature types" to ia(6, 10, 16, 24), "Boss / goal encounters" to ia(1, 2, 3, 5)),
        "strategy_rts" to listOf("Factions" to ia(1, 2, 3, 4), "Units per faction" to ia(6, 8, 10, 12), "Buildings per faction" to ia(8, 10, 12, 14), "Missions / maps" to ia(5, 8, 12, 18)),
        "turn_based_strategy" to listOf("Unit classes" to ia(6, 9, 14, 20), "Abilities" to ia(18, 30, 50, 80), "Maps" to ia(6, 10, 16, 24), "Enemy types" to ia(6, 10, 16, 24)),
        "tower_defense" to listOf("Towers" to ia(6, 9, 13, 18), "Enemy types" to ia(8, 12, 18, 24), "Maps" to ia(5, 8, 12, 18), "Bosses" to ia(1, 3, 5, 8)),
        "rpg" to listOf("Zones" to ia(4, 6, 9, 12), "Quests" to ia(12, 24, 40, 60), "Enemy types" to ia(15, 25, 40, 60), "Items" to ia(60, 100, 160, 240), "Bosses" to ia(4, 6, 9, 12)),
        "puzzle" to listOf("Levels" to ia(40, 80, 150, 250), "Distinct mechanics" to ia(2, 3, 5, 7), "Themes / worlds" to ia(3, 5, 8, 12)),
        "racing" to listOf("Tracks" to ia(4, 8, 12, 18), "Vehicles" to ia(4, 7, 10, 14), "Cups / championships" to ia(1, 2, 3, 5)),
        "card_deckbuilder" to listOf("Cards" to ia(60, 100, 160, 240), "Enemy types" to ia(15, 25, 40, 60), "Bosses" to ia(3, 4, 6, 8), "Relics" to ia(20, 40, 70, 100), "Heroes" to ia(1, 2, 3, 5)),
        "narrative_adventure" to listOf("Chapters" to ia(3, 5, 8, 12), "Endings" to ia(2, 3, 4, 6), "Scenes" to ia(30, 60, 100, 160), "Characters" to ia(4, 6, 9, 12)),
        "sim_management" to listOf("Generators / buildables" to ia(12, 20, 32, 48), "Upgrades" to ia(30, 60, 100, 160), "Prestige layers" to ia(1, 2, 3, 3)),
        "fighting" to listOf("Characters" to ia(4, 8, 12, 16), "Stages" to ia(3, 5, 8, 12), "Game modes" to ia(2, 3, 4, 5)),
        "other" to listOf("Core content units" to ia(10, 20, 35, 50)),
    )

    private fun ia(vararg v: Int) = v

    fun capacity(project: Project): Int {
        val base = when (project.prefs.claudePlan) {
            ClaudePlan.PRO, ClaudePlan.UNSURE -> 0
            ClaudePlan.MAX_5X, ClaudePlan.TEAM_OR_API -> 1
            ClaudePlan.MAX_20X -> 2
        }
        val usage = when (project.prefs.usageStyle) {
            UsageStyle.CONSERVATIVE -> -1
            UsageStyle.BALANCED -> 0
            UsageStyle.AGGRESSIVE -> 1
        }
        return base + usage
    }

    fun recommend(project: Project): ScopeRecommendation {
        val t = Traits(project)
        val complexity = t.complexity
        val rationale = mutableListOf<String>()
        var idx = 3 - maxOf(0, (complexity - 2) / 2)
        rationale += "Design complexity score $complexity (genre systems, dimension, procedural content, platform count)."
        val cap = capacity(project)
        if (cap < 0) { idx -= 1; rationale += "Claude plan and conservative usage preference reduce the single-pass scope by one tier." }
        else { if (cap >= 2 && idx < 3) idx += 1; rationale += "Claude plan/usage preference supports this tier as a durable multi-checkpoint build." }
        idx = idx.coerceIn(0, 3)
        val prototype = ProjectObjective.of(project) == BuildObjective.PROTOTYPE
        if (prototype) { idx = 0; rationale += "The owner defined the first build as a fully functional prototype, so content is sized to prove the design, not to ship it." }
        val recommended = tiers[idx]

        val choice = project.value(Keys.SCOPE_CHOICE)
        var eff = idx
        when (choice) {
            "bigger" -> { eff += 1; rationale += "Owner chose to go one tier bigger." }
            "smaller" -> { eff -= 1; rationale += "Owner chose to go one tier smaller." }
        }
        eff = eff.coerceIn(0, 3)
        val effective = tiers[eff]

        val genres = t.genres.ifEmpty { listOf(com.hotattic.gamedesigner.core.schema.GenreKnowledge.other) }
        val primary = genres.first()
        val relabel = if (t.continuousWorld) mapOf("Levels" to "Depth zones along the continuous world", "Worlds / themes" to "Visual themes") else emptyMap()
        val targets = (genreTargets[primary.id] ?: genreTargets.getValue("other")).map { (label, arr) -> ContentTarget(relabel[label] ?: label, arr[eff]) }.toMutableList()
        // Hybrid designs add a smaller contribution from the secondary genre so both halves of the hybrid are really present.
        val have = targets.map { it.label }.toSet()
        genres.drop(1).take(1).forEach { g ->
            genreTargets[g.id]?.filter { it.first !in have }?.take(2)?.forEach { (label, arr) ->
                targets += ContentTarget(label, maxOf(1, arr[maxOf(0, eff - 1)]))
            }
        }
        return ScopeRecommendation(recommended, effective, complexity, rationale, targets, resourceEstimate(effective, complexity, t, project), phases(effective, complexity, prototype))
    }

    fun resourceEstimate(tier: ScopeTier, complexity: Int, t: Traits, project: Project): ResourceEstimate {
        val points = tier.ordinal + complexity * 2
        val level = when {
            points <= 4 -> ResourceLevel.LOW
            points <= 9 -> ResourceLevel.MODERATE
            points <= 14 -> ResourceLevel.HEAVY
            else -> ResourceLevel.EXTREME
        }
        val why = buildString {
            append("${tier.label} scope with complexity $complexity. ")
            append(when (level) {
                ResourceLevel.LOW -> "Small enough to finish in a few focused sessions."
                ResourceLevel.MODERATE -> "Expect several focused sessions across multiple checkpoints."
                ResourceLevel.HEAVY -> "Expect many sessions; one authoritative spec with durable phases and handoff notes is essential."
                ResourceLevel.EXTREME -> "Expect a very large, multi-window effort; strongly consider a smaller tier or deliberate phased releases."
            })
            if (t.dimension == "3D") append(" 3D adds asset, performance and verification overhead.")
            if (project.prefs.claudePlan == ClaudePlan.PRO && level >= ResourceLevel.HEAVY) append(" On a Pro plan this will compete heavily with other projects; spread it over many windows.")
        }
        val strategy = buildList {
            add("Keep one authoritative CLAUDE.md; record decisions in files, not chat.")
            add("Work in durable phases with a committed checkpoint and HANDOFF.md at the end of each.")
            add("Run targeted tests while iterating; run the full suite and a full build only at phase gates.")
            add("Use subagents only when their value clearly exceeds their cost; avoid parallel swarms.")
            if (level >= ResourceLevel.HEAVY) add("Prefer data-driven content tables so large content volume is cheap to add and test.")
        }
        return ResourceEstimate(level, why, strategy)
    }

    fun phases(tier: ScopeTier, complexity: Int, prototype: Boolean = false): List<String> = buildList {
        add("Phase 0 - Foundations: toolchain pinned and verified, repository layout, CI pipeline producing an installable artifact, data schemas, test harness.")
        add("Phase 1 - Complete core loop: the full intended core loop playable end to end with real (not placeholder) visuals, input and feedback.")
        add("Phase 2 - Systems: every required system from this spec implemented and integrated (progression, saves, UI, settings, win/loss).")
        add(if (prototype) "Phase 3 - Prototype content: author only the content needed to prove each specified system (see Scope), data-driven so more can be added later; validate with automated checks."
            else "Phase 3 - Content: author the content volume in the Scope section using data-driven tables; validate with automated checks.")
        add("Phase 4 - Polish: audio, VFX/game feel, accessibility, performance tuning, onboarding.")
        add(if (prototype) "Phase 5 - Prototype candidate: full validation, repair pass, installable build artifact, handoff for human playtesting."
            else "Phase 5 - Release candidate: full validation, repair pass, final build artifact, handoff for human playtesting.")
        if (tier == ScopeTier.EPIC || complexity >= 9) add("Between phases: refresh HANDOFF.md and stop cleanly if the usage window is nearly spent; resume from the checkpoint.")
    }
}
