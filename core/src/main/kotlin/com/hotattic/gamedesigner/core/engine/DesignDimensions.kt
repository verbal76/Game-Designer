package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.schema.DimId
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits

enum class DimState(val resolved: Boolean) {
    DECIDED(true), DELEGATED(true), DISCRETION(true), UNRESOLVED(false), NOT_APPLICABLE(true)
}

/** [share] is the part of the dimension's relevant fields that is settled, so one inferred fact inside a larger dimension still counts. */
data class DimStatus(val dim: DimId, val state: DimState, val fields: List<String>, val share: Double = if (state.resolved) 1.0 else 0.0)

/**
 * Design completeness is the weighted share of BUILD-CRITICAL dimensions that are decided by the owner, knowingly delegated, or
 * safely left to implementation discretion - not the share of a fixed question list that was answered. Different projects reach
 * 100% with different numbers of questions; a rich concept needs only a few.
 */
object DesignDimensions {

    private fun fieldsOf(dim: DimId, t: Traits): List<String> = when (dim) {
        DimId.VISION -> listOf(Keys.CONCEPT, Keys.CORE_FANTASY)
        DimId.FEELING -> listOf(Keys.PLAYER_FEELING)
        DimId.LOOP -> listOf(Keys.CORE_LOOP)
        DimId.MOVEMENT -> listOf(Keys.MOVEMENT_CAMERA)
        DimId.INTERACTION -> listOf(Keys.HAS_COMBAT, Keys.HAS_ECONOMY, Keys.HAS_CRAFTING, Keys.COMBAT_MODEL, Keys.ENEMIES_BOSSES, Keys.ECONOMY, Keys.SURVIVAL_CRAFTING, Keys.AUTOMATION_SIM, Keys.CHARACTERS)
        DimId.WORLD -> listOf(Keys.WORLD_STRUCTURE)
        DimId.FAILURE -> listOf(Keys.DIFFICULTY_FAILURE)
        DimId.PROGRESSION -> listOf(Keys.PROGRESSION)
        DimId.SESSION -> listOf(Keys.SESSION_STRUCTURE)
        DimId.SLICE -> listOf(Keys.FIRST_SLICE)
        DimId.VISUAL -> listOf(Keys.ART_DIRECTION)
        DimId.AUDIO -> listOf(Keys.AUDIO)
        DimId.CONTROLS -> listOf(Keys.PLATFORMS, Keys.INPUT_METHODS)
        DimId.ASSETS -> listOf(Keys.ASSET_POLICY, Keys.BRAND_ICON, Keys.BRAND_STUDIO, Keys.BRAND_GAME_SPLASH)
        DimId.INVARIANTS -> listOf(Keys.MUST_NOT_CHANGE)
        DimId.COMPLETION -> listOf(Keys.WIN_LOSS, Keys.DONE)
        DimId.DISTRIBUTION -> listOf(Keys.STORE_PLAN)
    }

    /** Status of every applicable dimension, judged on the project with Bob's routine defaults applied. */
    fun status(project: Project): List<DimStatus> {
        val p = DerivedDefaults.apply(project)
        val t = Traits(p)
        return DimId.values().map { dim ->
            val keys = fieldsOf(dim, t).filter { k -> Fields.get(k)?.let { it.isRelevant(t) && !(it.expertOnly && t.beginner) } == true }
            if (keys.isEmpty()) return@map DimStatus(dim, DimState.NOT_APPLICABLE, emptyList())
            val states = keys.map { k ->
                val f = Fields.get(k)!!
                when (CompletenessEngine.stateOf(p, f)) {
                    FieldState.RESOLVED -> {
                        val d = p.decision(k)!!
                        when {
                            d.prov == Provenance.DEFAULT || (f.derived && !d.ownerAuthored) -> DimState.DISCRETION
                            d.prov == Provenance.OWNER_ACCEPTED_RECOMMENDATION -> DimState.DELEGATED
                            else -> DimState.DECIDED
                        }
                    }
                    FieldState.DEFERRED -> DimState.DELEGATED
                    // An optional field that nobody answered does not hold the dimension back.
                    else -> if (!f.required) DimState.DISCRETION else DimState.UNRESOLVED
                }
            }
            val state = when {
                DimState.UNRESOLVED in states -> DimState.UNRESOLVED
                states.all { it == DimState.DECIDED } -> DimState.DECIDED
                DimState.DELEGATED in states -> DimState.DELEGATED
                states.any { it == DimState.DECIDED } -> DimState.DECIDED
                else -> DimState.DISCRETION
            }
            DimStatus(dim, state, keys, if (state.resolved) 1.0 else states.count { it.resolved }.toDouble() / states.size)
        }
    }

    /**
     * Weighted resolved share. Capped at 99 until nothing blocks a coherent build AND the owner approved (or delegated approval of)
     * the plain-English design review: 100 means "ready to generate", not "we asked everything".
     */
    fun percent(project: Project): Int {
        val st = status(project).filter { it.state != DimState.NOT_APPLICABLE }
        val total = st.sumOf { it.dim.weight }
        if (total == 0) return 0
        val done = st.sumOf { it.dim.weight * it.share }
        val raw = (done * 100 / total).toInt()
        if (raw < 100) return raw.coerceAtMost(99)
        val blocked = ConflictEngine.open(project).any { it.severity == Severity.BLOCKER }
        return if (!blocked && ReviewGate.approved(project)) 100 else 99
    }

    fun unresolved(project: Project): List<DimStatus> = status(project).filter { it.state == DimState.UNRESOLVED }
}
