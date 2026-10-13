package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.DesignMode
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.director.LessonParser
import com.hotattic.gamedesigner.core.engine.DesignCoherence
import com.hotattic.gamedesigner.core.engine.ReviewLevel
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.DesignReview
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.HandoffDocs
import com.hotattic.gamedesigner.core.generate.MasterPromptGenerator
import com.hotattic.gamedesigner.core.model.Experience
import com.hotattic.gamedesigner.core.model.Role
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
 * Phase 7: a scripted beginner session. It is a SIMULATION: replies come from a script, interpretation is rule-based, and the questions the
 * "beginner" asks are fixed in advance. It shows what Bob says and when; it is not a human usability test and says nothing about whether a real
 * first-time developer finds it understandable.
 */
class BeginnerWalkthroughTest {
    private val opening = "I want to make a 3D game where a knight explores castles, fights monsters, and collects treasure. I want to play it on my Android phone and Windows computer."

    @Test fun knightWalkthroughCoversEveryBeginnerNeedAndKeepsAnswersAcrossModeSwitches() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        val said = mutableListOf<String>(); val asked = mutableListOf<String>()
        fun note(from: Int) { p.messages.drop(from).filter { it.role == Role.DIRECTOR }.forEach { said += it.text } }
        var before = p.messages.size
        p = d.handleUserMessage(p, settings, opening).project; note(before)
        var switchedUp = false; var switchedDown = false; var askedQuestions = false
        val snapshots = mutableListOf<Map<String, String>>()
        var done = false
        repeat(220) {
            if (done) return@repeat
            val pending = p.pendingFieldKey
            if (pending != null && (asked.isEmpty() || asked.last() != pending)) asked += pending
            before = p.messages.size
            // The beginner asks what an engine is, what the networking choice means, what procedural generation is, and how test builds arrive.
            if (!askedQuestions && pending == Keys.WORLD_STRUCTURE) {
                askedQuestions = true
                p = d.handleUserMessage(p, settings, "what is procedural generation?").project; note(before)
                assertTrue(said.last().contains("Procedural generation means"), "procedural generation explained when it matters")
                before = p.messages.size
            }
            if (!switchedUp && pending == Keys.CORE_LOOP) {
                val snap = p.decisions.mapValues { it.value.value }
                p = d.switchMode(p, settings, Experience.EXPERT); switchedUp = true; note(before)
                assertEquals(snap, p.decisions.mapValues { it.value.value }, "no answer disappears when switching to Advanced")
                before = p.messages.size
            }
            if (switchedUp && !switchedDown && pending == Keys.STORE_PLAN) {
                val snap = p.decisions.mapValues { it.value.value }
                p = d.switchMode(p, settings, Experience.BEGINNER); switchedDown = true
                assertEquals(snap, p.decisions.mapValues { it.value.value }, "no answer disappears when switching back to Beginner")
                before = p.messages.size
            }
            val reply = when {
                pending == "__proposals__" -> "yes"
                pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__asset_plan__" -> "looks good"; pending == "__ready__" -> "generate"; pending == "__review__" -> "looks right"
                pending == Keys.MULTIPLAYER_INTENT -> "online co-op"; pending == Keys.MULTIPLAYER_TIMING -> "in the first prototype"; pending == Keys.MULTIPLAYER_MODE -> "co-op"
                pending == Keys.PRIMARY_PLATFORM -> "Android"
                // Advanced mode asks the technical controls; the owner contradicts themselves on purpose: fully offline, while wanting online play.
                pending == Keys.NETWORK_POLICY -> "Fully offline"
                pending == Keys.FIVE_MINUTES || pending == Keys.CORE_FANTASY || pending == Keys.PLAYER_FEELING -> "Exploring a castle should feel tense, then rewarding when I find treasure."
                else -> "choose for me"
            }
            val t = d.handleUserMessage(p, settings, reply); p = t.project; note(before)
            if (t.action == DirectorAction.GenerateSpec) done = true
        }
        assertTrue(done, "reached the handoff; last asked: ${asked.takeLast(5)}")
        val traits = Traits(p)
        val lessons = said.mapNotNull { LessonParser.split(it).lesson }

        // 1. important choices are explained, with the disadvantages
        assertTrue(lessons.size >= 5 && lessons.all { it.disadvantages != null || it.term.isNotBlank() })
        assertTrue(lessons.map { it.term }.containsAll(listOf("Multiplayer", "Procedural generation")), lessons.map { it.term }.toString())
        // 2. primary platform
        assertTrue(Keys.PRIMARY_PLATFORM in asked); assertEquals(Platforms.ANDROID, traits.primaryPlatform); assertEquals(setOf("android", "windows"), traits.platforms)
        // 3 + 4. what a game engine is, and a recommendation that reaches both platforms in 3D
        val engineLesson = assertNotNull(lessons.firstOrNull { it.term == "Game engine" }, "the engine is explained")
        assertTrue("software foundation" in engineLesson.what && engineLesson.recommendation!!.startsWith("I recommend"))
        val engine = assertNotNull(traits.engine); assertTrue(engine.supports3D && EngineCatalog.supportsWrapped(engine, Platforms.ANDROID) && EngineCatalog.supportsWrapped(engine, Platforms.WINDOWS))
        // 5. multiplayer, 6. audience, 7. game length
        assertTrue(Keys.MULTIPLAYER_INTENT in asked && Keys.AUDIENCE in asked && Keys.GAME_LENGTH in asked, asked.toString())
        // 8. procedural generation explained
        assertTrue(lessons.any { it.term == "Procedural generation" })
        // 9. saving and controls are discussed in plain words before approval
        val review = said.firstOrNull { "Saving and controls" in it }
        assertNotNull(review, "Bob discusses saving and controls; asked=$asked; down=$switchedDown; reviewMsgs=${said.filter { "Here's the game" in it }.map { it.takeLast(500) }}"); assertTrue("progress is stored" in review)
        // 10. test-build delivery explained
        assertTrue(Keys.TEST_DELIVERY in asked && lessons.any { it.term == "Test builds" })
        // 11. the contradiction was detected and explained, not silently accepted
        assertTrue(said.any { "Online multiplayer needs a connection" in it }, "contradiction explained; asked=$asked; net=${p.decision(Keys.NETWORK_POLICY)}; mp=${p.value(Keys.MULTIPLAYER_INTENT)}; mode=${p.prefs.experience}")
        assertFalse(p.value(Keys.NETWORK_POLICY) == "fully_offline" && traits.onlineMultiplayer, "the contradiction was resolved")
        // 12. complete handoff
        val docs = mapOf("CLAUDE.md" to ClaudeMdGenerator.generate(p, 1, "Initial", "2026-10-14T00:00:00Z"), "MASTER_PROMPT.md" to MasterPromptGenerator.generate(p, 1), "ASSETS.md" to ExportPackage.assetsMarkdown(p)) + HandoffDocs.files(p)
        assertEquals(14, HandoffDocs.files(p).size); assertTrue(DesignCoherence.check(p, docs).none { it.level == ReviewLevel.ERROR })
        assertTrue("Build the ENTIRE approved game" in docs.getValue("MASTER_PROMPT.md") && "M1" in docs.getValue("docs/MILESTONES.md"))
        assertTrue(switchedUp && switchedDown)
        assertEquals(DesignMode.BEGINNER, DesignMode.of(p.prefs.experience))
    }

    @Test fun beginnerReviewTeachesTheEngineBeforeApproval() = runBlocking {
        val d = director(); var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, opening).project
        repeat(80) {
            val pending = p.pendingFieldKey
            if (pending == "__review__") return@repeat
            p = d.handleUserMessage(p, settings, when { pending == "__proposals__" -> "yes"; pending == "__asset_plan__" -> "looks good"; pending?.startsWith("__conflict:") == true -> "use alternative 1"; pending == Keys.MULTIPLAYER_INTENT -> "single player"; pending == Keys.PRIMARY_PLATFORM -> "Android"; pending == Keys.FIVE_MINUTES || pending == Keys.CORE_FANTASY -> "Exploring castles and finding treasure."; else -> "choose for me" }).project
        }
        val text = DesignReview.compose(p)
        val parts = LessonParser.split(text)
        assertEquals("Game engine", assertNotNull(parts.lesson).term)
        assertTrue("Saving and controls" in text)
    }
}
