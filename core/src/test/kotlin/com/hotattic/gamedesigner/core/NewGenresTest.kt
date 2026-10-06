package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NewGenresTest {
    private fun ids(s: String) = GenreKnowledge.detect(s).map { it.id }

    @Test fun detectsTheThreeNewGenres() {
        assertEquals(listOf("colony_sim"), ids("a RimWorld-style colony simulator where colonists get moods"))
        assertEquals(listOf("management_sim"), ids("a restaurant management game, tycoon style"))
        assertEquals(listOf("fantasy_city_builder"), ids("a fantasy city builder with elves and mana crystals"))
    }

    @Test fun existingGenresAreNotStolen() {
        assertEquals(listOf("city_builder"), ids("a medieval city builder like Cities Skylines"))
        assertEquals(listOf("sim_management"), ids("an idle clicker with prestige"))
        assertTrue("management_sim" !in ids("a survival game with resource management"))
    }

    @Test fun everyNewGenreHasScopeSystemsAndAFullSpec() = runBlocking {
        for ((id, concept) in listOf(
            "colony_sim" to "A colony simulator where colonists survive on a hostile planet, with raids and moods.",
            "management_sim" to "A tycoon management game where I run a busy cafe with staff and customers.",
            "fantasy_city_builder" to "A fantasy city builder where elves and dwarves share a mana-powered town.",
        )) {
            assertTrue(GenreKnowledge.resolve(id).systems.size >= 6, "systems for $id")
            val (p, ready) = driveToReady(director(), newProject(), concept)
            assertTrue(ready, "reaches ready: $id")
            assertTrue(id in p.list("genre"), "genre $id recorded, was ${p.list("genre")}")
            val md = ClaudeMdGenerator.generate(p, 1, "Initial", "2026-10-06T00:00:00Z")
            assertTrue(GenreKnowledge.resolve(id).systems.count { it.name in md } >= 3, "spec lists the genre's systems: $id")
        }
    }

    @Test fun topDownAndIsometricAreDimensionChoices() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A colony simulator where colonists survive on a hostile planet.").project
        p = d.askAbout(p, Keys.DIMENSION)
        val opts = com.hotattic.gamedesigner.core.schema.Fields.get(Keys.DIMENSION)!!.options(com.hotattic.gamedesigner.core.schema.Traits(p)).map { it.id }
        assertTrue("top_down" in opts && "isometric" in opts)
        val iso = d.submitSelection(p, settings, Keys.DIMENSION, listOf("isometric")).project
        assertEquals("2.5D", iso.value(Keys.DIMENSION)); assertEquals("isometric_2d", iso.value(Keys.PERSPECTIVE))
        val td = d.submitSelection(p, settings, Keys.DIMENSION, listOf("top_down")).project
        assertEquals("2D", td.value(Keys.DIMENSION)); assertEquals("top_down", td.value(Keys.PERSPECTIVE))
    }
}

class BackNavigationTest {
    @Test fun backTakesBackTheLastAnswerAndAsksItAgain() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A phone action platformer about a wizard.").project
        p = d.askAbout(p, Keys.ORIENTATION)
        p = d.submitSelection(p, settings, Keys.ORIENTATION, listOf("landscape")).project
        assertEquals("landscape", p.value(Keys.ORIENTATION))
        assertTrue(Keys.ORIENTATION in p.answerTrail)
        val back = d.goBack(p)!!
        assertEquals(null, back.value(Keys.ORIENTATION))
        assertEquals(Keys.ORIENTATION, back.pendingFieldKey)
        assertTrue(Keys.ORIENTATION !in back.answerTrail)
        // answering again works and is recorded again
        val again = d.submitSelection(back, settings, Keys.ORIENTATION, listOf("portrait")).project
        assertEquals("portrait", again.value(Keys.ORIENTATION))
        assertEquals(null, d.goBack(newProject()))
    }
}

class MultipleReferencesTest {
    @Test fun addAnotherCollectsSeveralGamesThenDoneMovesOn() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A colony simulator on a hostile planet.").project
        p = d.askAbout(p, Keys.REFERENCES)
        p = d.handleUserMessage(p, settings, "Dwarf Fortress").project
        assertEquals("__more_refs__", p.pendingFieldKey)
        assertTrue(p.messages.last().quickReplies.any { it.label == "Add another game" } && p.messages.last().quickReplies.any { it.label == "Done" })
        p = d.handleUserMessage(p, settings, "add another").project
        assertEquals("__more_refs__", p.pendingFieldKey)
        p = d.handleUserMessage(p, settings, "Songs of Syx").project
        assertEquals(setOf("Dwarf Fortress", "Songs of Syx"), p.references.map { it.name }.toSet())
        assertEquals("__more_refs__", p.pendingFieldKey)
        p = d.handleUserMessage(p, settings, "done").project
        assertTrue(p.pendingFieldKey != "__more_refs__")
        assertTrue(p.value(Keys.REFERENCES)!!.contains("Songs of Syx"))
    }
}

class ReferenceAspectsTest {
    private suspend fun twoGames(d: com.hotattic.gamedesigner.core.director.Director): com.hotattic.gamedesigner.core.model.Project {
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A colony simulator on a hostile planet.").project
        p = d.askAbout(p, Keys.REFERENCES)
        p = d.handleUserMessage(p, settings, "Dwarf Fortress").project
        p = d.handleUserMessage(p, settings, "add another").project
        p = d.handleUserMessage(p, settings, "Songs of Syx").project
        return d.handleUserMessage(p, settings, "done").project
    }

    @Test fun eachGameGetsItsOwnChoiceListAndPicksAreRecordedPerGame() = runBlocking {
        val d = director()
        var p = twoGames(d)
        assertEquals("__ref_aspects__", p.pendingFieldKey)
        val q1 = p.messages.last().question!!
        assertTrue(p.messages.last().text.contains("Dwarf Fortress"))
        assertTrue(q1.options.size in 5..6 && q1.kind == "MULTI")
        p = d.submitSelection(p, settings, "__ref_aspects__", listOf(q1.options[0].id, q1.options[1].id)).project
        assertEquals("__ref_aspects__", p.pendingFieldKey)
        assertTrue(p.messages.last().text.contains("Songs of Syx"))
        // typed free text is accepted for the second game
        p = d.handleUserMessage(p, settings, "I love how the whole city feels alive and crowded").project
        assertTrue(p.pendingFieldKey != "__ref_aspects__")
        val dwarf = p.references.first { it.name.contains("Dwarf") }
        val syx = p.references.first { it.name.contains("Syx") }
        assertEquals(2, dwarf.aspects.size)
        assertTrue(syx.aspects.single().contains("alive and crowded"))
        assertTrue(p.value(Keys.REFERENCE_ASPECTS)!!.let { "Dwarf Fortress" in it && "Songs of Syx" in it })
    }

    @Test fun llmOptionsAreValidatedAndRulesAreTheFallback() {
        assertTrue(com.hotattic.gamedesigner.core.engine.ReferenceAspects.validateLlm("nope") == null)
        assertTrue(com.hotattic.gamedesigner.core.engine.ReferenceAspects.validateLlm("[\"a\"]") == null)
        val ok = com.hotattic.gamedesigner.core.engine.ReferenceAspects.validateLlm("```[\"Digging through z-levels\",\"Fun failure stories\",\"Deep dwarf needs\",\"Water and magma physics\",\"Huge fortress scale\"]```")
        assertEquals(5, ok!!.size)
    }
}

class CombatMultiSelectTest {
    private class TopicResearch : com.hotattic.gamedesigner.core.research.ResearchProvider {
        val asked = mutableListOf<String>()
        override suspend fun researchReferenceGame(name: String) = com.hotattic.gamedesigner.core.research.ResearchOutcome.NotFound(name)
        override suspend fun toolchainFacts(engineId: String) = com.hotattic.gamedesigner.core.research.ResearchOutcome.Unavailable("test")
        override suspend fun researchTopic(term: String): com.hotattic.gamedesigner.core.research.ResearchOutcome<com.hotattic.gamedesigner.core.model.ResearchNote> {
            asked += term
            return com.hotattic.gamedesigner.core.research.ResearchOutcome.Found(com.hotattic.gamedesigner.core.model.ResearchNote("t_$term", term, "Dodge-roll combat rewards timing and spacing.", emptyList(), createdAt = 1L))
        }
    }

    private suspend fun atCombat(d: com.hotattic.gamedesigner.core.director.Director): com.hotattic.gamedesigner.core.model.Project {
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A top-down action roguelike on my phone with fast fights.").project
        return d.askAbout(p, Keys.COMBAT_MODEL)
    }

    @Test fun combatAcceptsSeveralOptions() = runBlocking {
        val d = director()
        var p = atCombat(d)
        assertEquals("MULTI", p.messages.last().question!!.kind)
        p = d.submitSelection(p, settings, Keys.COMBAT_MODEL, listOf("aimed_real_time", "ability_cooldown")).project
        assertEquals(listOf("aimed_real_time", "ability_cooldown"), p.list(Keys.COMBAT_MODEL))
    }

    @Test fun typedCustomStylesAreKeptAndResearched() = runBlocking {
        val research = TopicResearch()
        val d = director(com.hotattic.gamedesigner.core.director.DirectorDeps(research = research, clock = FakeClock()))
        val online = settings.copy(internetResearchAllowed = true)
        var p = atCombat(d)
        p = d.handleUserMessage(p, online, "aim and shoot in real time and also dodge roll combat").project
        val v = p.list(Keys.COMBAT_MODEL)
        assertTrue("aimed_real_time" in v, v.toString())
        assertTrue(v.any { it.contains("dodge roll", true) }, v.toString())
        assertTrue(research.asked.any { it.contains("dodge roll", true) })
        assertTrue(p.messages.any { it.text.startsWith("Researched") })
        assertTrue(p.research.isNotEmpty())
    }

    @Test fun customOnlyAndNoInternet() = runBlocking {
        val d = director()
        var p = atCombat(d)
        p = d.handleUserMessage(p, settings, "parry-based duels").project
        assertEquals(listOf("parry-based duels"), p.list(Keys.COMBAT_MODEL))
        assertTrue(p.messages.any { it.text.contains("exactly as you wrote it") })
    }
}

class GameTitleTest {
    @Test fun aTitleIsAcceptedAsTheNameWhateverWordsItContains() = runBlocking {
        for (title in listOf("Tryin' To Not Die", "No Way Out", "Never Alone Again", "Not Another Zombie Game", "Don't Starve Together Too", "Turn Based Hero")) {
            val d = director()
            var p = d.start(newProject(), settings)
            p = d.handleUserMessage(p, settings, "A top-down action roguelike on my phone about surviving a dangerous city.").project
            p = d.askAbout(p, Keys.DISPLAY_NAME)
            val t = d.handleUserMessage(p, settings, title).project
            assertEquals(title, t.value(Keys.DISPLAY_NAME), title)
            assertTrue(t.references.isEmpty(), "no phantom reference for '$title': ${t.references.map { it.name }}")
            assertTrue(t.pendingFieldKey != Keys.DISPLAY_NAME, "moved on after '$title'")
        }
    }
}

class RepeatedChangeTest {
    @Test fun theOwnerCanChangeTheSameDecisionAsOftenAsTheyLike() {
        var p = newProject()
        for (v in listOf("cc0_default", "original_only", "cc0_default", "cc0_or_cc_by", "cc0_default")) {
            p = com.hotattic.gamedesigner.core.engine.ProjectOps.setDecision(p, Keys.ASSET_POLICY, v, com.hotattic.gamedesigner.core.model.Provenance.OWNER_EXPLICIT, 1L)
            assertEquals(v, p.value(Keys.ASSET_POLICY))
        }
        // a system guess still cannot override the owner
        val g = com.hotattic.gamedesigner.core.engine.ProjectOps.setDecision(p, Keys.ASSET_POLICY, "original_only", com.hotattic.gamedesigner.core.model.Provenance.SYSTEM_INFERENCE, 2L)
        assertEquals("cc0_default", g.value(Keys.ASSET_POLICY))
    }
}

class AssetPlanLoopTest {
    @Test fun changingThePolicyAfterAnEarlierCorrectionTakesEffectAndTheQuestionIsNotRepeated() = runBlocking {
        val d = director()
        var (p, _) = driveToReadyUntilAssetPlan(d)
        // the owner already changed the policy once (so it is an OWNER_CORRECTION), as in the physical test
        val ops = com.hotattic.gamedesigner.core.engine.ProjectOps
        val prov = com.hotattic.gamedesigner.core.model.Provenance.OWNER_EXPLICIT
        p = ops.setDecision(p, Keys.ASSET_POLICY, "original_only", prov, 1L)
        p = ops.setDecision(p, Keys.ASSET_POLICY, "cc0_or_cc_by", prov, 2L)
        assertEquals(com.hotattic.gamedesigner.core.model.Provenance.OWNER_CORRECTION, p.decision(Keys.ASSET_POLICY)!!.prov)
        val planShown = p.messages.count { it.text.startsWith("Asset plan") }
        p = d.handleUserMessage(p, settings, "cc0 only").project
        assertEquals("cc0_default", p.value(Keys.ASSET_POLICY))
        assertTrue(p.pendingFieldKey != "__asset_plan__", "moved on, was ${p.pendingFieldKey}")
        assertTrue(p.messages.count { it.text.startsWith("Asset plan") } <= planShown + 1)
    }

    private suspend fun driveToReadyUntilAssetPlan(d: com.hotattic.gamedesigner.core.director.Director): Pair<com.hotattic.gamedesigner.core.model.Project, Boolean> {
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A top-down action roguelike on my phone about surviving a dangerous city.").project
        var n = 0
        while (p.pendingFieldKey != "__asset_plan__" && n++ < 200) {
            val pending = p.pendingFieldKey
            val reply = when {
                pending == "__proposals__" -> "yes"
                pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__review__" -> "looks right"
                pending == "__ready__" -> "generate"
                pending == "__more_refs__" -> "done"
                pending == "__ref_aspects__" -> "choose for me"
                else -> "choose for me"
            }
            p = d.handleUserMessage(p, settings, reply).project
        }
        return p to (p.pendingFieldKey == "__asset_plan__")
    }
}
