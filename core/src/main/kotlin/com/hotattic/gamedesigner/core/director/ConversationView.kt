package com.hotattic.gamedesigner.core.director

import com.hotattic.gamedesigner.core.model.ChatMessage
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Role

/**
 * What the conversation history shows, given what the question panel already shows. The active question is displayed in exactly one place (the panel),
 * so its own message is cut down to the notes Bob made before asking (or removed), an unanswered question that was simply asked again is shown once,
 * and every other question Bob asked is shown as the question only, without its teaching card, option list and suggestion (the panel showed those when
 * it was open). Nothing is deleted from the project: this only decides what to draw.
 */
object ConversationView {
    private fun questionKey(m: ChatMessage): String? = if (m.role == Role.DIRECTOR) (m.question?.fieldKey ?: m.fieldKey) else null

    fun history(p: Project, active: PanelModel?): List<ChatMessage> {
        val ms = p.messages
        // An ask of key K that no owner message followed before K was asked again is a duplicate of the later ask.
        val dropped = mutableSetOf<String>()
        for ((i, m) in ms.withIndex()) {
            val k = questionKey(m) ?: continue
            for (j in i + 1 until ms.size) {
                if (ms[j].role == Role.USER) break
                if (questionKey(ms[j]) == k) { dropped += m.id; break }
            }
        }
        return ms.mapNotNull { m ->
            when {
                m.id in dropped && m.id != active?.messageId -> null
                m.id == active?.messageId -> active.notes?.takeIf { it.isNotBlank() }?.let { m.copy(text = it, quickReplies = emptyList(), question = null, fieldKey = null, knowledgeEntryIds = emptyList(), knowledgeStage = null) }
                questionKey(m) != null -> LessonParser.split(m.text).before.takeIf { it.isNotBlank() }?.let { m.copy(text = it, quickReplies = emptyList()) }
                else -> m
            }
        }
    }
}
