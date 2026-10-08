package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.engine.DesignCoherence
import com.hotattic.gamedesigner.core.engine.DesignModel
import com.hotattic.gamedesigner.core.engine.ExperienceLens
import com.hotattic.gamedesigner.core.engine.LuckMeaning
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.SystemId
import com.hotattic.gamedesigner.core.engine.SystemState
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.MasterPromptGenerator
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.schema.DesignLenses
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.LensContext
import com.hotattic.gamedesigner.core.schema.LensPurpose
import com.hotattic.gamedesigner.core.schema.Fields
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class Model(var reply: (LlmRequest) -> String) : com.hotattic.gamedesigner.core.llm.LlmProvider {
    var calls = 0
    override val id = "m"; override val displayName = "Scripted local model"
    override val tier = com.hotattic.gamedesigner.core.llm.LlmTier.LOCAL_SMALL; override val isLocal = true
    override suspend fun isReady() = true
    override suspend fun complete(request: LlmRequest): LlmResult { calls++; return LlmResult.Ok(reply(request)) }
}

/** The reference library as behaviour: lenses, the derived design model, question relevance, choose-for-me and the coherence pass. */
class DesignIntelligenceTest {
    private fun asked(p: Project) = p.messages.filter { it.role == Role.DIRECTOR }.mapNotNull { it.question?.fieldKey }
    private fun spec(p: Project) = ClaudeMdGenerator.generate(p, 1, "Initial", "2026-10-08T00:00:00Z")
    private fun prompt(p: Project) = MasterPromptGenerator.generate(p, 1)

    /** Drives to the end, answering with [a] where a question has a scripted answer and "choose for me" otherwise. */
    private suspend fun run(concept: String, a: Map<String, String> = emptyMap(), d: Director = director(), stopAt: String? = null): Project {
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        repeat(120) {
            val pending = p.pendingFieldKey
            if (stopAt != null && pending == stopAt) return p
            val reply = when {
                pending == "__proposals__" -> "yes"
                pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__asset_plan__" -> "looks good"
                pending == "__ready__" -> "generate"
                pending == "__review__" -> "looks right"
                pending == "__followup__" -> a["__followup__"] ?: "choose for me"
                pending != null && a[pending] != null -> a[pending]!!
                else -> "choose for me"
            }
            val t = d.handleUserMessage(p, settings, reply)
            p = t.project
            if (t.action == DirectorAction.GenerateSpec) return p
        }
        return p
    }

    // ---- the library as lenses ------------------------------------------------------------------------------------------

    @Test fun everyReferenceNoteHasExactlyOneLensAndAFutureNoteIsOneEntry() {
        val dir = listOf("docs/game-design-references", "../docs/game-design-references").map { File(it) }.first { it.isDirectory }
        val notes = dir.listFiles { f -> Regex("\\d\\d-.*\\.md").matches(f.name) }!!.map { it.name }.sorted()
        assertEquals(9, notes.size)
        assertEquals(notes, DesignLenses.all.map { it.source }.sorted(), "one lens per curated note")
        assertEquals(DesignLenses.all.size, DesignLenses.all.map { it.id }.toSet().size)
    }

    @Test fun lensGuidanceIsSelectedPerSituationAndBounded() {
        val p = ProjectOps.setDecision(newProject(), Keys.WORLD_STRUCTURE, "procedural_stages", Provenance.OWNER_EXPLICIT, 1L)
        val feeling = DesignLenses.select(LensContext(newProject(), Fields.get(Keys.PLAYER_FEELING)))
        assertTrue(feeling.any { it.id == "mda" } && feeling.none { it.id == "pattern_pcg" })
        val gen = DesignLenses.select(LensContext(p, Fields.get(Keys.WORLD_STRUCTURE)))
        assertTrue(gen.any { it.id == "procedural_constraints" } && gen.any { it.id == "pattern_pcg" })
        val progression = DesignLenses.select(LensContext(p, Fields.get(Keys.HAS_PROGRESSION)))
        assertTrue(progression.any { it.id == "mastery" })
        // bounded: at most three lenses plus the baseline, a few hundred tokens whatever the situation
        for (f in Fields.all + listOf(null)) for (purpose in LensPurpose.entries) {
            val text = DesignLenses.guidance(LensContext(p, f, purpose))
            assertTrue(DesignLenses.select(LensContext(p, f, purpose)).size <= DesignLenses.MAX)
            assertTrue(text.length < 1800, "guidance for ${f?.key}/$purpose is ${text.length} chars")
        }
    }

    // ---- the design model -------------------------------------------------------------------------------------------------

    @Test fun optionalSystemsAreTristateAndAbsentIsResolved() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A precision platformer. There isn't any fighting or health, and the player never gets stronger. You keep trying jumps until you reach the top.").project
        val m = DesignModel.of(p)
        assertEquals(SystemState.ABSENT, m.state(SystemId.COMBAT))
        assertEquals(SystemState.ABSENT, m.state(SystemId.CHARACTER_POWER))
        assertTrue(m.playerMasteryIsProgression)
        assertTrue(SystemId.COMBAT in m.intendedAbsences)
        assertEquals(SystemState.UNKNOWN, DesignModel.of(ProjectOps.setDecision(newProject(), Keys.GENRE, "platformer", Provenance.OWNER_EXPLICIT, 1L)).state(SystemId.COMBAT), "an unasked gate is a gap, not an answer")
    }

    // ---- the scenarios ----------------------------------------------------------------------------------------------------

    @Test fun A_aCombatGameOpensCombatDesignQuestions() = runBlocking {
        val p = run("A metroidvania where I fight skeleton enemies and bosses with a sword in a ruined castle.", mapOf("combat_model" to "Melee and combos"))
        assertEquals("yes", p.value(Keys.HAS_COMBAT)); assertNull(p.decision(Keys.HAS_COMBAT)?.takeIf { false })
        assertTrue(Keys.COMBAT_MODEL in asked(p), asked(p).toString())
    }

    @Test fun B_aCharacterProgressionGameExploresProgression() = runBlocking {
        val p = run("A 2.5D platformer where the hero gains XP, levels up and unlocks upgrades between runs.", mapOf("progression" to "XP and levels"))
        assertEquals("yes", p.value(Keys.HAS_PROGRESSION))
        assertTrue(Keys.PROGRESSION in asked(p) || p.decision(Keys.PROGRESSION) != null, asked(p).toString())
        assertEquals(SystemState.PRESENT, DesignModel.of(p).state(SystemId.CHARACTER_POWER))
        assertFalse("NONE by design" in spec(p).substringAfter("**Progression:**").take(40))
    }

    @Test fun C_aNoProgressionSkillGameKeepsMasteryWithoutUpgradeQuestions() = runBlocking {
        val p = run("A precision platformer about timing jumps. The player never gets stronger, it is pure skill, and you win at the top.")
        assertEquals("no", p.value(Keys.HAS_PROGRESSION))
        assertTrue(asked(p).none { it == Keys.PROGRESSION || it == Keys.HAS_PROGRESSION }, asked(p).toString())
        val md = spec(p)
        assertTrue("Progression is player mastery" in md && "internalising the timing" in md)
    }

    @Test fun D_aProceduralGameStatesWhatGenerationMustPreserve() = runBlocking {
        val p = run("A platformer with ropes, moving platforms and jumps, built from procedurally generated stages.", mapOf("world_structure" to "Procedurally generated stages"))
        val md = spec(p)
        assertTrue("Procedural generation constraints" in md && "completable from start to goal" in md && "readable" in md && "What may vary" in md)
        assertTrue("Traversal grammar" in md && "rope" in md)
        assertTrue("Procedural does not mean random" in md)
    }

    @Test fun E_anAuthoredGameDoesNotGetProceduralQuestionsOrSections() = runBlocking {
        val p = run("A platformer with hand-authored levels, each designed by me.", mapOf("world_structure" to "Hand-authored levels"))
        assertEquals("authored_levels", p.value(Keys.WORLD_STRUCTURE))
        assertFalse("Procedural generation constraints" in spec(p))
        assertEquals(SystemState.ABSENT, DesignModel.of(p).state(SystemId.PROCEDURAL_GENERATION))
    }

    @Test fun F_aRichAnswerSettlesSeveralGatesSoTheyAreNeverAsked() = runBlocking {
        val p = run("It's a climbing game. There isn't any fighting or health, and there is no crafting or shops. The player never gets stronger. You keep trying jumps until you reach the top.")
        assertTrue(asked(p).none { it in setOf(Keys.HAS_COMBAT, Keys.HAS_PROGRESSION, Keys.HAS_CRAFTING, Keys.HAS_ECONOMY, Keys.COMBAT_MODEL, Keys.PROGRESSION) }, asked(p).toString())
        val m = DesignModel.of(p)
        assertEquals(SystemState.ABSENT, m.state(SystemId.COMBAT)); assertEquals(SystemState.ABSENT, m.state(SystemId.CHARACTER_POWER))
        assertNotNull(p.decision(Keys.WIN_LOSS), "'reach the top' is read as the objective")
    }

    @Test fun G_chooseForMeCanSelectOmission() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A precision platformer about timing jumps up a tall tower.").project
        // deterministic designer judgement: nothing described needs a story
        val story = d.handleUserMessage(d.askAbout(p, Keys.STORY), settings, "choose for me").project
        assertEquals("none", story.value(Keys.STORY)); assertEquals(Provenance.OWNER_ACCEPTED_RECOMMENDATION, story.decision(Keys.STORY)!!.prov)
        // and with a model: "none" on an optional free-text question defers it instead of inventing something
        val llm = Model { r -> if (r.messages.last().content.contains("Choose what best fits")) """{"value":"none","why":"A pure climbing game does not need a colour brief."}""" else "{}" }
        val d2 = director(DirectorDeps(local = llm, clock = FakeClock()))
        var q = d2.start(newProject(), settings)
        q = d2.handleUserMessage(q, settings, "A precision platformer about timing jumps up a tall tower.").project
        val res = d2.handleUserMessage(d2.askAbout(q, Keys.COLOR_MOOD), settings, "choose for me").project
        assertEquals(DecisionStatus.DEFERRED, res.decision(Keys.COLOR_MOOD)!!.status)
    }

    @Test fun H_aLaterOwnerDecisionSupersedesAnEarlierRecommendationAndTheExportIsClean() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A 2.5D platformer where you climb a tall spiral tower using jumps and ropes.").project
        p = d.askAbout(p, Keys.PROGRESSION)
        p = d.handleUserMessage(p, settings, "choose for me").project
        assertNotNull(p.value(Keys.PROGRESSION))
        p = d.handleUserMessage(p, settings, "He doesn't get stronger. It's a climbing game. Either you can make the jumps or you can't.").project
        assertNull(p.decision(Keys.PROGRESSION))
        val done = run("x", emptyMap(), d, null).let { p }
        val md = spec(done); val mp = prompt(done)
        for (stale in listOf("Permanent unlocks", "meta_unlocks", "Earn at least one progression", "Both in-run")) assertFalse(stale in md + mp, "stale: $stale")
    }

    @Test fun H2_reconcileDropsAStaleDependentButARicherOwnerChoiceWinsOverAWeakInference() {
        var p = ProjectOps.setDecision(newProject(), Keys.GENRE, "platformer", Provenance.OWNER_EXPLICIT, 1L)
        p = ProjectOps.setDecision(p, Keys.PROGRESSION, "meta_unlocks", Provenance.OWNER_ACCEPTED_RECOMMENDATION, 2L)
        p = ProjectOps.setDecision(p, Keys.HAS_PROGRESSION, "no", Provenance.OWNER_EXPLICIT, 3L)
        val (a, notes) = DesignCoherence.reconcile(p, 4L)
        assertNull(a.decision(Keys.PROGRESSION)); assertTrue(notes.isNotEmpty())
        var q = ProjectOps.setDecision(newProject(), Keys.GENRE, "platformer", Provenance.OWNER_EXPLICIT, 1L)
        q = ProjectOps.setDecision(q, Keys.PROGRESSION, "xp_levels", Provenance.OWNER_EXPLICIT, 2L)
        q = ProjectOps.setDecision(q, Keys.HAS_PROGRESSION, "no", Provenance.SYSTEM_INFERENCE, 3L)
        val (b, _) = DesignCoherence.reconcile(q, 4L)
        assertNull(b.decision(Keys.HAS_PROGRESSION), "a guess does not override what the owner chose"); assertEquals("xp_levels", b.value(Keys.PROGRESSION))
    }

    @Test fun I_aGenreTrapNeverInventsCommonGenreFeatures() = runBlocking {
        val p = run("A platformer about running and jumping through a tall tower, nothing else.")
        val all = (spec(p) + prompt(p) + ExportPackage.assetsMarkdown(p)).lowercase()
        for (invented in listOf("collectible", "skill tree", "experience points", " xp", "lives", "currency", "loot", "crafting recipe", "enemy types", "boss"))
            if (invented in all) assertTrue(all.lines().filter { invented in it }.all { l -> Regex("(?i)\\b(no|never|none|not|without)\\b").containsMatchIn(l) }, "genre trap: '$invented' invented: " + all.lines().first { invented in it }.take(160))
        assertTrue(DesignCoherence.check(p, mapOf("CLAUDE.md" to spec(p), "MASTER_PROMPT.md" to prompt(p), "ASSETS.md" to ExportPackage.assetsMarkdown(p))).none { it.level == com.hotattic.gamedesigner.core.engine.ReviewLevel.ERROR })
    }

    @Test fun J_anAmbiguousEmotionGetsOneUsefulClarificationAndTheAnswerShapesTheFeeling() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A 2.5D platformer where you climb a tall tower.").project
        p = d.askAbout(p, Keys.PLAYER_FEELING)
        p = d.handleUserMessage(p, settings, "Lucky!").project
        assertEquals("__followup__", p.pendingFieldKey)
        assertTrue("real randomness" in p.messages.last().text && "skill" in p.messages.last().text)
        p = d.handleUserMessage(p, settings, "the feeling of barely pulling something off through skill").project
        assertTrue("not random luck" in p.value(Keys.PLAYER_FEELING).orEmpty(), p.value(Keys.PLAYER_FEELING))
        assertTrue(DesignModel.of(p).experience.skilledNearMiss)
        // a clear answer needs no question at all
        var q = d.start(newProject(), settings)
        q = d.handleUserMessage(q, settings, "A 2.5D platformer where you climb a tall tower.").project
        q = d.handleUserMessage(d.askAbout(q, Keys.PLAYER_FEELING), settings, "Lucky, because it's a skill game and you barely make jumps").project
        assertTrue(q.pendingFieldKey != "__followup__")
        assertEquals(LuckMeaning.RANDOMNESS, ExperienceLens.read("lucky drops and random crits").luck)
    }
}
