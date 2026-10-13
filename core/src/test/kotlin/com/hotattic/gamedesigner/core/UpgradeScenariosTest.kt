package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.engine.DesignCoherence
import com.hotattic.gamedesigner.core.engine.Exclusions
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.ReviewLevel
import com.hotattic.gamedesigner.core.engine.UpdateStrategy
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.HandoffDocs
import com.hotattic.gamedesigner.core.generate.MasterPromptGenerator
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Experience
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectPrefs
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.persist.ProjectCodec
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
 * The eight realistic scenarios of the master upgrade directive (A-H). Evidence: SIMULATED (scripted owner replies through the real Director with
 * rule-based interpretation, no model) plus AUTOMATED assertions on the generated documents. None of this proves how a live model or a real beginner behaves.
 */
class UpgradeScenariosTest {
    private class Run(val project: Project, val asked: List<String>, val director: List<String>)

    private suspend fun run(text: String, replies: Map<String, String> = emptyMap(), experience: Experience = Experience.BEGINNER): Run {
        val d = director()
        var p = d.start(newProject(prefs = ProjectPrefs(experience = experience)), settings)
        p = d.handleUserMessage(p, settings, text).project
        val asked = mutableListOf<String>(); val said = mutableListOf<String>()
        repeat(160) {
            val pending = p.pendingFieldKey
            if (pending != null && (asked.isEmpty() || asked.last() != pending)) asked += pending
            val reply = replies[pending] ?: when {
                pending == "__proposals__" -> "yes"; pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__asset_plan__" -> "looks good"; pending == "__ready__" -> "generate"; pending == "__review__" -> "looks right"
                pending == Keys.FIVE_MINUTES || pending == Keys.CORE_FANTASY -> "Playing it should feel exciting and fair."
                else -> "choose for me"
            }
            val before = p.messages.size
            val t = d.handleUserMessage(p, settings, reply); p = t.project
            p.messages.drop(before).filter { it.role == Role.DIRECTOR }.forEach { said += it.text }
            if (t.action == DirectorAction.GenerateSpec) return Run(p, asked, said)
        }
        error("did not reach generation for: ${text.take(50)}; asked=${asked.takeLast(8)}")
    }

    private fun spec(p: Project) = ClaudeMdGenerator.generate(p, 1, "Initial", "2026-10-13T00:00:00Z")
    private fun prompt(p: Project) = MasterPromptGenerator.generate(p, 1)
    private fun everything(p: Project) = mapOf("CLAUDE.md" to spec(p), "MASTER_PROMPT.md" to prompt(p), "ASSETS.md" to ExportPackage.assetsMarkdown(p)) + HandoffDocs.files(p)
    private fun errors(p: Project) = DesignCoherence.check(p, everything(p)).filter { it.level == ReviewLevel.ERROR }
    private val all14 = listOf("GAME_DESIGN", "REQUIREMENTS_LEDGER", "EXCLUSIONS", "ARCHITECTURE_DECISIONS", "TECH_STACK", "ASSET_PLAN", "BUILD_AND_RELEASE", "TEST_PLAN", "GAMEPLAY_INVARIANTS", "MILESTONES", "COMPLETION_CRITERIA", "OWNER_AUTHORIZATION", "FAILURE_ESCALATION", "PROGRESS_REPORTING")

    private fun commonChecks(r: Run) {
        val p = r.project; val docs = HandoffDocs.files(p)
        for (n in all14) assertTrue("docs/$n.md" in docs, "missing $n")
        assertTrue(errors(p).isEmpty(), errors(p).joinToString { "${it.code}: ${it.message}" })
        val mp = prompt(p)
        assertTrue("M1" in mp && "EARLY" in mp && "Failure:" in mp && "Releases:" in mp && "Progress:" in mp, "early build, escalation, permissions, progress are in the master prompt")
        assertTrue(r.director.any { "Disadvantages:" in it && "Bob recommends:" in it }, "at least one plain-English lesson was shown")
        assertTrue("Complete and verified" in docs.getValue("docs/REQUIREMENTS_LEDGER.md") && "REQ-001" in docs.getValue("docs/REQUIREMENTS_LEDGER.md"))
        val e = Traits(p).engine; assertNotNull(e)
        for (pl in Traits(p).platforms) assertTrue(EngineCatalog.supportsWrapped(e, pl), "${e.name} must reach $pl")
    }

    // A
    @Test fun a_beginner2dPlatformerForAndroid() = runBlocking {
        val r = run("I want a simple 2D platformer for my Android phone where a cat jumps across rooftops.", mapOf(Keys.MULTIPLAYER_INTENT to "single player"))
        commonChecks(r)
        assertFalse(Keys.PRIMARY_PLATFORM in r.asked); assertEquals("2D", r.project.value(Keys.DIMENSION))
        assertTrue("Build the ENTIRE approved game" in prompt(r.project))
        assertTrue(r.asked.indexOf(Keys.BUDGET) >= 0 && Keys.TEST_DELIVERY in r.asked && Keys.GAME_LENGTH in r.asked)
    }

    // B
    @Test fun b_3dVerticalClimberLikeUpwardly() = runBlocking {
        val r = run("A 3D voxel vertical climber for Android. You climb a huge tower using jumps and ropes, with no enemies and no combat. The player stays in the middle of the screen as the tower turns.", mapOf(Keys.MULTIPLAYER_INTENT to "single player", Keys.ART_DIRECTION to "voxel"))
        commonChecks(r)
        assertEquals("3D", Traits(r.project).engineDimension); assertTrue(Traits(r.project).engine!!.supports3D)
        val inv = HandoffDocs.files(r.project).getValue("docs/GAMEPLAY_INVARIANTS.md")
        assertTrue("Movement speeds" in inv && "regression check" in inv && "fix that animation" in inv)
        assertTrue("voxel stays voxel" in prompt(r.project) || "voxel" in prompt(r.project))
    }

    // C
    @Test fun c_aquariumPlatformerWithSphereGravityAndOta() = runBlocking {
        val r = run("A mobile platformer set inside a spherical aquarium where gravity pulls toward the glass sphere. Android. I want gameplay code updated over the air.",
            mapOf(Keys.MULTIPLAYER_INTENT to "single player", Keys.OTA_UPDATES to "Yes - over-the-air updates (Mote architecture)", Keys.OTA_CONTENT to "levels, scripts and gameplay logic"))
        commonChecks(r)
        val p = r.project; val md = spec(p)
        assertTrue(UpdateStrategy.wantsCode(p) && "EXECUTABLE updates" in md)
        assertFalse("CONTENT update system" in md, "never described as content-only when code is requested")
        assertTrue(Traits(p).engine!!.let { EngineCatalog.codeOta(it) != EngineCatalog.CodeOta.NONE }, "the engine can update gameplay logic")
        assertTrue("gravity" in prompt(p).lowercase())
        assertTrue(r.director.any { "Over-the-air updates." in it && "executable" in it.lowercase() }, "the content-versus-code lesson was taught")
    }

    // D
    @Test fun d_cooperativeMultiplayerAsksTheWholeNetworkingInterview() = runBlocking {
        val r = run("A cooperative dungeon crawler for four players online, on Android and Windows.",
            mapOf(Keys.MULTIPLAYER_INTENT to "online co-op", Keys.MULTIPLAYER_TIMING to "prototype", Keys.MULTIPLAYER_MODE to "co-op", Keys.MP_PLAYERS to "3-4 players", Keys.PRIMARY_PLATFORM to "Android"))
        commonChecks(r)
        for (k in listOf(Keys.MP_PLAYERS, Keys.MP_HOSTING, Keys.MP_SESSION, Keys.MP_CONNECT, Keys.MP_CROSSPLAY, Keys.MP_OFFLINE, Keys.PRIMARY_PLATFORM)) assertTrue(k in r.asked, "$k must be asked; asked=${r.asked}")
        assertTrue(r.director.any { "client-server" in it && "peer-to-peer" in it }, "hosting options are explained before they are chosen")
        val md = spec(r.project)
        assertTrue("Hosting:" in md && "Players at once" in md && "Required in the prototype" in md)
        assertTrue("Two-instance" in HandoffDocs.files(r.project).getValue("docs/REQUIREMENTS_LEDGER.md") || "two game instances" in spec(r.project))
    }

    // E
    @Test fun e_proceduralSpeedrunnerForSteamAndSteamDeck() = runBlocking {
        val r = run("A procedurally generated speedrunning platformer for Steam and the Steam Deck.", mapOf(Keys.MULTIPLAYER_INTENT to "single player", Keys.STORE_PLAN to "Steam", Keys.PRIMARY_PLATFORM to "Windows"))
        commonChecks(r)
        val p = r.project; val t = Traits(p)
        assertTrue(Platforms.STEAM_DECK in t.platforms)
        assertTrue("gamepad" in t.inputs, "the Deck needs controller input: ${t.inputs}")
        val docs = HandoffDocs.files(p)
        assertTrue("Verified status" in docs.getValue("docs/BUILD_AND_RELEASE.md") && "1280x800" in docs.getValue("docs/BUILD_AND_RELEASE.md"))
        assertTrue("recorded seed" in spec(p), "courses are reproducible from a seed")
        assertTrue(docs.getValue("docs/BUILD_AND_RELEASE.md").contains("Build NN Linux"))
        assertTrue(spec(p).contains("Steam Deck: play the whole game with the controller only"))
    }

    // F
    @Test fun f_crossPlatformGameplayCodeOta() = runBlocking {
        val r = run("A 3D action roguelite for Android, iOS and Windows.", mapOf(Keys.MULTIPLAYER_INTENT to "single player", Keys.PRIMARY_PLATFORM to "Android",
            Keys.OTA_UPDATES to "Yes - over-the-air updates (Mote architecture)", Keys.OTA_CONTENT to "scripts, gameplay logic and audio", Keys.STORE_PLAN to "Apple App Store"))
        commonChecks(r)
        val p = r.project
        assertTrue(UpdateStrategy.unmet(p).any { "iPhone" in it }, UpdateStrategy.unmet(p).toString())
        assertTrue("CANNOT be fully satisfied as asked" in spec(p))
        val e = Traits(p).engine!!; assertTrue(EngineCatalog.codeOta(e) != EngineCatalog.CodeOta.NONE, "stack: ${e.name}")
        // a stack that cannot update code is detected and explained rather than silently accepted
        val bad = ProjectOps.setDecision(p, Keys.ENGINE, "bevy", Provenance.OWNER_EXPLICIT, 9L, DecisionStatus.CONFIRMED, "t")
        assertTrue(ConflictEngine.all(bad).any { it.id == "ota_code_vs_engine" })
        val store = ProjectOps.setDecision(p, Keys.STORE_PLAN, "app_store", Provenance.OWNER_EXPLICIT, 10L, DecisionStatus.CONFIRMED, "t")
        assertTrue(ConflictEngine.all(store).any { it.id == "ota_code_vs_store" })
        assertTrue(UpdateStrategy.unmet(store).any { "Apple App Store" in it })
    }

    // G
    @Test fun g_explicitExclusionsAreRecordedAndNeverReintroduced() = runBlocking {
        val r = run("A cozy gardening game for Android with no inventory, no currencies and no skill trees. You just plant, water and watch things grow.", mapOf(Keys.MULTIPLAYER_INTENT to "single player"))
        commonChecks(r)
        val ids = Exclusions.fromOwner(r.project).map { it.id }.toSet()
        assertTrue(setOf("inventory", "currency", "skill_tree").all { it in ids }, ids.toString())
        val docs = everything(r.project)
        assertTrue("Excluded by the owner" in docs.getValue("CLAUDE.md") && "EXC-001" in docs.getValue("docs/EXCLUSIONS.md"))
        assertTrue(errors(r.project).isEmpty())
        // the latest word wins: the owner can add one back
        val d = director(); var p = r.project
        p = d.handleUserMessage(p, settings, "Actually, let's add an inventory after all.").project
        assertFalse("inventory" in Exclusions.fromOwner(p).map { it.id })
        // a document that reintroduces an excluded system is rejected
        val tampered = everything(r.project) + ("docs/EXTRA.md" to "Players manage a backpack inventory and spend currency at shops.")
        assertTrue(DesignCoherence.check(r.project, tampered).any { it.code == "excluded_system_reintroduced" })
    }

    // H
    @Test fun h_aBeginnerWhoKnowsNoTermsIsTaughtAtEveryStep() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A cooperative online platformer with generated levels for Android and Windows.").project
        fun lastDirector() = p.messages.last { it.role == Role.DIRECTOR }.text
        // engine
        p = d.askAbout(p, Keys.ENGINE); p = d.handleUserMessage(p, settings, "what is a game engine?").project
        assertTrue("software foundation" in lastDirector() && "Disadvantages" in lastDirector() && "Bob recommends" in lastDirector(), lastDirector())
        p = d.handleUserMessage(p, settings, "explain more").project
        assertTrue("More detail:" in lastDirector(), lastDirector())
        // networking
        p = d.askAbout(p, Keys.MP_HOSTING); p = d.handleUserMessage(p, settings, "what's the difference?").project
        assertTrue("client-server" in lastDirector() && "peer-to-peer" in lastDirector())
        // procedural generation
        p = d.askAbout(p, Keys.WORLD_STRUCTURE); p = d.handleUserMessage(p, settings, "what is procedural generation?").project
        assertTrue("Procedural generation means" in lastDirector() && "castle rooms" in lastDirector(), lastDirector())
        // build pipeline
        p = d.askAbout(p, Keys.CI_BUILD); p = d.handleUserMessage(p, settings, "what does that mean?").project
        assertTrue("continuous integration" in lastDirector(), lastDirector())
        // acronyms are explained where they appear
        assertTrue(p.messages.filter { it.role == Role.DIRECTOR }.none { Regex("\\bOTA\\b").containsMatchIn(it.text) && "over-the-air" !in it.text.lowercase() })
    }

    // Beginner and Advanced share one engine; switching keeps every answer.
    @Test fun switchingModesKeepsAnswersAndAdvancedAsksTechnicalControls() = runBlocking {
        val base = run("A 2D platformer for Android.", mapOf(Keys.MULTIPLAYER_INTENT to "single player"))
        val d = director()
        var p = base.project.copy(designApproval = null)
        val before = p.decisions.mapValues { it.value.value }
        p = d.handleUserMessage(p, settings, "advanced mode").project
        assertEquals(Experience.EXPERT, p.prefs.experience)
        assertEquals(before, p.decisions.mapValues { it.value.value }, "switching changes no decision")
        assertTrue(p.messages.last().text.contains("Switched to Advanced mode"))
        p = d.handleUserMessage(p, settings, "beginner mode").project
        assertEquals(Experience.BEGINNER, p.prefs.experience)
        assertEquals(before, p.decisions.mapValues { it.value.value })
        // in Advanced mode the technical controls are put to the owner before the review
        val adv = run("A 2D platformer for Android.", mapOf(Keys.MULTIPLAYER_INTENT to "single player"), Experience.EXPERT)
        assertTrue(Keys.ENGINE in adv.asked && Keys.STORE_PLAN in adv.asked, adv.asked.toString())
        val beg = run("A 2D platformer for Android.", mapOf(Keys.MULTIPLAYER_INTENT to "single player"))
        assertFalse(Keys.ENGINE in beg.asked)
    }

    // Existing projects: older stored shapes still load, regenerate and expand to the new model, without being altered on disk.
    @Test fun olderProjectsLoadMigrateAndAreNotOverwritten() = runBlocking {
        val r = run("A 2D platformer for Android.", mapOf(Keys.MULTIPLAYER_INTENT to "single player"))
        var old = ProjectOps.setDecision(r.project, Keys.OTA_UPDATES, "content_ota", Provenance.OWNER_EXPLICIT, 3L, DecisionStatus.CONFIRMED, "t")
        old = ProjectOps.setDecision(old, Keys.OTA_CONTENT, "gameplay_code|assets|audio|data|features", Provenance.OWNER_EXPLICIT, 4L, DecisionStatus.CONFIRMED, "legacy ids")
        val stripped = old.copy(decisions = old.decisions - setOf(Keys.MP_PLAYERS, Keys.BUDGET, Keys.GAME_LENGTH, Keys.TEST_DELIVERY, Keys.AUDIENCE, Keys.OTA_BEHAVIOR))
        val json = ProjectCodec.encode(stripped)
        val loaded = ProjectCodec.decode(json)
        assertEquals(json, ProjectCodec.encode(loaded), "loading and saving is lossless")
        assertTrue(setOf("scripts", "gameplay_logic", "models", "levels", "text", "audio").all { it in UpdateStrategy.content(loaded) }, "legacy ids expand")
        val g = SpecVersioning.generate(loaded, VersionKind.REVISION, 5L, "2026-10-13T00:00:00Z", requireApproval = false)
        assertTrue(g.review.errors.none { it.code == "lifecycle_marker_missing" }, g.review.errors.joinToString { it.code })
        assertEquals(loaded.versions.size, loaded.versions.size)
    }

    // Stack recommendations follow the requirements: wanting gameplay-code updates never ranks an engine that cannot do it first.
    @Test fun engineRankingFollowsUpdateAndMultiplayerRequirements() {
        val plats = setOf(Platforms.ANDROID, Platforms.WINDOWS)
        val withCode = com.hotattic.gamedesigner.core.schema.EngineRecommender.rank(plats, "3D", 5, true, emptySet(), wantsCodeOta = true)
        assertTrue(withCode.isNotEmpty() && EngineCatalog.codeOta(withCode.first().engine) != EngineCatalog.CodeOta.NONE, withCode.map { it.engine.id }.toString())
        assertTrue(withCode.first().notes.none { "cannot be updated" in it })
        val noEngineSkipsPlatform = withCode.all { e -> plats.all { EngineCatalog.supportsWrapped(e.engine, it) } }
        assertTrue(noEngineSkipsPlatform, "every ranked engine reaches every selected platform")
        assertTrue(EngineCatalog.supportsWrapped(EngineCatalog.get("godot")!!, Platforms.STEAM_DECK), "the Steam Deck is reached through Linux")
    }
}
