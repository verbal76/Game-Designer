package com.hotattic.gamedesigner.core.generate

import com.hotattic.gamedesigner.core.engine.AssetPlan
import com.hotattic.gamedesigner.core.engine.AuditEngine
import com.hotattic.gamedesigner.core.engine.CompletenessEngine
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

    /** Generates a new version and appends it. Missing asset records are first resolved from the default asset plan. */
    fun createVersion(project: Project, kind: VersionKind, nowMillis: Long, nowIso: String, label: String? = null): Project {
        val withAssets = project.copy(assets = project.assets + AssetPlan.resolveMissing(project, nowMillis))
        val number = (project.versions.maxOfOrNull { it.number } ?: 0) + 1
        val lbl = label ?: kind.label
        val audit = AuditEngine.audit(withAssets)
        val md = ClaudeMdGenerator.generate(withAssets, number, lbl, nowIso, audit)
        val prompt = MasterPromptGenerator.generate(withAssets, number)
        val readiness = CompletenessEngine.compute(withAssets).percent
        val v = SpecVersion(number, kind, lbl, nowMillis, md, prompt, withAssets.decisions, readiness, audit.summary())
        // Incorporate open feedback: it is now part of this version.
        val fb = withAssets.feedback.map { if (it.status == FeedbackStatus.OPEN) it.copy(status = FeedbackStatus.INCORPORATED) else it }
        return withAssets.copy(versions = withAssets.versions + v, feedback = fb, updatedAt = nowMillis)
    }

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
