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
        val affirm: Regex = Regex("(?!)"),
    )

    val all: List<Gate> = listOf(
        Gate(Tag.COMBAT, Keys.HAS_COMBAT, "combat", listOf(Keys.COMBAT_MODEL, Keys.ENEMIES_BOSSES),
            setOf("survivors_like", "action_roguelite", "shooter", "fighting", "strategy_rts", "turn_based_strategy", "tower_defense", "card_deckbuilder"),
            setOf("metroidvania", "rpg", "colony_sim"),
            Regex("(?i)\\b(?:no|without|zero|not any|(?:doesn'?t|does not|don'?t|do not|never)\\s+(?:have|include|need|want|use)|no need for)\\s+(?:any\\s+)?(?:combat|fighting|fights?|violence|enemies|monsters)\\b|\\bnon-?violent\\b|\\bpeaceful\\b|\\bpacifist\\b|\\bnot\\s+(?:a|an)\\s+(?:combat|fighting)\\b|\\b(?:isn'?t|is not)\\s+(?:a|an)\\s+(?:combat|fighting)\\b|\\bno\\s+one\\s+gets\\s+hurt\\b"), affirm = Regex("(?i)\\b(?:combat|fighting|enem(?:y|ies)|boss(?:es)?|boss fights?|weapons?|monsters?|zombies?|skeletons?|shoot(?:ing)?)\\b")),
        Gate(Tag.ECONOMY, Keys.HAS_ECONOMY, "economy", listOf(Keys.ECONOMY),
            setOf("city_builder", "sim_management", "management_sim", "fantasy_city_builder", "colony_sim", "factory_automation"),
            setOf("strategy_rts"),
            Regex("(?i)\\b(?:no|without|zero|not any|(?:doesn'?t|does not|don'?t|do not|never)\\s+(?:have|include|need|want|use))\\s+(?:any\\s+)?(?:economy|currency|shops?|money|trading|gold|coins)\\b"), affirm = Regex("(?i)\\b(?:add|include|want|need|with|has|have)\\s+(?:some\\s+|a\\s+|an\\s+)?(?:economy|currency|shops?|trading)\\b")),
        Gate(Tag.CRAFTING, Keys.HAS_CRAFTING, "crafting", listOf(Keys.SURVIVAL_CRAFTING),
            setOf("survival_crafting", "factory_automation", "colony_sim"),
            setOf("rpg"),
            Regex("(?i)\\b(?:no|without|zero|not any|(?:doesn'?t|does not|don'?t|do not|never)\\s+(?:have|include|need|want|use))\\s+(?:any\\s+)?(?:crafting|gathering|building materials|resource gathering)\\b"), affirm = Regex("(?i)\\b(?:add|include|want|need|with|has|have)\\s+(?:some\\s+)?(?:crafting|gathering)\\b")),
    )
    val byTag: Map<Tag, Gate> = all.associateBy { it.tag }
    val byKey: Map<String, Gate> = all.associateBy { it.key }

    /** The gate question is only worth asking when the genre carries the tag without it being the point of the game. */
    fun needed(t: Traits, tag: Tag): Boolean {
        val g = byTag[tag] ?: return false
        return t.genresKnown && tag in t.rawTags && t.genres.none { it.id in g.core }
    }

    /** Words in the owner's own description that show they want the system. */
    private val wanted = mapOf(
        Tag.COMBAT to Regex("(?i)\\b(combat|fight(?:ing|s)?|enem(?:y|ies)|bosse?s?|battles?|weapons?|shoot(?:ing|er)?|kill(?:ing)?|attack(?:s|ing)?|swords?|monsters?|zombies?)\\b"),
        Tag.ECONOMY to Regex("(?i)\\b(economy|currency|shops?|gold|coins?|money|trad(?:e|ing)|buy|sell|prices?|market)\\b"),
        Tag.CRAFTING to Regex("(?i)\\b(craft(?:ing)?|gather(?:ing)?|harvest|forag(?:e|ing)|materials|recipes?)\\b"),
    )

    /** "Choose for me" judged on the owner's description first, the genre's habits second. */
    fun suggest(t: Traits, tag: Tag): Suggestion {
        val g = byTag.getValue(tag)
        val said = (listOf(t.project.originalConcept) + t.project.activeFacts().map { it.text }).joinToString(". ")
        val wants = wanted[tag]?.containsMatchIn(said) == true
        val yes = wants || t.genres.any { it.id in g.usually }
        return Suggestion(if (yes) "yes" else "no",
            if (wants) "You described ${g.noun} yourself." else if (yes) "Games like this usually have ${g.noun}." else "Nothing you've described needs ${g.noun}, and adding it would dilute the focus.")
    }

    /** Re-settles gates from something the owner just said: the latest explicit word wins over an earlier answer or an inference. */
    fun applyOwnerText(p0: Project, text: String, now: Long): Project {
        var p = p0
        for (g in all) {
            val no = g.denial.findAll(text).lastOrNull()?.range?.first ?: -1
            val yes = g.affirm.findAll(g.denial.replace(text) { " ".repeat(it.value.length) }).lastOrNull()?.range?.first ?: -1
            if (no < 0 && yes < 0) continue
            val f = Fields.get(g.key) ?: continue
            if (!f.isRelevant(Traits(p))) continue
            val answer = if (no > yes) "no" else "yes"
            val have = p.decision(g.key)
            if (have?.value == answer && have.status == com.hotattic.gamedesigner.core.model.DecisionStatus.CONFIRMED) continue
            p = com.hotattic.gamedesigner.core.engine.ProjectOps.setDecision(p, g.key, answer, com.hotattic.gamedesigner.core.model.Provenance.OWNER_CORRECTION, now, raw = text.take(200))
            if (answer == "no") p = dropDependents(p, g, now)
        }
        return p
    }

    /** Gates the owner has ruled out in their own words. */
    fun deniedIn(text: String): List<Gate> = all.filter { it.denial.containsMatchIn(text) }

    /** Gates the owner has asked for in their own words. */
    fun affirmedIn(text: String): List<Gate> = all.filter { it.affirm.containsMatchIn(it.denial.replace(text, " ")) }

    /** Takes back the detail answers of a system that has been ruled out, so nothing stale survives in the design. */
    fun dropDependents(p: Project, g: Gate, now: Long): Project =
        g.dependents.fold(p) { acc, k -> if (acc.decision(k) != null) acc.copy(decisions = acc.decisions - k, updatedAt = now) else acc }
}
