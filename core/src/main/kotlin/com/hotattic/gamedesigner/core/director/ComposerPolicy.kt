package com.hotattic.gamedesigner.core.director

/**
 * The rules of the answer box that are not about drawing. In a staged question the single answer-submitting action is CONFIRM; the separate send
 * arrow exists only for free messages (when no question is open), where it is the only way to send.
 */
object ComposerPolicy {
    /** No send arrow beside the answer field while a question is open: CONFIRM is the one submit action. */
    fun showSendArrow(questionActive: Boolean) = !questionActive

    /** The Enter key never submits; in a multi-line answer it starts a new line. */
    fun enterSubmits(@Suppress("UNUSED_PARAMETER") questionActive: Boolean) = false

    /** The keyboard closes the moment the answer is confirmed, without waiting for Bob. */
    const val DISMISS_KEYBOARD_ON_CONFIRM = true

    /** Showing a question or a stage never opens the keyboard; only the owner tapping an editable field does. */
    const val FOCUS_ON_NEW_QUESTION = false
}

/** Refuses a second submission of the same turn that arrives while the first is still being handled (a double tap). A retry later is allowed. */
class SubmitGuard(private val windowMs: Long = 1500L) {
    private var turn: String? = null
    private var at = 0L

    fun begin(turnId: String, nowMs: Long): Boolean {
        if (turn == turnId && nowMs - at < windowMs) return false
        turn = turnId; at = nowMs
        return true
    }
}
