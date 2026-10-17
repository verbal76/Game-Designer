package com.hotattic.gamedesigner.core.schema

import com.hotattic.gamedesigner.core.model.Project

/**
 * Systems that only some games of a genre have (combat in a platformer, an economy in a tower defense). A gate is one short
 * yes/no question asked before that system's detail questions; "no" removes the tag so none of them are ever asked, "yes" keeps them.
 */
object Gates {
    data class Gate(
        val tag: Tag?, val key: String, val noun: String,
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
            Regex("(?i)\\b(?:no|without|zero|not any|(?:doesn'?t|does not|don'?t|do not|never)\\s+(?:have|include|need|want|use)|no need for)\\s+(?:any\\s+)?(?:combat|fighting|fights?|violence|enemies|monsters|health(?: bars?)?|lives)\\b|\\bthere (?:is|are)(?:n'?t| not) (?:any\\s+)?(?:combat|fighting|fights?|enemies|violence)\\b|\\bnon-?violent\\b|\\bpeaceful\\b|\\bpacifist\\b|\\bnot\\s+(?:a|an)\\s+(?:combat|fighting)\\b|\\b(?:isn'?t|is not)\\s+(?:a|an)\\s+(?:combat|fighting)\\b|\\bno\\s+one\\s+gets\\s+hurt\\b"), affirm = Regex("(?i)\\b(?:combat|fighting|enem(?:y|ies)|boss(?:es)?|boss fights?|weapons?|monsters?|zombies?|skeletons?|shoot(?:ing)?)\\b")),
        Gate(Tag.ECONOMY, Keys.HAS_ECONOMY, "economy", listOf(Keys.ECONOMY),
            setOf("city_builder", "sim_management", "management_sim", "fantasy_city_builder", "colony_sim", "factory_automation"),
            setOf("strategy_rts"),
            Regex("(?i)\\b(?:no|without|zero|not any|(?:doesn'?t|does not|don'?t|do not|never)\\s+(?:have|include|need|want|use))\\s+(?:any\\s+)?(?:economy|currency|shops?|money|trading|gold|coins)\\b"), affirm = Regex("(?i)\\b(?:add|include|want|need|with|has|have)\\s+(?:some\\s+|a\\s+|an\\s+)?(?:economy|currency|shops?|trading)\\b")),
        Gate(Tag.CRAFTING, Keys.HAS_CRAFTING, "crafting", listOf(Keys.SURVIVAL_CRAFTING),
            setOf("survival_crafting", "factory_automation", "colony_sim"),
            setOf("rpg"),
            Regex("(?i)\\b(?:no|without|zero|not any|(?:doesn'?t|does not|don'?t|do not|never)\\s+(?:have|include|need|want|use))\\s+(?:any\\s+)?(?:crafting|gathering|building materials|resource gathering)\\b"), affirm = Regex("(?i)\\b(?:add|include|want|need|with|has|have)\\s+(?:some\\s+)?(?:crafting|gathering)\\b")),
        Gate(null, Keys.HAS_PROGRESSION, "player power progression (XP, levels, upgrades, unlocks that make the player stronger)", listOf(Keys.PROGRESSION),
            setOf("survivors_like", "action_roguelite", "rpg", "metroidvania", "colony_sim", "sim_management", "management_sim", "fantasy_city_builder", "card_deckbuilder", "factory_automation", "strategy_rts", "turn_based_strategy", "city_builder"),
            emptySet(),
            Regex("(?i)\\b(?:no|without|zero)\\s+(?:any\\s+)?(?:player\\s+)?(?:progression|upgrades?|xp|experience|levell?ing|level ups?|skill tree|unlocks?|power[- ]?ups?)\\b|\\b(?:doesn'?t|does not|don'?t|do not|never|won'?t|will not|can'?t)\\s+(?:ever\\s+)?(?:get|gets|become|becomes|grow|grows|getting)\\s+(?:any\\s+)?(?:stronger|better|more powerful)\\b|\\beither\\s+you\\s+(?:can|could|are able to|'re able to)\\b.{0,60}\\bor\\s+you\\s+(?:can'?t|cannot|are not|aren'?t|'re not)\\b"),
            affirm = Regex("(?i)\\b(?:get|gets|become|becomes|grow|grows)\\s+(?:much\\s+)?(?:stronger|more powerful)\\b|\\blevel(?:s|ing)?\\s+up\\b|\\b(?:upgrades?|skill tree|xp|experience points|unlock(?:s|ables?)?|power[- ]?ups?|stat (?:growth|boosts?))\\b")),
    )
    val byTag: Map<Tag, Gate> = all.filter { it.tag != null }.associateBy { it.tag!! }
    val byKey: Map<String, Gate> = all.associateBy { it.key }

    /** The gate question is only worth asking when the genre carries the tag without it being the point of the game. */
    fun needed(t: Traits, tag: Tag): Boolean {
        val g = byTag[tag] ?: return false
        return t.genresKnown && tag in t.rawTags && t.genres.none { it.id in g.core }
    }

    /** Gates with no genre tag (player power progression) are asked whenever the genre does not make the system the point of the game. */
    fun neededFor(t: Traits, key: String): Boolean {
        val g = byKey[key] ?: return false
        return t.genresKnown && t.genres.none { it.id in g.core }
    }


    // ---- Reading the owner's words per clause, so a negated, questioned or merely referenced system is never taken as a request -------

    private val negCue = Regex("(?i)\\b(?:no(?! matter| doubt)|not(?! only| just| sure| yet| too| so| necessarily)|without|never|none of|zero|skip(?:ping)?|drop(?:ping)?|remov(?:e|ing)|exclud(?:e|ing)|leav(?:e|ing) (?:out|off)|minus|cut(?:ting)?|ditch(?:ing)?|ignor(?:e|ing)|nor|don'?t (?:want|need|like|care (?:for|about))|do not (?:want|need|like)|doesn'?t (?:need|want|have)|does not (?:need|want|have)|rather not (?:have|include)|nothing (?:about|like))\\b")
    private val clauseSplit = Regex("[,;:]|\\bbut\\b|\\bhowever\\b|\\balthough\\b|\\bthough\\b")
    private val sentenceSplit = Regex("(?<=[.!?])\\s+|\\n+")
    private val questionStart = Regex("(?i)^\\s*(?:what|how|why|when|where|which|who|should|could|would|can|do|does|is|are|will)\\b")
    private val wantCue = Regex("(?i)\\b(?:i want|i'?d like|i need|add|include|with|has|have|must have|should have|needs?|there (?:is|are|should be))\\b")

    private val nounCore: Map<String, String> = mapOf(
        Keys.HAS_COMBAT to "combat|fight(?:ing|s)?|violence|enem(?:y|ies)|battl(?:e|es|ing)|bosse?s?|monsters?|weapons?|killing",
        Keys.HAS_ECONOMY to "economy|currenc(?:y|ies)|shops?|money|trading|gold|coins?|markets?|prices?",
        Keys.HAS_CRAFTING to "crafting|gathering|harvest(?:ing)?|resource gathering|building materials",
        Keys.HAS_PROGRESSION to "progression|upgrades?|xp|experience|levell?ing|level[- ]ups?|skill trees?|unlocks?|power[- ]?ups?",
    )

    private fun isQuestion(sentence: String) = sentence.trimEnd().endsWith("?") || questionStart.containsMatchIn(sentence)

    /** A clause negates the system when a negation cue is followed (within a few words) by the system's noun, or the noun is called out/unneeded. */
    private fun clauseDenies(clause: String, g: Gate): Boolean {
        if (g.denial.containsMatchIn(clause)) return true
        val n = nounCore[g.key] ?: return false
        for (m in negCue.findAll(clause)) {
            if (Regex("^[\\s'\\w-]{0,40}?\\b(?:$n)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause.substring(m.range.last + 1))) return true
        }
        return Regex("\\b(?:$n)\\b[^.]{0,20}\\b(?:isn'?t|is not|aren'?t|are not|won'?t be|will not be)\\b|\\b(?:$n)\\s+(?:is|are)\\s+out\\b|\\b(?:leav(?:e|ing)|tak(?:e|ing)|keep(?:ing)?|cut(?:ting)?)\\s+(?:the\\s+|any\\s+)?(?:$n)\\s+(?:out|off)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause)
    }

    /** The system's noun spoken about a referenced game ("its combat", "Syx's combat") with no wish to have it: an observation, not a requirement. */
    private fun referenceAttributed(clause: String, g: Gate): Boolean {
        val n = nounCore[g.key] ?: return false
        return Regex("(?:\\b(?:its|their|his|her)|'s)\\s+(?:\\w+\\s+){0,2}(?:$n)\\b", RegexOption.IGNORE_CASE).containsMatchIn(clause) && !wantCue.containsMatchIn(clause)
    }

    /** "no" or "yes" per clause in the order the owner said them; questions say nothing. */
    private fun verdicts(text: String, g: Gate, broad: Boolean = false): List<String> {
        val out = mutableListOf<String>()
        for (sentence in text.split(sentenceSplit).filter { it.isNotBlank() }) {
            if (isQuestion(sentence)) continue
            val clauses = sentence.split(clauseSplit).filter { it.isNotBlank() }
            var any = false
            for (c in clauses) {
                // "I might want crafting, not sure": a consideration, not a request or a rejection.
                if (com.hotattic.gamedesigner.core.engine.Hedge.hedged(c)) continue
                when {
                    clauseDenies(c, g) -> { out += "no"; any = true }
                    referenceAttributed(c, g) -> {}
                    (if (broad) (g.tag?.let { wanted[it] } ?: g.affirm) else g.affirm).containsMatchIn(c) -> { out += "yes"; any = true }
                }
            }
            if (!any && g.denial.containsMatchIn(sentence)) out += "no"
        }
        return out
    }

    /** True unless the owner (or a settled inference) has ruled the system out; gated detail questions use this. */
    fun open(t: Traits, key: String): Boolean = t.project.decision(key)?.value != "no"

    /** Words in the owner's own description that show they want the system. */
    private val wanted = mapOf(
        Tag.COMBAT to Regex("(?i)\\b(combat|fight(?:ing|s)?|enem(?:y|ies)|bosse?s?|battles?|weapons?|shoot(?:ing|er)?|kill(?:ing)?|attack(?:s|ing)?|swords?|monsters?|zombies?)\\b"),
        Tag.ECONOMY to Regex("(?i)\\b(economy|currency|shops?|gold|coins?|money|trad(?:e|ing)|buy|sell|prices?|market)\\b"),
        Tag.CRAFTING to Regex("(?i)\\b(craft(?:ing)?|gather(?:ing)?|harvest|forag(?:e|ing)|materials|recipes?)\\b"),
    )

    /** "Choose for me" judged on the owner's description first, the genre's habits second. */
    fun suggest(t: Traits, tag: Tag): Suggestion = suggestFor(t, byTag.getValue(tag))

    fun suggestFor(t: Traits, g: Gate): Suggestion {
        val said = (listOf(t.project.originalConcept) + t.project.activeFacts().map { it.text }).joinToString(". ")
        val v = verdicts(said, g)
        val denied = "no" in v
        val wants = !denied && "yes" in v
        val yes = !denied && (wants || t.genres.any { it.id in g.usually })
        return Suggestion(if (yes) "yes" else "no",
            if (denied) "You told me there is no ${g.noun}." else if (wants) "You described ${g.noun} yourself." else if (yes) "Games like this usually have ${g.noun}." else "Nothing you've described needs ${g.noun}, and adding it would dilute the focus.")
    }

    /** Re-settles gates from something the owner just said: the latest explicit word wins over an earlier answer or an inference. */
    fun applyOwnerText(p0: Project, text: String, now: Long): Project {
        var p = p0
        for (g in all) {
            val said = verdicts(text, g)
            if (said.isEmpty() || mixed(text, g)) continue
            val f = Fields.get(g.key) ?: continue
            if (!f.isRelevant(Traits(p))) continue
            val answer = said.last()
            val have = p.decision(g.key)
            // A confirmed owner decision changes only on an explicit change of mind; a passing remark is raised as a contradiction instead (see FactCoherence).
            if (have != null && have.ownerAuthored && have.status == com.hotattic.gamedesigner.core.model.DecisionStatus.CONFIRMED && have.value != answer && !explicitChange(text)) continue
            if (have?.value == answer && have.status == com.hotattic.gamedesigner.core.model.DecisionStatus.CONFIRMED) continue
            p = com.hotattic.gamedesigner.core.engine.ProjectOps.setDecision(p, g.key, answer, com.hotattic.gamedesigner.core.model.Provenance.OWNER_CORRECTION, now, raw = text.take(200))
            if (answer == "no") p = dropDependents(p, g, now)
        }
        return p
    }

    /** Gates the owner has ruled out in their own words. */
    /** What this text says about the system: "yes", "no", or null when it says nothing (or contradicts itself, which is a question for the owner). */
    fun verdictFor(text: String, g: Gate): String? = if (mixed(text, g, broad = true)) null else verdicts(text, g, broad = true).lastOrNull()

    /** Words that make a remark an instruction to change something ("actually", "after all", "I want", "add"), not a description. */
    fun explicitChange(text: String): Boolean = com.hotattic.gamedesigner.core.engine.OwnerChange.isChange(text) || Regex("(?i)\\bafter all\\b|\\b(?:i want|i'?d like|i need|please add|let'?s add|add|include|must have|should have)\\b").containsMatchIn(text)

    /** Both "no" and "yes" in one message about the same system is a contradiction the owner must settle; nothing is recorded silently. */
    private fun mixed(text: String, g: Gate, broad: Boolean = false) = verdicts(text, g, broad).let { "no" in it && "yes" in it }

    fun deniedIn(text: String): List<Gate> = all.filter { "no" in verdicts(text, it) && !mixed(text, it) }

    /** Gates the owner has asked for in their own words. */
    fun affirmedIn(text: String): List<Gate> = all.filter { "yes" in verdicts(text, it) && !mixed(text, it) }

    /** Takes back the detail answers of a system that has been ruled out, so nothing stale survives in the design. */
    fun dropDependents(p: Project, g: Gate, now: Long): Project =
        g.dependents.fold(p) { acc, k -> if (acc.decision(k) != null) acc.copy(decisions = acc.decisions - k, updatedAt = now) else acc }
}
