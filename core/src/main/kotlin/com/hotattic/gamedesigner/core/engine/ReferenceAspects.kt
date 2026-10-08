package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ReferenceGame

/**
 * The things a reference game is usually loved for, offered as choices after the game is named and researched. Deterministic:
 * the researched summary, traits and name pick which aspects fit; a ready LLM may offer sharper game-specific ones, but only
 * through [validateLlm], and the rules always supply a complete fallback.
 */
object ReferenceAspects {
    private class Aspect(val label: String, val triggers: List<String>)

    private val pool = listOf(
        Aspect("Emergent stories - things happen that nobody scripted", listOf("emergent", "simulation", "roguelike", "procedural", "colony", "sandbox", "generated")),
        Aspect("Deep simulation of people and systems", listOf("simulation", "management", "colony", "economy", "city", "citizen", "population")),
        Aspect("Building and base / city layout", listOf("construction", "building", "city", "builder", "base", "settlement", "factory", "colony")),
        Aspect("Resource chains and economy", listOf("economy", "resource", "crafting", "production", "trade", "management", "factory")),
        Aspect("Combat feel", listOf("combat", "action", "shooter", "fighting", "battle", "melee", "hack")),
        Aspect("Movement and controls feel", listOf("platform", "action", "movement", "parkour", "racing", "shooter", "jump")),
        Aspect("Progression and unlocks", listOf("rpg", "upgrade", "progression", "level", "roguelite", "unlock", "skill")),
        Aspect("Exploration and discovery", listOf("explor", "open world", "adventure", "metroidvania", "discover", "map")),
        Aspect("Tension, survival and risk", listOf("survival", "horror", "permadeath", "roguelike", "danger", "hardcore")),
        Aspect("Tactics and meaningful decisions", listOf("strategy", "tactic", "turn-based", "deck", "puzzle", "rts", "4x")),
        Aspect("Story, writing and characters", listOf("story", "narrative", "rpg", "visual novel", "character", "dialogue")),
        Aspect("Cozy, relaxing loop", listOf("farming", "cozy", "relax", "life sim", "casual", "garden")),
        Aspect("Scale - hundreds of things alive at once", listOf("colony", "city", "strategy", "swarm", "survivors", "horde", "kingdom")),
        Aspect("Magic and fantasy flavour", listOf("fantasy", "magic", "wizard", "dwarf", "elf", "rpg")),
    )
    private val general = listOf(
        "Overall structure and how a session flows",
        "Pacing and difficulty curve",
        "Art style and atmosphere",
        "The core loop - what you do minute to minute",
        "Progression and unlocks",
    )

    /** Five or six choices for [game], most fitting first. Never empty. */
    fun rules(game: ReferenceGame, project: Project): List<String> {
        val hay = (game.name + " " + game.summary + " " + game.traits.joinToString(" ") + " " + project.originalConcept).lowercase()
        val scored = pool.map { a -> a to a.triggers.count { it in hay } }.filter { it.second > 0 }.sortedByDescending { it.second }.map { it.first.label }
        return (scored.take(4) + general).distinct().take(6)
    }

    fun prompt(game: ReferenceGame): String = buildString {
        append("Game: ${game.name}\n")
        if (game.summary.isNotBlank()) append("What is known: ${game.summary.take(400)}\n")
        append("List 6 short things players love about THIS specific game that a designer might want to borrow (each under 9 words, specific to the game, no art/characters/music). ")
        append("Reply ONLY with a JSON array of strings.")
    }

    /** Accepts a model's JSON array only if it is a sane list of short plain strings. */
    fun validateLlm(raw: String): List<String>? {
        val start = raw.indexOf('['); val end = raw.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        val items = Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(raw.substring(start, end + 1)).map { it.groupValues[1].replace("\\\"", "\"").trim() }.toList()
        val clean = items.filter { it.length in 4..70 && it.count { c -> c == '\n' } == 0 }.distinctBy { it.lowercase() }.take(6)
        return clean.takeIf { it.size >= 4 }
    }
}
