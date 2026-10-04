package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.AckChoice
import com.hotattic.gamedesigner.core.model.ChatMessage
import com.hotattic.gamedesigner.core.model.ConflictAck
import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.DecisionSource
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.ProjectPrefs
import com.hotattic.gamedesigner.core.model.QuickReply
import com.hotattic.gamedesigner.core.model.ReferenceGame
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Suggestion
import com.hotattic.gamedesigner.core.schema.Traits

/** Pure, immutable state transitions for a [Project]. The UI and Director both go through these. */
object ProjectOps {

    fun newProject(id: String, name: String, mode: ProjectMode, prefs: ProjectPrefs, now: Long) =
        Project(id = id, name = name, mode = mode, createdAt = now, updatedAt = now, prefs = prefs)

    fun setDecision(
        p: Project, key: String, value: String, source: DecisionSource, now: Long,
        status: DecisionStatus = DecisionStatus.CONFIRMED, note: String = "", overrides: String? = null,
    ): Project {
        val d = Decision(value.trim(), source, status, note, overrides, now)
        var next = p.copy(decisions = p.decisions + (key to d), updatedAt = now, postponed = p.postponed - key)
        if (key == Keys.DISPLAY_NAME && p.name.isBlank()) next = next.copy(name = value.trim())
        return next
    }

    /** Removes a decision (e.g. when the owner changes their mind), keeping everything else. */
    fun clearDecision(p: Project, key: String, now: Long) = p.copy(decisions = p.decisions - key, updatedAt = now)

    /**
     * Applies extracted values as proposals. Never overwrites something the owner stated or confirmed explicitly,
     * and never overwrites an existing proposal with an empty value.
     */
    fun applyExtraction(p: Project, ex: Extraction, now: Long, source: DecisionSource = DecisionSource.INFERRED): Project {
        var next = p
        for ((k, v) in ex.values) {
            val existing = next.decision(k)
            if (existing != null && existing.status == DecisionStatus.CONFIRMED && existing.value.isNotBlank()) continue
            next = setDecision(next, k, v, source, now, DecisionStatus.PROPOSED)
        }
        if (ex.referenceGames.isNotEmpty()) next = addReferences(next, ex.referenceGames, now)
        return next
    }

    fun addReferences(p: Project, names: List<String>, now: Long): Project {
        val have = p.references.map { it.name.lowercase() }.toSet()
        val added = names.filter { it.lowercase() !in have }.map { ReferenceGame(name = it) }
        if (added.isEmpty()) return p
        val all = p.references + added
        val existing = p.decision(Keys.REFERENCES)
        val dec = if (existing != null && existing.status == DecisionStatus.CONFIRMED && existing.value != "none")
            p.decisions + (Keys.REFERENCES to existing.copy(value = all.joinToString(", ") { it.name }, updatedAt = now))
        else p.decisions + (Keys.REFERENCES to Decision(all.joinToString(", ") { it.name }, DecisionSource.INFERRED, DecisionStatus.PROPOSED, updatedAt = now))
        return p.copy(references = all, decisions = dec, updatedAt = now)
    }

    fun updateReference(p: Project, ref: ReferenceGame): Project =
        p.copy(references = p.references.map { if (it.name.equals(ref.name, true)) ref else it })

    fun confirm(p: Project, key: String, now: Long): Project {
        val d = p.decision(key) ?: return p
        return p.copy(decisions = p.decisions + (key to d.copy(status = DecisionStatus.CONFIRMED, updatedAt = now)), updatedAt = now, postponed = p.postponed - key)
    }

    fun confirmAllProposed(p: Project, now: Long): Project {
        var next = p
        for ((k, d) in p.decisions) if (d.status == DecisionStatus.PROPOSED) next = confirm(next, k, now)
        return next
    }

    fun proposedKeys(p: Project): List<String> = p.decisions.filter { it.value.status == DecisionStatus.PROPOSED }.keys.toList()

    /** "Choose for me": record the schema suggestion as a delegated decision. Returns null if there is no sensible suggestion. */
    fun delegate(p: Project, key: String, now: Long): Pair<Project, Suggestion>? {
        val f = Fields.get(key) ?: return null
        val s = f.suggest(Traits(p)) ?: return null
        val status = if (f.kind == com.hotattic.gamedesigner.core.schema.FieldKind.TEXT && key in setOf(Keys.CORE_FANTASY)) DecisionStatus.PROPOSED else DecisionStatus.CONFIRMED
        return setDecision(p, key, s.value, DecisionSource.DIRECTOR_CHOICE, now, status, note = s.rationale) to s
    }

    /** Explicitly deferred by the owner ("skip it / don't want it"). */
    fun defer(p: Project, key: String, now: Long, note: String = "Deferred by owner"): Project =
        setDecision(p, key, "", DecisionSource.USER, now, DecisionStatus.DEFERRED, note)

    fun postpone(p: Project, key: String, now: Long): Project =
        p.copy(postponed = (p.postponed + key).distinct(), pendingFieldKey = null, updatedAt = now)

    fun addMessage(p: Project, role: Role, text: String, now: Long, fieldKey: String? = null, quick: List<QuickReply> = emptyList()): Project {
        val msg = ChatMessage(id = "m${p.messages.size + 1}_$now", role = role, text = text, at = now, fieldKey = fieldKey, quickReplies = quick)
        return p.copy(messages = p.messages + msg, updatedAt = now)
    }

    fun setPending(p: Project, key: String?): Project = p.copy(pendingFieldKey = key)

    fun acknowledge(p: Project, conflictId: String, choice: AckChoice, now: Long): Project =
        p.copy(conflictAcks = p.conflictAcks.filter { it.conflictId != conflictId } + ConflictAck(conflictId, choice, now), updatedAt = now)

    /** Accept a recommendation: apply the alternative as a normal confirmed decision. */
    fun applyAlternative(p: Project, conflictId: String, alt: Alternative, now: Long): Project {
        var next = p
        for ((k, v) in alt.changes) next = setDecision(next, k, v, DecisionSource.USER, now, note = "Accepted recommendation for $conflictId")
        return acknowledge(next, conflictId, AckChoice.ACCEPTED_RECOMMENDATION, now)
    }

    /** Informed override: record what was recommended so the generated spec can say so. */
    fun overrideConflict(p: Project, conflict: Conflict, now: Long): Project {
        var next = acknowledge(p, conflict.id, AckChoice.OVERRIDDEN, now)
        for (k in conflict.affectedKeys) {
            val d = next.decision(k) ?: continue
            next = next.copy(decisions = next.decisions + (k to d.copy(source = DecisionSource.OVERRIDE, overrides = conflict.recommendation, updatedAt = now)))
        }
        return next
    }
}
