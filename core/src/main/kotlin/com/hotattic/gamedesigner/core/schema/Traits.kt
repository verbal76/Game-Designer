package com.hotattic.gamedesigner.core.schema

import com.hotattic.gamedesigner.core.model.Experience
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode

/**
 * Read-only derived view of a project's current design decisions. Relevance rules, defaults, conflict rules and
 * generators all query this instead of parsing raw decision strings in many places.
 */
class Traits(val project: Project) {
    val mode: ProjectMode = project.mode
    val experience: Experience = project.prefs.experience
    val beginner: Boolean get() = experience == Experience.BEGINNER

    val rejectedTags: Set<Tag> = project.rejected[Dependencies.TAG].orEmpty().mapNotNull { n -> runCatching { Tag.valueOf(n) }.getOrNull() }.toSet()
    val genres: List<Genre> = project.list(Keys.GENRE).filter { !project.isRejected("genre", it) }.map { GenreKnowledge.resolve(it) }.filter { g -> g.tags.none { it in rejectedTags } }
    val genresKnown: Boolean get() = genres.isNotEmpty()
    val tags: Set<Tag> = genres.flatMap { it.tags }.toSet() - rejectedTags
    fun has(tag: Tag) = tag in tags
    fun perspectiveIsScroller(): Boolean = project.value(Keys.PERSPECTIVE) in setOf("vertical_scroll", "side_view")
    /**
     * One continuous world (a shaft, a tower, an endless fall) rather than a string of discrete levels. True when the owner chose
     * it, or while the world structure is still open and the design is a vertical scroller.
     */
    val continuousWorld: Boolean
        get() {
            val w = project.value(Keys.WORLD_STRUCTURE)
            return w == "vertical_shaft" || w == "open_map" || (w == null && project.value(Keys.PERSPECTIVE) == "vertical_scroll")
        }

    /** The continuous world is a single tall shaft/tower rather than a wide connected map. */
    val verticalWorld: Boolean
        get() {
            val w = project.value(Keys.WORLD_STRUCTURE)
            return w == "vertical_shaft" || (w == null && project.value(Keys.PERSPECTIVE) == "vertical_scroll")
        }

    /** The owner described two distinct playable characters, sides or modes. */
    val twoSides: Boolean
        get() = (listOf(project.originalConcept) + project.activeFacts().map { it.text }).any { Regex("(?i)\\b(either character|two characters|both characters|two (modes|sides|playable)|which side|choose (which|a) (side|character)|two different characters)\\b").containsMatchIn(it) }

    /** Genre checklist adapted to the owner's actual structure: no level sets or level select in a continuous world. */
    fun systems(): List<Pair<Genre, SystemReq>> = genres.flatMap { g ->
        val list = if (continuousWorld && g.id == "platformer") g.systems.filter { it.id !in setOf("level_set", "level_select_progress") } + listOf(
            if (verticalWorld) SystemReq("continuous_world", "Continuous vertical world", "One continuous traversable world in contiguous depth zones with checkpoints by depth; no level boundaries or level-select.")
            else SystemReq("continuous_world", "Connected world", "One connected traversable world in contiguous regions with checkpoints; no level boundaries or level-select."),
            SystemReq("depth_progress_save", "World progress save", "Persist position, checkpoints, unlocks and abilities."),
        ) else g.systems
        list.map { g to it }
    }.distinctBy { it.second.id }

    fun loopText(g: Genre): String =
        if (continuousWorld && g.id == "platformer") "Move through the continuous world using tight movement, avoid hazards and enemies, and progress through it toward its far end."
        else g.loopTemplate

    fun smokeChecks(): List<String> = genres.flatMap { g ->
        if (continuousWorld && g.id == "platformer") listOf("A scripted run traverses the continuous world between its extremes with no unreachable section (automated reachability check).") else g.smokeChecks
    }.distinct()

    fun hasGenre(id: String) = genres.any { it.id == id }

    val dimension: String? = project.value(Keys.DIMENSION)
    val platforms: Set<String> = project.list(Keys.PLATFORMS).toSet()
    val platformsKnown: Boolean get() = platforms.isNotEmpty()
    val isMobile: Boolean = platforms.any { it in Platforms.mobile }
    val mobileOnly: Boolean = platforms.isNotEmpty() && platforms.all { it in Platforms.mobile }
    val inputs: Set<String> = project.list(Keys.INPUT_METHODS).toSet()
    val usesTouch: Boolean = "touch" in inputs || (inputs.isEmpty() && isMobile)
    val usesKeyboardOrPad: Boolean = inputs.any { it == "keyboard_mouse" || it == "gamepad" }

    val engineId: String? = project.value(Keys.ENGINE)
    val engine: EngineProfile? = engineId?.let { EngineCatalog.get(it) }
    val network: String? = project.value(Keys.NETWORK_POLICY)
    val storePlan: String? = project.value(Keys.STORE_PLAN)
    val releaseOriented: Boolean = storePlan in setOf("play_store", "app_store", "steam_or_pc_store", "itch_or_web")

    val fastTwitch: Boolean = has(Tag.FAST_TWITCH)
    val isNewGame: Boolean = mode == ProjectMode.NEW_GAME
    val isContinuation: Boolean = mode != ProjectMode.NEW_GAME

    fun value(key: String): String? = project.value(key)
    fun list(key: String): List<String> = project.list(key)
    fun resolved(key: String): Boolean = project.decision(key)?.let { it.value.isNotBlank() } == true

    /** Sum of genre complexity plus structural modifiers. Also used by ScopeEngine. */
    val complexity: Int
        get() {
            var c = genres.maxOfOrNull { it.complexity } ?: 2
            if (genres.size > 1) c += (genres.size - 1).coerceAtMost(2)
            c += when (dimension) { "3D" -> 2; "2.5D" -> 1; else -> 0 }
            if (Tag.PROCGEN in tags) c += 1
            if (platforms.size > 2) c += 1
            return c
        }
}
