package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.engine.AssetPlan
import com.hotattic.gamedesigner.core.engine.AuditEngine
import com.hotattic.gamedesigner.core.engine.CompletenessEngine
import com.hotattic.gamedesigner.core.engine.DerivedDefaults
import com.hotattic.gamedesigner.core.engine.ReviewGate
import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.engine.ConsistencyReview
import com.hotattic.gamedesigner.core.engine.Reconciler
import com.hotattic.gamedesigner.core.engine.ReviewFinding
import com.hotattic.gamedesigner.core.engine.ReviewLevel
import com.hotattic.gamedesigner.core.engine.ReviewReport
import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.FeedbackStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.SpecVersion
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.schema.Fields

/** Append-only version history. A generated spec is never overwritten; a new version is always appended. */
object SpecVersioning {

    fun suggestedKind(project: Project): VersionKind = when {
        project.versions.isEmpty() -> VersionKind.INITIAL
        project.mode == ProjectMode.PLAYTEST_CONTINUE || project.feedback.any { it.status == FeedbackStatus.OPEN } -> VersionKind.PLAYTEST_REPAIR
        else -> VersionKind.REVISION
    }

    data class Generation(val project: Project, val review: ReviewReport, val notes: List<String>) {
        val blocked get() = !review.clean
    }

    /**
     * The only path to an export: revalidate dependencies, settle contradictions by owner precedence, generate, then run the
     * mandatory consistency review. If errors remain the project is returned unchanged (no version appended) with the findings.
     */
    fun generate(project: Project, kind: VersionKind, nowMillis: Long, nowIso: String, label: String? = null, requireApproval: Boolean = true): Generation {
        val notes = mutableListOf<String>()
        // The authoritative spec is only generated from a design the owner approved (or delegated approval of).
        val approval = project.designApproval
        if (requireApproval && project.mode == ProjectMode.NEW_GAME && !ReviewGate.approved(project))
            return Generation(project, ReviewReport(listOf(ReviewFinding(ReviewLevel.ERROR, "review_not_approved", "The owner has not approved the plain-English design review yet."))), notes)
        val rec = Reconciler.revalidate(DerivedDefaults.apply(project, nowMillis), nowMillis); notes += rec.notices
        val (settled, stale) = com.hotattic.gamedesigner.core.engine.DesignCoherence.reconcile(rec.project, nowMillis); notes += stale
        val (resolved0, fixes) = ConsistencyReview.resolve(settled, nowMillis); notes += fixes
        // Corrective clean-ups do not make the owner's approval stale.
        val resolved = if (approval != null) resolved0.copy(designApproval = approval.copy(fingerprint = ReviewGate.fingerprint(resolved0))) else resolved0
        val withAssets = resolved.copy(assets = resolved.assets + AssetPlan.resolveMissing(resolved, nowMillis))
        val number = (project.versions.maxOfOrNull { it.number } ?: 0) + 1
        val reevalOpen = project.reeval?.status == "OPEN"
        val lbl = label ?: if (reevalOpen) "Reevaluation" else kind.label
        val audit = AuditEngine.audit(withAssets)
        val md = ClaudeMdGenerator.generate(withAssets, number, lbl, nowIso, audit)
        val prompt = MasterPromptGenerator.generate(withAssets, number)
        val assetsMd = ExportPackage.assetsMarkdown(withAssets)
        // Every authoritative document is checked against the structured state and against each other's claims.
        val review = ConsistencyReview.reviewAll(withAssets, mapOf("CLAUDE.md" to md, "MASTER_PROMPT.md" to prompt, "ASSETS.md" to assetsMd))
        // Contradictory conflicts that remain are also blocking.
        val conflictFindings = ConflictEngine.all(withAssets).filter { it.id == "combat_turn_based_vs_real_time" }
            .map { ReviewFinding(ReviewLevel.ERROR, it.id, it.message) }
        val coherence = com.hotattic.gamedesigner.core.engine.DesignCoherence.check(withAssets, mapOf("CLAUDE.md" to md, "MASTER_PROMPT.md" to prompt, "ASSETS.md" to assetsMd))
        val report = ReviewReport(review.findings + conflictFindings + coherence)
        if (!report.clean) return Generation(project, report, notes)
        val readiness = CompletenessEngine.compute(withAssets).percent
        val v = SpecVersion(number, kind, lbl, nowMillis, md, prompt, withAssets.decisions, readiness, audit.summary())
        // Incorporate open feedback: it is now part of this version.
        val fb = withAssets.feedback.map { if (it.status == FeedbackStatus.OPEN) it.copy(status = FeedbackStatus.INCORPORATED) else it }
        // Approving a reevaluation is what appends the new spec version; the old versions stay exactly as they were.
        val reeval = withAssets.reeval?.let { if (it.status == "OPEN") it.copy(status = "APPROVED", approvedSpec = number) else it }
        return Generation(withAssets.copy(versions = withAssets.versions + v, feedback = fb, reeval = reeval, updatedAt = nowMillis), report, notes)
    }

    /** Generates a new version and appends it; returns the project unchanged if the consistency review blocks export. */
    fun createVersion(project: Project, kind: VersionKind, nowMillis: Long, nowIso: String, label: String? = null): Project =
        generate(project, kind, nowMillis, nowIso, label).project

    /** Findings the owner must resolve before an export is possible (empty when generation would succeed). */
    fun preflight(project: Project, nowMillis: Long): ReviewReport = generate(project, VersionKind.REVISION, nowMillis, "preflight", requireApproval = false).review

    data class DecisionChange(val key: String, val title: String, val before: String?, val after: String?)

    fun diff(older: SpecVersion?, newer: SpecVersion): List<DecisionChange> {
        val a = older?.decisions.orEmpty()
        val b = newer.decisions
        return (a.keys + b.keys).distinct().mapNotNull { k ->
            val x: Decision? = a[k]
            val y: Decision? = b[k]
            if (x?.value == y?.value) null else DecisionChange(k, Fields.get(k)?.title ?: k, x?.value, y?.value)
        }.sortedBy { Fields.get(it.key)?.priority ?: 999 }
    }
}
