package com.hotattic.gamedesigner.core.engine

/** Whether a message is unmistakably asking to CHANGE something already decided ("actually make it pixel art", "switch to portrait"), as opposed to mentioning it. */
object OwnerChange {
    private val cue = Regex("(?i)\\b(actually|instead|rather than|from now on|i changed my mind|change (?:it|that|the \\w+)|switch (?:it|to)|make it|should (?:now )?be|must be|let'?s (?:go with|use|make)|i want it to be)\\b")

    fun isChange(text: String): Boolean = cue.containsMatchIn(text)
}
