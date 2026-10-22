package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys

/**
 * What the owner explicitly ruled out, in their own words ("no inventory, currencies or skill trees"). Recorded once, shown in the spec as the
 * owner's decision, and checked so no generated document reintroduces it. A later explicit "add an inventory" lifts the exclusion (the latest word wins).
 */
object Exclusions {
    data class Exclusion(val id: String, val label: String, val evidence: String, /** What a document would say if it reintroduced the system. */ val docPattern: Regex)

    private class Item(val id: String, val label: String, val words: Regex, val doc: Regex)

    private val vocabulary = listOf(
        Item("inventory", "Inventory", Regex("inventor(?:y|ies)|backpacks?|item slots?"), Regex("(?i)\\b(inventor(?:y|ies)|backpacks?|item slots?)\\b")),
        Item("equipment", "Equipment and loadouts", Regex("equipment|loadouts?|gear|armou?r"), Regex("(?i)\\b(equipment|loadouts?|armou?r|equip slots?)\\b")),
        Item("currency", "Currency, shops and trading", Regex("currenc(?:y|ies)|money|coins?|gold|shops?|vendors?|trading|economy"), Regex("(?i)\\b(currenc(?:y|ies)|vendors?|shops?|gold coins?|trading|economy)\\b")),
        Item("skill_tree", "Skill trees", Regex("skill trees?|talent trees?"), Regex("(?i)\\b(skill trees?|talent trees?)\\b")),
        Item("quests", "Quests", Regex("quests?|side quests?"), Regex("(?i)\\b(quests?|quest log)\\b")),
        Item("crafting", "Crafting", Regex("crafting"), Regex("(?i)\\b(crafting)\\b")),
        Item("xp_levels", "XP and levelling", Regex("xp|experience points|levell?ing|level[- ]ups?"), Regex("(?i)\\b(xp|experience points|level[- ]ups?|levell?ing)\\b")),
    )

    private val negation = Regex("(?i)\\b(?:no|without|zero|not any|(?:don'?t|do not|doesn'?t|does not|never|won'?t|will not)\\s+(?:want|need|have|include|use|add|require)|no need for|skip|leave out)\\b([^.;!?\\n]*)")
    private val stopTail = Regex("(?i)\\b(?:but|however|except|though|although|yet)\\b")

    private fun sources(p: Project): List<String> = buildList {
        add(p.originalConcept)
        p.activeFacts().forEach { add(it.text) }
        p.decision(Keys.MUST_NOT_CHANGE)?.takeIf { it.ownerAuthored }?.let { add(it.value) }
        p.decision(Keys.EXCLUSIONS)?.takeIf { it.ownerAuthored }?.let { add(it.value) }
    }

    fun fromOwner(p: Project): List<Exclusion> {
        val out = linkedMapOf<String, Exclusion>()
        for (src in sources(p)) for (sentence in src.split(Regex("(?<=[.!?\\n])\\s+"))) {
            val low = sentence.lowercase()
            for (m in negation.findAll(low)) {
                val tail = m.groupValues[1].let { t -> stopTail.find(t)?.let { t.substring(0, it.range.first) } ?: t }.take(120)
                for (item in vocabulary) if (Regex("\\b(?:${item.words.pattern})\\b").containsMatchIn(tail)) out[item.id] = Exclusion(item.id, item.label, sentence.trim().take(160), item.doc)
            }
            for (item in vocabulary) if (item.id in out && Regex("\\b(?:add|include|want|need|with|have|has|let'?s have)\\s+(?:an?\\s+|some\\s+|the\\s+)?(?:\\w+\\s+){0,2}(?:${item.words.pattern})\\b").containsMatchIn(low) && !negation.containsMatchIn(low)) out.remove(item.id)
        }
        return out.values.toList()
    }

    /** True when a genre checklist item is exactly the kind of system the owner excluded. */
    fun excludes(p: Project, systemName: String, detail: String = ""): Boolean {
        val text = "$systemName $detail".lowercase()
        return fromOwner(p).any { it.docPattern.containsMatchIn(text) }
    }
}
