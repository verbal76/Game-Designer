package com.hotattic.gamedesigner.core.knowledge

import com.hotattic.gamedesigner.core.model.KnowledgeNote
import com.hotattic.gamedesigner.core.model.KnowledgeStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.QuickReply

/** Wording, reply recognition and ledger updates for reference examples. Pure: it never writes a decision. */
object KnowledgeAdvisor {
    const val EXAMPLE_COMMAND = "show an example for this question"
    const val BACK_LABEL = "Back to my question"
    const val NONE_LABEL = "None of these fit"
    const val NOT_FOR_ME = "Not for my game"
    const val CARD = "CARD"
    const val ASPECT = "ASPECT"

    private val cue = listOf(
        Regex("(?i)\\b(?:give|show|got|have|send)\\b[^.?!]{0,25}\\b(?:examples?|advice|alternatives|inspiration|suggestions)\\b"),
        Regex("(?i)\\b(?:any|some|what)\\s+(?:examples?|advice|alternatives|suggestions|ideas)\\b"),
        Regex("(?i)\\bhow (?:do|did|does|have) (?:other|different|some|most|many|good) games?\\b"),
        Regex("(?i)\\bwhat do(?:es)? (?:other|most) games? do\\b"),
        Regex("(?i)\\b(?:need|want|could use|would like)\\s+(?:some\\s+)?(?:help|advice|ideas|examples?|inspiration)\\s+(?:with|on|for|about|in|from)\\b"),
        Regex("(?i)\\bwhat are my (?:options|alternatives|choices) for\\b"),
        Regex("(?i)\\b(?:examples?|advice|inspiration) (?:of|for|on|about|from)\\b"),
        Regex("(?i)\\bhow (?:does|did|do) [A-Z][\\w' -]{1,30} (?:handle|deal with|approach|do)\\b"),
    )

    fun isRequest(text: String): Boolean = text.trim().equals(EXAMPLE_COMMAND, true) || cue.any { it.containsMatchIn(text) }

    private fun interestLabel(e: KnowledgeEntry) = "Interested in ${e.game}"

    fun quickReplies(entries: List<KnowledgeEntry>) =
        entries.map { QuickReply(interestLabel(it), interestLabel(it)) } + QuickReply(NONE_LABEL, NONE_LABEL) + QuickReply(BACK_LABEL, BACK_LABEL)

    fun cardText(matches: List<KnowledgeLibrary.Match>): String = buildString {
        append("Here ${if (matches.size == 1) "is one possible approach" else "are two possible approaches"} from a design talk I have studied. They are ideas to weigh, not recommendations for your game, and nothing in your design has changed.\n")
        for (m in matches) {
            val e = m.entry
            append("\n${e.game}: ${e.problem.trimEnd('.')}.\n")
            append(if (e.fromPaper) "What the paper describes: ${e.mechanic}\n" else "How it works: ${e.mechanic}\n")
            append(if (e.fromPaper) "The experience it names: ${e.effect}\n" else "What it does for players: ${e.effect}\n")
            e.tradeoffs?.let { append(if (e.fromPaper) "Risks and limits: $it\n" else "Worth weighing: $it\n") }
            e.inference?.let { append("My own analysis (not from the source): $it\n") }
            append("Why I raised it: ${m.reason}.\n")
            append("Source: ${e.sourceTitle}, ${e.sourceCreator}, ${e.timestamp}\n")
            if (e.fromPaper) append("Source status: ${e.source.status} Mentioning it is not a request to add any mechanic.\n")
        }
    }.trimEnd()

    fun noMatchText() = "I don't have a well-supported example for that, so I won't stretch one. Your design is unchanged; tell me in your own words what you want and I'll record it."

    fun aspectText(e: KnowledgeEntry) =
        "Noted as interest only; nothing in your design has changed. To use any of it I need your specific choice. ${e.question} Tell me in your own words, or say \"none of it\"."

    private fun withNote(p: Project, id: String, status: KnowledgeStatus, now: Long) =
        p.copy(knowledge = p.knowledge.filterNot { it.entryId == id && it.status == status } + KnowledgeNote(id, status, now))

    fun markShown(p: Project, entries: List<KnowledgeEntry>, now: Long) = entries.fold(p) { acc, e -> withNote(acc, e.id, KnowledgeStatus.SHOWN, now) }
    fun markInterested(p: Project, id: String, now: Long) = withNote(p, id, KnowledgeStatus.INTERESTED, now)
    fun markRejected(p: Project, ids: List<String>, now: Long) = ids.fold(p) { acc, id -> withNote(acc, id, KnowledgeStatus.REJECTED, now) }
    fun markApplied(p: Project, id: String, now: Long) = withNote(p, id, KnowledgeStatus.APPLIED, now)

    sealed class Reply {
        data class Interested(val entry: KnowledgeEntry) : Reply()
        object None : Reply()
        object Back : Reply()
    }

    /** Recognises only the card's own quick replies (exact label), so ordinary answers are never swallowed. */
    fun parseCardReply(text: String, ids: List<String>): Reply? {
        val t = text.trim()
        if (t.equals(NONE_LABEL, true) || t.equals(NOT_FOR_ME, true)) return Reply.None
        if (t.equals(BACK_LABEL, true)) return Reply.Back
        return ids.mapNotNull { KnowledgeLibrary.byId(it) }.firstOrNull { t.equals(interestLabel(it), true) }?.let { Reply.Interested(it) }
    }

    private val declines = Regex("(?i)^\\s*(?:none of it|none of that|never ?mind|not for my game|no thanks?|skip (?:it|that)|forget (?:it|that))\\W*$")
    fun declinesAspect(text: String) = declines.containsMatchIn(text)
}
