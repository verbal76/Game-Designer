package com.hotattic.gamedesigner.core.schema

import com.hotattic.gamedesigner.core.model.Project

/** Why Bob is reasoning right now; lenses are selected per purpose so a model call carries only what it needs. */
enum class LensPurpose { INTERPRET, CHOOSE, FOLLOW_UP, RECONCILE }

class LensContext(val project: Project, val currentField: Field? = null, val purpose: LensPurpose = LensPurpose.INTERPRET) {
    val key: String? get() = currentField?.key
    val traits: Traits by lazy { Traits(project) }
    val procedural: Boolean get() = project.value(Keys.WORLD_STRUCTURE) == "procedural_stages"
}

/**
 * One curated reference note, distilled to a few lines of operational guidance. [source] is the file under
 * docs/game-design-references/ it was distilled from (a test checks every note has exactly one lens). To add a lens: drop the note in
 * that folder, add an entry here with a trigger and 1-3 sentences of guidance. Nothing else in Bob needs to change.
 */
class Lens(val id: String, val title: String, val source: String, val guidance: String, val appliesTo: (LensContext) -> Boolean)

/**
 * The reference library as design lenses. [select] returns at most [MAX] relevant lenses (plus a two-line baseline) so the guidance
 * that reaches a model call is a few hundred characters, never the papers.
 */
object DesignLenses {
    const val MAX = 3
    const val GUIDANCE_CAP = 300

    /** Always in force; kept to two sentences. */
    const val BASELINE = "Owner intent outranks genre convention, defaults and Bob's earlier recommendations. A system the owner does not want is a complete, resolved answer (intentionally absent); never fill it in."

    private val coreLoopKeys = setOf(Keys.CORE_LOOP, Keys.WIN_LOSS, Keys.DONE, Keys.FIRST_SLICE, Keys.FIVE_MINUTES, Keys.DIFFICULTY_FAILURE)
    private val experienceKeys = setOf(Keys.PLAYER_FEELING, Keys.CORE_FANTASY, Keys.MOVEMENT_CAMERA, Keys.FIVE_MINUTES, Keys.ART_DIRECTION, Keys.AUDIO)
    private val systemKeys = setOf(Keys.HAS_COMBAT, Keys.HAS_ECONOMY, Keys.HAS_CRAFTING, Keys.HAS_PROGRESSION, Keys.COMBAT_MODEL, Keys.ENEMIES_BOSSES, Keys.ECONOMY, Keys.SURVIVAL_CRAFTING, Keys.STORY, Keys.PROGRESSION, Keys.CHARACTERS)
    private val progressionKeys = setOf(Keys.HAS_PROGRESSION, Keys.PROGRESSION, Keys.DIFFICULTY_FAILURE, Keys.WIN_LOSS)

    val all: List<Lens> = listOf(
        Lens("player_centered", "Player-centered, iterative", "01-player-centered-iterative-design.md",
            "A coherent spec is not a fun game. Treat playtest feedback as evidence and separate bad concept from bad implementation, tuning or readability; do not over-specify speculative systems before a playable test.",
            { it.purpose == LensPurpose.RECONCILE || it.project.versions.isNotEmpty() || it.project.feedback.isNotEmpty() }),
        Lens("ocr", "Objective / Challenge / Reward", "02-gameplay-loops-objective-challenge-reward.md",
            "Read the loop as Objective (what the player is after now), Challenge (what makes it hard or interesting), Reward (the feedback that makes success meaningful). Reward may be relief, mastery, access, spectacle or completion; never assume XP, loot, currency or unlocks.",
            { it.key in coreLoopKeys || it.purpose == LensPurpose.RECONCILE }),
        Lens("rules_of_play", "Rules of play lenses", "03-rules-of-play-design-lenses.md",
            "Economy, narrative, character progression, community and the rest are lenses, not mandatory systems. Reason both ways: experience to rules, and each proposed rule to its effect on experience.",
            { it.key in systemKeys }),
        Lens("procedural_constraints", "Procedural content as constrained design", "04-procedural-content-constraint-design.md",
            "Procedural is not random. Fix the invariants first (completable route, fairness, readability, intended difficulty and experience), then decide what may vary inside them. Ask only about constraints that change the player's experience.",
            { it.procedural || it.key == Keys.WORLD_STRUCTURE }),
        Lens("ai_human", "AI-human collaboration", "05-ai-human-collaborative-design.md",
            "You are a design partner, not a transcriber: infer implications, spot contradictions and absent systems, ask the highest-value question, explain tradeoffs in player-experience terms. You never overwrite an explicit owner decision.",
            { it.purpose == LensPurpose.CHOOSE || it.purpose == LensPurpose.FOLLOW_UP }),
        Lens("mda", "MDA: experience first", "06-mda-experience-driven-design.md",
            "Work from the feeling the owner wants to the runtime dynamics that would create it, then to mechanics. Do not turn an emotion into a feature: 'lucky' may mean a skilled near-miss, not random rewards. Ask one question if the meaning would change the mechanics.",
            { it.key in experienceKeys }),
        Lens("mastery", "Player mastery vs. character power", "07-player-motivation-and-skill-development.md",
            "Separate player-skill progression (discovering actions, combining them, internalising timing, harder situations) from character-power progression (XP, stats, gear). A game can progress without the avatar ever getting stronger; do not ask upgrade questions of a game that rejected power growth.",
            { it.key in progressionKeys }),
        Lens("genre", "Genre is context, not authority", "08-genre-taxonomy-and-benchmarking.md",
            "A genre label gives vocabulary and comparables only. It never authorises combat, XP, collectibles, lives, economy or crafting; derive the design from the owner's fantasy, actions, objective and intended experience.",
            { it.key in systemKeys || it.purpose == LensPurpose.CHOOSE }),
        Lens("pattern_pcg", "Pattern-based generation", "09-pattern-based-procedural-generation.md",
            "Generated content must be readable and stylistically coherent, not merely different. Define the traversal grammar, challenge/recovery rhythm, topology and pacing at micro, meso and macro scale; variation lives inside them.",
            { it.procedural }),
    )

    /** At most [MAX] lenses, in declaration order, whose trigger fits this moment. */
    fun select(ctx: LensContext): List<Lens> = all.filter { runCatching { it.appliesTo(ctx) }.getOrDefault(false) }.take(MAX)

    /** The compact text a model call receives: the baseline plus the selected lenses. Bounded by construction. */
    fun guidance(ctx: LensContext): String =
        (listOf(BASELINE) + select(ctx).map { "${it.title}: ${it.guidance}" }).joinToString("\n")
}
