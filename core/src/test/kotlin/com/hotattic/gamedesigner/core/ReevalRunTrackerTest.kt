package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.ReevalRunState
import com.hotattic.gamedesigner.core.engine.ReevalRunTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The rules behind the reevaluation picker: which card is highlighted, that only one run happens, and how it ends. */
class ReevalRunTrackerTest {
    @Test fun theChosenProjectIsHighlightedAndOnlyThatOne() {
        val t = ReevalRunTracker(); assertEquals(ReevalRunState.Idle, t.state); assertEquals(null, t.state.id)
        assertTrue(t.begin("a", "Alpha")); assertEquals(ReevalRunState.Running("a", "Alpha"), t.state)
        assertTrue(t.isRunning("a")); assertFalse(t.isRunning("b")); assertEquals("a", t.state.id)
    }

    @Test fun rapidTapsAndASecondProjectAreIgnoredWhileOneRuns() {
        val t = ReevalRunTracker(); assertTrue(t.begin("a", "Alpha"))
        repeat(5) { assertFalse(t.begin("a", "Alpha")) }; assertFalse(t.begin("b", "Beta"))
        assertEquals(ReevalRunState.Running("a", "Alpha"), t.state, "the first run is untouched")
    }

    @Test fun completionLeadsToTheRightProjectThenIdle() {
        val t = ReevalRunTracker(); t.begin("a", "Alpha"); t.succeed("b")
        assertTrue(t.running, "a result for a different project is ignored"); t.succeed("a")
        assertEquals(ReevalRunState.Done("a"), t.state); t.acknowledge(); assertEquals(ReevalRunState.Idle, t.state)
        assertTrue(t.begin("b", "Beta"), "a new run can start after the result was seen")
    }

    @Test fun failureIsShownOnThatCardAndCanBeDismissedOrRetried() {
        val t = ReevalRunTracker(); t.begin("a", "Alpha"); t.fail("a", "model crashed. Your design is unchanged.")
        assertEquals(ReevalRunState.Failed("a", "model crashed. Your design is unchanged."), t.state); assertFalse(t.running)
        assertTrue(t.begin("a", "Alpha"), "try again from the failed state"); t.fail("a", "x"); t.acknowledge(); assertEquals(ReevalRunState.Idle, t.state)
    }

    @Test fun cancellingReturnsToIdleAndAcknowledgingARunningStateDoesNothing() {
        val t = ReevalRunTracker(); t.begin("a", "Alpha"); t.acknowledge(); assertTrue(t.running, "an unfinished run is not acknowledged away")
        t.cancel(); assertEquals(ReevalRunState.Idle, t.state); t.cancel(); assertEquals(ReevalRunState.Idle, t.state)
    }
}
