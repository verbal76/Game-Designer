package com.hotattic.gamedesigner.core.director

/**
 * When the conversation should follow new content and when it must leave the owner's reading position alone. Pure decision logic: the Compose screen
 * reports what happened (an answer was confirmed, content arrived, the keyboard moved, the owner dragged the list) and does what this says, after layout.
 *
 *  - An explicit owner action (confirm, back/next, "return to latest") always brings the newest content into view and resumes following.
 *  - New content (Bob's reply, the next question, a review) is followed only while the owner is at the bottom. If the owner scrolled up to read,
 *    the position is kept and the return control says there is something new.
 *  - The keyboard opening or closing never moves a reader who scrolled up; a reader at the bottom stays at the bottom while the viewport resizes.
 *  - Restoring a conversation shows the newest content once, instantly.
 */
object ConversationScroll {
    enum class Event { CONFIRMED, ADVANCED, RETURN_TO_LATEST, NEW_CONTENT, KEYBOARD_CHANGED, RESTORED, USER_SCROLLED_AWAY, USER_REACHED_BOTTOM }

    /** What the screen must do once the layout has settled. */
    enum class Action {
        NONE,
        /** Align the first new item with the top of the viewport; a short tail simply ends at the bottom, a tall one can be read from its start. */
        SHOW_NEW_CONTENT,
        /** Bring the very end of the conversation into view. */
        SHOW_BOTTOM,
    }

    data class State(val following: Boolean = true, val unseen: Boolean = false)
    data class Step(val state: State, val action: Action, val animate: Boolean = true)

    fun step(s: State, e: Event): Step = when (e) {
        Event.CONFIRMED, Event.ADVANCED, Event.RETURN_TO_LATEST -> Step(State(following = true, unseen = false), Action.SHOW_BOTTOM)
        Event.RESTORED -> Step(State(following = true, unseen = false), Action.SHOW_BOTTOM, animate = false)
        Event.NEW_CONTENT -> if (s.following) Step(s, Action.SHOW_NEW_CONTENT) else Step(s.copy(unseen = true), Action.NONE)
        Event.KEYBOARD_CHANGED -> if (s.following) Step(s, Action.SHOW_BOTTOM, animate = false) else Step(s, Action.NONE)
        Event.USER_SCROLLED_AWAY -> Step(s.copy(following = false), Action.NONE)
        Event.USER_REACHED_BOTTOM -> Step(State(following = true, unseen = false), Action.NONE)
    }

    /** The return-to-latest control exists only while there is content below the visible area. */
    fun showReturnControl(canScrollForward: Boolean) = canScrollForward

    fun returnLabel(s: State) = if (s.unseen) "New message below ↓" else "More below ↓"
}
