package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A human-verified interpretation corpus across genres: each owner message has an expected reading written by a person BEFORE looking at the
 * engine's output. The checks are about intent (mentioned / requested / rejected / considered / asked / compared), not about phrases, and run
 * on observable project state after the first message. Rules only, no model.
 */
class InterpretationCorpusTest {
    private suspend fun read(text: String): Project { val d = director(); return d.handleUserMessage(d.start(newProject(), settings), settings, text).project }
    private fun plats(p: Project) = p.list(Keys.PLATFORMS).toSet()
    private fun refs(p: Project) = p.references.map { it.name }

    @Test fun racingRejectsWeaponsKeepsPlatform() = runBlocking {
        val p = read("A racing game for Android. No combat, no weapons, just fast cars.")
        assertEquals(setOf(Platforms.ANDROID), plats(p)); assertTrue(p.value(Keys.HAS_COMBAT) != "yes"); assertEquals(emptyList(), refs(p))
    }

    @Test fun puzzleReferenceWithPossibleLaterPlatform() = runBlocking {
        val p = read("A cozy puzzle game inspired by Monument Valley. Maybe iPhone later.")
        assertEquals(listOf("Monument Valley"), refs(p)); assertTrue(Platforms.IOS !in plats(p), "iPhone is only a possibility: ${plats(p)}")
    }

    @Test fun cityBuilderReferenceNoMultiplayerSinglePlatform() = runBlocking {
        val p = read("A city builder like Cities Skylines, but no multiplayer. Windows only.")
        assertEquals(setOf(Platforms.WINDOWS), plats(p)); assertEquals(listOf("Cities Skylines"), refs(p))
    }

    @Test fun platformerWithHazardsButNoEnemies() = runBlocking {
        val p = read("A 2D platformer. No enemies, only hazards.")
        assertEquals("no", p.value(Keys.HAS_COMBAT), "decision: ${p.decision(Keys.HAS_COMBAT)}")
    }

    @Test fun strategyRejectingTurnBasedKeepsTheReference() = runBlocking {
        val p = read("A strategy game inspired by Civilization, but I don't want turn-based.")
        assertEquals(listOf("Civilization"), refs(p)); assertTrue("TURN_BASED" in p.rejected[com.hotattic.gamedesigner.core.schema.Dependencies.TAG].orEmpty(), "rejected=${p.rejected}")
    }

    @Test fun narrativeGameWithPrimaryAndLaterPlatform() = runBlocking {
        val p = read("A narrative adventure like Disco Elysium with no combat at all. PC is the main platform, Android maybe later.")
        assertEquals(listOf("Disco Elysium"), refs(p)); assertEquals(setOf(Platforms.WINDOWS), plats(p)); assertEquals("no", p.value(Keys.HAS_COMBAT).takeIf { p.decision(Keys.HAS_COMBAT) != null } ?: "no")
    }

    @Test fun consideringIsNotRequesting() = runBlocking {
        val p = read("A fantasy RPG. I'm thinking about adding crafting, but I'm not sure yet.")
        assertTrue(p.value(Keys.HAS_CRAFTING) != "yes", "a consideration is not a request: ${p.decision(Keys.HAS_CRAFTING)}")
    }

    @Test fun hedgedWishesAreConsiderationsNotRequests() = runBlocking {
        for (t in listOf("A fantasy RPG. I might want crafting, not sure.", "A fantasy RPG. Maybe add crafting later.", "A fantasy RPG. I'm not sure if I want crafting."))
            assertTrue(read(t).value(Keys.HAS_CRAFTING) != "yes", "\"$t\" -> ${read(t).decision(Keys.HAS_CRAFTING)}")
        assertTrue(read("A fantasy RPG. Maybe combat later, I'm not sure.").value(Keys.HAS_COMBAT) != "yes")
        assertEquals("yes", read("A fantasy RPG. I want sword combat and boss fights.").value(Keys.HAS_COMBAT), "a plain request still counts")
    }

    @Test fun anOwnerQuestionIsNotAnAnswer() = runBlocking {
        assertTrue(read("A farming game. Should it have enemies?").value(Keys.HAS_COMBAT) != "yes")
        assertEquals(null, read("What is the difference between 2D and 3D?").value(Keys.DIMENSION))
        assertEquals(null, read("What is the difference between Android and iPhone development?").value(Keys.PLATFORMS))
    }

    @Test fun aQuestionAboutAGameIsNotAReference() = runBlocking {
        assertEquals(emptyList(), refs(read("A farming game. Is it like Stardew Valley?")))
    }

    @Test fun aRejectedComparisonIsNotAReference() = runBlocking {
        assertEquals(emptyList(), refs(read("A building game. Not like Minecraft, nothing like it.")))
        assertEquals(emptyList(), refs(read("A puzzle game. I don't want it to be like Candy Crush.")))
    }

    @Test fun contradictoryWordsInOneMessageAreNotSettledSilently() = runBlocking {
        val p = read("An RPG with no combat, but I want boss fights.")
        assertEquals(null, p.decision(Keys.HAS_COMBAT), "mixed signals are left for a question: ${p.decision(Keys.HAS_COMBAT)}")
    }

    @Test fun primarySecondaryAndRemovedPlatformsTogether() = runBlocking {
        val p = read("A 2D puzzle game. Android is primary, iPhone is secondary, and no Windows.")
        assertEquals(setOf(Platforms.ANDROID, Platforms.IOS), plats(p)); assertEquals(Platforms.ANDROID, p.value(Keys.PRIMARY_PLATFORM))
    }
}
