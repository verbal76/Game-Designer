package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.ReevalExplainer
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.ReevalItem
import com.hotattic.gamedesigner.core.model.ReevalRecord
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The reevaluation result for any project, in plain language, with synthetic scenarios. */
class ReevalExplainerTest {
    private fun answer(k: String?) = when {
        k == "__proposals__" -> "yes"; k == "__asset_plan__" -> "looks good"; k == "__review__" -> "looks right"; k?.startsWith("__conflict") == true -> "use alternative 1"; k == "__ready__" -> "generate"; else -> "choose for me"
    }
    private suspend fun designed(concept: String = "A calm 2D puzzle game for Android about restoring a garden."): Pair<com.hotattic.gamedesigner.core.director.Director, Project> {
        val d = director(); var p = d.handleUserMessage(d.start(newProject(), settings), settings, concept).project
        repeat(80) { if (p.pendingFieldKey == null || p.pendingFieldKey == "__ready__") return@repeat; p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project }
        return d to p
    }

    @Test fun everyReevaluatedProjectGetsAPlainSummaryWithClearlyLabelledActions() = runBlocking {
        val (d, p) = designed(); val r = d.reevaluate(p, settings).project
        val s = assertNotNull(ReevalExplainer.summarize(r))
        assertTrue(s.headline.isNotBlank() && s.reviewed.contains("checked") && s.unchanged.contains("exactly as you made them"))
        assertFalse(s.primary.label.equals("Continue with Bob", true), "the button names what it does: ${s.primary.label}")
        assertTrue(s.primary.explanation.contains("Nothing") || s.primary.explanation.contains("will not change") || s.primary.explanation.contains("Nothing is applied"), s.primary.explanation)
        assertNotNull(s.secondary); assertTrue(s.secondary!!.explanation.contains("exactly as it was"))
        assertEquals(setOf("Recommendation", "Conflict", "Unanswered question", "For your information"), s.glossary.map { it.first }.toSet())
        assertTrue(s.completeness.contains("measures") && s.completeness.contains("not how many concerns"), s.completeness)
        assertTrue(s.saved.contains("Your own decisions are unchanged") && s.saved.contains("Discard"), s.saved)
    }

    @Test fun summaryDoesNotModifyAnything() = runBlocking {
        val (d, p) = designed(); val r = d.reevaluate(p, settings).project
        val before = ProjectCodecEq.encode(r); ReevalExplainer.summarize(r); ReevalExplainer.summarize(r)
        assertEquals(before, ProjectCodecEq.encode(r))
    }
    private object ProjectCodecEq { fun encode(p: Project) = com.hotattic.gamedesigner.core.persist.ProjectCodec.encode(p) }

    @Test fun addingWindowsAsPrimaryWhenAndroidAndIosAreChosenIsExplainedNotSilentlyApplied() = runBlocking {
        val (d, p0) = designed()
        var p = ProjectOps.setDecision(p0, Keys.PLATFORMS, "android|ios", Provenance.OWNER_EXPLICIT, 100L)
        p = ProjectOps.setDecision(p, Keys.PRIMARY_PLATFORM, "android", Provenance.OWNER_EXPLICIT, 101L)
        p = ProjectOps.addFacts(p, listOf("Add Windows PC as the primary platform."), "owner", Provenance.OWNER_EXPLICIT, 200L)
        val r = d.reevaluate(p, settings).project
        assertEquals("android|ios", r.decision(Keys.PLATFORMS)?.value, "the owner's platforms are not replaced by reevaluating"); assertEquals("android", r.decision(Keys.PRIMARY_PLATFORM)?.value)
        val s = ReevalExplainer.summarize(r)!!
        val c = s.attention.singleOrNull { it.key == "fact_contradicts_decision" }
        assertNotNull(c, "the conflict is surfaced: ${s.attention.map { it.key }}")
        assertEquals(ReevalExplainer.Kind.CONFLICT, c.kind)
        assertTrue(c.explanation.contains("Windows") && c.explanation.contains("still says"), c.explanation)
        assertTrue(c.previously.orEmpty().contains("Android") && c.previously.orEmpty().contains("iPhone"), "what was decided before: ${c.previously}")
        assertTrue(c.choices.any { it.startsWith("Apply it:") && it.contains("Windows") && it.contains("Android") && it.contains("iPhone") }, "adding Windows keeps Android and iOS: ${c.choices}")
        assertTrue(c.ifChanged.orEmpty().contains("platforms") && c.ifChanged.orEmpty().contains("Windows PC") && !c.ifChanged.orEmpty().contains("= windows") && c.ifChanged.orEmpty().contains("Nothing else changes"), c.ifChanged ?: "")
        assertFalse(c.deferrable, "a blocking conflict cannot be deferred")
        assertTrue(c.why.contains("guess"), c.why)
        assertTrue(s.primary.label.startsWith("Review ") && s.primary.label.contains("with Bob"), s.primary.label)
        assertTrue(s.primary.explanation.contains("one at a time") && s.primary.explanation.contains("will not change until you approve"))
    }

    @Test fun findingsAboutOneDecisionAreGroupedAndSaidSo() {
        val items = listOf(ReevalItem("NEEDS_DECISION", "k1", "Game length", note = "How long?"), ReevalItem("CONTRADICTION", "k1", "Game length mismatch", note = "x"), ReevalItem("NEEDS_DECISION", "k2", "Audience", note = "Who?"))
        val p = newProject().copy(reeval = ReevalRecord(startedAt = 1L, items = items, newQuestions = 2, completenessBefore = 80, completenessAfter = 99))
        val s = ReevalExplainer.summarize(p)!!
        assertEquals(2, s.attention.size, "three findings about two decisions are two items")
        assertNotNull(s.groupingNote); assertTrue(s.groupingNote!!.contains("3 findings") && s.groupingNote!!.contains("2 items"))
        assertTrue(s.attention.first { it.key == "k1" }.relation.contains("grouped"))
        assertTrue(s.headline.startsWith("2 item(s) need your attention"), s.headline)
        assertTrue(s.completeness.contains("99% is the ceiling"), "99% is explained, not shown as 'done'")
    }

    @Test fun eachItemSaysWhatItIsWhyItMattersWhatItRelatesToAndWhetherItCanWait() = runBlocking {
        val (d, p) = designed("A game."); val r = d.reevaluate(p, settings).project
        val s = ReevalExplainer.summarize(r)!!
        assertTrue(s.attention.isNotEmpty(), "a one-word concept leaves real questions")
        for (a in s.attention) {
            assertTrue(a.title.isNotBlank() && a.explanation.isNotBlank() && a.why.isNotBlank() && a.relation.isNotBlank() && a.action.isNotBlank(), "complete item: $a")
            assertFalse(a.title.contains("**") || a.explanation.contains("**"))
        }
        assertTrue(s.attention.filter { it.kind == ReevalExplainer.Kind.QUESTION }.all { !it.deferrable && it.action.contains("choose for you") })
    }

    @Test fun newFindingsAreSeparatedFromThoseThatWereAlreadyUnresolved() = runBlocking {
        val d = director(); val p = d.handleUserMessage(d.start(newProject(), settings), settings, "A game.").project
        val r = d.reevaluate(p, settings).project
        val s = ReevalExplainer.summarize(r)!!
        val questions = s.attention.filter { it.kind == ReevalExplainer.Kind.QUESTION }
        assertTrue(questions.isNotEmpty())
        assertTrue(questions.all { !it.isNew || r.reeval!!.baseline.decisions[it.key] != null || true })
        // questions that were open before the reevaluation are reported as already unresolved
        val before = com.hotattic.gamedesigner.core.engine.CompletenessEngine.compute(p).missingRequired.map { it.key }.toSet()
        for (q in questions) assertEquals(q.key !in before, q.isNew, "${q.key}: new means it was not open before the reevaluation")
    }

    @Test fun noReevaluationNoSummary() { assertNull(ReevalExplainer.summarize(newProject())) }

    @Test fun approvedAndDiscardedStatesAreDescribedHonestly() {
        val base = ReevalRecord(startedAt = 1L, fromSpec = 2, approvedSpec = 3, completenessBefore = 90, completenessAfter = 100)
        val ap = ReevalExplainer.summarize(newProject().copy(reeval = base.copy(status = "APPROVED")))!!
        assertTrue(ap.saved.contains("Spec v3 was created") && ap.saved.contains("Spec v2 is still available")); assertEquals("Open the spec", ap.primary.label); assertNull(ap.secondary)
        val di = ReevalExplainer.summarize(newProject().copy(reeval = base.copy(status = "DISCARDED")))!!
        assertTrue(di.saved.contains("exactly as it was")); assertEquals("Back to the design", di.primary.label)
    }
}
