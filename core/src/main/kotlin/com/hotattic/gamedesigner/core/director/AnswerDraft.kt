package com.hotattic.gamedesigner.core.director

/**
 * The owner's unconfirmed answer to the open question: either picked options or their own words, never both. Pure rules so the screen only holds the
 * values. Nothing here records anything; recording happens only when the owner confirms, through [GuidedPanel.confirm].
 */
data class AnswerDraft(val selected: Set<String> = emptySet(), val text: String = "") {
    val isEmpty get() = selected.isEmpty() && text.isBlank()

    /** Picking an option replaces typed text (the confirm button must show exactly what will be recorded). */
    fun pick(model: PanelModel, id: String): AnswerDraft =
        AnswerDraft(if (model.multi) (if (id in selected) selected - id else selected + id) else setOf(id), "")

    fun pickAll(ids: Collection<String>) = AnswerDraft(ids.toSet(), "")

    /** Typing replaces any picked option for the same reason. Clearing the text keeps nothing selected. */
    fun type(text: String): AnswerDraft = AnswerDraft(if (text.isBlank()) selected else emptySet(), text)

    fun clear() = AnswerDraft()

    /** What the confirm button says, so the owner knows what pressing it records. */
    fun confirmLabel(model: PanelModel): String = when {
        text.isNotBlank() -> "Confirm my answer"
        model.multi && selected.isNotEmpty() -> "Confirm ${selected.size} selected"
        selected.isNotEmpty() -> "Confirm: " + (model.options.firstOrNull { it.id in selected }?.label ?: "selection").take(24)
        else -> "Confirm"
    }

    /** One short line saying what to do next, or null when nothing needs saying (the choices are visible and an answer is ready). */
    fun hint(model: PanelModel): String? = when {
        text.isNotBlank() -> "Press Confirm to record your answer."
        selected.isNotEmpty() -> "Press Confirm to record your choice."
        model.options.isEmpty() -> "Type your answer, then press Confirm."
        model.multi -> "Pick one or more, or type your own answer."
        else -> "Choose an answer, or type your own."
    }

    companion object {
        /** Drafts are kept per question key, so leaving a question with Back and returning does not lose what was typed or picked. */
        fun encode(drafts: Map<String, AnswerDraft>): String = drafts.entries.joinToString("\u0002") { (k, d) -> k + "\u0001" + d.selected.sorted().joinToString("\u0003") + "\u0001" + d.text }
        fun decode(raw: String): Map<String, AnswerDraft> = if (raw.isEmpty()) emptyMap() else raw.split("\u0002").mapNotNull { e ->
            val p = e.split("\u0001", limit = 3); if (p.size < 3 || p[0].isEmpty()) null
            else p[0] to AnswerDraft(p[1].split("\u0003").filter { it.isNotEmpty() }.toSet(), p[2])
        }.toMap()
    }
}
