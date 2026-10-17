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

    data class FromOwner(val wanted: List<String>, val excluded: List<String>)

    private val clauseSplit = Regex("[,;]|\\bbut\\b|\\bhowever\\b")
    private val phraseTail = "([a-z][a-z' -]{2,38}?)"
    private val liking = "(?:like|love|enjoy|admire|adore|want|am into|am inspired by|prefer)"
    private val rejecting = "(?:don'?t want|do not want|don'?t need|do not need|don'?t like|do not like|not|no|without|minus|except|skip|leave out|exclude|drop)"

    /**
     * What the owner said about ONE reference in their own words: the characteristic they like ("the population simulation in X") and the
     * ones they reject ("but I don't want its combat system"). Mentioning a game never inherits anything else from it.
     */
    fun fromOwnerWords(text: String, name: String): FromOwner {
        val n = Regex.escape(name.lowercase())
        val wanted = linkedSetOf<String>(); val excluded = linkedSetOf<String>()
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
        for ((i, sentence) in sentences.withIndex()) {
            // "its/their" refers to the reference when the sentence names it, or the one before did.
            val names = Regex(n, RegexOption.IGNORE_CASE).containsMatchIn(sentence) || (i > 0 && Regex(n, RegexOption.IGNORE_CASE).containsMatchIn(sentences[i - 1]))
            if (!names) continue
            val low = sentence.lowercase().replace("\u2019", "'")
            Regex("$liking\\s+(?:the\\s+|its\\s+|how\\s+)?$phraseTail\\s+(?:in|of|from)\\s+$n", RegexOption.IGNORE_CASE).findAll(low).forEach { wanted += tidy(it.groupValues[1]) }
            Regex("$liking\\s+$n's\\s+$phraseTail(?:$|[.,;])", RegexOption.IGNORE_CASE).findAll(low).forEach { wanted += tidy(it.groupValues[1]) }
            for (clause in low.split(clauseSplit)) {
                Regex("(?:^|\\s)$rejecting\\s+(?:its|their|the|that|any)\\s+$phraseTail\\s*$", RegexOption.IGNORE_CASE).find(clause.trim().trimEnd('.', '!'))?.let { excluded += tidy(it.groupValues[1]) }
            }
        }
        return FromOwner(wanted.filter { it.isNotBlank() && it !in excluded }, excluded.filter { it.isNotBlank() })
    }

    private fun tidy(s: String) = s.trim().removeSuffix(" system").removeSuffix(" mechanics").trim().let { it.ifBlank { s.trim() } }

    /** The reference decision text: only what the owner named. A game with nothing named is inspiration only and nothing is inherited. */
    fun summary(p: Project): String {
        val body = p.references.joinToString(" ") { r ->
            val taken = if (r.aspects.isNotEmpty()) "take ${r.aspects.joinToString(", ")}" else "inspiration only; no specific mechanics, content or features are inherited"
            val not = if (r.excluded.isNotEmpty()) "; NOT taking ${r.excluded.joinToString(", ")}" else ""
            "From ${r.name}: $taken$not."
        }
        return "$body Take design ideas only, never characters, art, maps, music or writing."
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
