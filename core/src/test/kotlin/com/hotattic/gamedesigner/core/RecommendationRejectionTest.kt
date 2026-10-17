package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.GuidedPanel
import com.hotattic.gamedesigner.core.director.PanelKind
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Rejecting what Bob recommended must never record it as the owner's choice. Evidence: AUTOMATED, synthetic project, rules only. */
class RecommendationRejectionTest {
    private fun ans(k: String?) = when {
        k == "__proposals__" -> "yes"; k == "__asset_plan__" -> "looks good"; k == "__review__" -> "looks right"
        k?.startsWith("__conflict") == true -> "use alternative 1"; k == "__ready__" -> "generate"
        k == Keys.FIVE_MINUTES || k == Keys.CORE_FANTASY -> "Playing it should feel calm and rewarding."
        else -> "choose for me"
    }

    /** A single-choice question that carries a recommendation. */
    private suspend fun atRecommendedQuestion(): Triple<Director, Project, com.hotattic.gamedesigner.core.director.PanelModel> {
        val d = director(); var p = d.handleUserMessage(d.start(newProject(), settings), settings, "A top-down 2D action game with swords, for Android.").project
        repeat(120) {
            val m = GuidedPanel.model(p)
            if (m != null && m.kind == PanelKind.SINGLE && m.options.size >= 3 && m.recommendationLabel != null && m.key != Keys.DIMENSION) return Triple(d, p, m)
            p = d.handleUserMessage(p, settings, ans(p.pendingFieldKey)).project
        }
        error("no recommended question")
    }

    @Test fun rejectingTheRecommendationIsNotChoosingIt() = runBlocking {
        val (d, p, m) = atRecommendedQuestion()
        val rec = m.options.first { it.recommended }
        val q = d.handleUserMessage(p, settings, "No, not ${rec.label.lowercase()}.").project
        assertTrue(q.decision(m.key)?.value != rec.id, "the rejected option was recorded as the decision: ${q.decision(m.key)}")
        assertTrue(rec.id in q.rejected[m.key].orEmpty(), "the rejection is remembered: ${q.rejected}")
        val again = GuidedPanel.model(q)
        assertNotNull(again); assertEquals(m.key, again.key, "the question is asked again")
        assertFalse(again.options.any { it.id == rec.id }, "the rejected option is no longer offered")
        assertTrue(again.recommendationLabel != rec.label, "and is not recommended again")
    }

    @Test fun anAlternativeNamedWithTheRejectionIsTheChoice() = runBlocking {
        val (d, p, m) = atRecommendedQuestion()
        val rec = m.options.first { it.recommended }; val other = m.options.first { it.id != rec.id }
        val q = d.handleUserMessage(p, settings, "I want ${other.label.lowercase()}, not ${rec.label.lowercase()}.").project
        assertEquals(other.id, q.decision(m.key)?.value)
        assertTrue(q.decision(m.key)!!.prov.ownerAuthored)
        assertTrue(rec.id in q.rejected[m.key].orEmpty())
    }

    @Test fun delegatingAfterARejectionNeverReturnsTheRejectedOption() = runBlocking {
        val (d, p, m) = atRecommendedQuestion()
        val rec = m.options.first { it.recommended }
        val q = d.handleUserMessage(p, settings, "No, not ${rec.label.lowercase()}.").project
        val r = d.handleUserMessage(q, settings, "choose for me").project
        assertTrue(r.decision(m.key)?.value != rec.id, "delegation re-chose the rejected option: ${r.decision(m.key)}")
        assertEquals(Provenance.OWNER_ACCEPTED_RECOMMENDATION, r.decision(m.key)?.prov)
    }

    @Test fun theRejectionSurvivesSaveAndReload() = runBlocking {
        val (d, p, m) = atRecommendedQuestion()
        val rec = m.options.first { it.recommended }
        val q = ProjectCodec.decode(ProjectCodec.encode(d.handleUserMessage(p, settings, "No, not ${rec.label.lowercase()}.").project))
        assertTrue(rec.id in q.rejected[m.key].orEmpty())
        assertFalse(GuidedPanel.model(q)!!.options.any { it.id == rec.id })
    }

    @Test fun anOptionsOwnNegativeWordingIsStillAnAnswer() = runBlocking {
        val d = director(); var p = d.handleUserMessage(d.start(newProject(), settings), settings, "A 2D platformer for Android.").project
        repeat(80) { if (p.pendingFieldKey != Keys.MULTIPLAYER_INTENT && p.pendingFieldKey != null) p = d.handleUserMessage(p, settings, ans(p.pendingFieldKey)).project }
        if (p.pendingFieldKey == Keys.MULTIPLAYER_INTENT) {
            val q = d.handleUserMessage(p, settings, "no multiplayer").project
            assertEquals("single_player", q.value(Keys.MULTIPLAYER_INTENT))
        }
    }
}
