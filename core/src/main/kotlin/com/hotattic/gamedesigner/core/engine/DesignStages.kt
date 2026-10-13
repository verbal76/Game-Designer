package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Traits

enum class StageState { COMPLETE, CURRENT, REMAINING }

data class Stage(val id: String, val title: String, val state: StageState, val open: Int, val total: Int)

data class StageProgress(val stages: List<Stage>) {
    val current: Stage? get() = stages.firstOrNull { it.state == StageState.CURRENT }
    val completed: Int get() = stages.count { it.state == StageState.COMPLETE }
    /** "Stage 3 of 9: Platforms and controls", or the finished sentence once every stage is done. */
    val summary: String get() = current?.let { "Stage ${stages.indexOf(it) + 1} of ${stages.size}: ${it.title}" } ?: "All ${stages.size} stages complete"
}

/**
 * Where the owner is in the design, as meaningful stages rather than a question count. A stage is complete only when every decision that belongs to it and
 * applies to THIS game is resolved, so a new requirement (say, online play) reopens the stages it touches and the strip says so honestly. Nothing here is
 * stored: it is read from the project every time, so it can never drift from the real state.
 */
object DesignStages {
    private class Def(val id: String, val title: String, val keys: List<String>)

    private val defs = listOf(
        Def("concept", "Game concept", listOf(Keys.CONCEPT, Keys.GENRE, Keys.CORE_FANTASY, Keys.PLAYER_FEELING, Keys.AUDIENCE, Keys.GAME_LENGTH)),
        Def("gameplay", "Core gameplay", listOf(Keys.CORE_LOOP, Keys.WORLD_STRUCTURE, Keys.MOVEMENT_CAMERA, Keys.HAS_COMBAT, Keys.COMBAT_MODEL, Keys.ENEMIES_BOSSES, Keys.CHARACTERS, Keys.HAS_PROGRESSION, Keys.PROGRESSION, Keys.HAS_ECONOMY, Keys.ECONOMY, Keys.HAS_CRAFTING, Keys.SURVIVAL_CRAFTING, Keys.AUTOMATION_SIM, Keys.WIN_LOSS, Keys.DIFFICULTY_FAILURE)),
        Def("visual", "Visual direction", listOf(Keys.DIMENSION, Keys.PERSPECTIVE, Keys.ART_DIRECTION, Keys.COLOR_MOOD, Keys.AUDIO, Keys.VFX)),
        Def("platforms", "Platforms and controls", listOf(Keys.PLATFORMS, Keys.PRIMARY_PLATFORM, Keys.ORIENTATION, Keys.INPUT_METHODS, Keys.TOUCH_SCHEME)),
        Def("multiplayer", "Multiplayer and connectivity", listOf(Keys.MULTIPLAYER_INTENT, Keys.MULTIPLAYER_TIMING, Keys.MULTIPLAYER_MODE, Keys.MP_PLAYERS, Keys.MP_DEVICES, Keys.MP_HOSTING, Keys.MP_SESSION, Keys.MP_CONNECT, Keys.MP_CROSSPLAY, Keys.MP_OFFLINE, Keys.NETWORK_POLICY)),
        Def("technical", "Technical architecture", listOf(Keys.ENGINE, Keys.SAVE_SYSTEM, Keys.OTA_UPDATES, Keys.OTA_CONTENT, Keys.OTA_BEHAVIOR, Keys.PERFORMANCE, Keys.CI_BUILD, Keys.TESTING, Keys.STORE_PLAN, Keys.TEST_DELIVERY, Keys.ASSET_POLICY, Keys.BUDGET)),
        Def("scope", "Content and scope", listOf(Keys.FIRST_SLICE, Keys.DONE, Keys.SCOPE_CHOICE, Keys.DISPLAY_NAME, Keys.PACKAGE_ID, Keys.MUST_NOT_CHANGE, Keys.BRAND_ICON, Keys.BRAND_STUDIO, Keys.BRAND_GAME_SPLASH)),
    )

    fun of(p: Project): StageProgress {
        if (p.mode != ProjectMode.NEW_GAME) return StageProgress(emptyList())
        val c = CompletenessEngine.compute(p)
        val t = Traits(p)
        val open = (c.missingRequired.map { it.key } + c.proposed.filter { it.required }.map { it.key } + c.pendingUploads.map { it.key }).toSet()
        val rows = mutableListOf<Triple<String, String, Pair<Int, Int>>>()
        for (d in defs) {
            val relevant = d.keys.mapNotNull { Fields.get(it) }.filter { it.required && it.isRelevant(t) && !(it.expertOnly && t.beginner) }
            // Before the genre is known most decisions do not apply yet; the stage still exists (so the strip shows the whole journey) and simply has not started.
            if (relevant.isEmpty()) { if (!t.genresKnown) rows += Triple(d.id, d.title, 1 to 1); continue }
            rows += Triple(d.id, d.title, relevant.count { it.key in open } to relevant.size)
        }
        val conflictsOpen = ConflictEngine.open(p).isNotEmpty()
        rows += Triple("review", "Review and conflict resolution", (if (conflictsOpen || !ReviewGate.approved(p)) 1 else 0) to 1)
        rows += Triple("handoff", "Development handoff", (if (p.versions.isEmpty()) 1 else 0) to 1)
        var currentSet = false
        val stages = rows.map { (id, title, counts) ->
            val (openCount, total) = counts
            val state = when {
                openCount == 0 -> StageState.COMPLETE
                !currentSet -> { currentSet = true; StageState.CURRENT }
                else -> StageState.REMAINING
            }
            Stage(id, title, state, openCount, total)
        }
        return StageProgress(stages)
    }
}
