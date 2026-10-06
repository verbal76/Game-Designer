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
