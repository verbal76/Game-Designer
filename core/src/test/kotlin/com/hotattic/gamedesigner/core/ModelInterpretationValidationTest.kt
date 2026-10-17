package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.engine.FactStance
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.llm.LlmProvider
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.llm.LlmTier
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Traits
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Hedged, negated and contradictory owner statements through the MODEL-ASSISTED path.
 *
 * IMPORTANT: there is no real on-device model in this environment. The "model" below is a HAND-AUTHORED stand-in that replies with the mistakes a small
 * model typically makes (it ignores a negation, inverts a rejection, promotes a maybe to a fact, overrides an owner decision). It is NOT output captured
 * from LiteRT-LM or any real model, and these tests say nothing about how a real model interprets the words. What they prove is the layer that
 * must hold whatever a model says: owner statements, hedges and exclusions win over model proposals. The real model is exercised by the procedure
 * in docs/DEVICE_TEST_PROCEDURE.md.
 */
class ModelInterpretationValidationTest {
    private class HandAuthoredModel(var reply: String) : LlmProvider {
        override val id = "hand-authored"; override val displayName = "Hand-authored stand-in (not a real model)"
        override val tier = LlmTier.LOCAL_SMALL; override val isLocal = true
        var calls = 0
        override suspend fun isReady() = true
        override suspend fun complete(request: LlmRequest): LlmResult { calls++; return LlmResult.Ok(reply) }
    }

    private suspend fun base(model: HandAuthoredModel?): Pair<Director, Project> {
        val plain = director(); val p = plain.handleUserMessage(plain.start(newProject(), settings), settings, "A calm 2D puzzle game for Android about restoring a garden.").project
        return (if (model == null) plain else director(DirectorDeps(local = model, clock = FakeClock()))) to p
    }

    private class Case(val name: String, val owner: String, val reply: String, val pending: String? = null, val check: (Project) -> Unit, val setup: ((Project) -> Project)? = null)

    private val cases = listOf(
        Case("negated system: the model ignores 'no combat'", "There is no combat at all in this game.",
            """{"intent":"freeform","edits":{"has_combat":"yes"},"facts":["The player fights enemies with a sword"]}""",
            check = { p -> assertTrue(p.value(Keys.HAS_COMBAT) != "yes", "combat must not be switched on: ${p.decision(Keys.HAS_COMBAT)}") }),
        Case("hedged platform: the model promotes 'maybe Windows later'", "Android first. Maybe Windows later.",
            """{"intent":"freeform","edits":{"platforms":"android|windows"}}""",
            check = { p -> assertFalse("windows" in p.list(Keys.PLATFORMS), "a hedge is not a platform: ${p.decision(Keys.PLATFORMS)}") }),
        Case("rejection inverted: the model selects the option the owner refused", "No, not for a general audience.",
            """{"intent":"select","selected":["general"]}""", pending = Keys.AUDIENCE,
            check = { p -> assertTrue(p.value(Keys.AUDIENCE) != "general", "refused option must not be recorded: ${p.decision(Keys.AUDIENCE)}") }),
        Case("reference exclusion: the model turns the excluded mechanic into a fact", "I like Pac-Man's role reversal but I do not want power-ups.",
            """{"intent":"freeform","references":["Pac-Man"],"facts":["The game has power-ups that make the player invincible"]}""",
            check = { p ->
                val ref = p.references.single { it.name == "Pac-Man" }
                assertTrue(ref.excluded.any { it.contains("power-up") }, "exclusion kept on the reference: ${p.references}")
                assertTrue(p.activeFacts().none { it.text.contains("has power-ups", true) }, "the model's contradicting fact must not become an owner fact: ${p.activeFacts().map { it.text }}")
            }),
        Case("contradiction: the model keeps the first half and overrides the owner's final word", "Make it 3D. No wait, keep it 2.5D.",
            """{"intent":"freeform","edits":{"dimension":"3D"}}""",
            setup = { p -> ProjectOps.setDecision(p, Keys.DIMENSION, "2.5D", Provenance.OWNER_EXPLICIT, 1L) },
            check = { p -> assertEquals("2.5D", p.value(Keys.DIMENSION)) }),
        Case("tentative idea: the model records a maybe as a decision", "I might want multiplayer someday, I'm not sure.",
            """{"intent":"freeform","edits":{"multiplayer_intent":"online"}}""",
            check = { p -> val d = p.decision(Keys.MULTIPLAYER_INTENT); assertTrue(d == null || !(d.ownerAuthored && d.status == DecisionStatus.CONFIRMED), "a maybe is not a confirmed owner decision: $d") }),
    )

    private suspend fun run(c: Case, withModel: Boolean): Project {
        val model = if (withModel) HandAuthoredModel(c.reply) else null
        val (d, p0) = base(model)
        var p = c.setup?.invoke(p0) ?: p0
        if (c.pending != null) p = d.askAbout(p, c.pending)
        return d.handleUserMessage(p, settings, c.owner).project
    }

    @Test fun everyCaseHoldsOnTheRulesOnlyPath() = runBlocking { for (c in cases) try { c.check(run(c, false)) } catch (e: AssertionError) { throw AssertionError("rules path, ${c.name}: ${e.message}", e) } }

    @Test fun everyCaseHoldsWhenAModelProposesTheWrongThing() = runBlocking { for (c in cases) try { c.check(run(c, true)) } catch (e: AssertionError) { throw AssertionError("model path, ${c.name}: ${e.message}", e) } }

    @Test fun theModelPathNeverMakesOwnerAuthoredDecisionsWeaker() = runBlocking {
        for (c in cases) {
            val (_, p0) = base(null); val start = (c.setup?.invoke(p0) ?: p0).decisions.filterValues { it.ownerAuthored }
            val after = run(c, true).decisions
            for ((k, d) in start) assertTrue(after[k]?.let { it.ownerAuthored && it.rank() >= d.rank() } == true, "${c.name}: owner decision $k was weakened or removed")
        }
    }

    private fun com.hotattic.gamedesigner.core.model.Decision.rank() = prov.rank

    @Test fun theStandInIsReallyConsultedSoTheTestsAreNotVacuous() = runBlocking {
        val m = HandAuthoredModel("""{"intent":"freeform"}"""); val (d, p) = base(m); val before = m.calls
        d.handleUserMessage(p, settings, "I would like the garden to feel peaceful, with soft music and slow changes over time.")
        assertTrue(m.calls > before, "the model-assisted path must actually be exercised")
    }
}
