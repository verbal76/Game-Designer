package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.model.ReferenceGame
import com.hotattic.gamedesigner.core.model.ResearchNote
import com.hotattic.gamedesigner.core.research.ResearchOutcome
import com.hotattic.gamedesigner.core.research.ResearchProvider
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Research informs; it never decides. Evidence: AUTOMATED with a recording fake provider. */
class ReferenceResearchIntegrityTest {
    private class Fake(val outcome: ResearchOutcome<ReferenceGame>) : ResearchProvider {
        val names = mutableListOf<String>()
        override suspend fun researchReferenceGame(name: String): ResearchOutcome<ReferenceGame> { names += name; return outcome }
        override suspend fun toolchainFacts(engineId: String): ResearchOutcome<List<ResearchNote>> = ResearchOutcome.Unavailable("test")
    }
    private val on = settings.copy(internetResearchAllowed = true)

    @Test fun aReferenceThatWasNotFoundIsNotSearchedAgainOnEveryMessage() = runBlocking {
        val research = Fake(ResearchOutcome.NotFound("x"))
        val d = director(DirectorDeps(research = research, clock = FakeClock()))
        var p = d.handleUserMessage(d.start(newProject(), on), on, "A cozy puzzle game inspired by Monument Valley.").project
        repeat(6) { p = d.handleUserMessage(p, on, "choose for me").project }
        assertEquals(1, research.names.count { it == "Monument Valley" }, "searched ${research.names}")
    }

    @Test fun anUnavailableSourceIsRetriedLater() = runBlocking {
        val research = Fake(ResearchOutcome.Unavailable("offline"))
        val d = director(DirectorDeps(research = research, clock = FakeClock()))
        var p = d.handleUserMessage(d.start(newProject(), on), on, "A cozy puzzle game inspired by Monument Valley.").project
        p = d.handleUserMessage(p, on, "choose for me").project
        assertTrue(research.names.size >= 2, "a failed lookup (offline) may be retried: ${research.names}")
    }

    @Test fun researchIsBackgroundNotARequirementAndAddsNoDecision() = runBlocking {
        val summary = "A puzzle game with fighting enemies and a crafting economy."
        val research = Fake(ResearchOutcome.Found(ReferenceGame(name = "Monument Valley", summary = summary, traits = listOf("combat", "crafting"))))
        val d = director(DirectorDeps(research = research, clock = FakeClock()))
        var p = d.handleUserMessage(d.start(newProject(), on), on, "A cozy puzzle game inspired by Monument Valley.").project
        repeat(100) { if (p.pendingFieldKey != null) p = d.handleUserMessage(p, on, when (p.pendingFieldKey) { "__proposals__" -> "yes"; "__asset_plan__" -> "looks good"; "__review__" -> "looks right"; "__ready__" -> "generate"; else -> "choose for me" }).project }
        assertTrue(p.value("has_combat") != "yes" && p.value("has_crafting") != "yes", "researched traits must not become decisions")
        val md = ClaudeMdGenerator.generate(p, 1, "x", "2026-10-17T00:00:00Z")
        val line = md.lines().firstOrNull { it.contains(summary) }
        assertTrue(line != null && Regex("(?i)not a requirement|background").containsMatchIn(line), "the research text is labelled: $line")
    }
}
