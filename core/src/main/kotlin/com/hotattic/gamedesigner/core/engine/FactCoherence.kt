package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms

/**
 * Detects what the owner said that the structured design does not reflect: a recorded fact that is newer than the decision it
 * changes and disagrees with it. There is no second source of truth here: facts and decisions already exist, and this only compares
 * them. An instruction that a later owner decision superseded is not a contradiction (the latest word wins).
 */
object FactCoherence {

    data class Finding(
        val factText: String,
        /** What the design still says, in plain words. */
        val state: String,
        /** The decisions that would make the design match the fact. */
        val fix: Map<String, String>,
        val fixLabel: String,
    ) {
        val message get() = "You told me \"$factText\", but the design still says $state."
        val unapplied get() = "I recorded but did not apply: \"$factText\" (the design still says $state)."
    }

    data class GateFinding(val gate: com.hotattic.gamedesigner.core.schema.Gates.Gate, val factText: String, val decided: String)

    /** A newer remark that says the opposite of a system the owner confirmed in or out, without saying they changed their mind. */
    fun gateFindings(p: Project): List<GateFinding> {
        val out = mutableListOf<GateFinding>()
        for (g in com.hotattic.gamedesigner.core.schema.Gates.all) {
            val d = p.decision(g.key)?.takeIf { it.ownerAuthored && it.status == DecisionStatus.CONFIRMED && it.value in setOf("yes", "no") } ?: continue
            for (f in p.activeFacts().filter { it.provenance.ownerAuthored && it.at > d.updatedAt }) {
                val v = com.hotattic.gamedesigner.core.schema.Gates.verdictFor(f.text, g) ?: continue
                if (v != d.value && !com.hotattic.gamedesigner.core.schema.Gates.explicitChange(f.text)) { out += GateFinding(g, f.text, d.value); break }
            }
        }
        return out
    }

    private fun label(ids: List<String>) = ids.joinToString(", ") { Platforms.labels[it] ?: it }

    fun platformFindings(p: Project): List<Finding> {
        val platDec = p.decision(Keys.PLATFORMS)?.takeIf { it.value.isNotBlank() && it.status != DecisionStatus.DEFERRED } ?: return emptyList()
        val current = platDec.list()
        val primDec = p.decision(Keys.PRIMARY_PLATFORM)?.takeIf { it.value.isNotBlank() }
        val primary = primDec?.value ?: current.singleOrNull()
        val primaryAfter = maxOf(primDec?.updatedAt ?: 0L, platDec.updatedAt)
        val out = mutableListOf<Finding>()
        for (f in p.activeFacts().filter { it.provenance.ownerAuthored }) {
            val instruction = PlatformInstructions.parse(f.text) ?: continue
            val o = instruction.applyTo(current, primDec?.value)
            if (!o.valid) continue
            val platformsDiffer = f.at > platDec.updatedAt && o.platforms.toSet() != current.toSet()
            val primaryDiffers = f.at > primaryAfter && o.primaryRequested != null && o.primaryRequested != primary
            if (!platformsDiffer && !primaryDiffers) continue
            val fix = linkedMapOf(Keys.PLATFORMS to Decision.joinList(o.platforms))
            if (o.platforms.size >= 2 && o.primary != null) fix[Keys.PRIMARY_PLATFORM] = o.primary
            val state = "platforms ${label(current)}" + (primary?.takeIf { current.size >= 2 }?.let { ", built and tested first on ${label(listOf(it))}" } ?: "")
            val fixLabel = "Apply it: ${label(o.platforms)}" + (o.primary?.takeIf { o.platforms.size >= 2 }?.let { ", ${label(listOf(it))} first" } ?: "")
            out += Finding(f.text, state, fix, fixLabel)
        }
        return out
    }
}
