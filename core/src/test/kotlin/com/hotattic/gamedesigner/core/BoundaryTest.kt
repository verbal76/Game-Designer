package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.llm.LlmProvider
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.llm.LlmTier
import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.core.model.ReferenceGame
import com.hotattic.gamedesigner.core.model.ResearchNote
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.SourceRef
import com.hotattic.gamedesigner.core.research.ResearchOutcome
import com.hotattic.gamedesigner.core.research.ResearchProvider
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class FakeLlm(var reply: String, var ready: Boolean = true) : LlmProvider {
    var calls = 0
    override val id = "fake"; override val displayName = "Fake local model"; override val tier = LlmTier.LOCAL_SMALL; override val isLocal = true
    override suspend fun isReady() = ready
    override suspend fun complete(request: LlmRequest): LlmResult { calls++; return LlmResult.Ok(reply) }
}

class FakeResearch(val outcome: ResearchOutcome<ReferenceGame>) : ResearchProvider {
    var calls = 0
    override suspend fun researchReferenceGame(name: String): ResearchOutcome<ReferenceGame> { calls++; return outcome }
    override suspend fun toolchainFacts(engineId: String): ResearchOutcome<List<ResearchNote>> = ResearchOutcome.Unavailable("test")
}

class BoundaryTest {
    @Test fun modelExtractionFillsGapsButNeverOverridesDeterministicAndIsSanitized() = runBlocking {
        val llm = FakeLlm("""Sure! {"genre":["tower_defense","bogus"],"dimension":"3D","art_direction":"voxel","bogus":"x","orientation":"landscape"}""")
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "I'd like a 2D tower defense game with cute robots on my android phone").project
        assertEquals(1, llm.calls, "one model call both reads the message and settles what comes next")
        assertEquals("2D", p.value(Keys.DIMENSION), "deterministic reading must win over model guess")
        assertEquals("voxel", p.value(Keys.ART_DIRECTION))
        assertTrue(p.list(Keys.GENRE).contains("tower_defense") && !p.list(Keys.GENRE).contains("bogus"))
        assertEquals(null, p.decision("bogus"))
    }

    @Test fun worksWithNoModelAtAll() = runBlocking {
        val d = director(DirectorDeps(clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A 2D puzzle game on android where you arrange cats").project
        assertTrue(p.list(Keys.GENRE).contains("puzzle"))
    }

    @Test fun unreadyModelIsNotCalled() = runBlocking {
        val llm = FakeLlm("{}", ready = false)
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        d.handleUserMessage(d.start(newProject(), settings), settings, "A big 3D racing game for android and windows")
        assertEquals(0, llm.calls)
    }

    @Test fun questionsAreAnsweredByModelWhenAvailableElseDeterministically() = runBlocking {
        val llm = FakeLlm("2.5D means 3D looking graphics but 2D gameplay.")
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A puzzle game for android").project
        p = d.handleUserMessage(p, settings, "yes").project
        assertEquals(Keys.DIMENSION, p.pendingFieldKey)
        val t = d.handleUserMessage(p, settings, "what is 2.5D?")
        assertTrue(t.project.messages.last().text.contains("looking graphics"))
        assertEquals(Keys.DIMENSION, t.project.pendingFieldKey, "still waiting for the answer")
        val plain = director().handleUserMessage(p, settings, "what is 2.5D?")
        assertTrue(plain.project.messages.last().text.contains("Options", ignoreCase = true))
    }

    @Test fun researchRunsOnlyWithPermissionAndRecordsProvenance() = runBlocking {
        val found = ResearchOutcome.Found(ReferenceGame("Vampire Survivors", summary = "A roguelite survival game.", traits = listOf("auto-attack"), sources = listOf(SourceRef("Wikipedia", "https://en.wikipedia.org/wiki/Vampire_Survivors", 1L, "CC BY-SA"))))
        val r1 = FakeResearch(found)
        val denied = director(DirectorDeps(research = r1, clock = FakeClock()))
        denied.handleUserMessage(denied.start(newProject(), settings), settings, "I want something like Vampire Survivors on my android phone")
        assertEquals(0, r1.calls, "no internet permission, no research")

        val r2 = FakeResearch(found)
        val allowed = director(DirectorDeps(research = r2, clock = FakeClock()))
        val s = AppSettings(internetResearchAllowed = true)
        val p = allowed.handleUserMessage(allowed.start(newProject(), s), s, "I want something like Vampire Survivors on my android phone").project
        assertEquals(1, r2.calls)
        val ref = p.references.first { it.name == "Vampire Survivors" }
        assertTrue(ref.sources.single().url.startsWith("https://"))
        assertEquals("A roguelite survival game.", ref.summary)
    }

    @Test fun offlineResearchDegradesGracefully() = runBlocking {
        val d = director(DirectorDeps(research = FakeResearch(ResearchOutcome.Unavailable("no network")), clock = FakeClock()))
        val s = AppSettings(internetResearchAllowed = true)
        val p = d.handleUserMessage(d.start(newProject(), s), s, "I want something like Vampire Survivors on my android phone").project
        assertTrue(p.messages.any { it.role == Role.SYSTEM && it.text.contains("can't reach the internet") })
        assertFalse(p.references.isEmpty())
    }

    @Test fun bulkDelegateStopsAtCreativeInputAndResolvesTheRest() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "choose everything for me").project
        // Nothing can be chosen without a concept
        assertEquals(Keys.CONCEPT, p.pendingFieldKey)
        p = d.handleUserMessage(p, settings, "A 2D tower defense game for my android phone with robots").project
        p = d.handleUserMessage(p, settings, "yes").project
        p = d.handleUserMessage(p, settings, "choose everything for me").project
        val c = com.hotattic.gamedesigner.core.engine.CompletenessEngine.compute(p)
        assertTrue(c.missingRequired.size <= 1, c.missingRequired.map { it.key }.toString())
    }
}

class ModeSwitchTest {
    @Test fun playtestAndBackToDesigning() = runBlocking {
        val d = director()
        val (p, _) = driveToReady(d, newProject(), "A cozy 2D puzzle game for my android phone about arranging cats")
        val v = com.hotattic.gamedesigner.core.generate.SpecVersioning.createVersion(p, com.hotattic.gamedesigner.core.model.VersionKind.INITIAL, 10L, "2026-10-04")
        val pt = d.enterMode(v, settings, com.hotattic.gamedesigner.core.model.ProjectMode.PLAYTEST_CONTINUE)
        assertEquals(com.hotattic.gamedesigner.core.model.ProjectMode.PLAYTEST_CONTINUE, pt.mode)
        val back = d.handleUserMessage(pt, settings, "back to designing").project
        assertEquals(com.hotattic.gamedesigner.core.model.ProjectMode.NEW_GAME, back.mode)
        val noSpec = d.enterMode(newProject(), settings, com.hotattic.gamedesigner.core.model.ProjectMode.PLAYTEST_CONTINUE)
        assertEquals(com.hotattic.gamedesigner.core.model.ProjectMode.NEW_GAME, noSpec.mode)
    }
}
