package com.hotattic.gamedesigner.core.director

import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Traits

enum class PanelKind { SINGLE, MULTI, YES_NO, TEXT, UPLOAD, CONFLICT, REVIEW, READY, PROPOSALS, OTHER }

/**
 * One answer the owner can pick. [send] is null for an option of a structured question (it is submitted by id through the
 * selection pipeline) and holds the exact command text for an option that is a quick reply (conflict alternatives, Yes / Not quite, ...).
 */
data class PanelOption(val id: String, val label: String, val description: String, val recommended: Boolean, val send: String?)

/** What the engine is asking right now, shaped for the guided question panel. Pure data derived from [Project]; the panel never stores decisions. */
data class PanelModel(
    val key: String,
    /** The Director message that asked the question; the panel's draft belongs to it and resets when the engine asks something else. */
    val messageId: String,
    val kind: PanelKind,
    val title: String,
    val why: String?,
    /** The engine's own wording for questions that are not simple field questions (the review, a conflict). */
    val detail: String?,
    val options: List<PanelOption>,
    val recommendationLabel: String?,
    val recommendationWhy: String?,
    val lesson: LessonView?,
    val canDelegate: Boolean,
    val canSkip: Boolean,
    val allowsText: Boolean,
    /** What the engine already holds for this question (a revisit shows it, but it is never selected for the owner). */
    val currentLabel: String?,
) {
    val multi get() = kind == PanelKind.MULTI
    val structured get() = options.isNotEmpty() && options.all { it.send == null }
}

/** What pressing CONFIRM does, in the engine's own vocabulary. */
sealed class PanelAction {
    abstract val turnId: String
    data class Select(val fieldKey: String, val ids: List<String>, override val turnId: String) : PanelAction()
    data class Say(val text: String, override val turnId: String) : PanelAction()
}

data class Accepted(val summary: String, val notes: List<String>, val messageCount: Int)

data class NextState(val enabled: Boolean, val reason: String?)

/**
 * The guided question panel as a pure view over engine state: which question is open, what the owner's pending (unconfirmed) choice maps to,
 * and what BACK / CONFIRM / NEXT may do. There is no second state machine: the open question, its options, conflicts, approval and every
 * decision stay in the engine; only the draft selection lives in the screen.
 */
object GuidedPanel {

    private val specials = setOf("__proposals__", "__asset_plan__", "__review__", "__ready__", "__more_refs__", "__ref_aspects__", "__followup__")

    fun model(p: Project, busy: Boolean = false): PanelModel? {
        if (busy || p.mode != ProjectMode.NEW_GAME || p.pendingTurn != null) return null
        val key = p.pendingFieldKey ?: return null
        val last = p.messages.lastOrNull() ?: return null
        if (last.role != Role.DIRECTOR) return null
        val spec = last.question?.takeIf { it.fieldKey == key }
        val quick = last.quickReplies
        if (spec == null && quick.isEmpty() && key.startsWith("__") .not() && Fields.get(key) == null) return null
        val parts = LessonParser.split(last.text)
        val field = key.takeUnless { it.startsWith("__") }?.let { Fields.get(it) }
        val t = Traits(p)
        val suggestion = field?.recommendation(t)

        val kind = when {
            key == "__review__" -> PanelKind.REVIEW
            key == "__ready__" -> PanelKind.READY
            key == "__proposals__" -> PanelKind.PROPOSALS
            key.startsWith("__conflict:") -> PanelKind.CONFLICT
            spec?.kind == "MULTI" -> PanelKind.MULTI
            spec?.kind == "SINGLE" -> PanelKind.SINGLE
            spec?.kind == "BOOLEAN" -> PanelKind.YES_NO
            spec?.kind == "ASSET_UPLOAD" -> PanelKind.UPLOAD
            spec != null && spec.kind in setOf("TEXT", "NUMBER") -> PanelKind.TEXT
            specials.contains(key) && spec != null -> PanelKind.SINGLE
            else -> PanelKind.OTHER
        }

        val options: List<PanelOption> = when {
            kind == PanelKind.YES_NO -> listOf(PanelOption("yes", "Yes", "", false, "yes"), PanelOption("no", "No", "", false, "no"))
            spec != null && spec.options.isNotEmpty() -> spec.options.map { PanelOption(it.id, it.label, it.description, it.id in spec.recommended, null) }
            else -> quick.map { PanelOption(it.send, it.label, "", false, it.send) }
        }.filterNot { o -> o.send != null && o.send.trim().lowercase() in setOf("choose for me", "skip", "ask me later") }

        val title = when {
            field != null -> Messages.promptFor(field, t)
            key == "__review__" -> "Is this the game you want?"
            key == "__ready__" -> "Ready to create your development package?"
            key == "__proposals__" -> "Did I understand this correctly?"
            key == "__asset_plan__" -> "How should we handle the art and sound?"
            key.startsWith("__conflict:") -> ConflictEngine.all(p).firstOrNull { it.id == key.removePrefix("__conflict:").removeSuffix("__") }?.title ?: "Something needs a decision"
            else -> parts.after.ifBlank { parts.before }.lines().lastOrNull { it.isNotBlank() }.orEmpty().take(180)
        }
        val detail = if (field == null) parts.before.ifBlank { last.text }.takeIf { it.isNotBlank() && it != title } else null
        val why = parts.lesson?.why ?: field?.why?.takeIf { it.isNotBlank() }
        val recLabel = suggestion?.value?.split("|")?.mapNotNull { id -> options.firstOrNull { it.id == id }?.label }?.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val current = field?.let { p.decision(it.key) }?.takeIf { it.value.isNotBlank() }?.let { Messages.display(p, field.key, it.value) }

        return PanelModel(
            key, last.id, kind, title, why, detail, options, recLabel, suggestion?.rationale ?: parts.lesson?.recommendation, parts.lesson,
            canDelegate = spec?.canDelegate == true || quick.any { it.send.trim().lowercase() == "choose for me" },
            canSkip = spec?.canSkip == true || quick.any { it.send.trim().lowercase() == "skip" },
            allowsText = kind in setOf(PanelKind.TEXT, PanelKind.SINGLE, PanelKind.MULTI, PanelKind.OTHER, PanelKind.UPLOAD, PanelKind.CONFLICT, PanelKind.REVIEW, PanelKind.PROPOSALS),
            currentLabel = current,
        )
    }

    private fun draftId(model: PanelModel, draft: Collection<String>, text: String) =
        "panel:${model.messageId}:${(draft.sorted().joinToString(",") + "|" + text.trim()).hashCode().toUInt().toString(16)}"

    /** Why CONFIRM is not available yet, or null when it is. */
    fun confirmBlocked(model: PanelModel, draft: Set<String>, text: String): String? = when {
        text.isNotBlank() -> null
        model.options.isEmpty() -> "Type your answer, then confirm."
        draft.isEmpty() -> if (model.multi) "Pick at least one, or type your own." else "Pick an answer, or type your own, then confirm."
        else -> null
    }

    /** The one action CONFIRM performs. Null while nothing is selected: nothing is confirmed on the owner's behalf. */
    fun confirm(model: PanelModel, draft: Set<String>, text: String): PanelAction? {
        if (confirmBlocked(model, draft, text) != null) return null
        val id = draftId(model, draft, text)
        if (text.isNotBlank()) return PanelAction.Say(text.trim(), id)
        val picked = model.options.filter { it.id in draft }
        return if (model.structured) PanelAction.Select(model.key, picked.map { it.id }, id) else PanelAction.Say(picked.first().send!!, id)
    }

    /** Choose-for-me and skip are explicit actions with their own stable id; they are the same texts the old quick replies sent. */
    fun delegate(model: PanelModel) = PanelAction.Say("choose for me", "panel:${model.messageId}:delegate")
    fun skip(model: PanelModel) = PanelAction.Say(if (model.canSkip) "skip" else "ask me later", "panel:${model.messageId}:skip")

    /** BACK is the engine's undo of the last answer, and it is only offered when there is something to undo. */
    fun canBack(p: Project, busy: Boolean) = !busy && p.pendingTurn == null && p.answerTrail.isNotEmpty()

    /** What the owner sees after CONFIRM, until NEXT reveals the question the engine has already asked. Valid only while nothing else happened. */
    fun accepted(after: Project, beforeMessageCount: Int, beforeKey: String, turnId: String, chosen: List<String>): Accepted? {
        if (turnId !in after.processedTurns || after.pendingTurn != null) return null
        // The engine asking the same question again (it could not use the answer) is not an acceptance.
        if (after.pendingFieldKey == beforeKey) return null
        val added = after.messages.drop(beforeMessageCount)
        val directorNotes = added.filter { it.role == Role.DIRECTOR }.dropLast(1).map { LessonParser.split(it.text).before.ifBlank { it.text }.lines().firstOrNull { l -> l.isNotBlank() }.orEmpty().take(220) }.filter { it.isNotBlank() }
        val summary = if (chosen.isEmpty()) "Your answer was recorded." else "Recorded: ${chosen.joinToString(", ")}"
        return Accepted(summary, directorNotes, after.messages.size)
    }

    fun stillCurrent(a: Accepted, p: Project) = p.messages.size == a.messageCount && p.pendingTurn == null

    /** NEXT only reveals what the engine already asked; it is blocked while the current answer is unconfirmed or Bob is still working. */
    fun next(p: Project, model: PanelModel?, accepted: Accepted?, busy: Boolean): NextState = when {
        busy -> NextState(false, "Bob is still working on your last answer.")
        p.pendingTurn != null -> NextState(false, "Your last message is saved but not answered yet. Tap Retry in the conversation.")
        accepted != null && stillCurrent(accepted, p) -> NextState(true, if (model?.kind == PanelKind.CONFLICT) "Bob found something to settle before the next question." else null)
        model != null -> NextState(false, if (model.kind == PanelKind.CONFLICT) "This needs a decision first. Pick one of the options, then confirm." else "Confirm your answer first. NEXT never answers for you.")
        else -> NextState(false, "There is no open question right now.")
    }
}
