package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.schema.DimId
import com.hotattic.gamedesigner.core.schema.DimensionLexicon
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.SelectLexicon
import com.hotattic.gamedesigner.core.schema.Traits

/**
 * "Do I already know this?" Turns what the owner has already said (their facts, from the concept or a five-minutes answer) into
 * decisions for the dimensions they covered, so Bob never asks a question the owner already answered. Seeds are the owner's own
 * words (OWNER_EXPLICIT) and never replace an existing decision; the final plain-English review is where any misreading is caught.
 */
object DesignSeeder {
    private val textSeeds = listOf(
        Keys.CORE_LOOP to DimId.LOOP, Keys.MOVEMENT_CAMERA to DimId.MOVEMENT, Keys.PLAYER_FEELING to DimId.FEELING, Keys.WIN_LOSS to DimId.COMPLETION,
    )
    private val selectSeeds = listOf(Keys.WORLD_STRUCTURE, Keys.DIFFICULTY_FAILURE)

    /** Returns the project with seeds applied and the keys that were seeded. */
    fun seed(p0: Project, now: Long): Pair<Project, List<String>> {
        var p = p0
        val seeded = mutableListOf<String>()
        val t = Traits(p)
        for ((key, dim) in textSeeds) {
            val f = Fields.get(key) ?: continue
            if (!f.isRelevant(t) || p.decision(key) != null) continue
            val sents = DimensionLexicon.sentencesFor(p, dim)
            // One passing mention is not an answer; require real content: a dedicated sentence or at least two.
            val strong = sents.filter { it.length >= 25 }
            val chosen = if (dim == DimId.FEELING) sents.filter { Regex("(?i)\\b(feel|feeling|mood|atmosphere)\\b|tense|cozy|wonder|dread|eerie|calm|frantic|claustrophobic|oppressive").containsMatchIn(it) } else strong
            if (chosen.isEmpty()) continue
            if (dim == DimId.LOOP && chosen.size < 2 && !Regex("(?i)\\b(core|gameplay|main) loop\\b").containsMatchIn(chosen.first())) continue
            if (dim == DimId.MOVEMENT && chosen.size < 1) continue
            p = ProjectOps.setDecision(p, key, chosen.take(3).joinToString(" "), Provenance.OWNER_EXPLICIT, now, DecisionStatus.CONFIRMED, "From your description", raw = "")
            seeded += key
        }
        for (key in selectSeeds) {
            val f = Fields.get(key) ?: continue
            if (!f.isRelevant(Traits(p)) || p.decision(key) != null) continue
            val hits = p.activeFacts().mapNotNull { SelectLexicon.forField(key, it.text) }.distinct()
            val id = hits.singleOrNull() ?: continue
            if (p.isRejected(key, id)) continue
            p = ProjectOps.setDecision(p, key, id, Provenance.OWNER_EXPLICIT, now, DecisionStatus.CONFIRMED, "From your description")
            seeded += key
        }
        return p to seeded
    }
}
