package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Field

sealed class NextStep {
    /** Ask an open field. */
    data class Ask(val field: Field) : NextStep()
    /** A value was inferred; ask the owner to confirm it. */
    data class Confirm(val field: Field, val proposedValue: String) : NextStep()
    /** Present and confirm the derived asset plan. */
    data class AssetPlanStep(val needs: List<AssetNeed>) : NextStep()
    /** Conflicts still need an informed decision. */
    data class ResolveConflicts(val conflicts: List<Conflict>) : NextStep()
    /** An optional refinement question; the owner can generate at any time. */
    data class Optional(val field: Field) : NextStep()
    /** Everything required is resolved and audited; ready to generate. */
    object Ready : NextStep()
}

/** Chooses what the Director does next. Fully deterministic; the LLM only phrases things, it never decides order. */
object InterviewPlanner {

    fun next(project: Project): NextStep {
        val c = CompletenessEngine.compute(project)
        val postponed = project.postponed.toSet()

        val open = c.missingRequired.filter { it.key !in postponed }
        open.firstOrNull()?.let { return NextStep.Ask(it) }

        c.proposed.firstOrNull { it.required && it.key !in postponed }?.let {
            return NextStep.Confirm(it, project.value(it.key).orEmpty())
        }

        // Postponed required fields come back once everything else is done.
        c.missingRequired.firstOrNull()?.let { return NextStep.Ask(it) }
        c.proposed.firstOrNull { it.required }?.let { return NextStep.Confirm(it, project.value(it.key).orEmpty()) }

        c.pendingUploads.firstOrNull()?.let { return NextStep.Ask(it) }

        if (c.unresolvedAssets.isNotEmpty()) return NextStep.AssetPlanStep(c.unresolvedAssets)

        val open2 = ConflictEngine.open(project)
        if (open2.isNotEmpty()) return NextStep.ResolveConflicts(open2)

        return NextStep.Ready
    }

    /** Next optional refinement question, if any (never blocks generation). */
    fun nextOptional(project: Project): Field? =
        CompletenessEngine.compute(project).optionalOpen.firstOrNull { it.key !in project.postponed }
}
