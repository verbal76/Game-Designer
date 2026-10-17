package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.ReferenceAspects
import com.hotattic.gamedesigner.core.knowledge.KnowledgeLibrary
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An explicit exclusion stated next to a liked aspect of a reference is kept on that reference (not only worked around by the library). Evidence: AUTOMATED. */
class ReferenceExclusionTest {
    @Test fun theLikedAspectAndTheExclusionAreBothRead() {
        val r = ReferenceAspects.fromOwnerWords("I like Pac-Man's role reversal but I do not want power-ups", "Pac-Man")
        assertEquals(listOf("role reversal"), r.wanted)
        assertEquals(listOf("power-ups"), r.excluded)
    }

    @Test fun otherPhrasingsAndExistingPatternsStillWork() {
        val syx = ReferenceAspects.fromOwnerWords("I like the population simulation in Songs of Syx, but I don't want its combat system.", "Songs of Syx")
        assertEquals(listOf("population simulation"), syx.wanted); assertEquals(listOf("combat"), syx.excluded)
        val b = ReferenceAspects.fromOwnerWords("I love Hades's combat feel, but without any story", "Hades")
        assertEquals(listOf("combat feel"), b.wanted); assertEquals(listOf("story"), b.excluded)
        val c = ReferenceAspects.fromOwnerWords("I like the exploration in Zelda but no crafting", "Zelda")
        assertEquals(listOf("exploration"), c.wanted); assertEquals(listOf("crafting"), c.excluded)
    }

    @Test fun anExclusionWithoutAnythingLikedAboutTheGameIsNotAttributedToIt() {
        assertTrue(ReferenceAspects.fromOwnerWords("I do not want power-ups, and Pac-Man is a game I know", "Pac-Man").excluded.isEmpty())
        assertTrue(ReferenceAspects.fromOwnerWords("Pac-Man is old. I don't want power-ups.", "Pac-Man").excluded.isEmpty())
        assertTrue(ReferenceAspects.fromOwnerWords("I don't want power-ups", "Pac-Man").let { it.wanted.isEmpty() && it.excluded.isEmpty() })
    }

    @Test fun theExclusionIsStoredOnTheReferenceThroughTheConversation() = runBlocking {
        val d = director()
        val p = d.handleUserMessage(d.start(newProject(), settings), settings, "I like Pac-Man's role reversal but I do not want power-ups").project
        val ref = p.references.single { it.name == "Pac-Man" }
        assertEquals(listOf("power-ups"), ref.excluded, "stored on the reference: ${p.references}")
        assertEquals(listOf("role reversal"), ref.aspects)
        assertTrue(KnowledgeLibrary.retrieve(p, "give me examples of variety and pacing in a simple arcade game").none { it.entry.id == "gmtk-002-pac-man-role-reversal" })
        // reopening and re-reading the owner's words yields the same record
        val again = d.reevaluate(p, settings).project
        assertEquals(listOf("power-ups"), again.references.single { it.name == "Pac-Man" }.excluded)
    }
}
