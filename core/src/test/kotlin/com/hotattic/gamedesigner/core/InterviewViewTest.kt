package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.AnswerDraft
import com.hotattic.gamedesigner.core.director.ConversationView
import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.GuidedPanel
import com.hotattic.gamedesigner.core.director.PanelKind
import com.hotattic.gamedesigner.core.director.SimpleMarkdown
import com.hotattic.gamedesigner.core.model.Experience
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Role
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The interview as the owner sees it: one question shown once, history that never repeats it, readable text, a draft that records only what the owner
 * confirms, and mode switching that neither replays nor duplicates. Pure view logic over synthetic projects; the Compose layout itself is covered by the
 * device checklist in docs/DEVICE_TEST_PROCEDURE.md.
 */
class InterviewViewTest {
    private fun answer(k: String?) = when {
        k == "__proposals__" -> "yes"; k == "__asset_plan__" -> "looks good"; k == "__review__" -> "looks right"
        k?.startsWith("__conflict") == true -> "use alternative 1"; k == "__ready__" -> "generate"; else -> "choose for me"
    }
    private suspend fun started(concept: String = "A calm 2D puzzle game for Android about restoring a garden."): Pair<Director, Project> {
        val d = director(); return d to d.handleUserMessage(d.start(newProject(), settings), settings, concept).project
    }
    private fun count(haystack: List<String>, needle: String) = haystack.sumOf { h -> Regex(Regex.escape(needle)).findAll(h).count() }

    // ---- readable text -------------------------------------------------------------------------------------------------------------------------

    @Test fun markdownMarkersAreStyledNotShown() {
        val l = SimpleMarkdown.parse("**Game length.** How long is one *play session*? Use `autosave`.").single()
        assertEquals("Game length. How long is one play session? Use autosave.", l.text)
        assertEquals(listOf(SimpleMarkdown.Style.BOLD, SimpleMarkdown.Style.ITALIC, SimpleMarkdown.Style.CODE), l.spans.map { it.style })
        assertEquals("Game length.", l.text.substring(l.spans[0].start, l.spans[0].end))
        assertFalse(l.text.contains("*") || l.text.contains("`"))
    }

    @Test fun markdownLeavesOrdinaryTextAlone() {
        assertEquals("snake_case_name and 2*3 and a lone * star", SimpleMarkdown.plain("snake_case_name and 2*3 and a lone * star"))
        assertEquals("unmatched **bold stays", SimpleMarkdown.plain("unmatched **bold stays"))
        assertEquals("a bold and italic", SimpleMarkdown.plain("a **bold** and _italic_"))
        val lines = SimpleMarkdown.parse("# Title\n- one\n* two\n1. three")
        assertTrue(lines[0].heading && lines[1].bullet && lines[2].bullet && !lines[3].bullet)
        assertEquals(listOf("Title", "one", "two", "1. three"), lines.map { it.text })
    }

    @Test fun noQuestionOrLessonInTheRealConversationShowsRawMarkers() = runBlocking {
        val (d, p0) = started(); var p = p0; val seen = mutableListOf<String>()
        repeat(40) { val m = GuidedPanel.model(p); if (m != null) { seen += listOfNotNull(m.title, m.why, m.detail, m.lesson?.what, m.lesson?.why) }; p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project }
        assertTrue(seen.isNotEmpty())
        for (t in seen) assertTrue(SimpleMarkdown.plain(t) == SimpleMarkdown.plain(SimpleMarkdown.plain(t)), "idempotent: $t")
    }

    // ---- one question, shown once ---------------------------------------------------------------------------------------------------------------

    @Test fun theActiveQuestionIsShownOnceAcrossHistoryAndPanel() = runBlocking {
        val (d, p0) = started(); var p = p0; var checked = 0
        repeat(45) {
            val m = GuidedPanel.model(p)
            if (m != null && m.kind != PanelKind.OTHER) {
                val history = ConversationView.history(p, m).map { it.text }
                assertEquals(1, count(history + m.title, m.title), "'${m.title}' must appear exactly once (history + panel) but did not at ${m.key}")
                m.lesson?.let { assertEquals(0, count(history, "Why it matters here:"), "the teaching card is in the panel, not repeated in history") }
                assertTrue(history.none { it.contains("I'd go with:") && p.messages.last().text.contains("I'd go with:") && it == p.messages.last().text }, "the full question message is not drawn again")
                checked++
            }
            p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project
        }
        assertTrue(checked > 10, "the interview produced enough questions to audit: $checked")
    }

    @Test fun whatBobSaidBeforeAskingIsShownOnceAsANote() = runBlocking {
        val (d, p0) = started(); var p = p0; var withNotes = 0
        repeat(40) {
            val m = GuidedPanel.model(p)
            if (m?.notes != null) { withNotes++; val h = ConversationView.history(p, m); assertEquals(m.notes, h.last().text, "the note stays in history"); assertFalse(m.title in h.last().text) }
            p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project
        }
        assertTrue(withNotes > 0, "at least one question follows an acknowledgement")
    }

    @Test fun answeredQuestionsInHistoryAreCompactAndNothingIsDeletedFromTheProject() = runBlocking {
        val (d, p0) = started(); var p = p0
        repeat(12) { p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project }
        val before = p.messages.size; val m = GuidedPanel.model(p)
        val h = ConversationView.history(p, m)
        assertTrue(h.none { it.role == Role.DIRECTOR && it.text.contains("Why it matters here:") }, "old teaching cards are not repeated")
        assertEquals(before, p.messages.size, "view logic never edits the stored conversation")
        assertTrue(p.messages.any { it.text.contains("Why it matters here:") || it.text.contains("I'd go with:") }, "the full messages are still stored")
    }

    @Test fun anAskedAgainQuestionIsDrawnOnlyOnce() = runBlocking {
        val (d, p0) = started(); var p = p0
        repeat(6) { p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project }
        val key = p.pendingFieldKey!!.takeUnless { it.startsWith("__") } ?: return@runBlocking
        val m1 = GuidedPanel.model(p)!!
        p = d.switchMode(p, settings, if (p.prefs.experience == Experience.BEGINNER) Experience.EXPERT else Experience.BEGINNER)   // re-asks the same open question
        val m2 = GuidedPanel.model(p)!!
        assertEquals(key, m2.key); assertTrue(m1.messageId != m2.messageId, "the switch really did add a second copy of the question")
        val h = ConversationView.history(p, m2).map { it.text }
        assertEquals(1, count(h + m2.title, m2.title), "the question is drawn once after a mode switch")
    }

    @Test fun theReevaluationSummaryIsNeverGluedIntoAnUnrelatedQuestion() = runBlocking {
        val (d, p0) = started(); var p = p0
        repeat(40) { if (p.pendingFieldKey == null) return@repeat; p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project }
        val r = d.reevaluate(p, settings).project
        val summary = r.messages.last { it.role == Role.DIRECTOR && it.text.contains("REEVALUATION COMPLETE") }.text
        val m = GuidedPanel.model(r)
        if (m != null) {
            assertFalse((m.detail ?: "").contains("REEVALUATION COMPLETE") || m.title.contains("REEVALUATION COMPLETE"), "the summary is history, not part of the question")
            val h = ConversationView.history(r, m).map { it.text }
            assertTrue(h.any { it == summary }, "the summary stays in the history once")
        }
    }

    // ---- draft, confirm and what it records ------------------------------------------------------------------------------------------------------

    @Test fun pickingAndTypingAreMutuallyExclusiveAndConfirmNamesWhatItRecords() = runBlocking {
        val (d, p0) = started(); var p = p0; var m = GuidedPanel.model(p)
        while (m == null || m.kind != PanelKind.SINGLE || m.options.size < 2) { p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project; m = GuidedPanel.model(p) }
        val model = m!!; val a = model.options[0]; val b = model.options[1]
        var dr = AnswerDraft()
        assertTrue(dr.isEmpty && dr.hint(model)!!.startsWith("Choose an answer")); assertNull(GuidedPanel.confirm(model, dr.selected, dr.text), "nothing is confirmable until the owner chooses")
        dr = dr.pick(model, a.id); assertEquals("Confirm: " + a.label.take(24), dr.confirmLabel(model))
        dr = dr.pick(model, b.id); assertEquals(setOf(b.id), dr.selected, "a second pick replaces the first")
        dr = dr.type("something of my own"); assertTrue(dr.selected.isEmpty()); assertEquals("Confirm my answer", dr.confirmLabel(model))
        val say = GuidedPanel.confirm(model, dr.selected, dr.text) as com.hotattic.gamedesigner.core.director.PanelAction.Say; assertEquals("something of my own", say.text)
        dr = dr.pick(model, a.id); assertEquals("", dr.text, "choosing an option drops typed text, so the button shows exactly what is recorded")
        assertEquals(AnswerDraft(), dr.clear())
        assertEquals(dr.copy(text = "x").type(""), dr.copy(text = "x").type("").copy(), "clearing the text keeps nothing selected")
    }

    @Test fun confirmingRecordsExactlyTheChosenOptionAndNextOrBackNeverAnswers() = runBlocking {
        val (d, p0) = started(); var p = p0; var m = GuidedPanel.model(p)
        while (m == null || m.kind != PanelKind.SINGLE || m.options.size < 2) { p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project; m = GuidedPanel.model(p) }
        val model = m!!; val before = p.decisions
        // NEXT with nothing confirmed does not answer
        assertFalse(GuidedPanel.next(p, model, null, false).enabled)
        val pick = model.options.last(); val action = GuidedPanel.confirm(model, setOf(pick.id), "")!!
        val sel = action as com.hotattic.gamedesigner.core.director.PanelAction.Select
        val after = d.submitSelection(p, settings, sel.fieldKey, sel.ids, sel.turnId).project
        assertEquals(pick.id, after.decision(model.key)?.value, "exactly the approved option is recorded")
        assertTrue(before.filterKeys { it != model.key }.all { (k, v) -> after.decision(k)?.value == v.value || after.decision(k) == null || !v.ownerAuthored }, "other owner decisions are untouched")
    }

    @Test fun draftsAreKeptPerQuestionSoBackDoesNotLoseThem() {
        val drafts = mapOf("platforms" to AnswerDraft(setOf("android", "ios")), "core_loop" to AnswerDraft(text = "pick up, plant, wait"))
        val raw = AnswerDraft.encode(drafts)
        assertEquals(drafts, AnswerDraft.decode(raw)); assertEquals(emptyMap(), AnswerDraft.decode(""))
        assertEquals(drafts["core_loop"], AnswerDraft.decode(raw)["core_loop"])
    }

    @Test fun disabledControlsExplainThemselvesInOneNeutralLine() = runBlocking {
        val (d, p0) = started(); var p = p0; var m = GuidedPanel.model(p)
        while (m == null || m.kind != PanelKind.SINGLE) { p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project; m = GuidedPanel.model(p) }
        val text = listOfNotNull(GuidedPanel.confirmBlocked(m!!, emptySet(), ""), GuidedPanel.next(p, m, null, false).reason, AnswerDraft().hint(m)).joinToString(" ")
        assertFalse(text.contains("Nothing selected yet") || text.contains("never answers for you") || text.contains("Pick one of the options"), text)
    }

    // ---- mode switching ------------------------------------------------------------------------------------------------------------------------

    @Test fun switchingModeKeepsEveryDecisionAndTheCurrentQuestionAndAddsNoReplay() = runBlocking {
        val (d, p0) = started(); var p = p0
        repeat(10) { p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project }
        val key = p.pendingFieldKey; val decisions = p.decisions; val msgs = p.messages.size
        for (to in listOf(Experience.EXPERT, Experience.BEGINNER, Experience.EXPERT)) {
            p = d.switchMode(p, settings, to)
            assertEquals(key, p.pendingFieldKey, "the open question stays open"); assertEquals(decisions, p.decisions, "no decision changed")
            assertTrue(p.messages.size - msgs <= 3 * 2, "a mode switch adds a short note and at most the one open question, never a replay (${p.messages.size - msgs})")
            val m = GuidedPanel.model(p)
            if (m != null) assertTrue(ConversationView.history(p, m).count { it.role == Role.DIRECTOR && it.text.startsWith("Switched to") } >= 1, "the note stays visible: ${ConversationView.history(p, m).takeLast(3).map { it.text.take(50) }}")
        }
        val hist = ConversationView.history(p, GuidedPanel.model(p))
        assertTrue(hist.count { it.role == Role.DIRECTOR && (it.question?.fieldKey ?: it.fieldKey) == key } <= 1, "the open question is not drawn in history once per switch")
    }

    @Test fun bothModesReachTheSameDecisionsForTheSameAnswers() = runBlocking {
        suspend fun run(mode: Experience): Map<String, String> {
            val d = director(); var p = d.start(newProject(prefs = com.hotattic.gamedesigner.core.model.ProjectPrefs(experience = mode)), settings)
            p = d.handleUserMessage(p, settings, "A calm 2D puzzle game for Android about restoring a garden.").project
            repeat(120) { if (p.pendingFieldKey == "__ready__" || p.pendingFieldKey == null) return@repeat; p = d.handleUserMessage(p, settings, answer(p.pendingFieldKey)).project }
            return p.decisions.filterValues { it.ownerAuthored }.mapValues { it.value.value }
        }
        val b = run(Experience.BEGINNER); val a = run(Experience.EXPERT)
        // Advanced asks extra technical questions; everything Beginner decided is decided identically, and Advanced only adds to it.
        assertTrue(a.keys.containsAll(b.keys), "Advanced never drops a decision: ${b.keys - a.keys}")
        assertEquals(b, a.filterKeys { it in b.keys }, "shared decisions are identical in both modes")
        assertTrue((a.keys - b.keys).all { it in setOf("toolchain_prefs", "engine", "ci_build", "performance_target", "network_policy", "android_target_api", "package_id", "version_strategy", "store_plan", "testing_strategy") }, "only technical questions are added: ${a.keys - b.keys}")
    }
}
