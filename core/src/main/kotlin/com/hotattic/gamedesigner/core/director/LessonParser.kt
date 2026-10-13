package com.hotattic.gamedesigner.core.director

import com.hotattic.gamedesigner.core.model.Experience

/** A teaching card split into its parts so a phone screen can show the essentials first and the rest on demand. Pure data: testable without a device. */
data class LessonView(
    val term: String,
    val what: String,
    val how: String?,
    val why: String?,
    val advantages: String?,
    val disadvantages: String?,
    val recommendation: String?,
    val ifElse: String?,
    val longTerm: String?,
    val more: String?,
    /** The consequence is expensive to reverse. Always shown, never tucked away. */
    val hardToReverse: Boolean,
) {
    /** True when there is something worth expanding (how it works, what happens if you choose differently, long-term effects, deeper detail). */
    val hasDetails: Boolean get() = listOfNotNull(how, ifElse, longTerm, more).isNotEmpty()
}

/** A Director message as the screen draws it: the question text, the lesson card (if any), and whatever follows the card (numbered options, a suggestion). */
data class MessageParts(val before: String, val lesson: LessonView?, val after: String)

/** Splits a Director message into prose and lesson card. The card format is produced by Explainer.card, and the two are tested against each other. */
object LessonParser {
    private val head = Regex("^\\*\\*(.+?)\\.\\*\\*\\s*(.*)$")
    private val labels = listOf("How it works: ", "Why it matters here: ", "Advantages: ", "Disadvantages: ", "Bob recommends: ", "If you choose something else: ", "Long-term: ", "More detail: ")
    private const val TRAILER = "(Say \"explain more\""
    private val hard = Regex("(?i)\\bhard(?:est)?\\b[^.]*\\b(?:change|reverse)\\b")

    fun split(text: String): MessageParts {
        val lines = text.split("\n")
        // A card is the heading line followed immediately (no blank line) by its labelled lines; ordinary bold headings elsewhere in a message are not cards.
        val start = lines.indices.firstOrNull { i -> head.matches(lines[i]) && (lines.getOrNull(i + 1)?.startsWith("Why it matters here: ") == true || (lines.getOrNull(i + 1)?.startsWith("How it works: ") == true && lines.getOrNull(i + 2)?.startsWith("Why it matters here: ") == true)) }
            ?: return MessageParts(text, null, "")
        val m = head.matchEntire(lines[start])!!
        val fields = linkedMapOf<String, String>()
        var i = start + 1
        while (i < lines.size) {
            val l = lines[i]
            val label = labels.firstOrNull { l.startsWith(it) }
            if (label != null) { fields[label] = l.removePrefix(label).trim(); i++ }
            else if (l.startsWith(TRAILER)) { i++; break }
            else break
        }
        val longTerm = fields["Long-term: "]
        val lesson = LessonView(
            term = m.groupValues[1].trim(), what = m.groupValues[2].trim(), how = fields["How it works: "], why = fields["Why it matters here: "],
            advantages = fields["Advantages: "], disadvantages = fields["Disadvantages: "], recommendation = fields["Bob recommends: "],
            ifElse = fields["If you choose something else: "], longTerm = longTerm, more = fields["More detail: "],
            hardToReverse = longTerm?.let { hard.containsMatchIn(it) } == true,
        )
        return MessageParts(lines.take(start).joinToString("\n").trim(), lesson, lines.drop(i).joinToString("\n").trim())
    }
}

/** The two ways to work. Same design engine, same plan, same decisions: only how much Bob asks and shows differs. */
enum class DesignMode(val label: String, val blurb: String, val experience: Experience) {
    BEGINNER("Beginner", "Bob explains the choices and recommends the technical details.", Experience.BEGINNER),
    ADVANCED("Advanced", "Show additional technical options and give me more control.", Experience.EXPERT);

    companion object {
        const val BOTH_MODES_NOTE = "Both modes produce the same complete development plan."
        fun of(e: Experience): DesignMode = if (e == Experience.BEGINNER) BEGINNER else ADVANCED
    }
}
