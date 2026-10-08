package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys

/**
 * What the player meets versus what the builder authors to make generation work. "Procedural" never means "nothing authored":
 * the safe reading of a plain "procedurally generated stages" is generation under authored rules, and which ingredients the
 * generator composes (authored modules, patterns, templates, or direct generation) stays an engineering choice unless the owner said more.
 */
enum class ProceduralMode(val label: String) {
    FULLY_AUTHORED("fully authored"),
    ASSEMBLED_FROM_AUTHORED("procedurally assembled from authored components"),
    CONSTRAINED_GENERATION("procedurally generated under authored constraints"),
    HIGHLY_GENERATIVE("highly generative"),
    HYBRID("hybrid of authored and procedural content"),
}

data class ProceduralAuthorship(val mode: ProceduralMode, val ownerNote: String?) {
    /** The authorship statement the export carries. It forbids nothing the owner did not forbid. */
    fun statement(): String = when (mode) {
        ProceduralMode.FULLY_AUTHORED -> "All content is hand-authored; there is no procedural generation."
        ProceduralMode.ASSEMBLED_FROM_AUTHORED -> "Authorship: the course is assembled at runtime from authored components (modules, chunks, patterns or templates) that the builder authors, joined by authored rules for fairness, difficulty, pacing and recovery. Authoring those components is expected."
        ProceduralMode.CONSTRAINED_GENERATION -> "Authorship: generation runs under authored constraints (fairness, difficulty, pacing, recovery, validation, traversal grammar). Authored modules, patterns, templates, grammars and rules are all allowed ingredients, and so is generating geometry directly; which mix to use is an engineering decision. The owner has not forbidden hand-authored ingredients."
        ProceduralMode.HIGHLY_GENERATIVE -> "Authorship: the owner wants content generated as directly as practical. Do not require authored chunks; the invariants below still bind the generator."
        ProceduralMode.HYBRID -> "Authorship: some content is authored and some is procedural${ownerNote?.let { " (the owner said: \"$it\")" } ?: ""}. Keep the authored parts exactly as designed and apply generation only where the owner wants it; the invariants below bind the generated parts."
    }

    companion object {
        private val assembled = Regex("(?i)\\b(chunks?|modules?|modular|templates?|prefabs?|pieces?|segments?|building blocks?|hand-?(?:made|built|crafted|authored) (?:sections?|pieces?|parts?)|authored (?:sections?|pieces?|patterns?))\\b")
        private val hybrid = Regex("(?i)\\b(hybrid|some (?:levels|stages|parts|sections) (?:are )?(?:hand|authored|handcrafted|designed)|(?:hand-?(?:made|crafted|authored|designed)|authored|handcrafted)\\b[^.]{0,60}\\b(?:and|but|plus|with)\\b[^.]{0,60}\\b(?:procedural|generated|random)|(?:procedural|generated)\\b[^.]{0,60}\\b(?:and|but|plus|with)\\b[^.]{0,60}\\b(?:hand-?(?:made|crafted|authored|designed)|authored|handcrafted)|boss(?:es)? (?:levels?|stages?) (?:are )?(?:hand|authored))")
        private val generative = Regex("(?i)\\b(fully|completely|entirely|purely|infinitely|endlessly) (?:procedural|generated|random)|\\bendless(?:ly)? generated\\b|\\bno (?:hand-?(?:made|authored|crafted)|authored) (?:content|levels?|pieces?|chunks?)\\b")

        fun of(p: Project): ProceduralAuthorship? {
            val world = p.value(Keys.WORLD_STRUCTURE) ?: return null
            if (world != "procedural_stages") return ProceduralAuthorship(ProceduralMode.FULLY_AUTHORED, null)
            val owner = (listOf(p.originalConcept) + p.activeFacts().map { it.text } + listOfNotNull(p.decision(Keys.CORE_LOOP), p.decision(Keys.FIRST_SLICE), p.decision(Keys.WORLD_STRUCTURE))
                .filter { it.ownerAuthored }.map { it.value + " " + it.rawAnswer }).joinToString(". ")
            val sentences = owner.split(Regex("(?<=[.!?])\\s+"))
            hybrid.find(owner)?.let { m -> return ProceduralAuthorship(ProceduralMode.HYBRID, sentences.firstOrNull { m.value in it }?.trim()?.take(200)) }
            if (generative.containsMatchIn(owner)) return ProceduralAuthorship(ProceduralMode.HIGHLY_GENERATIVE, null)
            val procSentences = sentences.filter { Regex("(?i)procedural|generated|random").containsMatchIn(it) }
            if (procSentences.any { assembled.containsMatchIn(it) }) return ProceduralAuthorship(ProceduralMode.ASSEMBLED_FROM_AUTHORED, null)
            return ProceduralAuthorship(ProceduralMode.CONSTRAINED_GENERATION, null)
        }
    }
}
