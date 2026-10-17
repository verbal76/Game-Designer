package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.director.Messages
import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.FactStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.schema.Fields

/** One line of the before/after table: the saved design on the left, the reevaluated design on the right. */
data class CompareRow(
    val key: String,
    val title: String,
    val group: String,
    val before: String?,
    val after: String?,
    /** SAME, CHANGED, NEW or REMOVED. */
    val status: String,
    /** Who stands behind each side: "You", "You (correction)", "Accepted from Bob", "Bob", "" when absent. */
    val beforeBy: String = "",
    val afterBy: String = "",
)

/** Builds the side-by-side view of a reevaluation from the stored baseline and the current working design. Pure and deterministic. */
object ReevalCompare {

    fun by(d: Decision?): String = when {
        d == null -> ""
        d.prov == Provenance.OWNER_CORRECTION -> "You (correction)"
        d.prov == Provenance.OWNER_EXPLICIT -> "You"
        d.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION -> "Accepted from Bob"
        else -> "Bob"
    }

    /** Every decision, plus the owner's stored statements, as a before/after table. Empty when there is no reevaluation. */
    fun rows(p: Project): List<CompareRow> {
        val r = p.reeval ?: return emptyList()
        val old = r.baseline.decisions
        val cur = p.decisions
        fun show(k: String, d: Decision?): String? = d?.value?.takeIf { it.isNotBlank() && d.status != com.hotattic.gamedesigner.core.model.DecisionStatus.DEFERRED }
            ?.let { v -> runCatching { Messages.display(p, k, v) }.getOrDefault(v) }
        val out = mutableListOf<CompareRow>()
        for (k in (old.keys + cur.keys).distinct().sortedBy { Fields.get(it)?.priority ?: 999 }) {
            val f = Fields.get(k) ?: continue
            val o = old[k]; val n = cur[k]
            val ov = show(k, o); val nv = show(k, n)
            if (ov == null && nv == null) continue
            val status = when {
                ov == null -> "NEW"
                nv == null -> "REMOVED"
                o!!.value == n!!.value -> "SAME"
                else -> "CHANGED"
            }
            out += CompareRow(k, f.title, f.category.name.lowercase().replaceFirstChar { it.uppercase() }, ov, nv, status, by(o), by(n))
        }
        // What the owner said in their own words: statements added or withdrawn.
        val oldFacts = r.baseline.facts.filter { it.status == FactStatus.ACTIVE }.map { it.text }.toSet()
        val newFacts = p.activeFacts().map { it.text }.toSet()
        for (t in oldFacts + newFacts) {
            val status = when { t in oldFacts && t in newFacts -> "SAME"; t in newFacts -> "NEW"; else -> "REMOVED" }
            if (status != "SAME") out += CompareRow("fact:" + t.hashCode(), "Your statement", "Your words", t.takeIf { it in oldFacts }, t.takeIf { it in newFacts }, status, if (t in oldFacts) "You" else "", if (t in newFacts) "You" else "")
        }
        return out
    }

    fun counts(rows: List<CompareRow>): Map<String, Int> = rows.groupingBy { it.status }.eachCount()
}
