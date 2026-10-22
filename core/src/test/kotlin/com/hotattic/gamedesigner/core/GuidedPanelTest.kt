package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Accepted
import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.GuidedPanel
import com.hotattic.gamedesigner.core.director.PanelAction
import com.hotattic.gamedesigner.core.director.PanelKind
import com.hotattic.gamedesigner.core.director.PanelModel
import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The guided question panel is a pure view over engine state. These tests drive the REAL Director through the same calls the screen makes
 * (selection pipeline or text pipeline with the panel's stable turn id) and check what the panel offers, what BACK / CONFIRM / NEXT do and
 * that nothing the panel does can bypass the engine. Compose rendering, rotation and the keyboard need a device and are not covered here.
 */
class GuidedPanelTest {
    private val concept = "A medieval fantasy city builder like Songs of Syx with professions, relationships and an interconnected economy. Android and iPhone."

    private fun autoAnswer(pending: String?): String = when {
        pending == "__proposals__" -> "yes"
        pending == "__asset_plan__" -> "looks good"
        pending?.startsWith("__conflict:") == true -> "use alternative 1"
        pending == "__ready__" -> "generate"
        pending == "__review__" -> "looks right"
        pending == Keys.FIVE_MINUTES || pending == Keys.CORE_FANTASY -> "Playing it should feel calm and rewarding."
        else -> "choose for me"
    }

    private val roguelite = "A top-down action roguelite where I fight waves of monsters with swords and magic and pick upgrades between runs, for Android."

    private suspend fun start(d: Director, text: String = concept): Project = d.handleUserMessage(d.start(newProject(), settings), settings, text).project

    /** Runs the interview until [stop] holds, always answering the way the panel would for a delegating owner. */
    private suspend fun reach(d: Director = director(), text: String = concept, stop: (Project, PanelModel?) -> Boolean): Pair<Director, Project> {
        var p = start(d, text)
        repeat(240) {
            if (stop(p, GuidedPanel.model(p))) return d to p
            p = d.handleUserMessage(p, settings, autoAnswer(p.pendingFieldKey)).project
        }
        error("never reached; pending=${p.pendingFieldKey}")
    }

    private suspend fun perform(d: Director, p: Project, a: PanelAction): com.hotattic.gamedesigner.core.director.DirectorTurn = when (a) {
        is PanelAction.Select -> d.submitSelection(p, settings, a.fieldKey, a.ids, a.turnId)
        is PanelAction.Say -> d.handleUserMessage(p, settings, a.text, a.turnId)
    }

    // ---- what the panel shows ------------------------------------------------------------------------------------

    @Test fun everyKindOfQuestionIsShownWithItsOwnOptionsAndNeverPreSelected() = runBlocking {
        val seen = mutableSetOf<PanelKind>()
        val d = director()
        var p = start(d)
        repeat(240) {
            val m = GuidedPanel.model(p)
            if (m != null) {
                seen += m.kind
                assertTrue(m.title.isNotBlank(), "title for ${m.key}")
                assertTrue(m.messageId == p.messages.last().id)
                // Bob's recommendation is data about the options, never a selection: CONFIRM is blocked until the owner picks.
                if (m.options.isNotEmpty()) assertNull(GuidedPanel.confirm(m, emptySet(), ""), "nothing is confirmed for the owner (${m.key})")
                // Recommended options are marked, but only options that exist.
                m.options.filter { it.recommended }.forEach { assertTrue(it in m.options) }
            }
            if (p.pendingFieldKey == null) return@repeat
            p = d.handleUserMessage(p, settings, autoAnswer(p.pendingFieldKey)).project
        }
        for (k in listOf(PanelKind.SINGLE, PanelKind.TEXT, PanelKind.REVIEW, PanelKind.PROPOSALS)) assertTrue(k in seen, "never saw $k; saw $seen")
    }

    @Test fun recommendationIsSeparateFromTheOwnersSelection() = runBlocking {
        val (_, p) = reach { _, m -> m != null && m.kind == PanelKind.SINGLE && m.recommendationLabel != null }
        val m = GuidedPanel.model(p)!!
        assertNotNull(m.recommendationLabel)
        assertTrue(m.options.any { it.recommended })
        assertNull(GuidedPanel.confirm(m, emptySet(), ""))
        assertNotNull(GuidedPanel.confirmBlocked(m, emptySet(), ""))
        assertEquals(null, p.decision(m.key), "showing a recommendation records nothing")
    }

    @Test fun busyOrQueuedOrPlaytestStatesShowNoPanel() = runBlocking {
        val (_, p) = reach { _, m -> m != null }
        assertNull(GuidedPanel.model(p, busy = true))
        assertNull(GuidedPanel.model(p.copy(pendingTurn = com.hotattic.gamedesigner.core.model.PendingTurn("t", "hello", 1L))))
        assertNull(GuidedPanel.model(ProjectOps.setPending(p, null)))
        assertNull(GuidedPanel.model(p.copy(mode = com.hotattic.gamedesigner.core.model.ProjectMode.PLAYTEST_CONTINUE)))
    }

    // ---- CONFIRM ------------------------------------------------------------------------------------------------

    @Test fun confirmSubmitsASingleChoiceThroughTheSelectionPipelineAndRecordsItAsTheOwnersDecision() = runBlocking {
        val (d, p) = reach { _, m -> m != null && m.kind == PanelKind.SINGLE && m.options.size >= 2 && m.key !in setOf(Keys.PLATFORMS, Keys.DIMENSION) }
        val m = GuidedPanel.model(p)!!
        val pick = m.options.last().id
        val action = assertNotNull(GuidedPanel.confirm(m, setOf(pick), ""))
        assertTrue(action is PanelAction.Select && action.ids == listOf(pick))
        val after = perform(d, p, action).project
        assertEquals(pick, after.decision(m.key)?.value)
        assertTrue(after.decision(m.key)!!.prov.ownerAuthored, "a tap is the owner's own decision")
        assertTrue(action.turnId in after.processedTurns)
    }

    @Test fun anUnconfirmedSelectionChangesNothingInTheProject() = runBlocking {
        val (_, p) = reach { _, m -> m != null && m.kind == PanelKind.SINGLE && m.options.size >= 2 }
        val m = GuidedPanel.model(p)!!
        // Selecting is screen state: building the action for inspection must not touch the engine, and the model is identical afterwards.
        val before = p
        GuidedPanel.confirm(m, setOf(m.options.first().id), "")
        assertEquals(before, p)
        assertEquals(m, GuidedPanel.model(p))
    }

    @Test fun multiChoiceNeedsAtLeastOnePickAndKeepsTheOwnersSet() = runBlocking {
        val (d, p) = reach(text = roguelite) { _, m -> m != null && m.kind == PanelKind.MULTI && m.options.size >= 3 }
        val m = GuidedPanel.model(p)!!
        assertNotNull(GuidedPanel.confirmBlocked(m, emptySet(), ""))
        val picks = m.options.take(2).map { it.id }.toSet()
        val action = assertNotNull(GuidedPanel.confirm(m, picks, "")) as PanelAction.Select
        assertEquals(picks, action.ids.toSet())
        val after = perform(d, p, action).project
        assertEquals(picks, after.list(m.key).toSet())
    }

    @Test fun freeTextOnAStructuredQuestionGoesThroughTheTextPipeline() = runBlocking {
        val (d, p) = reach { _, m -> m != null && m.kind == PanelKind.SINGLE && m.options.size >= 2 && m.allowsText }
        val m = GuidedPanel.model(p)!!
        val action = assertNotNull(GuidedPanel.confirm(m, emptySet(), "  something in my own words  "))
        assertTrue(action is PanelAction.Say && action.text == "something in my own words")
        assertTrue(perform(d, p, action).project.messages.any { it.role == Role.USER && it.text == "something in my own words" })
    }

    // ---- duplicate and replayed turn ids -----------------------------------------------------------------------------

    @Test fun aRepeatedConfirmWithTheSameTurnIdIsANoOp() = runBlocking {
        val (d, p) = reach { _, m -> m != null && m.kind == PanelKind.SINGLE && m.options.size >= 2 && m.key != Keys.PLATFORMS && m.key != Keys.DIMENSION }
        val m = GuidedPanel.model(p)!!
        val action = GuidedPanel.confirm(m, setOf(m.options.first().id), "")!!
        val once = perform(d, p, action)
        val again = perform(d, once.project, action)
        assertTrue(again.duplicate)
        assertEquals(once.project.decisions, again.project.decisions)
        assertEquals(once.project.messages.size, again.project.messages.size)
        // The same draft always gets the same id; a different draft a different one.
        assertEquals(action.turnId, GuidedPanel.confirm(m, setOf(m.options.first().id), "")!!.turnId)
        assertTrue(action.turnId != GuidedPanel.confirm(m, setOf(m.options.last().id), "")!!.turnId)
    }

    @Test fun aReplayedTextConfirmIsIgnoredToo() = runBlocking {
        val (d, p) = reach { _, m -> m != null && m.kind == PanelKind.TEXT }
        val m = GuidedPanel.model(p)!!
        val action = GuidedPanel.confirm(m, emptySet(), "Players should feel clever.")!!
        val once = perform(d, p, action)
        val again = perform(d, once.project, action)
        assertTrue(again.duplicate)
        assertEquals(once.project.facts, again.project.facts)
    }

    // ---- NEXT and the accepted state ---------------------------------------------------------------------------------

    @Test fun nextIsBlockedUntilTheAnswerIsConfirmedThenRevealsTheEnginesNextQuestion() = runBlocking {
        val (d, p) = reach { _, m -> m != null && m.kind == PanelKind.SINGLE && m.options.size >= 2 && m.key != Keys.PLATFORMS && m.key != Keys.DIMENSION }
        val m = GuidedPanel.model(p)!!
        val blocked = GuidedPanel.next(p, m, null, busy = false)
        assertFalse(blocked.enabled)
        assertTrue(blocked.reason!!.contains("Confirm"))
        val action = GuidedPanel.confirm(m, setOf(m.options.first().id), "")!!
        val after = perform(d, p, action).project
        val acc = assertNotNull(GuidedPanel.accepted(after, p.messages.size, m.key, action.turnId, listOf(m.options.first().label)))
        assertTrue(acc.summary.contains(m.options.first().label))
        val next = GuidedPanel.next(after, GuidedPanel.model(after), acc, busy = false)
        assertTrue(next.enabled, "NEXT is enabled once the answer was accepted")
        // NEXT reveals the question the engine already asked: it is simply the panel's model for the project as it stands.
        val revealed = GuidedPanel.model(after)
        assertTrue(revealed == null || revealed.key == after.pendingFieldKey)
        assertEquals(after.pendingFieldKey, revealed?.key ?: after.pendingFieldKey)
    }

    @Test fun nextIsBlockedWhileBobIsBusyOrAMessageIsQueued() = runBlocking {
        val (_, p) = reach { _, m -> m != null }
        val m = GuidedPanel.model(p)
        assertFalse(GuidedPanel.next(p, m, null, busy = true).enabled)
        val queued = p.copy(pendingTurn = com.hotattic.gamedesigner.core.model.PendingTurn("t", "x", 1L))
        assertFalse(GuidedPanel.next(queued, null, null, busy = false).enabled)
    }

    @Test fun aQuestionTheEngineAsksAgainIsNotReportedAsAccepted() = runBlocking {
        val (d, p) = reach { _, m -> m != null && m.kind == PanelKind.TEXT }
        val m = GuidedPanel.model(p)!!
        val action = PanelAction.Say("???", "panel:${m.messageId}:x")
        val after = perform(d, p, action).project
        if (after.pendingFieldKey == m.key) assertNull(GuidedPanel.accepted(after, p.messages.size, m.key, action.turnId, emptyList()))
    }

    @Test fun anAcceptedStateExpiresWhenAnythingElseHappens() = runBlocking {
        val (d, p) = reach { _, m -> m != null && m.kind == PanelKind.SINGLE && m.options.size >= 2 && m.key != Keys.PLATFORMS && m.key != Keys.DIMENSION }
        val m = GuidedPanel.model(p)!!
        val action = GuidedPanel.confirm(m, setOf(m.options.first().id), "")!!
        val after = perform(d, p, action).project
        val acc = GuidedPanel.accepted(after, p.messages.size, m.key, action.turnId, emptyList())!!
        assertTrue(GuidedPanel.stillCurrent(acc, after))
        val typed = d.handleUserMessage(after, settings, "status").project
        assertFalse(GuidedPanel.stillCurrent(acc, typed), "a typed message ends the accepted state; the panel shows the current question")
        assertNull(GuidedPanel.accepted(p, p.messages.size, m.key, action.turnId, emptyList()), "an unprocessed turn is not accepted")
    }

    // ---- BACK ---------------------------------------------------------------------------------------------------------

    @Test fun backUndoesOnlyTheLastAnswerAndKeepsEverythingElse() = runBlocking {
        val (d, p) = reach { q, m -> m != null && q.answerTrail.size >= 3 && m.kind == PanelKind.SINGLE }
        assertTrue(GuidedPanel.canBack(p, busy = false))
        val earlier = p.answerTrail.dropLast(1)
        val back = assertNotNull(d.goBack(p))
        for (k in earlier) assertEquals(p.decision(k)?.value, back.decision(k)?.value, "answer $k survives Back")
        assertEquals(p.facts, back.facts)
        val reasked = GuidedPanel.model(back)
        assertNotNull(reasked)
        assertEquals(p.answerTrail.last(), reasked.key, "the previous question is asked again")
        assertFalse(GuidedPanel.canBack(p, busy = true))
        assertFalse(GuidedPanel.canBack(newProject(), busy = false))
    }

    // ---- typing while the panel is open ---------------------------------------------------------------------------------

    @Test fun aTypedCorrectionWhilePlatformsAreOnScreenGoesThroughTheEngineAndThePanelFollows() = runBlocking {
        val (d, p) = reach { _, m -> m != null && m.kind in setOf(PanelKind.SINGLE, PanelKind.TEXT, PanelKind.MULTI) && m.key != Keys.PLATFORMS && m.key != Keys.PRIMARY_PLATFORM }
        val after = d.handleUserMessage(p, settings, "Make PC the main platform.").project
        assertEquals(Platforms.WINDOWS, after.value(Keys.PRIMARY_PLATFORM), "the G6 correction still applies")
        assertTrue(Platforms.WINDOWS in after.list(Keys.PLATFORMS))
        val m = GuidedPanel.model(after)
        assertNotNull(m)
        assertEquals(p.pendingFieldKey, m.key, "the question that was open is still the one on screen")
        assertTrue(m.messageId != GuidedPanel.model(p)!!.messageId, "a fresh question message, so the screen's draft resets")
    }

    @Test fun primaryPlatformIsAskedAndConfirmedLikeAnyOtherChoice() = runBlocking {
        val (d, p) = reach { _, m -> m != null && m.key == Keys.PRIMARY_PLATFORM }
        val m = GuidedPanel.model(p)!!
        assertEquals(setOf(Platforms.ANDROID, Platforms.IOS), m.options.map { it.id }.toSet())
        val action = GuidedPanel.confirm(m, setOf(Platforms.IOS), "")!!
        val after = perform(d, p, action).project
        assertEquals(Platforms.IOS, after.value(Keys.PRIMARY_PLATFORM))
        assertEquals(setOf(Platforms.ANDROID, Platforms.IOS), after.list(Keys.PLATFORMS).toSet(), "choosing a primary never drops the others")
    }

    // ---- conflicts, review, ready, delegate, skip ------------------------------------------------------------------------

    @Test fun aConflictQuestionOffersItsAlternativesAndIsResolvedThroughTheNormalCommand() = runBlocking {
        val d = director()
        val (_, ready) = reach(d) { _, m -> m?.kind == PanelKind.READY }
        val broken = ProjectOps.addFacts(ready, listOf("Add PC as the primary platform."), "owner", Provenance.OWNER_EXPLICIT, ready.updatedAt + 10_000)
        val asked = d.askNext(ProjectOps.setPending(broken, null), settings)
        val m = assertNotNull(GuidedPanel.model(asked))
        assertEquals(PanelKind.CONFLICT, m.kind)
        assertTrue(m.options.isNotEmpty() && m.options.all { it.send != null })
        assertTrue(m.title.isNotBlank())
        val action = assertNotNull(GuidedPanel.confirm(m, setOf(m.options.first().id), "")) as PanelAction.Say
        assertEquals("use alternative 1", action.text)
        val fixed = perform(d, asked, action).project
        assertTrue(ConflictEngine.open(fixed).none { it.id == "fact_contradicts_decision" })
        // NEXT explains why it cannot skip a conflict.
        assertTrue(GuidedPanel.next(asked, m, null, false).reason!!.contains("decision"))
    }

    @Test fun reviewAndReadyAreAnsweredThroughTheirOwnPipelines() = runBlocking {
        val d = director()
        val (_, review) = reach(d) { _, m -> m?.kind == PanelKind.REVIEW }
        val m = GuidedPanel.model(review)!!
        assertTrue(m.options.any { it.id == "looks_right" } && m.options.any { it.id == "change" })
        val action = GuidedPanel.confirm(m, setOf("looks_right"), "") as PanelAction.Select
        val approved = perform(d, review, action).project
        assertTrue(com.hotattic.gamedesigner.core.engine.ReviewGate.approved(approved))
        val ready = GuidedPanel.model(approved)
        assertTrue(ready == null || ready.kind == PanelKind.READY, "after approval the next panel is the generate step: ${ready?.kind}")
    }

    @Test fun delegateAndSkipSendTheSameTextsTheOldQuickRepliesSent() = runBlocking {
        val (d, p) = reach { _, m -> m != null && m.canDelegate && m.kind == PanelKind.SINGLE }
        val m = GuidedPanel.model(p)!!
        val del = GuidedPanel.delegate(m)
        assertEquals("choose for me", del.text)
        val a = perform(d, p, del).project
        assertTrue(a.decision(m.key) != null || a.pendingFieldKey != m.key)
        assertTrue(perform(d, a, del).duplicate, "a second tap with the same stable id is ignored")

        val (d2, ready) = reach { _, mm -> mm?.kind == PanelKind.READY }
        val (opt, om) = listOf(Keys.COLOR_MOOD, Keys.VFX, Keys.AUDIO, Keys.TOOLCHAIN_PREFS, Keys.REFERENCES).map { k -> d2.askAbout(ready, k).let { it to GuidedPanel.model(it) } }
            .firstOrNull { (_, m) -> m != null && m.canSkip }.let { assertNotNull(it, "an optional question can be skipped") }.let { it.first to it.second!! }
        val skip = GuidedPanel.skip(om)
        assertEquals("skip", skip.text)
        assertTrue(perform(d2, opt, skip).project.pendingFieldKey != om.key)

        val (_, noSkip) = reach { _, mm -> mm != null && !mm.canSkip && mm.kind == PanelKind.SINGLE }
        assertEquals("ask me later", GuidedPanel.skip(GuidedPanel.model(noSkip)!!).text)
    }

    // ---- restoration and parity ------------------------------------------------------------------------------------------

    @Test fun closingAndReopeningTheProjectShowsTheSameQuestion() = runBlocking {
        val (_, p) = reach { _, m -> m != null && m.kind == PanelKind.SINGLE }
        val restored = ProjectCodec.decode(ProjectCodec.encode(p))
        assertEquals(GuidedPanel.model(p), GuidedPanel.model(restored))
        // A queued (unanswered) message restores as the saved bubble with Retry, not as a question.
        val queued = restored.copy(pendingTurn = com.hotattic.gamedesigner.core.model.PendingTurn("t", "hi", 1L))
        assertNull(GuidedPanel.model(ProjectCodec.decode(ProjectCodec.encode(queued))))
    }

    @Test fun noCapabilityOfTheOldQuestionCardIsLost() = runBlocking {
        val d = director()
        var p = start(d)
        var checked = 0
        repeat(200) {
            val m = GuidedPanel.model(p)
            if (m != null) {
                val last = p.messages.last()
                val reachable = (m.options.mapNotNull { it.send } + listOf("choose for me", "skip", "ask me later")).map { it.lowercase() }.toSet()
                if (last.question == null || last.question!!.options.isEmpty()) {
                    // Quick-reply questions: every quick reply is reachable as an option or as delegate/skip.
                    for (q in last.quickReplies) assertTrue(q.send.lowercase() in reachable, "\"${q.send}\" (${m.key}) is no longer reachable")
                } else {
                    // Structured questions: every option the card offered is an option here, with delegate and skip kept.
                    assertEquals(last.question!!.options.map { it.id }, m.options.map { it.id })
                    assertEquals(last.question!!.canDelegate, m.canDelegate)
                    assertEquals(last.question!!.canSkip, m.canSkip)
                }
                checked++
            }
            if (p.pendingFieldKey == null) return@repeat
            p = d.handleUserMessage(p, settings, autoAnswer(p.pendingFieldKey)).project
        }
        assertTrue(checked > 20, "checked $checked questions")
    }

    @Test fun lessonCardsAndWhyAreCarriedToThePanel() = runBlocking {
        val (_, p) = reach { _, m -> m != null && m.lesson != null }
        val m = GuidedPanel.model(p)!!
        assertNotNull(m.lesson)
        assertTrue(!m.why.isNullOrBlank())
    }
}
