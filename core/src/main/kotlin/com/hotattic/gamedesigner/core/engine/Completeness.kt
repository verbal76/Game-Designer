package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.BrandingMode
import com.hotattic.gamedesigner.core.model.BrandingSlot
import com.hotattic.gamedesigner.core.model.Category
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Field
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Traits

enum class FieldState { RESOLVED, PROPOSED, DEFERRED, PENDING_UPLOAD, MISSING }

data class CategoryProgress(val category: Category, val resolved: Int, val total: Int) {
    val fraction: Float get() = if (total == 0) 1f else resolved.toFloat() / total
}

data class Completeness(
    val categories: List<CategoryProgress>,
    val resolvedRequired: Int,
    val totalRequired: Int,
    val missingRequired: List<Field>,
    val proposed: List<Field>,
    val deferred: List<Field>,
    val pendingUploads: List<Field>,
    val unresolvedAssets: List<AssetNeed>,
    val optionalOpen: List<Field>,
) {
    /** Real percentage of concrete required items resolved - not a cosmetic number. */
    val percent: Int get() = if (totalRequired == 0) 0 else (resolvedRequired * 100 / totalRequired)
    val readyForGeneration: Boolean get() = missingRequired.isEmpty() && pendingUploads.isEmpty() && proposed.none { it.required }
}

object CompletenessEngine {

    fun stateOf(project: Project, field: Field): FieldState {
        val d = project.decision(field.key) ?: return FieldState.MISSING
        if (d.value.isBlank() && d.status != DecisionStatus.DEFERRED) return FieldState.MISSING
        if (d.status == DecisionStatus.DEFERRED) return FieldState.DEFERRED
        if (d.status == DecisionStatus.PROPOSED) return FieldState.PROPOSED
        val slot = Keys.brandingKeyForSlot.entries.firstOrNull { it.value == field.key }?.key
        if (slot != null && d.value == "upload") {
            val b = project.branding[slot]
            if (b == null || b.mode != BrandingMode.UPLOADED || b.localFile.isNullOrBlank()) return FieldState.PENDING_UPLOAD
        }
        return FieldState.RESOLVED
    }

    fun relevantFields(project: Project): List<Field> {
        val t = Traits(project)
        return Fields.all.filter { it.isRelevant(t) }.filter { !(it.expertOnly && t.beginner) }.sortedBy { it.priority }
    }

    fun compute(project: Project): Completeness {
        val fields = relevantFields(project)
        val required = fields.filter { it.required }
        val states = required.associateWith { stateOf(project, it) }
        val resolvedReq = states.count { it.value == FieldState.RESOLVED || it.value == FieldState.DEFERRED }
        val missing = required.filter { states[it] == FieldState.MISSING }
        val proposed = fields.filter { stateOf(project, it) == FieldState.PROPOSED }
        val deferred = fields.filter { stateOf(project, it) == FieldState.DEFERRED }
        val pending = fields.filter { stateOf(project, it) == FieldState.PENDING_UPLOAD }
        val unresolvedAssets = AssetPlan.unresolved(project)
        val optionalOpen = fields.filter { !it.required && stateOf(project, it) == FieldState.MISSING }

        val cats = Category.values().mapNotNull { c ->
            val inCat = required.filter { it.category == c }
            val assetNeeds = if (c == Category.ASSETS) AssetPlan.needs(Traits(project)) else emptyList()
            val total = inCat.size + assetNeeds.size
            if (total == 0) return@mapNotNull null
            val done = inCat.count { states[it] == FieldState.RESOLVED || states[it] == FieldState.DEFERRED } +
                assetNeeds.count { n -> project.assets.any { it.needId == n.id } }
            CategoryProgress(c, done, total)
        }
        return Completeness(cats, resolvedReq, required.size, missing, proposed, deferred, pending, unresolvedAssets, optionalOpen)
    }
}
