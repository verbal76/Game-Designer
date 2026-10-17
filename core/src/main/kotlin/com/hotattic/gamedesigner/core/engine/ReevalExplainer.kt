package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.director.Messages
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms

/**
 * The reevaluation result in plain language, for any project: what Bob reviewed, what stayed the same, what he suggests, what still needs the owner, and what
 * each button does. Pure and derived from the stored [com.hotattic.gamedesigner.core.model.ReevalRecord]; it records nothing and changes nothing.
 * The same underlying issue is never counted twice: findings about one decision are grouped and the grouping is explained.
 */
object ReevalExplainer {
    enum class Kind(val label: String, val meaning: String) {
        RECOMMENDATION("Recommendation", "Bob suggests adding or changing something. It only happens if you approve it."),
        CONFLICT("Conflict", "Two things in the design disagree. Bob cannot write a reliable spec until you settle it."),
        QUESTION("Unanswered question", "A decision the spec needs that has no answer yet."),
        INFO("For your information", "Nothing is wrong. Bob is telling you something so you can decide whether it matters."),
    }

    data class Attention(
        val key: String, val kind: Kind, val title: String, val explanation: String, val why: String, val relation: String, val action: String,
        val deferrable: Boolean, val isNew: Boolean, val previously: String?, val choices: List<String>, val ifChanged: String?,
    ) { val needsOwner get() = kind == Kind.CONFLICT || kind == Kind.QUESTION }

    data class Action(val label: String, val explanation: String)

    data class Summary(
        val headline: String, val reviewed: String, val unchanged: String,
        val adds: List<String>, val changes: List<String>, val foundInWords: List<String>,
        val attention: List<Attention>, val groupingNote: String?,
        val completeness: String, val saved: String, val primary: Action, val secondary: Action?,
    ) {
        val needsOwner get() = attention.count { it.needsOwner }
        val glossary get() = Kind.values().map { it.label to it.meaning }
    }

    fun summarize(p: Project): Summary? {
        val r = p.reeval ?: return null
        val baseline = p.copy(decisions = r.baseline.decisions, facts = r.baseline.facts, rejected = r.baseline.rejected, references = r.baseline.references, designApproval = r.baseline.designApproval)
        val oldConflicts = runCatching { ConflictEngine.all(baseline).map { it.id }.toSet() }.getOrDefault(emptySet())
        val oldReviewCodes = runCatching { ConsistencyReview.review(baseline, "").errors.map { it.code }.toSet() }.getOrDefault(emptySet())
        val oldMissing = runCatching { CompletenessEngine.compute(baseline).missingRequired.map { it.key }.toSet() }.getOrDefault(emptySet())
        val conflicts = runCatching { ConflictEngine.all(p).associateBy { it.id } }.getOrDefault(emptyMap())
        fun show(k: String) = p.decision(k)?.value?.takeIf { it.isNotBlank() }?.let { runCatching { Messages.display(p, k, it) }.getOrDefault(it) }
        fun titleOf(k: String) = Fields.get(k)?.title ?: k
        // A value that is about to be applied may not be a current option yet (Windows before it is added), so platform ids are named explicitly.
        fun pretty(k: String, v: String) = when (k) {
            Keys.PLATFORMS -> v.split(com.hotattic.gamedesigner.core.model.Decision.LIST_SEPARATOR).filter { it.isNotBlank() }.joinToString(", ") { Platforms.labels[it] ?: it }
            Keys.PRIMARY_PLATFORM -> Platforms.labels[v] ?: v
            else -> runCatching { Messages.display(p, k, v) }.getOrDefault(v)
        }

        val findings = r.items.filter { it.kind in setOf("CONTRADICTION", "NEEDS_DECISION", "KEPT_REC") }.map { it ->
            when (it.kind) {
                "CONTRADICTION" -> {
                    val c = conflicts[it.key]
                    if (c != null) {
                        val prior = c.affectedKeys.mapNotNull { k -> show(k)?.let { v -> "${titleOf(k)}: $v" } }
                        val choices = c.alternatives.map { a -> a.label } + (if (c.overridable) listOf("Keep it as it is, knowing about the disagreement") else emptyList())
                        val first = c.alternatives.firstOrNull()
                        Attention(it.key, Kind.CONFLICT, c.title, c.message, "Bob cannot write a spec that Claude Code can follow while the design disagrees with itself; it would have to guess.",
                            if (prior.isEmpty()) "It does not concern a recorded decision." else "Concerns your recorded decisions: " + prior.joinToString("; ") + ".",
                            "Choose how to settle it. " + c.recommendation, c.severity != Severity.BLOCKER, it.key !in oldConflicts,
                            prior.takeIf { x -> x.isNotEmpty() }?.joinToString("; "), choices,
                            first?.let { f -> "Choosing \"${f.label}\" would set: " + f.changes.entries.joinToString("; ") { (k, v) -> "${titleOf(k)} = ${pretty(k, v)}" } + ". Nothing else changes." })
                    } else Attention(it.key, Kind.CONFLICT, it.title, it.note.ifBlank { it.title }, "Bob cannot write a spec that Claude Code can follow while the design disagrees with itself; it would have to guess.",
                        "A consistency check found it in the stored design.", "Bob will explain it and offer a way to settle it.", false, it.key !in oldReviewCodes, null, emptyList(), null)
                }
                "NEEDS_DECISION" -> {
                    val f = Fields.get(it.key)
                    val related = f?.derivedFrom.orEmpty().mapNotNull { k -> show(k)?.let { v -> "${titleOf(k)}: $v" } }
                    Attention(it.key, Kind.QUESTION, it.title, it.note.ifBlank { "This decision has no answer yet." }, f?.why?.takeIf { w -> w.isNotBlank() } ?: "The spec needs an answer here.",
                        if (related.isEmpty()) "It does not depend on a decision you have already made." else "It follows from: " + related.joinToString("; ") + ".",
                        "Answer it, or tell Bob to choose for you. It cannot be skipped, because the spec needs an answer.", false, it.key !in oldMissing, null, emptyList(), null)
                }
                else -> Attention(it.key, Kind.INFO, it.title, it.note, "You accepted the earlier recommendation, so it is kept. Bob's newer one differs.",
                    "It concerns your accepted choice for ${titleOf(it.key)}.", "Nothing is required. Switch only if you prefer Bob's newer suggestion.", true, true, it.before,
                    listOfNotNull(it.before?.let { b -> "Keep: $b" }, it.after?.let { a -> "Switch to: $a" }), null)
            }
        }
        // One underlying decision, one entry: findings about the same key are merged so nothing is counted twice.
        val groups = findings.groupBy { it.key }
        val attention = groups.values.map { g -> g.first().let { a -> if (g.size > 1) a.copy(relation = a.relation + " (${g.size} related findings are grouped here.)") else a } }
        val grouping = if (findings.size > attention.size) "${findings.size} findings were grouped into ${attention.size} items because several concern the same decision." else null

        val adds = r.items.filter { it.kind == "NEW" && it.note.startsWith("Bob") }.map { "${it.title}: ${it.after}" }
        val foundInWords = r.items.filter { it.kind == "NEW" && !it.note.startsWith("Bob") }.map { "${it.title}: ${it.after}" }
        val changes = r.items.filter { it.kind == "CHANGED" || it.kind == "REMOVED" }.map { if (it.kind == "REMOVED") "${it.title}: ${it.before} no longer applies (Bob's earlier guess)" else "${it.title}: ${it.before} → ${it.after}" + if (it.note.startsWith("Owner")) " (this differs from your decision, which is kept)" else " (replaces Bob's earlier guess)" }
        val owner = attention.count { it.needsOwner }
        val headline = when {
            attention.isEmpty() && adds.isEmpty() && changes.isEmpty() -> "Nothing needs your attention. Your design agrees with Bob's newest design rules."
            else -> buildString {
                append(if (attention.isEmpty()) "Nothing is wrong with your design" else "${attention.size} item(s) need your attention")
                if (adds.size + changes.size > 0) append(", and Bob has ${adds.size + changes.size} suggestion(s)")
                append(".")
            }
        }
        val total = r.source.decisionsTotal
        val reviewed = "Bob re-read your ${r.source.ownerMessages} message(s) and checked $total saved decision(s) against his current design rules."
        val unchanged = "${r.preservedOwner} of your own decisions are exactly as you made them. ${(total - r.preservedOwner - changes.size).coerceAtLeast(0)} of Bob's own earlier answers are unchanged."
        val completeness = "Completeness ${r.completenessBefore}% → ${r.completenessAfter}%. It measures how many of the build-critical decisions have an answer (weighted by importance), not how many concerns are open. " +
            if (r.completenessAfter >= 99) "99% is the ceiling until no blocking conflict is open and you approve the design review, so a design at 99% can still have items to settle." else "It reaches 100% only after no blocking conflict is open and you approve the design review."
        val saved = when (r.status) {
            "APPROVED" -> "Approved: Spec v${r.approvedSpec} was created. Spec v${r.fromSpec ?: "-"} is still available."
            "DISCARDED" -> "Discarded: your design is exactly as it was before."
            else -> "Your own decisions are unchanged. Bob recomputed his earlier guesses in a working copy; the original is kept, and Discard puts everything back exactly as it was. Nothing becomes your design until you approve it."
        }
        val primary = when (r.status) {
            "APPROVED" -> Action("Open the spec", "Shows the new spec version.")
            "OPEN" -> if (owner > 0) Action("Review $owner item${if (owner == 1) "" else "s"} with Bob", "Bob will walk you through the findings one at a time. Your saved design will not change until you approve the proposed changes.")
                else if (adds.size + changes.size > 0) Action("Review ${adds.size + changes.size} suggestion${if (adds.size + changes.size == 1) "" else "s"} with Bob", "Bob shows what he would add or change, one at a time. Nothing is applied until you approve; approving creates the next spec version and keeps the current one.")
                else Action("Review and approve the design", "Nothing needs your decision. Approving creates the next spec version and keeps the current one.")
            else -> Action("Back to the design", "Returns to the conversation. Nothing changes.")
        }
        val secondary = if (r.status == "OPEN") Action("Discard this reevaluation", "Puts everything back exactly as it was before. Nothing is lost.") else null
        return Summary(headline, reviewed, unchanged, adds, changes, foundInWords, attention, grouping, completeness, saved, primary, secondary)
    }
}
