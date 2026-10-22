package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Dependencies
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Traits

data class Reconciliation(val project: Project, val notices: List<String>)

/**
 * Dependency invalidation. When a root decision changes (genre, dimension, platforms, a rejected tag...), every
 * decision that was DERIVED from it by the system or accepted from a recommendation is stale: it is cleared so it is
 * re-derived or re-asked. Decisions the owner stated themselves are never silently removed; the conflict rules surface
 * any real contradiction with them instead.
 */
object Reconciler {

    /** Fingerprint of the roots each non-root decision was derived against, so only real changes invalidate. */
    private fun rootSnapshot(p: Project): Map<String, String> {
        val t = Traits(p)
        return mapOf(
            Keys.GENRE to t.genres.joinToString(",") { it.id },
            Keys.DIMENSION to (t.dimension ?: ""),
            Keys.PLATFORMS to t.platforms.sorted().joinToString(","),
            Dependencies.TAG to t.tags.map { it.name }.sorted().joinToString(","),
            Keys.INPUT_METHODS to p.list(Keys.INPUT_METHODS).sorted().joinToString(","),
            Keys.STORE_PLAN to (p.value(Keys.STORE_PLAN) ?: ""),
            Keys.MONETIZATION to (p.value(Keys.MONETIZATION) ?: ""),
            Keys.NETWORK_POLICY to (p.value(Keys.NETWORK_POLICY) ?: ""),
            Keys.AUDIO to (p.value(Keys.AUDIO) ?: ""),
            Keys.CONCEPT to (p.value(Keys.CONCEPT) ?: ""),
            Keys.DISPLAY_NAME to (p.value(Keys.DISPLAY_NAME) ?: ""),
            Keys.REFERENCES to p.references.joinToString(",") { it.name },
        )
    }

    /** Full revalidation: treats every root as changed, dropping any non-owner decision that no longer fits. */
    fun revalidate(p: Project, now: Long): Reconciliation = reconcile(p.copy(decisions = emptyMap(), references = emptyList(), rejected = emptyMap()), p, now, strict = false)

    /** Compares the project before and after an edit and clears whatever the edit made stale. */
    /** [strict] true: a real edit happened, so decisions derived from changed roots are dropped for re-derivation. false: only decisions that are no longer valid are dropped. */
    fun reconcile(before: Project, after: Project, now: Long, strict: Boolean = true): Reconciliation {
        val notices = mutableListOf<String>()
        var p = after
        val old = rootSnapshot(before)
        // Iterate to a fixpoint: clearing a decision can in turn invalidate decisions derived from it.
        var prevSnapshot = old
        repeat(6) {
            val cur = rootSnapshot(p)
            val changed = cur.filter { (k, v) -> prevSnapshot[k] != v }.keys
            if (changed.isEmpty()) return@repeat
            val t = Traits(p)
            val stale = p.decisions.filter { (key, d) ->
                if (d.ownerAuthored) return@filter false
                if (d.value.isBlank()) return@filter false
                // Decisions written in this very step were derived from the new roots, so they are not stale.
                if (strict && before.decision(key)?.value != d.value) return@filter false
                val f = Fields.get(key) ?: return@filter false
                if (Dependencies.sourcesOf(f).none { it in changed }) return@filter false
                // Only drop it if it no longer fits: an option that is still valid under the new roots is kept.
                if (!strict && !f.kind.isSelect) return@filter !f.isRelevant(t) || f.validate(t, d.value) != null
                if (f.kind.isSelect) {
                    val valid = f.options(t).map { it.id }.toSet()
                    val ids = d.list()
                    if (valid.isNotEmpty() && ids.all { it in valid } && f.isRelevant(t)) return@filter false
                }
                true
            }.keys
            for (k in stale) {
                val f = Fields.get(k)!!
                notices += "I dropped my earlier ${f.title.lowercase()} (${display(p.decision(k)!!)}) because it no longer fits what you've told me; I'll re-derive or re-ask it."
                p = ProjectOps.clearDecision(p, k, now)
            }
            // Rejected values can never remain, whoever put them there.
            for ((k, rej) in p.rejected) {
                val d = p.decision(k) ?: continue
                if (d.ownerAuthored) continue
                val left = d.list().filter { it !in rej }
                if (left.size != d.list().size) p = if (left.isEmpty()) ProjectOps.clearDecision(p, k, now) else p.copy(decisions = p.decisions + (k to d.copy(value = Decision.joinList(left), updatedAt = now)))
            }
            prevSnapshot = cur
            if (stale.isEmpty()) return@repeat
        }
        return Reconciliation(p, notices)
    }

    private fun display(d: Decision) = d.list().joinToString(", ").take(60)
}
