package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.HandoffDocs
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Dependency precision and spec accuracy found by the audit. Evidence: AUTOMATED, synthetic projects, rules only. */
class DependencyAndSpecAccuracyTest {
    private fun ans(k: String?) = when {
        k == "__proposals__" -> "yes"; k == "__asset_plan__" -> "looks good"; k == "__review__" -> "looks right"
        k?.startsWith("__conflict") == true -> "use alternative 1"; k == "__ready__" -> "generate"
        k == Keys.FIVE_MINUTES || k == Keys.CORE_FANTASY -> "Playing it should feel calm and rewarding."
        else -> "choose for me"
    }

    private suspend fun ready(text: String): Pair<Director, Project> {
        val d = director(); var p = d.handleUserMessage(d.start(newProject(), settings), settings, text).project
        repeat(160) { if (p.pendingFieldKey != "__ready__") p = d.handleUserMessage(p, settings, ans(p.pendingFieldKey)).project }
        return d to p
    }

    private fun both(p: Project) = ClaudeMdGenerator.generate(p, 1, "x", "2026-10-17T00:00:00Z") to HandoffDocs.files(p).getValue("docs/REQUIREMENTS_LEDGER.md")

    // ---- dependencies are as precise as the decisions they protect ----------------------------------------------------

    @Test fun changingThePresentationReopensOnlyWhatDependsOnIt() = runBlocking {
        val (d, p) = ready("A 3D low poly flight game where you glide over islands, for Android and PC.")
        val unrelated = listOf(Keys.PLAYER_FEELING, Keys.WIN_LOSS, Keys.DONE, Keys.MUST_NOT_CHANGE, Keys.CORE_LOOP, Keys.FIRST_SLICE).filter { p.decision(it) != null }
        assertTrue(unrelated.size >= 4, "the scenario must have such decisions: $unrelated")
        val q = d.handleUserMessage(p, settings, "Actually make it 2D pixel art.").project
        assertEquals("2D", q.value(Keys.DIMENSION)); assertEquals("pixel_art", q.value(Keys.ART_DIRECTION))
        for (k in unrelated) assertEquals(p.decision(k)?.value, q.decision(k)?.value, "$k does not depend on 2D/3D and must survive")
        // What really depends on it is reopened, and the owner is told why.
        assertTrue(q.decision(Keys.MOVEMENT_CAMERA) == null, "camera was derived for 3D")
        assertTrue(q.messages.any { it.text.contains("dropped my earlier camera", true) || it.text.contains("camera", true) && it.text.contains("no longer fits", true) })
    }

    @Test fun unrelatedOwnerDecisionsSurviveAnyRootChange() = runBlocking {
        val (d, p) = ready("A calm 2D puzzle game for Android with soft colors.")
        val q = d.handleUserMessage(p, settings, "Actually also add Windows.").project
        for (k in listOf(Keys.PLAYER_FEELING, Keys.CORE_LOOP, Keys.WIN_LOSS, Keys.DONE)) assertEquals(p.decision(k)?.value, q.decision(k)?.value, k)
    }

    // ---- the spec states what the owner decided, with the right weight --------------------------------------------------

    @Test fun aSentenceTheOwnerLaterCorrectedIsNotAPlainRequirement() = runBlocking {
        val (d, p) = ready("A 3D low poly flight game where you glide over islands, for Android and PC.")
        val q = d.handleUserMessage(p, settings, "Actually make it 2D pixel art.").project
        var r = q
        repeat(80) { if (r.pendingFieldKey != null) r = d.handleUserMessage(r, settings, ans(r.pendingFieldKey)).project }
        val (md, ledger) = both(r)
        val a2 = md.substringAfter("A2. Owner requirements").substringBefore("A3.")
        assertTrue(a2.lines().filter { it.contains("3D low poly", true) }.all { it.contains("superseded", true) }, "A2 marks the old wording: $a2")
        assertFalse(ledger.lines().any { it.contains("3D low poly", true) && Regex("\\|\\s*Must\\s*\\|").containsMatchIn(it) }, "the ledger does not demand the superseded wording")
    }

    @Test fun considerationsAndRejectionsAreNotRequirements() = runBlocking {
        val (_, p) = ready("A pixel art RPG. I like the population simulation in Songs of Syx, but I don't want its combat system. Android is my primary platform, and I might bring it to Windows later.")
        val (md, ledger) = both(p)
        val a2 = md.substringAfter("A2. Owner requirements").substringBefore("A3.")
        assertTrue(a2.lines().filter { it.contains("Windows later", true) }.all { it.contains("not a requirement", true) || it.contains("possible", true) }, a2)
        val windowsRow = ledger.lines().firstOrNull { it.contains("Windows later", true) }
        assertTrue(windowsRow == null || !Regex("\\|\\s*Must\\s*\\|").containsMatchIn(windowsRow), "a hedged statement is not a Must: $windowsRow")
        val combatRow = ledger.lines().firstOrNull { it.contains("don't want its combat", true) }
        assertTrue(combatRow == null || Regex("(?i)exclu|not wanted|must not|do not").containsMatchIn(combatRow), "an exclusion is recorded as one: $combatRow")
        assertNotNull(md)
    }
}
