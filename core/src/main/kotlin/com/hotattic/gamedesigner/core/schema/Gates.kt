package com.hotattic.gamedesigner.core.schema

import com.hotattic.gamedesigner.core.model.Project

/**
 * Systems that only some games of a genre have (combat in a platformer, an economy in a tower defense). A gate is one short
 * yes/no question asked before that system's detail questions; "no" removes the tag so none of them are ever asked, "yes" keeps them.
 */
object Gates {
    data class Gate(
        val tag: Tag, val key: String, val noun: String,
        /** Detail questions that exist only when the gate is open; answered ones are taken back when the owner says "no". */
        val dependents: List<String>,
        /** Genres where this system is the point of the game, so there is nothing to gate. */
        val core: Set<String>,
        /** Genres whose usual shape includes the system, so "choose for me" says yes. */
        val usually: Set<String>,
        val denial: Regex,
    )

    val all: List<Gate> = listOf(
        Gate(Tag.COMBAT, Keys.HAS_COMBAT, "combat", listOf(Keys.COMBAT_MODEL, Keys.ENEMIES_BOSSES),
            setOf("survivors_like", "action_roguelite", "shooter", "fighting", "strategy_rts", "turn_based_strategy", "tower_defense", "card_deckbuilder"),
            setOf("metroidvania", "rpg", "colony_sim"),
            Regex("(?i)\\b(?:no|without|zero|not any|don'?t want any|doesn'?t have any|no need for)\\s+(?:any\\s+)?(?:combat|fighting|fights?|violence|enemies|monsters)\\b|\\bnon-?violent\\b|\\bpacifist\\b|\\bnot\\s+(?:a|an)\\s+(?:combat|fighting)\\b|\\b(?:isn'?t|is not)\\s+(?:a|an)\\s+(?:combat|fighting)\\b|\\bno\\s+one\\s+gets\\s+hurt\\b")),
        Gate(Tag.ECONOMY, Keys.HAS_ECONOMY, "economy", listOf(Keys.ECONOMY),
            setOf("city_builder", "sim_management", "management_sim", "fantasy_city_builder", "colony_sim", "factory_automation"),
            setOf("strategy_rts"),
            Regex("(?i)\\b(?:no|without|zero|not any|don'?t want any)\\s+(?:any\\s+)?(?:economy|currency|shops?|money|trading|gold|coins)\\b")),
        Gate(Tag.CRAFTING, Keys.HAS_CRAFTING, "crafting", listOf(Keys.SURVIVAL_CRAFTING),
            setOf("survival_crafting", "factory_automation", "colony_sim"),
            setOf("rpg"),
            Regex("(?i)\\b(?:no|without|zero|not any|don'?t want any)\\s+(?:any\\s+)?(?:crafting|gathering|building materials|resource gathering)\\b")),
    )
    val byTag: Map<Tag, Gate> = all.associateBy { it.tag }
    val byKey: Map<String, Gate> = all.associateBy { it.key }

    /** The gate question is only worth asking when the genre carries the tag without it being the point of the game. */
    fun needed(t: Traits, tag: Tag): Boolean {
        val g = byTag[tag] ?: return false
        return t.genresKnown && tag in t.rawTags && t.genres.none { it.id in g.core }
    }

    fun suggest(t: Traits, tag: Tag): Suggestion {
        val g = byTag.getValue(tag)
        val yes = t.genres.any { it.id in g.usually }
        return Suggestion(if (yes) "yes" else "no", if (yes) "Games like this usually have ${g.noun}." else "Nothing you've described needs ${g.noun}.")
    }

    /** Gates the owner has ruled out in their own words. */
    fun deniedIn(text: String): List<Gate> = all.filter { it.denial.containsMatchIn(text) }

    /** Takes back the detail answers of a system that has been ruled out, so nothing stale survives in the design. */
    fun dropDependents(p: Project, g: Gate, now: Long): Project =
        g.dependents.fold(p) { acc, k -> if (acc.decision(k) != null) acc.copy(decisions = acc.decisions - k, updatedAt = now) else acc }
}
