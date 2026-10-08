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
    private val selectSeeds = listOf(Keys.WORLD_STRUCTURE, Keys.DIFFICULTY_FAILURE, Keys.PROGRESSION)

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
            // A feeling needs an explicit mood/feel statement; an adjective inside the pitch ("a relaxing puzzle game") is not one.
            val chosen = if (dim == DimId.FEELING) strong.filter { Regex("(?i)\\b(mood|atmosphere|feel|feeling|tone)\\b").containsMatchIn(it) } else strong
            if (chosen.isEmpty()) continue
            if (dim == DimId.LOOP && chosen.size < 2 && !chosen.any { DimensionLexicon.isLoopStatement(it) }) continue
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
        // Systems the owner ruled out in their own words ("no combat", "no crafting") close their gate, so none of their questions are asked.
        val said = (listOf(p.originalConcept) + p.activeFacts().map { it.text }).joinToString(". ")
        for (g in com.hotattic.gamedesigner.core.schema.Gates.deniedIn(said)) {
            if (p.decision(g.key) != null || !Fields.get(g.key)!!.isRelevant(Traits(p))) continue
            p = ProjectOps.setDecision(p, g.key, "no", Provenance.OWNER_EXPLICIT, now, DecisionStatus.CONFIRMED, "From your description")
            p = com.hotattic.gamedesigner.core.schema.Gates.dropDependents(p, g, now)
            seeded += g.key
        }
        for (g in com.hotattic.gamedesigner.core.schema.Gates.affirmedIn(said)) {
            if (p.decision(g.key) != null || !Fields.get(g.key)!!.isRelevant(Traits(p))) continue
            p = ProjectOps.setDecision(p, g.key, "yes", Provenance.OWNER_EXPLICIT, now, DecisionStatus.CONFIRMED, "From your description")
            seeded += g.key
        }
        // A hypothesis from the owner's words, held for confirmation: "try to reach the top" implies the win condition.
        if (p.decision(Keys.WIN_LOSS) == null && Fields.get(Keys.WIN_LOSS)?.isRelevant(Traits(p)) == true) {
            val win = Regex("(?i)\\b(?:reach|get to|make it to|climb to|get up to|arrive at)\\s+(?:the\\s+)?(top|summit|peak|end|exit|goal|finish)\\b").find(said)
            if (win != null) {
                val fall = Regex("(?i)\\b(fall(?:s|ing)?|slip(?:s|ping)?)\\b").containsMatchIn(said)
                val v = "Win by reaching the ${win.groupValues[1].lowercase()}." + if (fall) " Failure is falling, which sends the player back to the last checkpoint." else ""
                p = ProjectOps.setDecision(p, Keys.WIN_LOSS, v, Provenance.SYSTEM_INFERENCE, now, DecisionStatus.PROPOSED, "Read from your words: \"${win.value}\"")
                seeded += Keys.WIN_LOSS
            }
        }
        // What the owner said about where assets come from ("use the packs I give you, CC0 for gaps, original for the rest") is an asset
        // decision, not a remark. Only sentences that talk about assets are read, so "procedurally generated stages" is never an asset preference.
        if (p.decision(Keys.ASSET_POLICY) == null && Fields.get(Keys.ASSET_POLICY)?.isRelevant(Traits(p)) == true) {
            val ownerText = (listOf(said) + listOf(Keys.FIRST_SLICE, Keys.CORE_LOOP).mapNotNull { p.decision(it)?.takeIf { d -> d.ownerAuthored }?.value }).joinToString(". ")
            val assetSentences = ownerText.split(Regex("(?<=[.!?])\\s+")).filter { com.hotattic.gamedesigner.core.engine.AssetStrategy.assetTalk.containsMatchIn(it) }.joinToString(" ")
            val st = com.hotattic.gamedesigner.core.engine.AssetStrategy.parse(assetSentences)
            if (st != null && (st.usesSupplied || st.preferredFreeSource != null || st.byCategory.isNotEmpty())) {
                p = ProjectOps.setDecision(p, Keys.ASSET_POLICY, st.encode(), Provenance.SYSTEM_INFERENCE, now, DecisionStatus.CONFIRMED, "Read from your words about assets")
                seeded += Keys.ASSET_POLICY
            }
        }
        // The owner's own first-build scope, and what finishes it, when they already said so.
        val sliceFact = p.activeFacts().map { it.text }.firstOrNull { it.length >= 25 && Regex("(?i)\\b(prototype|first (build|version|playable)|vertical slice|slice|demo)\\b").containsMatchIn(it) }
        if (sliceFact != null && p.decision(Keys.FIRST_SLICE) == null && Fields.get(Keys.FIRST_SLICE)?.isRelevant(Traits(p)) == true) {
            p = ProjectOps.setDecision(p, Keys.FIRST_SLICE, sliceFact, Provenance.OWNER_EXPLICIT, now, DecisionStatus.CONFIRMED, "From your description"); seeded += Keys.FIRST_SLICE
            if (p.decision(Keys.DONE) == null && Regex("(?i)\\b(until|at the end|final boss|end boss|complete when|finish|ending|playable)\\b").containsMatchIn(sliceFact) && Fields.get(Keys.DONE)?.isRelevant(Traits(p)) == true) {
                p = ProjectOps.setDecision(p, Keys.DONE, sliceFact, Provenance.OWNER_EXPLICIT, now, DecisionStatus.CONFIRMED, "From your description"); seeded += Keys.DONE
            }
        }
        return p to seeded
    }
}
