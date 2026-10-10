package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.engine.DesignCoherence
import com.hotattic.gamedesigner.core.engine.PlatformCompat
import com.hotattic.gamedesigner.core.engine.PrototypeLifecycle
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.ReviewLevel
import com.hotattic.gamedesigner.core.engine.StackAdvisor
import com.hotattic.gamedesigner.core.engine.UpdateStrategy
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.MasterPromptGenerator
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.schema.EngineCatalog
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Traits
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Architectural repair and prototype-handoff reform.
 *
 * Evidence classes: AUTOMATED = deterministic code behaviour asserted here. SIMULATED = the interview is replayed through the scripted Director.
 * Claims about how a live Claude model will behave when it reads the generated files are NOT proven by any test; the tests only prove the files
 * contain the operational rules (marker, handoff steps, working model).
 */
class PrototypeHandoffTest {
    private val concept = "A voxel survivors-like where a wizard defends a moving castle from waves of monsters."
    private class Run(val project: Project, val asked: List<String>, val ready: Boolean)

    private suspend fun run(replies: Map<String, String>, d: Director = director(), text: String = concept): Run {
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, text).project
        val asked = mutableListOf<String>()
        repeat(200) {
            val pending = p.pendingFieldKey
            if (pending != null && (asked.isEmpty() || asked.last() != pending)) asked += pending
            val reply = replies[pending] ?: when {
                pending == "__proposals__" -> "yes"
                pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__asset_plan__" -> "looks good"
                pending == "__ready__" -> "generate"
                pending == "__review__" -> "looks right"
                else -> "choose for me"
            }
            val t = d.handleUserMessage(p, settings, reply)
            p = t.project
            if (t.action == DirectorAction.GenerateSpec) return Run(p, asked, true)
        }
        return Run(p, asked, false)
    }

    private val multi = mapOf(Keys.PLATFORMS to "Android, iOS and Windows", Keys.PRIMARY_PLATFORM to "Android", Keys.MULTIPLAYER_INTENT to "Online multiplayer",
        Keys.MULTIPLAYER_TIMING to "later", Keys.MULTIPLAYER_MODE to "co-op", Keys.OTA_UPDATES to "Yes - over-the-air updates (Mote architecture)")
    private fun spec(p: Project) = ClaudeMdGenerator.generate(p, 1, "Initial", "2026-10-10T00:00:00Z")
    private fun prompt(p: Project) = MasterPromptGenerator.generate(p, 1)
    private fun errors(p: Project) = DesignCoherence.check(p, mapOf("CLAUDE.md" to spec(p), "MASTER_PROMPT.md" to prompt(p), "ASSETS.md" to ExportPackage.assetsMarkdown(p))).filter { it.level == ReviewLevel.ERROR }

    // 1 AUTOMATED+SIMULATED: OTA is interpreted as a requirement, not silently data-only.
    @Test fun otaIsARequirementNotSilentlyDataOnly() = runBlocking {
        var p = run(multi).project
        p = ProjectOps.setDecision(p, Keys.OTA_CONTENT, "gameplay_code|assets|audio", Provenance.OWNER_EXPLICIT, 1L, DecisionStatus.CONFIRMED, "test")
        val md = spec(p)
        assertTrue("gameplay fixes and new gameplay logic" in md, "gameplay code is part of the requirement")
        assertTrue("Never reduce the requirement to data-only updates" in md)
        assertTrue("Always needs a new package" in md && "Do not promise unrestricted dynamic code loading" in md)
        assertTrue(UpdateStrategy.perPlatform(p).any { it.platform == Platforms.IOS && it.code == com.hotattic.gamedesigner.core.engine.CodeUpdates.INTERPRETED_ONLY })
        assertTrue(UpdateStrategy.limitations(p).isNotEmpty(), "limits are told to the owner before generation")
        assertTrue(Regex("(?i)Upwardly").containsMatchIn(md).not(), "no other project's OTA is copied")
    }

    // 2 SIMULATED: several platforms -> ask which first, keep all.
    @Test fun multiplatformAsksPrimaryAndRetainsAll() = runBlocking {
        val r = run(multi)
        assertTrue(Keys.PRIMARY_PLATFORM in r.asked)
        assertEquals(setOf(Platforms.ANDROID, Platforms.IOS, Platforms.WINDOWS), Traits(r.project).platforms.toSet())
        assertEquals(Platforms.ANDROID, Traits(r.project).primaryPlatform)
        assertTrue("Android is built and tested first" in spec(r.project))
    }

    @Test fun singlePlatformNeverAsksForPrimary() = runBlocking {
        val r = run(mapOf(Keys.PLATFORMS to "Android"))
        assertFalse(Keys.PRIMARY_PLATFORM in r.asked)
    }

    // 3 SIMULATED: multiplayer intent is discovered before the package is generated.
    @Test fun multiplayerDiscoveryHappensBeforeGeneration() = runBlocking {
        val r = run(multi)
        assertTrue(r.ready)
        assertTrue(Keys.MULTIPLAYER_INTENT in r.asked)
        assertTrue(Keys.MULTIPLAYER_TIMING in r.asked && Keys.MULTIPLAYER_MODE in r.asked)
        val solo = run(mapOf(Keys.PLATFORMS to "Android", Keys.MULTIPLAYER_INTENT to "Single-player only"))
        assertFalse(Keys.MULTIPLAYER_TIMING in solo.asked)
        assertTrue("Single-player only" in spec(solo.project))
    }

    // 4 AUTOMATED: the chosen stack supports every selected platform.
    @Test fun stackSupportsEverySelectedPlatform() = runBlocking {
        val p = run(multi).project
        val e = Traits(p).engine
        assertNotNull(e)
        assertTrue(StackAdvisor.incompatibilities(p).isEmpty(), StackAdvisor.incompatibilities(p).joinToString())
        for (pl in Traits(p).platforms) assertTrue(EngineCatalog.supportsWrapped(e, pl), "${e.name} must reach $pl")
        assertTrue("Why this stack" in spec(p))
    }

    // 5 + 14 AUTOMATED: designer authority expires at handoff and does not return.
    @Test fun authorityExpiresAtHandoffAndDesignerKeepsNoControl() = runBlocking {
        val md = spec(run(multi).project)
        assertEquals(PrototypeLifecycle.Phase.PROTOTYPE, PrototypeLifecycle.phase(md))
        assertTrue(PrototypeLifecycle.restrictionsInForce(md).isNotEmpty(), "prototype-only items are tagged and bind before handoff")
        val after = PrototypeLifecycle.handoff(md, "2026-10-11", "owner accepted the prototype")
        assertEquals(PrototypeLifecycle.Phase.OWNER_DIRECTED, PrototypeLifecycle.phase(after))
        assertTrue(PrototypeLifecycle.restrictionsInForce(after).isEmpty())
        assertTrue("Handoff record" in after)
        assertEquals(after, PrototypeLifecycle.handoff(after, "2026-10-12", "again"), "handoff is idempotent; nothing can re-impose prototype control")
        assertTrue("Never cite an expired limitation" in md && "no role" in md)
    }

    // 6 AUTOMATED: the owner can evolve the game (add enemies) after handoff; nothing in the spec forbids it.
    @Test fun creativeEvolutionIsNotBlockedByPrototypeScope() = runBlocking {
        val p = run(mapOf(Keys.PLATFORMS to "Android", Keys.MULTIPLAYER_INTENT to "Single-player only")).project
        val md = spec(p)
        assertTrue("None is excluded by this document" in md)
        assertTrue("never 'forbidden'" in md)
        assertTrue(Regex("(?i)permanently (prohibit|forbid)").containsMatchIn(md).not())
        assertTrue(PrototypeLifecycle.restrictionsInForce(PrototypeLifecycle.handoff(md, "2026-10-11", "owner asked for new enemies")).isEmpty())
    }

    // 7 AUTOMATED: the chosen art style is protected against substitution.
    @Test fun voxelStaysVoxel() = runBlocking {
        var p = run(multi).project
        p = ProjectOps.setDecision(p, Keys.ART_DIRECTION, "voxel", Provenance.OWNER_EXPLICIT, 3L, DecisionStatus.CONFIRMED, "t")
        val mp = prompt(p)
        assertTrue("a voxel look is not smooth low-poly, pixel art or generic stylized 3D" in mp)
        assertTrue("voxel stays voxel" in mp)
    }

    // 8 AUTOMATED: one source of truth, optional delegation, no disconnected per-platform agents.
    @Test fun agentCoordinationIsOneProject() = runBlocking {
        val mp = prompt(run(multi).project)
        assertTrue("ONE authoritative project" in mp && "optional resource" in mp)
        assertTrue("Do not run disconnected per-platform agents" in mp && "work sequentially" in mp)
        assertTrue("Shared gameplay core first" in mp)
    }

    // 9 AUTOMATED: questions are not instructions.
    @Test fun questionsAreNotInstructions() = runBlocking {
        val mp = prompt(run(multi).project); val md = spec(run(multi).project)
        for (doc in listOf(mp, md)) assertTrue("A question from the owner is a question" in doc && "implement" in doc)
        assertTrue("without asking for repeated approval" in mp)
    }

    // 10 AUTOMATED: a fresh conversation with only the two files can find the lifecycle state and the handoff steps.
    @Test fun freshConversationHandoffIsSelfContained() = runBlocking {
        val p = run(multi).project
        val md = spec(p); val mp = prompt(p)
        assertTrue(md.lines().take(12).any { PrototypeLifecycle.MARKER_PREFIX in it })
        assertTrue("0a. Lifecycle and handoff" in md && "docs/HANDOFF.md" in md && "New conversation?" in md)
        assertTrue("LIFECYCLE AND HANDOFF" in mp && "OWNER_DIRECTED" in mp && "no longer governs" in mp)
        assertFalse("Game Designer app" in md.replace("Game Designer is a temporary", ""), "the spec never depends on the app being present")
    }

    // 11 AUTOMATED: earlier features survive.
    @Test fun existingFeaturesArePreserved() = runBlocking {
        val p = run(multi).project
        val md = spec(p); val mp = prompt(p)
        for (s in listOf("Part A", "ASSETS.md", "Android Target API 36", "Mote")) assertTrue(s in md + mp, s)
        assertTrue(errors(p).isEmpty(), errors(p).joinToString { it.message })
        assertTrue("PLAYABLE FIRST-BUILD SCOPE" in mp && "VERIFY BEFORE FINISHING" in mp)
    }

    // 12 ENVIRONMENT: the working tree touches only this repository's paths.
    @Test fun repositoryIsolation() {
        val root = java.io.File(System.getProperty("user.dir")).parentFile
        val out = runCatching { ProcessBuilder("git", "status", "--porcelain").directory(root).redirectErrorStream(true).start().inputStream.bufferedReader().readText() }.getOrDefault("")
        val offenders = out.lines().filter { it.isNotBlank() }.map { it.substring(3) }.filter { Regex("(?i)upwardly|climb[_-]?up|tartarian|(^|/)mote(/|$)").containsMatchIn(it) && !it.startsWith("docs/") && !it.startsWith("core/src/test/") }
        assertTrue(offenders.isEmpty(), offenders.joinToString())
    }

    // 13 SIMULATED+AUTOMATED: Android+iOS+Windows, online multiplayer planned for later.
    @Test fun futureMultiplayerScenarioIsOnePlanWithoutPrematureNetworking() = runBlocking {
        val r = run(multi)
        assertTrue(r.ready)
        assertTrue(Keys.PRIMARY_PLATFORM in r.asked)
        val p = r.project; val md = spec(p); val mp = prompt(p)
        assertEquals(3, Traits(p).platforms.size)
        assertTrue("implement no networking now" in md && "build no networking now" in mp)
        assertTrue("deterministic" in md && "ONE shared gameplay core" in md)
        assertTrue(StackAdvisor.incompatibilities(p).isEmpty())
        assertTrue(PlatformCompat.notes(p).any { "Mac with Xcode" in it }, "iOS limits are told before generation")
        assertTrue(errors(p).isEmpty(), errors(p).joinToString { it.message })
        assertEquals(1, Regex("ONE authoritative project").findAll(mp).count(), "one coordinated package")
    }

    @Test fun primaryPlatformDroppedWhenNoLongerSelected() = runBlocking {
        var p = run(multi).project
        p = ProjectOps.setDecision(p, Keys.PLATFORMS, Platforms.ANDROID, Provenance.OWNER_CORRECTION, 9L, DecisionStatus.CONFIRMED, "t")
        val (q, notes) = DesignCoherence.reconcile(p, 10L)
        assertTrue(Traits(q).primaryPlatform == Platforms.ANDROID)
        assertTrue(notes.isEmpty() || notes.any { "primary-platform" in it })
    }
}
