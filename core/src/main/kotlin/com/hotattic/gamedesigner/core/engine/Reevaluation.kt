package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.FactStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.ReevalBaseline
import com.hotattic.gamedesigner.core.model.ReevalItem
import com.hotattic.gamedesigner.core.model.ReevalRecord
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.SourceAudit
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Traits

/**
 * Runs a saved design through the CURRENT design intelligence without repeating the interview.
 *
 * The owner's words and decisions are authoritative and are never changed here. Only what old Bob inferred or defaulted is
 * recomputed; what the owner merely accepted from an old recommendation is kept, with the newer recommendation shown beside it.
 * Nothing historical is rewritten: spec versions are untouched and the starting state is kept so the reevaluation can be discarded.
 */
object Reevaluation {

    /** Honest inventory of what the stored project can support. Never invents anything. */
    fun audit(p: Project): SourceAudit {
        val ds = p.decisions.values
        val owner = p.messages.count { it.role == Role.USER }
        return SourceAudit(
            ownerMessages = owner,
            directorMessages = p.messages.count { it.role == Role.DIRECTOR },
            verbatimConcept = p.originalConcept.isNotBlank(),
            conceptRecovered = false,
            decisionsTotal = ds.size,
            withRawAnswer = ds.count { it.rawAnswer.isNotBlank() },
            legacyProvenance = ds.count { it.provenance == null },
            ownerExplicit = ds.count { it.prov == Provenance.OWNER_EXPLICIT },
            ownerCorrection = ds.count { it.prov == Provenance.OWNER_CORRECTION },
            acceptedRecommendations = ds.count { it.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION },
            inferred = ds.count { it.prov == Provenance.SYSTEM_INFERENCE },
            defaults = ds.count { it.prov == Provenance.DEFAULT },
            rejections = p.rejected.values.sumOf { it.size },
            facts = p.facts.count { it.status == FactStatus.ACTIVE },
            retractedFacts = p.facts.count { it.status == FactStatus.RETRACTED },
            specVersions = p.versions.size,
            uploadedAssets = p.branding.values.count { it.mode == com.hotattic.gamedesigner.core.model.BrandingMode.UPLOADED },
            fidelity = if (owner > 0) "VERBATIM" else "STRUCTURED_ONLY",
            note = buildString {
                if (owner > 0) append("$owner of the owner's messages are stored word for word. ") else append("No owner messages are stored; only structured decisions survive, so nothing was re-read from the owner's words. ")
                val legacy = ds.count { it.provenance == null }
                if (legacy > 0) append("$legacy decision(s) predate provenance tracking; their authority is inferred from their stored source and marked uncertain, never upgraded.")
            }.trim(),
        )
    }

    private fun baselineOf(p: Project) = ReevalBaseline(p.decisions, p.facts, p.rejected, p.references, p.originalConcept, p.designApproval, p.pendingFieldKey, p.announcedConflicts, p.mode.name)

    /** Puts the project back exactly as it was before the reevaluation began. Spec versions were never touched. */
    fun discard(p: Project, now: Long): Project {
        val r = p.reeval ?: return p
        if (r.status != "OPEN") return p
        val b = r.baseline
        return p.copy(decisions = b.decisions, facts = b.facts, rejected = b.rejected, references = b.references, originalConcept = b.originalConcept,
            designApproval = b.designApproval, pendingFieldKey = b.pendingFieldKey, announcedConflicts = b.announcedConflicts,
            mode = b.mode?.let { m -> runCatching { com.hotattic.gamedesigner.core.model.ProjectMode.valueOf(m) }.getOrNull() } ?: p.mode,
            reeval = r.copy(status = "DISCARDED"), updatedAt = now)
    }

    /**
     * [reread] is the project after the owner's stored words were read again by the current interpreter (only safe additions made).
     * Returns the reevaluated working state with a [ReevalRecord]; follow-up questions and the plain-English review are then
     * handled by the normal Director flow.
     */
    fun run(original: Project, reread: Project, now: Long, conceptRecovered: Boolean): Project {
        val src = audit(original).copy(conceptRecovered = conceptRecovered)
        val before = CompletenessEngine.compute(original).percent
        val old = original.decisions

        // 1. Only what old Bob inferred or defaulted is reconsidered. Owner words, corrections, deferrals and accepted recommendations stay.
        var p = reread.copy(mode = if (reread.mode == com.hotattic.gamedesigner.core.model.ProjectMode.PLAYTEST_CONTINUE) com.hotattic.gamedesigner.core.model.ProjectMode.NEW_GAME else reread.mode, decisions = reread.decisions.filterValues { d -> d.ownerAuthored || d.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION || d.status == DecisionStatus.DEFERRED })
        // 2. Recompute with the current logic.
        p = DerivedDefaults.apply(DesignSeeder.seed(p, now).first, now)
        p = Reconciler.revalidate(p, now).project
        // Invariant: nothing the owner decided may differ from what they decided, whatever the newer logic did.
        for ((k, d) in old) if (d.ownerAuthored && d.status == DecisionStatus.CONFIRMED && d.value.isNotBlank() && p.decisions[k]?.value != d.value) p = p.copy(decisions = p.decisions + (k to d))

        // 3. What changed.
        val items = mutableListOf<ReevalItem>()
        var preserved = 0; var newRecs = 0; var changedRecs = 0
        fun title(k: String) = Fields.get(k)?.title ?: k
        fun show(k: String, v: String?) = v?.takeIf { it.isNotBlank() }?.let { v2 -> runCatching { com.hotattic.gamedesigner.core.director.Messages.display(p, k, v2) }.getOrDefault(v2) }
        for (k in (old.keys + p.decisions.keys).distinct().sortedBy { Fields.get(it)?.priority ?: 999 }) {
            val o = old[k]; val n = p.decisions[k]
            val ov = o?.value?.takeIf { it.isNotBlank() }; val nv = n?.value?.takeIf { it.isNotBlank() }
            when {
                o != null && o.ownerAuthored && ov != null && nv == ov -> { preserved++; items += ReevalItem("PRESERVED", k, title(k), after = show(k, nv), note = if (o.prov == Provenance.OWNER_CORRECTION) "Your correction" else "You decided this") }
                o != null && o.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION && n != null && nv == ov -> {
                    val fresh = runCatching { Fields.get(k)?.suggest?.invoke(Traits(p.copy(decisions = p.decisions - k)))?.value }.getOrNull()
                    if (fresh != null && fresh != nv) { changedRecs++; items += ReevalItem("KEPT_REC", k, title(k), before = show(k, nv), after = show(k, fresh), note = "You accepted the earlier recommendation, so it is kept. Bob's newer recommendation differs.") }
                }
                ov == null && nv != null -> { if (!n!!.ownerAuthored) newRecs++; items += ReevalItem("NEW", k, title(k), after = show(k, nv), note = if (n.ownerAuthored) "Read from your own words" else "Bob's recommendation") }
                ov != null && nv != null && ov != nv -> { if (!n!!.ownerAuthored) changedRecs++; items += ReevalItem("CHANGED", k, title(k), before = show(k, ov), after = show(k, nv), note = if (o?.ownerAuthored == true) "Owner decision" else "Old Bob's ${if (o?.prov == Provenance.DEFAULT) "default" else "inference"} replaced") }
                ov != null && nv == null && o != null && !o.ownerAuthored -> items += ReevalItem("REMOVED", k, title(k), before = show(k, ov), note = "Old Bob's ${if (o.prov == Provenance.DEFAULT) "default" else "inference"} no longer applies")
            }
        }

        // 4. Contradictions and missing build-important decisions under the current rules.
        val review = ConsistencyReview.review(p, "")
        val contradictions = review.errors.map { ReevalItem("CONTRADICTION", it.code, it.message, note = it.line.take(140)) } +
            ConflictEngine.all(p).filter { it.severity != Severity.NOTE && it.id !in p.conflictAcks.map { a -> a.conflictId } }.map { ReevalItem("CONTRADICTION", it.id, it.title, note = it.message) }
        items += contradictions
        val comp = CompletenessEngine.compute(p)
        val needs = comp.missingRequired.map { ReevalItem("NEEDS_DECISION", it.key, it.title, note = it.prompt) }
        items += needs

        val record = ReevalRecord(
            startedAt = now, status = "OPEN", fromSpec = original.versions.lastOrNull()?.number, source = src, items = items,
            preservedOwner = preserved, newRecommendations = newRecs, changedRecommendations = changedRecs, contradictions = contradictions.size,
            newQuestions = needs.size, completenessBefore = before, completenessAfter = CompletenessEngine.compute(p.copy(designApproval = null)).percent,
            baseline = baselineOf(original),
        )
        // The design changed under the owner's feet: it must be reviewed and approved again before a new spec is generated.
        // A design reevaluation always runs in design mode: playtest-feedback mode would ask what you noticed in a build that does not exist yet.
        val mode = if (p.mode == com.hotattic.gamedesigner.core.model.ProjectMode.PLAYTEST_CONTINUE) com.hotattic.gamedesigner.core.model.ProjectMode.NEW_GAME else p.mode
        return p.copy(mode = mode, designApproval = null, announcedConflicts = emptyList(), pendingFieldKey = null, reeval = record, updatedAt = now)
    }

    /** The plain-English summary shown in chat and on the reevaluation screen header. */
    fun summary(r: ReevalRecord): String = buildString {
        appendLine("REEVALUATION COMPLETE")
        appendLine("Preserved owner decisions: ${r.preservedOwner}")
        appendLine("New recommendations: ${r.newRecommendations}")
        appendLine("Changed recommendations: ${r.changedRecommendations}")
        appendLine("Contradictions found: ${r.contradictions}")
        appendLine("New questions needed: ${r.newQuestions}")
        append("Completeness: ${r.completenessBefore}% -> ${r.completenessAfter}%")
    }
}
