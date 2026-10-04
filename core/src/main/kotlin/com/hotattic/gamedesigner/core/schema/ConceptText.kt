package com.hotattic.gamedesigner.core.schema

/** Deterministic text hygiene for turning a rambling (often dictated) concept into usable spec phrases. */
object ConceptText {
    private val platformWord = Regex("(?i)\\b(android|iphone|ios|ipad|phone|mobile|pc|windows|macos|mac|linux|browser|steam|tablet|google play)\\b")
    private val leadIn = Regex("(?i)^(?:i\\s+want(?:\\s+to\\s+make)?|i(?:'d| would)\\s+like(?:\\s+to\\s+make)?|let'?s\\s+make|make\\s+me)\\s+(?:a|an|the)?\\s*")

    fun sentences(text: String): List<String> =
        text.trim().split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.isNotEmpty() }

    /** Drops sentences that are only about where it will be played. Never returns empty for non-empty input. */
    fun withoutPlatformSentences(text: String): String {
        val keep = sentences(text).filter { !platformWord.containsMatchIn(it) }
        return (if (keep.isEmpty()) sentences(text).take(1) else keep).joinToString(" ").trim()
    }

    private fun secondPerson(s: String) = s
        .replace(Regex("(?i)\\bI'm\\b"), "You are").replace(Regex("(?i)\\bI am\\b"), "You are")
        .replace(Regex("(?i)\\bI'd\\b"), "You would").replace(Regex("(?i)\\bmy\\b"), "your")
        .replace(Regex("(?i)\\bI\\b"), "You")

    /** A one-or-two sentence core fantasy drawn from the concept. */
    fun fantasy(concept: String): String {
        val cleaned = withoutPlatformSentences(concept)
        val after = Regex("(?i)(?:,\\s*|\\s)but\\s+(.+)").find(cleaned)?.groupValues?.get(1)
            ?: Regex("(?i)\\bwhere\\s+(.+)").find(cleaned)?.groupValues?.get(1)
        var core = (if (after != null && after.trim().length >= 12) after else cleaned).trim()
        core = core.replace(leadIn, "").trim()
        if (core.isEmpty()) core = cleaned
        core = secondPerson(core)
        core = core.replaceFirstChar { it.uppercase() }
        return if (core.endsWith(".") || core.endsWith("!") || core.endsWith("?")) core else "$core."
    }

    /** Short theme phrase for derived content text; cut on a word boundary, never mid-word. */
    fun theme(concept: String, maxLen: Int = 110): String {
        val f = fantasy(concept).trimEnd('.', '!', '?')
        if (f.length <= maxLen) return f
        val cut = f.substring(0, maxLen).substringBeforeLast(' ')
        return cut.ifEmpty { f.substring(0, maxLen) }
    }
}
