package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.ReevalCompare
import com.hotattic.gamedesigner.core.generate.SpecCompare
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CompareTest {
    @Test fun sideBySideAlignsOnlyRealEditsAndSplitsBySection() {
        val old = "# Title\n\nintro line\n\n## Combat\n- pistol\n- flares\n- melee\n\n## Art\n- pixel art\n"
        val new = "# Title\n\nintro line\n\n## Combat\n- pistol\n- flares and a lantern\n- melee\n- dodge roll\n\n## Audio\n- sparse drones\n"
        val sec = SpecCompare.compare(old, new).associateBy { it.title }
        assertEquals("SAME", sec["Title"]!!.status)
        assertEquals("CHANGED", sec["Combat"]!!.status)
        assertEquals("REMOVED", sec["Art"]!!.status)
        assertEquals("NEW", sec["Audio"]!!.status)
        val rows = sec["Combat"]!!.rows
        assertTrue(rows.any { it.kind == "CHANGED" && it.left == "- flares" && it.right == "- flares and a lantern" })
        assertTrue(rows.any { it.kind == "ADDED" && it.right == "- dodge roll" })
        assertTrue(rows.count { it.kind == "SAME" } >= 3)
    }

    @Test fun collapseKeepsContextAndHidesLongUnchangedRuns() {
        val a = (1..40).map { "line $it" }
        val b = a.toMutableList().also { it[20] = "line 21 EDITED" }
        val rows = SpecCompare.collapse(SpecCompare.align(a, b), 1)
        assertTrue(rows.any { it.kind == "GAP" }); assertTrue(rows.size < 12)
        assertTrue(rows.any { it.kind == "CHANGED" && it.right == "line 21 EDITED" })
    }

    @Test fun reevaluationTableShowsBeforeAfterAndWhoStandsBehindEachSide() = runBlocking {
        val d = director()
        val (p0, ready) = driveToReady(d, newProject(), "A top-down action roguelike for my Android phone about surviving a dangerous city.")
        assertTrue(ready)
        val old = SpecVersioning.createVersion(p0, VersionKind.INITIAL, 1L, "2026-10-01")
        // an old guess that the newer logic will redo
        val stale = old.copy(decisions = old.decisions + (Keys.PERFORMANCE to com.hotattic.gamedesigner.core.model.Decision("ancient", com.hotattic.gamedesigner.core.model.DecisionSource.INFERRED, provenance = com.hotattic.gamedesigner.core.model.Provenance.DEFAULT)))
        val p = d.reevaluate(stale, settings).project
        val rows = ReevalCompare.rows(p)
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.any { it.status == "SAME" && it.beforeBy.startsWith("You") && it.afterBy.startsWith("You") }, "owner decisions appear on both sides")
        val perf = rows.firstOrNull { it.key == Keys.PERFORMANCE }
        assertTrue(perf != null && perf.status != "SAME" && perf.beforeBy == "Bob", "old Bob's guess is flagged as Bob's and replaced: $perf")
        assertEquals(rows.size, rows.sumOf { 1 }.also { assertTrue(ReevalCompare.counts(rows).values.sum() == rows.size) })
    }
}
