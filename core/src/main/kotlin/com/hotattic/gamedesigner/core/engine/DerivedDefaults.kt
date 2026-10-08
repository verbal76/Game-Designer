package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Traits

/**
 * Routine engineering decisions are Bob's to make. For every relevant derived field the owner has not touched, record the schema's
 * recommendation as a DEFAULT-provenance decision. It is implementation guidance, never an owner requirement, and the lowest rank,
 * so any owner statement replaces it and a changed foundation re-derives it.
 */
object DerivedDefaults {
    const val NOTE = "Chosen by Bob (implementation detail)"

    fun apply(p: Project, now: Long = 0L): Project {
        var next = p
        val t = Traits(p)
        for (f in Fields.all) {
            if (!f.derived || !f.isRelevant(t)) continue
            val existing = next.decision(f.key)
            when {
                existing == null -> {
                    val s = f.suggest(Traits(next)) ?: continue
                    next = ProjectOps.setDecision(next, f.key, s.value, Provenance.DEFAULT, now, DecisionStatus.CONFIRMED, NOTE)
                }
                // A default always tracks the design as it is NOW: if what the owner ruled in or out since changed Bob's own suggestion, re-derive it.
                existing.prov == Provenance.DEFAULT && existing.status == DecisionStatus.CONFIRMED -> {
                    val s = f.suggest(Traits(next))
                    if (s != null && s.value != existing.value) next = ProjectOps.setDecision(next, f.key, s.value, Provenance.DEFAULT, now, DecisionStatus.CONFIRMED, NOTE)
                }
                // An inferred-but-unconfirmed value on a derived field is simply accepted as an implementation choice.
                existing.status == DecisionStatus.PROPOSED && existing.value.isNotBlank() && !existing.ownerAuthored ->
                    next = next.copy(decisions = next.decisions + (f.key to existing.copy(status = DecisionStatus.CONFIRMED, note = existing.note.ifBlank { NOTE })))
            }
        }
        return next
    }

    /** Derived fields still at their default: the ones "refine" can offer. */
    fun refinable(p: Project): List<com.hotattic.gamedesigner.core.schema.Field> {
        val t = Traits(p)
        return Fields.all.filter { it.derived && it.isRelevant(t) && p.decision(it.key)?.let { d -> !d.ownerAuthored && d.status != DecisionStatus.DEFERRED } != false }.sortedBy { it.priority }
    }
}
