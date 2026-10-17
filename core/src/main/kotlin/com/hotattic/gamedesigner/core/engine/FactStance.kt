package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.director.Messages
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.DesignFact
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys

enum class Stance { REQUEST, REJECTION, CONSIDERATION, SUPERSEDED }

/** How a recorded owner sentence must be weighed in a specification: a wish, a rejection, a mere possibility, or wording the owner later replaced. */
data class FactReading(val stance: Stance, val note: String?)

object FactStance {
    private val rejection = Regex("(?i)\\b(?:don'?t|do not|doesn'?t|does not|no|not|without|never|nothing|skip|exclude)\\b")
    private val singleValue = listOf(Keys.DIMENSION, Keys.ART_DIRECTION, Keys.PERSPECTIVE, Keys.ORIENTATION)

    fun of(p: Project, f: DesignFact): FactReading {
        // Wording the owner later replaced by an explicit decision ("3D low poly", then "make it 2D pixel art") is history, not a requirement.
        val said = DecisionExtractor.extract(f.text).values
        val replaced = singleValue.mapNotNull { k ->
            val v = said[k] ?: return@mapNotNull null
            val d = p.decision(k)?.takeIf { it.ownerAuthored && it.status == DecisionStatus.CONFIRMED && it.updatedAt > f.at && it.value != v } ?: return@mapNotNull null
            Messages.display(p, k, d.value)
        }
        if (replaced.isNotEmpty()) return FactReading(Stance.SUPERSEDED, "superseded by the owner's later decision: ${replaced.joinToString(", ")} - do not implement the old wording")
        val hedged = Hedge.hedgedClauses(f.text)
        if (hedged.isNotEmpty()) return FactReading(Stance.CONSIDERATION, "only a possibility the owner mentioned, NOT a requirement: \"${hedged.joinToString("; ") { it.trim() }}\"")
        if (rejection.containsMatchIn(f.text)) return FactReading(Stance.REJECTION, "owner exclusion or negative statement - do not build what it rules out")
        return FactReading(Stance.REQUEST, null)
    }
}
