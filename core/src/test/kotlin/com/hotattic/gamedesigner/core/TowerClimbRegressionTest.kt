package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.MasterPromptGenerator
import com.hotattic.gamedesigner.core.model.BrandingAsset
import com.hotattic.gamedesigner.core.model.BrandingMode
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A SYNTHETIC interview ("Lantern Tower", invented for this test) replayed through the Director. It keeps the behavioural coverage of an earlier
 * regression that was built from a real owner project: a skill-based 2.5D climbing platformer, no combat, no power progression, procedurally
 * generated stages, supplied assets and OTA. The expected result is docs/SYNTHETIC_CLIMB_GROUND_TRUTH.md: a regression oracle, not a game to
 * build. Each branch flips one answer and checks the interview and the export follow it. No owner data lives in this file.
 */
class TowerClimbRegressionTest {
    private val concept = "I want to make a game where you just keep climbing a lighthouse, as a 2.5d platformer that feels like it is built around a ring of stairs because it is kind of round, and you should be able to jump across gaps and catch other things. The ring that it is supposedly built around does not really exist, I just want that look, so as you climb the whole spiral rotates and you are always centered in the screen. You are the camera anchor. I want it to be a platformer with real jumps. I want trampoline pads. I want moving platforms going up and down and left and right. I want ropes to climb. I want swinging platforms. I want platform sections themselves to move"
    private val answers: Map<String, String> = mapOf(
        "platforms" to "Android",
        "five_minutes" to "One great five minute loop is making a string of risky jumps where the next ledge was almost too far and you only just made it, or catching a rope at the last moment to reach a moving platform before a crumbling ledge gives way",
        "player_feeling" to "They should feel a little lucky, but this is a skill based game",
        "core_loop" to "Minute to minute the player finds an obstacle, finds a way past it, and repeats. They have to get from this ledge to that ledge to move on. There may be a moving platform, a trampoline pad to hit, or a rope to catch mid-air, but something always blocks their path and they always have a place to reach",
        "world_structure" to "Procedurally generated stages",
        "has_combat" to "No",
        "has_progression" to "The climber never gets stronger. Either you can make the jumps or you cannot",
        "progression" to "The climber never gets stronger. Either you can make the jumps or you cannot",
        "win_loss_conditions" to "They win by getting to the top of the lighthouse. They never really lose, they just have not won yet",
        "difficulty_failure" to "Checkpoints and quick retry",
        "first_slice" to "About 20 minutes of climbing with several kinds of obstacle in combinations. The camera stays on the player and the lighthouse rotates to meet it, using the asset packs supplied with the master prompt, and creating player animations if needed. One gap should be slightly too wide, so the climber can grab the ledge and pull itself up. It has on-screen controls and it is 2.5d so all the asset packs are 3D",
        "art_direction" to "Voxel-look 2.5D",
        "ota_updates" to "Yes - over-the-air updates (Mote architecture)",
        "asset_policy" to "CC0 / public domain, else original/procedural",
        "branding_icon" to "Create an original one for me",
        "branding_studio_splash" to "Upload my own",
        "branding_game_splash" to "Create an original one for me",
        "display_name" to "Lantern Tower",
        "must_not_change" to "It must not change where the camera focus sits, how the stairs rotate to match it, or that this is purely a climbing game with environmental obstacles",
        "definition_of_done" to "A working gameplay loop from the first ledge to the lamp room",
    )

    private class Run(val project: Project, val asked: List<String>)

    private fun askedOf(p: Project) = p.messages.filter { it.role == Role.DIRECTOR }.mapNotNull { it.question?.fieldKey }

    private suspend fun replay(d: Director, overrides: Map<String, String> = emptyMap(), conceptText: String = concept, preface: (suspend (Project) -> Project)? = null): Run {
        val a = answers + overrides
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, conceptText).project
        if (preface != null) p = preface(p)
        var turns = 0
        while (turns++ < 120) {
            val pending = p.pendingFieldKey
            val reply = when {
                pending == "__proposals__" -> "yes"
                pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__asset_plan__" -> "looks good"
                pending == "__ready__" -> "generate"
                pending == "__review__" -> "looks right"
                pending != null && a[pending] != null -> a[pending]!!
                else -> "choose for me"
            }
            val t = d.handleUserMessage(p, settings, reply)
            p = t.project
            if (t.action is DirectorAction.RequestUpload) {
                val asset = BrandingAsset(slot = (t.action as DirectorAction.RequestUpload).slot, mode = BrandingMode.UPLOADED, localFile = "branding/master/studio_splash_e3d9bb56.png",
                    originalName = "Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png", sha256 = "e3d9bb5653eafb783eede827606e7ac73a4e45564a1c25b1ed13ad1429f48c4e", width = 1536, height = 1024, addedAt = 1L)
                p = d.attachmentReceived(p, settings, asset.slot, asset).project
            }
            if (t.action == DirectorAction.GenerateSpec) break
        }
        return Run(p, askedOf(p))
    }

    private fun spec(p: Project) = ClaudeMdGenerator.generate(p, 1, "Initial", "2026-10-08T00:00:00Z")
    private fun prompt(p: Project) = MasterPromptGenerator.generate(p, 1)
    private val combatDetail = setOf(Keys.COMBAT_MODEL, Keys.ENEMIES_BOSSES)

    @Test fun theSyntheticTowerInterviewReachesTheGroundTruthDesign() = runBlocking {
        val run = replay(director())
        val p = run.project
        // what the owner actually described
        assertEquals("platformer", p.list(Keys.GENRE).single())
        assertEquals("2.5D", p.value(Keys.DIMENSION)); assertEquals("android", p.value(Keys.PLATFORMS))
        assertEquals("procedural_stages", p.value(Keys.WORLD_STRUCTURE)); assertEquals("checkpoint_retry", p.value(Keys.DIFFICULTY_FAILURE)); assertEquals("voxel", p.value(Keys.ART_DIRECTION))
        assertTrue("getting to the top" in p.value(Keys.WIN_LOSS).orEmpty())
        // lucky keeps the owner's own words: skill-based near-miss, not randomness
        val feeling = p.value(Keys.PLAYER_FEELING).orEmpty()
        assertTrue("lucky" in feeling && "skill based" in feeling, feeling)
        // the camera/world idea survives as facts and as a binding constraint
        val facts = p.activeFacts().joinToString(" | ") { it.text }.lowercase()
        assertTrue("camera anchor" in facts && "ring of stairs" in facts && "rotates" in facts, facts)
        assertTrue("camera focus" in p.value(Keys.MUST_NOT_CHANGE).orEmpty())
        // no combat and no power progression, and neither branch was ever opened
        assertEquals("no", p.value(Keys.HAS_COMBAT)); assertFalse(Traits(p).has(Tag.COMBAT))
        assertEquals("no", p.value(Keys.HAS_PROGRESSION)); assertNull(p.decision(Keys.PROGRESSION), "no stale meta_unlocks")
        assertTrue(run.asked.none { it in combatDetail || it == Keys.PROGRESSION }, run.asked.toString())
        // supplied asset packs survive, and OTA 'yes' opened exactly one small follow-up
        val st = com.hotattic.gamedesigner.core.engine.AssetStrategy.of(p.value(Keys.ASSET_POLICY))
        assertEquals(com.hotattic.gamedesigner.core.engine.AssetSourceKind.SUPPLIED, st.order.first()); assertTrue(st.usesSupplied)
        assertFalse(Keys.ASSET_POLICY in run.asked, "already answered by the owner's own words")
        assertEquals("content_ota", p.value(Keys.OTA_UPDATES)); assertFalse(Keys.OTA_SCOPE in run.asked, "Mote decides what is OTA-safe; no scope question")
        // studio logo kept
        assertEquals("Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png", p.branding["studio_splash"]?.originalName)
    }

    @Test fun theV5AssetStatementKeepsSuppliedFirstThenFreeThenOriginalAndProceduralNeverForbidsAuthoredWork() = runBlocking {
        val said = "I want to be able to use asset packs that I give Claude with this prompt and any CC0 items you can find for me and original work done by Claude to fill in the gaps."
        val noPacks = answers["first_slice"]!!.replace(", using the asset packs supplied with the master prompt, and creating player animations if needed", "").replace("so all the asset packs are 3D", "so everything is 3D")
        val p = replay(director(), mapOf("first_slice" to noPacks, "asset_policy" to said)).project
        val st = com.hotattic.gamedesigner.core.engine.AssetStrategy.of(p.value(Keys.ASSET_POLICY))
        assertEquals(listOf(com.hotattic.gamedesigner.core.engine.AssetSourceKind.SUPPLIED, com.hotattic.gamedesigner.core.engine.AssetSourceKind.FREE, com.hotattic.gamedesigner.core.engine.AssetSourceKind.ORIGINAL), st.order)
        val md = spec(p); val mp = prompt(p)
        assertTrue(st.describe() in md && st.describe() in mp && st.describe() in ExportPackage.assetsMarkdown(p))
        assertFalse("CC0 / public domain, else original/procedural" in md + mp)
        val all = (md + mp).lowercase()
        assertFalse("never authored by hand" in all)
        assertTrue("has not forbidden hand-authored ingredients" in all && "authored modules, patterns, templates, grammars and rules are all allowed ingredients" in all)
    }

    @Test fun theDesignModelReadsTheTowerInterviewTheWayTheGroundTruthDoes() = runBlocking {
        val p = replay(director()).project
        val m = com.hotattic.gamedesigner.core.engine.DesignModel.of(p)
        assertEquals(com.hotattic.gamedesigner.core.engine.SystemState.ABSENT, m.state(com.hotattic.gamedesigner.core.engine.SystemId.COMBAT))
        assertEquals(com.hotattic.gamedesigner.core.engine.SystemState.ABSENT, m.state(com.hotattic.gamedesigner.core.engine.SystemId.CHARACTER_POWER))
        assertTrue(m.playerMasteryIsProgression, "mastery, not power, is the progression")
        assertEquals(com.hotattic.gamedesigner.core.engine.SystemState.PRESENT, m.state(com.hotattic.gamedesigner.core.engine.SystemId.PROCEDURAL_GENERATION))
        assertEquals(com.hotattic.gamedesigner.core.engine.SystemState.PRESENT, m.state(com.hotattic.gamedesigner.core.engine.SystemId.SUPPLIED_ASSETS))
        assertEquals(com.hotattic.gamedesigner.core.engine.SystemState.PRESENT, m.state(com.hotattic.gamedesigner.core.engine.SystemId.OTA_UPDATES))
        assertTrue(m.experience.skilledNearMiss, "'lucky' + 'skill based' is a skilled near-miss, not randomness")
        assertTrue(Keys.PROGRESSION !in m.unresolvedMaterialDecisions && m.unresolvedMaterialDecisions.isEmpty(), m.unresolvedMaterialDecisions.toString())
        val md = spec(p); val mp = prompt(p)
        assertTrue("Design reading of that feeling" in md && "skilled execution" in md && "success is decided by the player's skill" in md)
        assertTrue("Procedural generation constraints" in md && "Traversal grammar" in md && "trampoline" in md && "rope" in md)
        assertTrue("Progression is player mastery" in md && "Design reading" in mp)
        assertTrue(com.hotattic.gamedesigner.core.engine.DesignCoherence.check(p, mapOf("CLAUDE.md" to md, "MASTER_PROMPT.md" to mp, "ASSETS.md" to ExportPackage.assetsMarkdown(p))).none { it.level == com.hotattic.gamedesigner.core.engine.ReviewLevel.ERROR })
    }

    @Test fun theExportedPackageContainsNoContradictoryOrInventedRequirements() = runBlocking {
        val p = replay(director()).project
        val md = spec(p); val mp = prompt(p); val assets = ExportPackage.assetsMarkdown(p)
        System.getenv("TOWER_DUMP")?.let { java.io.File(it, "NEW_MASTER_PROMPT.md").writeText(mp); java.io.File(it, "NEW_CLAUDE.md").writeText(md); java.io.File(it, "NEW_ASSETS.md").writeText(assets) }
        val all = md + "\n" + mp + "\n" + assets
        for (stale in listOf("Permanent unlocks", "meta_unlocks", "Enemy types", "Bosses:", "Power-ups / abilities", "Hand-built level set", "Level select", "Earn at least one progression", "Fight every enemy",
            "Typical session: 1-5", "View: Side view", "Perspective:** Side view", "Implementing only one of the described characters", "Meta unlocks", "Hazards and enemies", "sprites"))
            assertFalse(stale.lowercase() in all.lowercase(), "export must not contain \"$stale\": " + all.lines().filter { stale.lowercase() in it.lowercase() }.joinToString(" // ") { it.take(200) })
        for (needed in listOf("NONE by design", "asset packs", "Procedural stage generator", "camera", "Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png", "skill based", "pull itself up", "Mote", "API 36"))
            assertTrue(needed.lowercase() in all.lowercase(), "export must contain \"$needed\"")
        assertTrue("Owner-supplied asset packs" in assets)
        assertTrue("3D" in mp, "supplied packs are described as 3D assets in the prompt")
        val review = com.hotattic.gamedesigner.core.engine.ConsistencyReview.reviewAll(p, mapOf("CLAUDE.md" to md, "MASTER_PROMPT.md" to mp, "ASSETS.md" to assets))
        assertTrue(review.errors.isEmpty(), review.errors.joinToString { it.code + ": " + it.message })
    }

    // ---- the same climbing concept with different answers: nothing is hard-coded to one project ---------------------------

    @Test fun answeringYesToCombatOpensCombatFollowUps() = runBlocking {
        val run = replay(director(), mapOf("has_combat" to "Yes", "combat_model" to "Melee and combos", "enemies_bosses" to "Skeletons that patrol ledges, and one boss at the top"))
        assertEquals("yes", run.project.value(Keys.HAS_COMBAT))
        assertTrue(Keys.COMBAT_MODEL in run.asked, run.asked.toString())
        assertTrue("Enemy" in spec(run.project) || "enem" in spec(run.project).lowercase())
    }

    @Test fun answeringYesToProgressionOpensProgressionFollowUps() = runBlocking {
        val run = replay(director(), mapOf("has_progression" to "Yes", "progression" to "Permanent unlocks between runs"), conceptText = concept)
        // the concept has no progression words, but the interview answer below is a plain 'yes'
        assertEquals("yes", run.project.value(Keys.HAS_PROGRESSION), run.asked.toString())
        assertEquals("meta_unlocks", run.project.value(Keys.PROGRESSION))
        assertTrue(Keys.PROGRESSION in run.asked)
        assertTrue("Permanent unlocks" in spec(run.project))
    }

    @Test fun answeringNoToOtaRemovesAllOtaDetail() = runBlocking {
        val run = replay(director(), mapOf("ota_updates" to "No - updates come as a normal new install"))
        assertEquals("none", run.project.value(Keys.OTA_UPDATES))
        assertFalse(Keys.OTA_SCOPE in run.asked)
        assertNull(run.project.decision(Keys.OTA_SCOPE))
        assertFalse("over-the-air" in prompt(run.project).lowercase() || "Mote" in prompt(run.project))
    }

    @Test fun changingYourMindAboutPowerProgressionUpdatesTheDesignAndTheExport() = runBlocking {
        val d = director()
        val run = replay(d)
        var p = run.project
        assertTrue("NONE by design" in spec(p))
        p = d.handleUserMessage(p, settings, "Actually, I changed my mind: the player gets stronger with upgrades and unlocks.").project
        assertEquals("yes", p.value(Keys.HAS_PROGRESSION))
        assertEquals(Provenance.OWNER_CORRECTION, p.decision(Keys.HAS_PROGRESSION)!!.prov)
        assertTrue(Fields.get(Keys.PROGRESSION)!!.isRelevant(Traits(p)))
    }

    @Test fun anEarlierRecommendationNeverSurvivesALaterOwnerStatement() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A 2.5D platformer where you climb a giant spiral lighthouse using jumps and ropes.").project
        p = d.askAbout(p, Keys.PROGRESSION)
        p = d.handleUserMessage(p, settings, "choose for me").project
        val first = p.value(Keys.PROGRESSION)
        assertNotNull(first, "Bob's recommendation was accepted")
        p = d.handleUserMessage(p, settings, "No, the climber does not get stronger. It is a climbing game.").project
        assertEquals("no", p.value(Keys.HAS_PROGRESSION))
        assertNull(p.decision(Keys.PROGRESSION), "the old recommendation is superseded, not kept next to the correction")
        assertFalse("Permanent unlocks" in spec(p) || "Both in-run" in spec(p))
    }

    @Test fun backAfterGateAnswerReconsidersTheBranch() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        var guard = 0
        while (p.pendingFieldKey != Keys.HAS_COMBAT && guard++ < 40) p = d.handleUserMessage(p, settings, if (p.pendingFieldKey == "__proposals__") "yes" else answers[p.pendingFieldKey] ?: "choose for me").project
        p = d.handleUserMessage(p, settings, "no").project
        val back = d.goBack(p)!!
        assertEquals(Keys.HAS_COMBAT, back.pendingFieldKey)
        val yes = d.handleUserMessage(back, settings, "yes").project
        assertTrue(Fields.get(Keys.COMBAT_MODEL)!!.isRelevant(Traits(yes)))
    }
}
