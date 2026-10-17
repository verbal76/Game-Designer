package com.hotattic.gamedesigner.core.director

/**
 * The small amount of Markdown Bob's own messages use (bold, italic, code, bullets, headings), parsed into plain text plus style ranges, so the screen
 * never shows raw markers such as `**Game length.**`. Pure and testable; the screen turns a [Line] into styled text. Unmatched markers stay as typed.
 */
object SimpleMarkdown {
    enum class Style { BOLD, ITALIC, CODE }
    data class Span(val start: Int, val end: Int, val style: Style)
    /** [text] has no markers; [spans] index into it. [bullet] lines had a "- " / "* " lead; [heading] lines had leading #'s (drawn bold). */
    data class Line(val text: String, val spans: List<Span>, val bullet: Boolean = false, val heading: Boolean = false)

    private val bulletLead = Regex("^\\s{0,3}[-*\\u2022]\\s+")
    private val headingLead = Regex("^\\s{0,3}#{1,6}\\s+")

    fun parse(text: String): List<Line> = text.split("\n").map { raw ->
        val bullet = bulletLead.find(raw)
        val heading = if (bullet == null) headingLead.find(raw) else null
        val body = raw.removeRange(0, (bullet ?: heading)?.range?.last?.plus(1) ?: 0)
        val (plain, spans) = inline(body)
        Line(plain, if (heading != null) listOf(Span(0, plain.length, Style.BOLD)) + spans else spans, bullet != null, heading != null)
    }

    /** The text with every marker removed, for places that cannot style (notifications, accessibility labels, tests). */
    fun plain(text: String): String = parse(text).joinToString("\n") { (if (it.bullet) "• " else "") + it.text }

    private data class Marker(val open: String, val style: Style)
    private val markers = listOf(Marker("**", Style.BOLD), Marker("`", Style.CODE), Marker("*", Style.ITALIC), Marker("_", Style.ITALIC))

    private fun isWord(c: Char?) = c != null && (c.isLetterOrDigit())

    private fun inline(s: String): Pair<String, List<Span>> {
        val out = StringBuilder(); val spans = mutableListOf<Span>()
        var i = 0
        while (i < s.length) {
            var matched = false
            for (m in markers) {
                if (!s.startsWith(m.open, i)) continue
                // "_" and "*" italics only at word edges, so snake_case and 2*3 survive; "**" must not be read as two single stars.
                if (m.open == "*" && s.startsWith("**", i)) continue
                if (m.open == "_" && (isWord(s.getOrNull(i - 1)) || s.startsWith("__", i))) continue
                val from = i + m.open.length
                if (from >= s.length || s[from].isWhitespace()) continue
                var j = s.indexOf(m.open, from)
                while (j >= 0 && (s.getOrNull(j - 1)?.isWhitespace() == true || (m.open == "_" && isWord(s.getOrNull(j + 1))) || (m.open == "*" && s.startsWith("**", j)))) j = s.indexOf(m.open, j + 1)
                if (j < 0 || j == from) continue
                val (inner, innerSpans) = if (m.style == Style.CODE) s.substring(from, j) to emptyList() else inline(s.substring(from, j))
                val base = out.length
                out.append(inner); spans += Span(base, base + inner.length, m.style); spans += innerSpans.map { Span(it.start + base, it.end + base, it.style) }
                i = j + m.open.length; matched = true; break
            }
            if (!matched) { out.append(s[i]); i++ }
        }
        return out.toString() to spans
    }
}
