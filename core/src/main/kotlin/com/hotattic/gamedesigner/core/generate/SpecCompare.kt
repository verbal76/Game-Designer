package com.hotattic.gamedesigner.core.generate

/** One line of a side-by-side text comparison. [kind]: SAME, CHANGED (left and right differ), REMOVED (left only), ADDED (right only), GAP (collapsed unchanged lines). */
data class DiffRow(val left: String?, val right: String?, val kind: String)

/** A heading-delimited part of a spec, compared between two versions. [status]: SAME, CHANGED, NEW or REMOVED. */
data class SectionDiff(val title: String, val status: String, val rows: List<DiffRow>, val changedLines: Int)

/**
 * Side-by-side comparison of two generated specs (or prompts). Sections are matched by heading so that moving a paragraph inside
 * a section does not look like a rewrite of the whole file; inside a section, lines are aligned (LCS) so only real edits light up.
 */
object SpecCompare {

    private val heading = Regex("^#{1,3} +\\S")

    fun sections(text: String): List<Pair<String, List<String>>> {
        val out = mutableListOf<Pair<String, MutableList<String>>>()
        var cur: MutableList<String> = mutableListOf(); var title = "Preamble"; var started = false
        val seen = mutableMapOf<String, Int>()
        fun flush() { if (started || cur.isNotEmpty()) out += title to cur }
        for (line in text.lines()) {
            if (heading.containsMatchIn(line)) {
                flush(); started = true; cur = mutableListOf()
                val base = line.trim('#', ' ').trim()
                val n = (seen[base] ?: 0) + 1; seen[base] = n
                title = if (n == 1) base else "$base ($n)"
            }
            cur.add(line)
        }
        flush()
        return out.map { (t, ls) -> t to ls.dropLastWhile { it.isBlank() } }.filter { (_, ls) -> ls.isNotEmpty() }
    }

    /** Aligns two line lists; consecutive removed+added lines are paired as CHANGED. */
    fun align(a: List<String>, b: List<String>): List<DiffRow> {
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) dp[i][j] = if (a[i].trim() == b[j].trim()) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val rows = mutableListOf<DiffRow>()
        var i = 0; var j = 0
        val del = mutableListOf<String>(); val ins = mutableListOf<String>()
        fun flush() {
            val k = minOf(del.size, ins.size)
            for (x in 0 until k) rows += DiffRow(del[x], ins[x], "CHANGED")
            for (x in k until del.size) rows += DiffRow(del[x], null, "REMOVED")
            for (x in k until ins.size) rows += DiffRow(null, ins[x], "ADDED")
            del.clear(); ins.clear()
        }
        while (i < n && j < m) {
            when {
                a[i].trim() == b[j].trim() -> { flush(); rows += DiffRow(a[i], b[j], "SAME"); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { del += a[i]; i++ }
                else -> { ins += b[j]; j++ }
            }
        }
        while (i < n) { del += a[i]; i++ }
        while (j < m) { ins += b[j]; j++ }
        flush()
        return rows
    }

    /** Collapses long runs of unchanged lines, keeping [context] lines around every change. */
    fun collapse(rows: List<DiffRow>, context: Int = 1): List<DiffRow> {
        val keep = BooleanArray(rows.size)
        rows.forEachIndexed { i, r -> if (r.kind != "SAME") for (x in (i - context)..(i + context)) if (x in rows.indices) keep[x] = true }
        val out = mutableListOf<DiffRow>(); var skipped = 0
        rows.forEachIndexed { i, r ->
            if (keep[i]) { if (skipped > 0) { out += DiffRow("... $skipped unchanged line(s) ...", "... $skipped unchanged line(s) ...", "GAP"); skipped = 0 }; out += r } else skipped++
        }
        if (skipped > 0) out += DiffRow("... $skipped unchanged line(s) ...", "... $skipped unchanged line(s) ...", "GAP")
        return out
    }

    fun compare(old: String, new: String): List<SectionDiff> {
        val a = sections(old).toMap(LinkedHashMap()); val b = sections(new).toMap(LinkedHashMap())
        val order = (b.keys + a.keys).distinct()
        return order.map { t ->
            val l = a[t]; val r = b[t]
            when {
                l == null -> SectionDiff(t, "NEW", r!!.map { DiffRow(null, it, "ADDED") }, r.size)
                r == null -> SectionDiff(t, "REMOVED", l.map { DiffRow(it, null, "REMOVED") }, l.size)
                else -> {
                    val rows = align(l, r); val changed = rows.count { it.kind != "SAME" }
                    SectionDiff(t, if (changed == 0) "SAME" else "CHANGED", rows, changed)
                }
            }
        }
    }
}
