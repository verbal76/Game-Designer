package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.AckChoice
import com.hotattic.gamedesigner.core.model.ChatMessage
import com.hotattic.gamedesigner.core.model.ConflictAck
import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.DecisionSource
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.DesignFact
import com.hotattic.gamedesigner.core.model.FactStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.ProjectPrefs
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.QuestionSpec
import com.hotattic.gamedesigner.core.model.QuickReply
import com.hotattic.gamedesigner.core.model.ReferenceGame
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.schema.Dependencies
import com.hotattic.gamedesigner.core.schema.FieldKind
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Suggestion
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits

/** Pure, immutable state transitions for a [Project]. The UI and Director both go through these. */
object ProjectOps {

    fun newProject(id: String, name: String, mode: ProjectMode, prefs: ProjectPrefs, now: Long) =
        Project(id = id, name = name, mode = mode, createdAt = now, updatedAt = now, prefs = prefs)

    // ---- Decisions with authority ------------------------------------------------------------------------------

    /**
     * Writes a decision, enforcing provenance precedence:
     *  - a lower-authority write can never replace a confirmed higher-authority decision;
     *  - an owner statement that changes an earlier owner-authored value is recorded as OWNER_CORRECTION;
     *  - system-originated writes cannot reintroduce values the owner rejected, while an owner statement lifts the rejection.
     */
    fun setDecision(
        p: Project, key: String, value: String, prov: Provenance, now: Long,
        status: DecisionStatus = DecisionStatus.CONFIRMED, note: String = "", overrides: String? = null, raw: String = "",
    ): Project {
        var v = value.trim()
        var effective = prov
        val existing = p.decision(key)
        if (existing != null && existing.value.isNotBlank() && existing.status == DecisionStatus.CONFIRMED) {
            if (prov.rank < existing.prov.rank) return p
            if (prov == Provenance.OWNER_EXPLICIT && existing.ownerAuthored && existing.value != v) effective = Provenance.OWNER_CORRECTION
        }
        var rejected = p.rejected
        val field = Fields.get(key)
        val isListy = key == Keys.GENRE || field?.kind == FieldKind.MULTI || field?.kind == FieldKind.SINGLE
        if (isListy && v.isNotEmpty()) {
            val ids = v.split(Decision.LIST_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
            val rejKey = key
            val rej = rejected[rejKey].orEmpty()
            if (prov.ownerAuthored) {
                if (ids.any { it in rej }) rejected = rejected + (rejKey to (rej - ids.toSet()))
            } else {
                val kept = ids.filter { it !in rej }
                if (kept.isEmpty()) return p
                v = Decision.joinList(kept)
            }
        }
        val d = Decision(v, effective.legacySource(), status, note, overrides, now, effective, raw)
        var next = p.copy(decisions = p.decisions + (key to d), rejected = rejected, updatedAt = now, postponed = p.postponed - key)
        if (key == Keys.DISPLAY_NAME && p.name.isBlank()) next = next.copy(name = v)
        return next
    }

    /** Legacy entry point kept for callers that still speak [DecisionSource]. */
    fun setDecision(
        p: Project, key: String, value: String, source: DecisionSource, now: Long,
        status: DecisionStatus = DecisionStatus.CONFIRMED, note: String = "", overrides: String? = null,
    ): Project = setDecision(p, key, value, Provenance.fromLegacy(source, overrides), now, status, note, overrides)

    fun clearDecision(p: Project, key: String, now: Long) = p.copy(decisions = p.decisions - key, updatedAt = now)

    // ---- Owner rejections --------------------------------------------------------------------------------------

    /** The owner ruled these option ids out for [key]; remove them from any current decision and block re-inference. */
    fun reject(p: Project, key: String, ids: Collection<String>, now: Long): Project {
        if (ids.isEmpty()) return p
        var next = p.copy(rejected = p.rejected + (key to ((p.rejected[key].orEmpty() + ids).distinct())), updatedAt = now)
        val d = next.decision(key)
        if (d != null) {
            val left = d.list().filter { it !in ids.toSet() }
            next = if (left.isEmpty()) next.copy(decisions = next.decisions - key)
            else if (left.size != d.list().size) next.copy(decisions = next.decisions + (key to d.copy(value = Decision.joinList(left), updatedAt = now))) else next
        }
        return next
    }

    /**
     * "Not turn based": rejects the tag, and with it every genre that carries it, so tactical systems cannot be re-inferred.
     * Returns the project plus the genre ids that were removed.
     */
    fun rejectTag(p: Project, tag: Tag, now: Long): Pair<Project, List<String>> {
        val dropped = GenreKnowledge.all.filter { tag in it.tags }.map { it.id }
        val present = p.list(Keys.GENRE).filter { it in dropped }
        var next = p.copy(rejected = p.rejected + (Dependencies.TAG to ((p.rejected[Dependencies.TAG].orEmpty() + tag.name).distinct())), updatedAt = now)
        // Reject only genres actually in play, so unrelated genres are not blocked for later.
        next = reject(next, Keys.GENRE, present, now)
        next = next.copy(rejected = next.rejected + ("genre" to (next.rejected[Keys.GENRE].orEmpty())))
        return next to present
    }

    fun restoreTag(p: Project, tag: Tag, now: Long): Project {
        val rest = p.rejected[Dependencies.TAG].orEmpty() - tag.name
        val genreIds = GenreKnowledge.all.filter { tag in it.tags }.map { it.id }.toSet()
        val genres = p.rejected[Keys.GENRE].orEmpty().filter { it !in genreIds }
        return p.copy(rejected = p.rejected + (Dependencies.TAG to rest) + (Keys.GENRE to genres), updatedAt = now)
    }

    // ---- Facts and the original concept ------------------------------------------------------------------------

    fun setOriginalConcept(p: Project, text: String): Project = if (p.originalConcept.isBlank()) p.copy(originalConcept = text.trim()) else p

    fun addFacts(p: Project, texts: List<String>, category: String, prov: Provenance, now: Long): Project {
        val have = p.facts.filter { it.status == FactStatus.ACTIVE }.map { norm(it.text) }.toSet()
        val fresh = texts.map { it.trim() }.filter { it.length >= 4 && norm(it) !in have }.distinctBy { norm(it) }
        if (fresh.isEmpty()) return p
        val start = p.facts.size
        return p.copy(facts = p.facts + fresh.mapIndexed { i, t -> DesignFact("f${start + i + 1}", t, category, prov, FactStatus.ACTIVE, now) }, updatedAt = now)
    }

    /** Facts are retracted (kept, flagged) rather than deleted so the export can say "withdrawn by the owner: do not implement". */
    fun retractFacts(p: Project, note: String, now: Long, matches: (DesignFact) -> Boolean): Project =
        p.copy(facts = p.facts.map { if (it.status == FactStatus.ACTIVE && matches(it)) it.copy(status = FactStatus.RETRACTED, retractionNote = note) else it }, updatedAt = now)

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    // ---- Extraction as proposals -------------------------------------------------------------------------------

    /**
     * Applies extracted values as proposals. Never overwrites something the owner stated or confirmed, and never
     * reintroduces what the owner rejected (enforced by [setDecision]).
     */
    fun applyExtraction(p: Project, ex: Extraction, now: Long, prov: Provenance = Provenance.SYSTEM_INFERENCE): Project {
        var next = p
        for ((k, v) in ex.values) {
            val existing = next.decision(k)
            if (existing != null && existing.status == DecisionStatus.CONFIRMED && existing.value.isNotBlank()) continue
            next = setDecision(next, k, v, prov, now, DecisionStatus.PROPOSED)
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
        else p.decisions + (Keys.REFERENCES to Decision(all.joinToString(", ") { it.name }, DecisionSource.INFERRED, DecisionStatus.PROPOSED, updatedAt = now, provenance = Provenance.SYSTEM_INFERENCE))
        return p.copy(references = all, decisions = dec, updatedAt = now)
    }

    /** Attaches a repository and turns what inspection verified into proposals (never into confirmed facts). */
    fun applyInspection(p: Project, owner: String, repo: String, ins: com.hotattic.gamedesigner.core.model.RepoInspection, now: Long): Project {
        var next = p.copy(repo = com.hotattic.gamedesigner.core.model.RepoLink(owner, repo, ins.defaultBranch, ins), updatedAt = now)
        if (ins.detectedEngine.isNotBlank()) next = setDecision(next, Keys.ENGINE, ins.detectedEngine, Provenance.SYSTEM_INFERENCE, now, DecisionStatus.PROPOSED, "Detected in repository")
        if (next.value(Keys.CI_BUILD) == null && ins.hasCi) next = setDecision(next, Keys.CI_BUILD, "github_actions", Provenance.SYSTEM_INFERENCE, now, DecisionStatus.PROPOSED, "Workflows exist in the repository")
        return next
    }

    fun updateReference(p: Project, ref: ReferenceGame): Project =
        p.copy(references = p.references.map { if (it.name.equals(ref.name, true)) ref else it })

    fun confirm(p: Project, key: String, now: Long): Project {
        val d = p.decision(key) ?: return p
        // Confirming an inference keeps it a system inference that the owner accepted.
        val prov = if (d.prov == Provenance.SYSTEM_INFERENCE) Provenance.OWNER_ACCEPTED_RECOMMENDATION else d.prov
        return p.copy(decisions = p.decisions + (key to d.copy(status = DecisionStatus.CONFIRMED, provenance = prov, source = prov.legacySource(), updatedAt = now)), updatedAt = now, postponed = p.postponed - key)
    }

    fun confirmAllProposed(p: Project, now: Long): Project {
        var next = p
        for ((k, d) in p.decisions) if (d.status == DecisionStatus.PROPOSED) next = confirm(next, k, now)
        return next
    }

    fun proposedKeys(p: Project): List<String> = p.decisions.filter { it.value.status == DecisionStatus.PROPOSED }.keys.toList()

    /** "Choose for me": record the schema suggestion as an accepted recommendation (never as an owner requirement). */
    fun delegate(p: Project, key: String, now: Long): Pair<Project, Suggestion>? {
        val f = Fields.get(key) ?: return null
        // If something was already understood from the owner's words, "choose for me" accepts that rather than replacing it.
        val pending = p.decision(key)
        if (pending != null && pending.status == DecisionStatus.PROPOSED && pending.value.isNotBlank() && pending.prov == Provenance.SYSTEM_INFERENCE)
            return confirm(p, key, now) to Suggestion(pending.value, "Using what I understood from your description.")
        val s = f.suggest(Traits(p)) ?: return null
        val status = if (f.kind == FieldKind.TEXT && key in setOf(Keys.CORE_FANTASY)) DecisionStatus.PROPOSED else DecisionStatus.CONFIRMED
        return setDecision(p, key, s.value, Provenance.OWNER_ACCEPTED_RECOMMENDATION, now, status, note = s.rationale) to s
    }

    fun defer(p: Project, key: String, now: Long, note: String = "Deferred by owner"): Project =
        setDecision(p, key, "", Provenance.OWNER_EXPLICIT, now, DecisionStatus.DEFERRED, note)

    fun postpone(p: Project, key: String, now: Long): Project =
        p.copy(postponed = (p.postponed + key).distinct(), pendingFieldKey = null, updatedAt = now)

    fun addMessage(p: Project, role: Role, text: String, now: Long, fieldKey: String? = null, quick: List<QuickReply> = emptyList(), question: QuestionSpec? = null): Project {
        val msg = ChatMessage(id = "m${p.messages.size + 1}_$now", role = role, text = text, at = now, fieldKey = fieldKey, quickReplies = quick, question = question)
        return p.copy(messages = p.messages + msg, updatedAt = now)
    }

    fun setMode(p: Project, mode: ProjectMode, now: Long): Project = p.copy(mode = mode, pendingFieldKey = null, updatedAt = now)

    fun setPending(p: Project, key: String?): Project = p.copy(pendingFieldKey = key)

    fun acknowledge(p: Project, conflictId: String, choice: AckChoice, now: Long): Project =
        p.copy(conflictAcks = p.conflictAcks.filter { it.conflictId != conflictId } + ConflictAck(conflictId, choice, now), updatedAt = now)

    /** Accept a recommendation: apply the alternative as a normal confirmed owner decision. */
    fun applyAlternative(p: Project, conflictId: String, alt: Alternative, now: Long): Project {
        var next = p
        for ((k, v) in alt.changes) next = setDecision(next, k, v, Provenance.OWNER_EXPLICIT, now, note = "Accepted recommendation for $conflictId")
        return acknowledge(next, conflictId, AckChoice.ACCEPTED_RECOMMENDATION, now)
    }

    /** Informed override: record what was recommended so the generated spec can say so. */
    fun overrideConflict(p: Project, conflict: Conflict, now: Long): Project {
        var next = acknowledge(p, conflict.id, AckChoice.OVERRIDDEN, now)
        for (k in conflict.affectedKeys) {
            val d = next.decision(k) ?: continue
            next = next.copy(decisions = next.decisions + (k to d.copy(provenance = Provenance.OWNER_EXPLICIT, source = DecisionSource.OVERRIDE, overrides = conflict.recommendation, updatedAt = now)))
        }
        return next
    }
}
