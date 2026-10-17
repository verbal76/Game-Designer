package com.hotattic.gamedesigner.core.engine

/** Which project is being reevaluated right now, and how the last attempt ended. One reevaluation at a time; the picker highlights exactly that project. */
sealed class ReevalRunState {
    object Idle : ReevalRunState()
    data class Running(val projectId: String, val projectName: String) : ReevalRunState()
    data class Done(val projectId: String) : ReevalRunState()
    data class Failed(val projectId: String, val message: String) : ReevalRunState()

    /** The project this state is about, or null when idle. */
    val id: String? get() = when (this) { is Running -> projectId; is Done -> projectId; is Failed -> projectId; Idle -> null }
}

/** Pure state rules for the reevaluation picker, so rapid taps, failure, cancellation and completion are testable without a screen. */
class ReevalRunTracker {
    var state: ReevalRunState = ReevalRunState.Idle
        private set

    val running get() = state is ReevalRunState.Running

    /** Starts a run unless one is already going. A second tap (on the same or another project) is ignored. */
    fun begin(projectId: String, projectName: String): Boolean {
        if (running) return false
        state = ReevalRunState.Running(projectId, projectName); return true
    }

    fun succeed(projectId: String) { if ((state as? ReevalRunState.Running)?.projectId == projectId) state = ReevalRunState.Done(projectId) }
    fun fail(projectId: String, message: String) { if ((state as? ReevalRunState.Running)?.projectId == projectId) state = ReevalRunState.Failed(projectId, message) }
    /** Cancelled by the owner: nothing was saved, so there is nothing to report. */
    fun cancel() { if (running) state = ReevalRunState.Idle }
    /** The owner has seen the result (navigated on, or dismissed the error). */
    fun acknowledge() { if (!running) state = ReevalRunState.Idle }

    fun isRunning(projectId: String) = (state as? ReevalRunState.Running)?.projectId == projectId
}
