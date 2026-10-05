package com.hotattic.gamedesigner.core.engine

/**
 * Separates talk ABOUT USING Game Designer ("I already uploaded it", "go back", "why are you asking again") from game-design
 * content. Meta statements help conversation repair; they must never become requirements in the spec.
 */
object MetaConversation {
    private val patterns = listOf(
        Regex("(?i)\\bi(?:'ve|\\s+have)?\\s+(?:already|just|now)\\s+(?:added|uploaded|attached|answered|told|said|sent|picked|chosen|selected|chose|gave|given|done|did|put)\\b"),
        Regex("(?i)\\bi(?:'ve|\\s+have)?\\s+(?:told|asked|said|answered|sent|uploaded|added)\\s+(?:you\\s+)?(?:it\\s+|that\\s+|this\\s+)?(?:once|twice|already|before|two times|three times|\\d+ times)\\b"),
        Regex("(?i)\\b(?:twice|two times|three times|again)\\b.{0,30}\\b(?:added|uploaded|attached|asked|told|answered|sent)\\b"),
        Regex("(?i)\\b(?:added|uploaded|attached|asked|told|answered|sent)\\b.{0,30}\\b(?:twice|two times|three times)\\b"),
        Regex("(?i)\\bwhy\\s+(?:are|did|do|is)\\s+(?:you|it)\\s+(?:keep\\s+)?(?:asking|ask|repeating|saying|showing|not)\\b"),
        Regex("(?i)\\byou(?:'re|\\s+are)?\\s+(?:keep\\s+)?(?:asking|repeating)\\b"),
        Regex("(?i)\\byou\\s+(?:already|just)\\s+asked\\b"),
        Regex("(?i)\\b(?:go|going)\\s+back\\b"),
        Regex("(?i)\\b(?:that|the|this)\\s+(?:button|chip|picker|upload|attachment|banner|screen|app)\\b.{0,40}\\b(?:didn't|did not|doesn't|does not|isn't|is not|not|won't|failed|broke)\\b"),
        Regex("(?i)\\bi\\s+meant\\s+(?:option|number|choice|the\\s+(?:first|second|third|fourth|fifth|last))\\b"),
        Regex("(?i)\\bi\\s+already\\s+(?:answered|said|did|uploaded|gave|picked|chose|selected|told)\\b"),
        Regex("(?i)\\b(?:asked|answered)\\s+(?:me\\s+)?(?:that|this|it)\\s+(?:already|before)\\b"),
        Regex("(?i)\\b(?:it|that)\\s+(?:didn't|did not)\\s+(?:work|upload|attach|save)\\b"),
    )

    fun isMeta(sentence: String): Boolean = patterns.any { it.containsMatchIn(sentence) }

    /** The sentences of [text] that describe the game, in order. */
    fun designSentences(text: String, minLen: Int = 12): List<String> =
        text.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.length >= minLen && !isMeta(it) }
}
