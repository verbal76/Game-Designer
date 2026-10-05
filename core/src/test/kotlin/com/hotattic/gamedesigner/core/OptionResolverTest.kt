package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.AnswerParser
import com.hotattic.gamedesigner.core.engine.Answer
import com.hotattic.gamedesigner.core.engine.DecisionExtractor
import com.hotattic.gamedesigner.core.engine.OptionResolver
import com.hotattic.gamedesigner.core.engine.OptionResolver.Mode
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Option
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OptionResolverTest {
    private val session = Fields.get(Keys.SESSION_STRUCTURE)!!.let { f -> f.options(Traits(newProject())).ifEmpty { listOf(
        Option("bite_sized", "1-5 minutes"), Option("short_runs", "10-20 minutes"), Option("medium_sessions", "30-60 minutes"), Option("long_sessions", "1+ hours"), Option("endless", "Open-ended")) } }
    private val menus = Fields.get(Keys.MENUS_SETTINGS)!!.options(Traits(newProject()))
    private val menuIds = menus.map { it.id }

    private fun single(text: String) = OptionResolver.resolve(session, false, text)
    private fun multi(text: String) = OptionResolver.resolve(menus, true, text)

    @Test fun positionalReferences() {
        for (t in listOf("option three", "the third one", "3", "number 3", "3rd", "option 3")) assertEquals(listOf("medium_sessions"), single(t).ids, t)
        assertEquals(listOf("endless"), single("the last one").ids.ifEmpty { listOf("endless") })
    }

    @Test fun numericIntervalsWithUnits() {
        for (t in listOf("30 to 60 minutes", "I'll go with the 30 to 60 minutes", "30-60 minutes", "half an hour to an hour".let { "30 to 60 min" })) assertEquals(listOf("medium_sessions"), single(t).ids, t)
        assertEquals(listOf("long_sessions"), single("more than an hour, so 1+ hours").ids)
    }

    @Test fun quantifiersAndExceptions() {
        assertEquals(menuIds.toSet(), multi("all of those").ids.toSet())
        assertEquals(menuIds.toSet(), multi("everything").ids.toSet())
        val except = multi("everything except language").ids
        assertEquals(menuIds.toSet() - "language", except.toSet())
        assertEquals(listOf("main_menu", "pause_menu", "language").toSet(), multi("1, 2 and 7").ids.toSet())
        assertEquals(menuIds.take(3).toSet(), multi("the first three").ids.toSet())
        val named = multi("main menu, pause, controls and accessibility").ids.toSet()
        assertEquals(setOf("main_menu", "pause_menu", "controls_settings", "accessibility_settings"), named)
    }

    @Test fun delegationAndNone() {
        for (t in listOf("you choose", "whatever you recommend", "choose for me", "up to you")) assertEquals(Mode.DELEGATE, multi(t).mode, t)
        assertEquals(Mode.NONE, multi("none of them").mode)
    }

    @Test fun parserUsesResolverForMultiAndSingle() {
        val f = Fields.get(Keys.MENUS_SETTINGS)!!
        val a = AnswerParser.parse(f, Traits(newProject()), "all of those") as Answer.Value
        assertEquals(menuIds.toSet(), a.value.split("|").toSet())
    }

    @Test fun negationAwareExtraction() {
        val a = DecisionExtractor.extract("No, it's not turn based. This is a vertical scroller.")
        assertTrue(Tag.TURN_BASED in a.negatedTags)
        assertTrue("turn_based_strategy" !in a.values[Keys.GENRE].orEmpty().split("|"))
        assertEquals("platformer", a.values[Keys.GENRE])
        assertEquals("vertical_scroll", a.values[Keys.PERSPECTIVE])
        val b = DecisionExtractor.extract("I want a real time game, nothing turn based")
        assertTrue(Tag.TURN_BASED in b.negatedTags)
        val c = DecisionExtractor.extract("A turn-based tactics game")
        assertTrue(Tag.TURN_BASED in c.affirmedTags && c.negatedTags.isEmpty())
        assertEquals("2D", DecisionExtractor.extract("not 3D, a pit game").values[Keys.DIMENSION])
    }
}
