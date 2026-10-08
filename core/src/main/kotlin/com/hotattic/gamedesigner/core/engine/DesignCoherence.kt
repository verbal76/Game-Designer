package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Gates
import com.hotattic.gamedesigner.core.schema.Keys

/**
 * The mandatory pre-export coherence pass over the [DesignModel].
 *
 * [reconcile] resolves stale decisions deterministically by precedence (latest explicit owner decision > earlier owner decision >
 * accepted recommendation > grounded inference > default). [check] then verifies the generated documents against the model:
 * nothing the owner ruled out (or never established) may reappear, procedural designs must state their invariants, supplied assets
 * must stay first, and the experience must not be contradicted by the mechanics.
 */
object DesignCoherence {

    /** A decision that survives a gate said "no" must outrank that "no"; otherwise it is stale and goes. */
    fun reconcile(p0: Project, now: Long): Pair<Project, List<String>> {
        var p = p0
        val notes = mutableListOf<String>()
        for (g in Gates.all) {
            val gate = p.decision(g.key) ?: continue
            if (gate.value != "no") continue
            for (dep in g.dependents) {
                val d = p.decision(dep) ?: continue
                if (d.prov.rank > gate.prov.rank) {
                    // The "no" was only an inference or default; the dependent is the stronger statement, so the gate is withdrawn instead.
                    p = p.copy(decisions = p.decisions - g.key, updatedAt = now)
                    notes += "Withdrew the inferred \"no ${g.noun.substringBefore(" (")}\" because you chose ${dep.replace('_', ' ')} yourself."
                    break
                } else {
                    p = p.copy(decisions = p.decisions - dep, updatedAt = now)
                    notes += "Dropped the earlier ${dep.replace('_', ' ')} choice: you ruled out ${g.noun.substringBefore(" (")}."
                }
            }
        }
        return p to notes
    }

    private val negation = Regex("(?i)\\b(no|never|none|not|without|nothing|isn'?t|doesn'?t|don'?t|do not|absent|ruled out|rules out|neither|nor)\\b")

    private val forbidden: Map<SystemId, Regex> = mapOf(
        SystemId.COMBAT to Regex("(?i)\\b(enemy types|enemies and bosses|boss fights?|fight every enemy|damage rules|hit points|health bars?)\\b"),
        SystemId.CHARACTER_POWER to Regex("(?i)\\b(xp|experience points|skill tree|meta[_ ]unlocks?|permanent unlocks?|permanent upgrades?|power-ups? / abilities|earn at least one progression|level[- ]ups?)\\b"),
        SystemId.ECONOMY to Regex("(?i)\\b(currency|vendors?|shops?|gold coins|economy:)\\b"),
        SystemId.CRAFTING to Regex("(?i)\\b(crafting recipes?|crafting stations?|crafting system)\\b"),
    )

    fun check(p: Project, docs: Map<String, String>): List<ReviewFinding> {
        val m = DesignModel.of(p)
        val out = mutableListOf<ReviewFinding>()
        val all = docs.entries.joinToString("\n") { it.value }

        // A, B, F: nothing ruled out or never established may be (re)introduced by the export.
        for ((system, rx) in forbidden) {
            if (m.state(system) == SystemState.PRESENT) continue
            for ((name, text) in docs) {
                val hit = text.lines().firstOrNull { rx.containsMatchIn(it) && !negation.containsMatchIn(it) } ?: continue
                out += ReviewFinding(if (m.state(system) == SystemState.ABSENT) ReviewLevel.ERROR else ReviewLevel.WARNING, "absent_system_reintroduced", "[$name] ${system.label} is ${m.state(system).name.lowercase()} in this design but the export requires it.", hit.trim().take(160))
            }
        }
        // C: the experience must not be contradicted by the mechanics.
        if (m.experience.skilledNearMiss) {
            val hit = all.lines().firstOrNull { Regex("(?i)\\b(loot drops?|gacha|critical hits?|random rewards?|rng decides)\\b").containsMatchIn(it) && !negation.containsMatchIn(it) }
            if (hit != null) out += ReviewFinding(ReviewLevel.WARNING, "experience_mechanics_mismatch", "The owner wants skilled near-misses ('lucky'), but the export leans on randomness.", hit.trim().take(160))
        }
        // D: a core loop needs an objective to steer it.
        if (p.decision(Keys.CORE_LOOP) != null && p.decision(Keys.WIN_LOSS) == null && p.decision(Keys.DONE) == null)
            out += ReviewFinding(ReviewLevel.WARNING, "loop_without_objective", "The core loop has no stated objective or completion condition.")
        // E: when the avatar never gets stronger, the export must say what progression IS.
        if (m.playerMasteryIsProgression && docs.containsKey("CLAUDE.md") && "NONE by design" !in docs.getValue("CLAUDE.md"))
            out += ReviewFinding(ReviewLevel.ERROR, "mastery_progression_unstated", "Character power progression is absent but the spec does not state that mastery is the progression.", "CLAUDE.md")
        // G: procedural never means 'random'.
        if (m.procedural && docs.containsKey("CLAUDE.md") && "Procedural generation constraints" !in docs.getValue("CLAUDE.md"))
            out += ReviewFinding(ReviewLevel.ERROR, "procedural_without_invariants", "The world is procedural but the spec defines no generation constraints.", "CLAUDE.md")
        // H: supplied assets stay first.
        if (m.state(SystemId.SUPPLIED_ASSETS) == SystemState.PRESENT) for ((name, text) in docs) {
            if (name != "ASSETS.md" && name != "CLAUDE.md" && name != "MASTER_PROMPT.md") continue
            if (!Regex("(?i)asset packs").containsMatchIn(text)) out += ReviewFinding(ReviewLevel.ERROR, "supplied_assets_lost", "[$name] The owner supplies asset packs but this document does not tell the builder to use them first.", name)
        }
        return out
    }
}
