package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Field
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys

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
    /** Everything is resolved: show the plain-English design review and get the owner's approval. */
    object Review : NextStep()
    /** Everything required is resolved, audited and approved; ready to generate. */
    object Ready : NextStep()
}

/** Chooses what the Director does next. Fully deterministic; the LLM only phrases things, it never decides order. */
object InterviewPlanner {

    fun next(project: Project): NextStep {
        val c = CompletenessEngine.compute(project)
        val postponed = project.postponed.toSet()

        val open = c.missingRequired.filter { it.key !in postponed }
        // "A great five minutes": only when the owner has said little about the core loop, feel, world and failure so far.
        val five = Fields.get(Keys.FIVE_MINUTES)?.takeIf { f -> f.isRelevant(com.hotattic.gamedesigner.core.schema.Traits(project)) && project.decision(f.key) == null && f.key !in postponed }
        val first = (open + listOfNotNull(five)).minByOrNull { it.priority }
        first?.let { return NextStep.Ask(it) }

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

        return if (ReviewGate.approved(project)) NextStep.Ready else NextStep.Review
    }

    /** Next optional refinement question, if any (never blocks generation). */
    fun nextOptional(project: Project): Field? =
        CompletenessEngine.compute(project).let { c -> (c.optionalOpen.filter { it.key != Keys.FIVE_MINUTES } + c.refinable).sortedBy { it.priority } }.firstOrNull { it.key !in project.postponed }
}
