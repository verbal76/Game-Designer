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
