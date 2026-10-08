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
 * The real "Climb up" interview (raw owner answers from the exported project), replayed through the Director. The expected result is
 * docs/CLIMB_UP_GROUND_TRUTH.md: a regression oracle, not a game to build. The same machinery must work for other answers, so each
 * branch below flips one answer and checks the interview and the export follow it.
 */
class ClimbUpRegressionTest {
    private val concept = "I want to make a game like up where you just keep climbing but I want it to be a 2.5d platformer that feels like it's built around a cylinder because it's like kind of round but you should be able to jump across and catch other things. The cylinder that it's a supposedly built around doesn't really exist. I just want that look so as you're climbing the whole spiral platform rotates so you are always centered in the screen. You are the camera anchor. I want it to be a platformer. We had to make jumps. I want trampoline pads. I want moving levels, moving platforms up and down left and right. I want ropes to climb. I want swinging platforms. I want platform sections themselves to move"
    private val answers: Map<String, String> = mapOf(
        "platforms" to "Android",
        "five_minutes" to "One great 5-minute Play loop would be able to make all of these risky, sketchy jumps where the platform was too far away and you just barely made it. Or you climbed the rope just in time to catch the moving platform or the crumbling platformers you just ran across to stay together just long enough for you to make that epic jump",
        "player_feeling" to "They would feel very lucky cuz this is a skill based game",
        "core_loop" to "So the player loot their repeat minute to minute is find obstacle find way around obstacle repeat. So hey, I got to go for this part of the platform to this part of the platform to progress. There might be a moving platform, a trampoline jump to make or something that I got to catch mid-air or a grappling hook or a cable but they always have something impeding their travel. They have a location that they have to get to and their game loop is to make across that",
        "world_structure" to "Procedurally generated stages",
        "has_combat" to "No",
        "has_progression" to "He doesn't get stronger. It's a climbing game. Either you can make the jumps or you can't",
        "progression" to "He doesn't get stronger. It's a climbing game. Either you can make the jumps or you can't",
        "win_loss_conditions" to "They win by getting to the top. They don't really ever fail. They just don't win",
        "difficulty_failure" to "Checkpoints and quick retry",
        "first_slice" to "About 20 minutes of climbing gameplay with multiple obstacles of various kinds in combinations. It's a climbing game that's vaguely in a spiral where the camera is anchored on the player and the tower rotates to meet the camera angle and it should use the asset packs that I give it along with the master prompt. And if it has to create animations for the player than it does. It should also have a part where the gap for the jump is just a little bit too big and it can grab on with its fingerprints and pull itself up and it should have on-screen controls and it's a 2.5d so all the asset packs are going to be 3D",
        "art_direction" to "Voxel-look 2.5D",
        "ota_updates" to "Yes - over-the-air updates (least intrusive)",
        "ota_scope" to "Content and tuning only",
        "asset_policy" to "CC0 / public domain, else original/procedural",
        "branding_icon" to "Create an original one for me",
        "branding_studio_splash" to "Upload my own",
        "branding_game_splash" to "Create an original one for me",
        "display_name" to "Climb up",
        "must_not_change" to "It must not reinterpret where the camera focus is. How the terrain rotates to match the camera focus and that this is purely a climbing game with environmental obstacles",
        "definition_of_done" to "A successful gameplay loop throughout the entire game from start to finish",
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

    @Test fun theRealClimbUpInterviewReachesTheGroundTruthDesign() = runBlocking {
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
        assertTrue("camera anchor" in facts && "cylinder" in facts && "rotates" in facts, facts)
        assertTrue("camera focus" in p.value(Keys.MUST_NOT_CHANGE).orEmpty())
        // no combat and no power progression, and neither branch was ever opened
        assertEquals("no", p.value(Keys.HAS_COMBAT)); assertFalse(Traits(p).has(Tag.COMBAT))
        assertEquals("no", p.value(Keys.HAS_PROGRESSION)); assertNull(p.decision(Keys.PROGRESSION), "no stale meta_unlocks")
        assertTrue(run.asked.none { it in combatDetail || it == Keys.PROGRESSION }, run.asked.toString())
        // supplied asset packs survive, and OTA 'yes' opened exactly one small follow-up
        assertEquals("supplied_cc0", p.value(Keys.ASSET_POLICY)); assertFalse(Keys.ASSET_POLICY in run.asked, "already answered by the owner's own words")
        assertEquals("content_ota", p.value(Keys.OTA_UPDATES)); assertEquals("content_only", p.value(Keys.OTA_SCOPE))
        // studio logo kept
        assertEquals("Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png", p.branding["studio_splash"]?.originalName)
    }

    @Test fun theExportedPackageContainsNoContradictoryOrInventedRequirements() = runBlocking {
        val p = replay(director()).project
        val md = spec(p); val mp = prompt(p); val assets = ExportPackage.assetsMarkdown(p)
        System.getenv("CLIMB_DUMP")?.let { java.io.File(it, "NEW_MASTER_PROMPT.md").writeText(mp); java.io.File(it, "NEW_CLAUDE.md").writeText(md); java.io.File(it, "NEW_ASSETS.md").writeText(assets) }
        val all = md + "\n" + mp + "\n" + assets
        for (stale in listOf("Permanent unlocks", "meta_unlocks", "Enemy types", "Bosses:", "Power-ups / abilities", "Hand-built level set", "Level select", "Earn at least one progression", "Fight every enemy",
            "Typical session: 1-5", "View: Side view", "Perspective:** Side view", "Implementing only one of the described characters", "Meta unlocks", "Hazards and enemies", "sprites"))
            assertFalse(stale.lowercase() in all.lowercase(), "export must not contain \"$stale\": " + all.lines().filter { stale.lowercase() in it.lowercase() }.joinToString(" // ") { it.take(200) })
        for (needed in listOf("NONE by design", "asset packs", "Procedural stage generator", "camera", "Hot_Attic_Games_Master_Logo_ALPHA_FINAL.png", "skill based", "pull itself up", "Content and tuning"))
            assertTrue(needed.lowercase() in all.lowercase(), "export must contain \"$needed\"")
        assertTrue("Owner-supplied asset packs" in assets)
        assertTrue("3D" in mp, "supplied packs are described as 3D assets in the prompt")
        val review = com.hotattic.gamedesigner.core.engine.ConsistencyReview.reviewAll(p, mapOf("CLAUDE.md" to md, "MASTER_PROMPT.md" to mp, "ASSETS.md" to assets))
        assertTrue(review.errors.isEmpty(), review.errors.joinToString { it.code + ": " + it.message })
    }

    // ---- the same climbing concept with different answers: nothing is hard-coded to Climb up ---------------------------

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
        assertFalse("over-the-air" in prompt(run.project).lowercase())
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
        p = d.handleUserMessage(p, settings, "A 2.5D platformer where you climb a giant spiral tower using jumps and ropes.").project
        p = d.askAbout(p, Keys.PROGRESSION)
        p = d.handleUserMessage(p, settings, "choose for me").project
        val first = p.value(Keys.PROGRESSION)
        assertNotNull(first, "Bob's recommendation was accepted")
        p = d.handleUserMessage(p, settings, "No, he doesn't get stronger. It's a climbing game.").project
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
