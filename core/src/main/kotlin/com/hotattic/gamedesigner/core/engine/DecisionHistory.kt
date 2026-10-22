package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.DecisionRevision
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance

/**
 * Keeps what the owner had decided when a later change replaces or removes it. Applied once, at the single commit point of the open project, by
 * comparing the committed project with the one about to be saved, so every mutation path (a correction, a ruled-out system, a reconciliation)
 * is covered without touching any of them. Only owner-authored or owner-accepted confirmed decisions are kept: guesses and defaults are not intent.
 */
object DecisionHistory {
    private fun worthKeeping(p: Provenance, s: DecisionStatus) = s == DecisionStatus.CONFIRMED && (p.ownerAuthored || p == Provenance.OWNER_ACCEPTED_RECOMMENDATION)

    fun record(before: Project, after: Project): Project {
        if (before.decisions === after.decisions) return after
        val added = mutableListOf<DecisionRevision>()
        for ((k, old) in before.decisions) {
            if (old.value.isBlank() || !worthKeeping(old.prov, old.status)) continue
            val now = after.decisions[k]
            if (now != null && now.value == old.value) continue
            val rev = DecisionRevision(k, old.value, old.prov, old.status, old.updatedAt, after.updatedAt, old.rawAnswer, now?.value)
            if (after.decisionHistory.none { it.key == k && it.value == old.value && it.decidedAt == old.updatedAt }) added += rev
        }
        return if (added.isEmpty()) after else after.copy(decisionHistory = after.decisionHistory + added)
    }
}
