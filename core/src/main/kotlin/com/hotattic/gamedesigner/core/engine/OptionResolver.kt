package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.schema.Option

/**
 * Deterministic, general-purpose mapping of what an owner said onto the options of the question that was asked.
 * It is the offline fallback and the sanity check for the LLM interpreter; it is built from a few general mechanisms
 * (positional references, quantifiers with exceptions, distinctive-word matching, numeric interval matching with units)
 * rather than a list of magic phrases.
 */
object OptionResolver {

    enum class Mode { SELECT, ALL, NONE, DELEGATE, UNCLEAR }

    data class Resolution(val mode: Mode, val ids: List<String>, val confident: Boolean)

    private val wordNumbers = mapOf("one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10)
    private val ordinals = mapOf("first" to 1, "second" to 2, "third" to 3, "fourth" to 4, "fifth" to 5, "sixth" to 6, "seventh" to 7, "eighth" to 8, "ninth" to 9, "tenth" to 10,
        "1st" to 1, "2nd" to 2, "3rd" to 3, "4th" to 4, "5th" to 5, "6th" to 6, "7th" to 7, "8th" to 8, "9th" to 9, "10th" to 10)
    private val stop = setOf("the", "a", "an", "of", "and", "or", "to", "for", "in", "on", "with", "my", "it", "is", "ill", "i'll", "go", "want", "like", "please", "just", "that", "this", "those", "these", "them", "one", "ones", "i", "id", "i'd", "pick", "choose", "take", "use", "let", "lets", "let's", "me", "also", "too", "as", "be", "about", "around", "roughly")
    private val unitMinutes = mapOf("minute" to 1.0, "minutes" to 1.0, "min" to 1.0, "mins" to 1.0, "hour" to 60.0, "hours" to 60.0, "hr" to 60.0, "hrs" to 60.0, "h" to 60.0)

    private val allCue = Regex("\\b(all|everything|every one|every single|each of them|each|the lot|whole list|entire list)\\b")
    private val noneCue = Regex("\\b(none|nothing|neither|clear all|clear them|no menus|nothing at all|skip them all|none of them|none of those)\\b")
    private val exceptCue = Regex("\\b(except|but not|other than|excluding|apart from|besides|minus|without|but leave out|leave out|not including)\\b")
    private val delegateCue = Regex("\\b(you choose|you pick|you decide|choose for me|pick for me|decide for me|surprise me|up to you|your call|whatever you recommend|your recommendation|whatever makes sense|what makes sense|whatever is best|whatever you think|you tell me|go with your|i don'?t care|dont care|best option|sensible|bob choose)\\b")

    fun norm(s: String) = s.lowercase().replace("’", "'").replace("–", "-").replace("—", "-")
        .replace(Regex("[^a-z0-9.'+\\-#\\s]"), " ").replace(Regex("\\s+"), " ").trim()

    fun resolve(options: List<Option>, multi: Boolean, text: String): Resolution {
        val t = norm(text)
        if (t.isEmpty() || options.isEmpty()) return Resolution(Mode.UNCLEAR, emptyList(), false)
        val allIds = options.map { it.id }

        // Quantifier with optional exception: "all", "everything except language", "all but the last one".
        val ex = exceptCue.find(t)
        if (ex != null) {
            val left = t.substring(0, ex.range.first).trim()
            val right = t.substring(ex.range.last + 1).trim()
            val removed = select(options, right, multi = true).first.toSet()
            if (removed.isNotEmpty()) {
                val base = if (left.isEmpty() || allCue.containsMatchIn(left)) allIds else select(options, left, multi = true).first
                val ids = base.filter { it !in removed }
                if (ids.isNotEmpty() || base.isNotEmpty()) return Resolution(Mode.SELECT, ids, true)
            }
        }
        if (allCue.containsMatchIn(t) && !delegateCue.containsMatchIn(t)) {
            // "all" only means everything when the rest of the sentence selects nothing narrower ("all of those", "all of them").
            val narrower = select(options, t.replace(allCue, " "), multi = true).first
            return if (narrower.isEmpty() || !multi) Resolution(Mode.ALL, allIds, true) else Resolution(Mode.SELECT, narrower, true)
        }
        if (delegateCue.containsMatchIn(t)) return Resolution(Mode.DELEGATE, emptyList(), true)
        val (ids, confident) = select(options, t, multi)
        if (ids.isNotEmpty()) return Resolution(Mode.SELECT, ids, confident)
        if (noneCue.containsMatchIn(t)) return Resolution(Mode.NONE, emptyList(), true)
        return Resolution(Mode.UNCLEAR, emptyList(), false)
    }

    /** Positional first (it is unambiguous when present), then numeric intervals, then wording. */
    private fun select(options: List<Option>, t: String, multi: Boolean): Pair<List<String>, Boolean> {
        positional(options.size, t)?.let { idx -> return idx.mapNotNull { options.getOrNull(it - 1)?.id }.distinct().let { if (multi) it else it.take(1) } to true }
        val numeric = numeric(options, t)
        if (numeric.isNotEmpty()) return (if (multi) numeric else numeric.take(1)) to true
        return lexical(options, t, multi)
    }

    // ---- positional: "option three", "the third one", "1, 3 and 5", "first three", "last", "1 to 3" -----------------

    private fun positional(n: Int, t: String): List<Int>? {
        val cue = Regex("\\b(option|options|choice|choices|number|numbers|pick|#)\\s*")
        val hasCue = cue.containsMatchIn(t)
        val hasUnit = Regex("\\b(minutes?|mins?|hours?|hrs?|seconds?|days?|fps|percent|%|gb|mb)\\b").containsMatchIn(t)
        val out = mutableListOf<Int>()

        Regex("\\b(first|top|last|bottom)\\s+(\\d+|one|two|three|four|five|six|seven|eight|nine|ten)\\b").find(t)?.let { m ->
            val k = m.groupValues[2].toIntOrNull() ?: wordNumbers[m.groupValues[2]] ?: return@let
            if (k in 1..n) return if (m.groupValues[1] in setOf("first", "top")) (1..k).toList() else ((n - k + 1)..n).toList()
        }
        if (Regex("\\b(second to last|second last|penultimate)\\b").containsMatchIn(t) && n >= 2) out += n - 1
        else if (Regex("\\b(last|final|bottom)( one)?\\b").containsMatchIn(t) && !Regex("\\blast (\\d+|\\w+) ?(minutes?|hours?)").containsMatchIn(t)) out += n
        ordinals.forEach { (w, k) -> if (Regex("\\b$w\\b").containsMatchIn(t) && k <= n) out += k }

        // numbers: only treated as positions when they are small, in range, and not attached to a unit
        val numsOnly = t.replace(Regex("\\b(option|options|choice|choices|number|numbers|pick|and|also|plus|then|the|a|please|i'll|ill|go|with|take|want|choose|through|thru|to|and then|&)\\b"), " ").replace(Regex("[,\\s#]+"), " ").trim()
        val wordsToDigits = wordNumbers.entries.fold(numsOnly) { acc, (w, k) -> acc.replace(Regex("\\b$w\\b"), k.toString()) }
        val onlyNumbers = wordsToDigits.matches(Regex("[\\d\\s\\-]+")) && Regex("\\d").containsMatchIn(wordsToDigits)
        if (onlyNumbers && (hasCue || out.isEmpty()) && !hasUnit) {
            val rangeMatches = Regex("(\\d+)\\s*(?:-|to|through|thru)\\s*(\\d+)").findAll(t.replace(wordNumbers, ))
            rangeMatches.forEach { m -> val a = m.groupValues[1].toInt(); val b = m.groupValues[2].toInt(); if (a in 1..n && b in 1..n && a <= b) out += (a..b) }
            Regex("\\d+").findAll(wordsToDigits).map { it.value.toInt() }.filter { it in 1..n }.forEach { out += it }
            if (out.isNotEmpty() && Regex("\\d+").findAll(wordsToDigits).all { it.value.toInt() in 1..n }) return out.distinct().sorted()
            return null
        }
        if (hasCue && !hasUnit) {
            Regex("\\b(?:option|choice|number|#)\\s*(\\d+|one|two|three|four|five|six|seven|eight|nine|ten)\\b").findAll(t).forEach { m ->
                val k = m.groupValues[1].toIntOrNull() ?: wordNumbers[m.groupValues[1]]
                if (k != null && k in 1..n) out += k
            }
        }
        return out.distinct().sorted().ifEmpty { null }
    }

    private fun String.replace(map: Map<String, Int>, unused: Unit = Unit): String = map.entries.fold(this) { acc, (w, k) -> acc.replace(Regex("\\b$w\\b"), k.toString()) }

    // ---- numeric intervals with units: "30 to 60 minutes" ~ "30-60 minutes", "45 minutes" in [30,60], "2 hours" ~ "1+ hours" -----

    private data class Interval(val lo: Double, val hi: Double)

    private fun unitFactor(s: String): Double? = Regex("\\b(minutes?|mins?|hours?|hrs?|h)\\b").find(s)?.let { unitMinutes[it.value] }

    private fun labelInterval(label: String): Interval? {
        val l = norm(label)
        val f = unitFactor(l) ?: 1.0
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:-|to)\\s*(\\d+(?:\\.\\d+)?)").find(l)?.let { return Interval(it.groupValues[1].toDouble() * f, it.groupValues[2].toDouble() * f) }
        Regex("(\\d+(?:\\.\\d+)?)\\s*\\+").find(l)?.let { return Interval(it.groupValues[1].toDouble() * f, Double.POSITIVE_INFINITY) }
        return null
    }

    private fun textInterval(t: String, defaultFactor: Double): Interval? {
        val f = unitFactor(t) ?: defaultFactor
        Regex("(\\d+(?:\\.\\d+)?)\\s*(?:-|to|through|and)\\s*(\\d+(?:\\.\\d+)?)").find(t)?.let { return Interval(it.groupValues[1].toDouble() * f, it.groupValues[2].toDouble() * f) }
        Regex("(\\d+(?:\\.\\d+)?)\\s*\\+|(?:over|more than|at least)\\s*(\\d+(?:\\.\\d+)?)").find(t)?.let { m -> val v = (m.groupValues[1].ifEmpty { m.groupValues[2] }).toDouble() * f; return Interval(v, Double.POSITIVE_INFINITY) }
        Regex("(\\d+(?:\\.\\d+)?)").find(t)?.let { val v = it.groupValues[1].toDouble() * f; return Interval(v, v) }
        return null
    }

    private fun numeric(options: List<Option>, t: String): List<String> {
        val intervals = options.map { it.id to labelInterval(it.label) }.filter { it.second != null }
        if (intervals.size < 2 || !Regex("\\d").containsMatchIn(t)) return emptyList()
        val labelUnit = options.firstNotNullOfOrNull { unitFactor(norm(it.label)) } ?: 1.0
        val u = textInterval(t, labelUnit) ?: return emptyList()
        val hits = intervals.filter { (_, iv) ->
            iv!!
            if (u.lo == u.hi) u.lo >= iv.lo && u.lo <= iv.hi
            else {
                val overlap = minOf(u.hi, iv.hi) - maxOf(u.lo, iv.lo)
                val denom = minOf(u.hi - u.lo, iv.hi - iv.lo)
                overlap > 0 && overlap >= 0.5 * denom
            }
        }
        // Prefer the tightest match when several intervals contain the point (e.g. 60 minutes sits on a boundary).
        return hits.sortedBy { (_, iv) -> iv!!.hi - iv.lo }.map { it.first }.take(if (u.lo == u.hi) 1 else hits.size)
    }

    // ---- wording: full label, distinctive words, then overlap ---------------------------------------------------------

    private fun tokens(s: String): Set<String> = norm(s).split(' ').map { it.trim('.', '-', '\'') }.filter { it.length >= 2 && it !in stop }.toSet()

    private fun lexical(options: List<Option>, t: String, multi: Boolean): Pair<List<String>, Boolean> {
        val utt = tokens(t)
        val optTokens = options.associate { it.id to (tokens(it.label) + tokens(it.id.replace('_', ' '))) }
        val padded = " ${t.replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ")} "
        val scored = options.map { o ->
            val mine = optTokens.getValue(o.id)
            val others = options.filter { it.id != o.id }.flatMap { optTokens.getValue(it.id) }.toSet()
            val distinctive = mine - others
            val label = " ${norm(o.label).replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()} "
            val phrase = label.trim().isNotEmpty() && padded.contains(label)
            val distinctHits = (distinctive intersect utt).size
            val overlap = if (mine.isEmpty()) 0.0 else (mine intersect utt).size.toDouble() / mine.size
            val score = when {
                phrase -> 1.0
                distinctHits >= 1 -> 0.8 + 0.05 * distinctHits
                overlap >= 0.6 -> overlap
                else -> 0.0
            }
            o.id to score
        }.filter { it.second >= 0.6 }
        if (scored.isEmpty()) return emptyList<String>() to false
        if (multi) return scored.map { it.first } to scored.all { it.second >= 0.8 }
        val best = scored.maxOf { it.second }
        val top = scored.filter { it.second >= best - 0.05 }
        return if (top.size == 1) listOf(top.first().first) to (best >= 0.8) else emptyList<String>() to false // ambiguous: ask again
    }
}
