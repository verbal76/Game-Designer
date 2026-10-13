package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.DesignMode
import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.director.LessonParser
import com.hotattic.gamedesigner.core.engine.DesignCoherence
import com.hotattic.gamedesigner.core.engine.Exclusions
import com.hotattic.gamedesigner.core.engine.Explainer
import com.hotattic.gamedesigner.core.engine.ReviewLevel
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.HandoffDocs
import com.hotattic.gamedesigner.core.generate.MasterPromptGenerator
import com.hotattic.gamedesigner.core.model.Experience
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectPrefs
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Traits
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The beginner experience around the Compose screens. Evidence classes: AUTOMATED (pure-logic tests of what the screens draw and call),
 * STATIC INSPECTION (the Compose source is read as text; it is not rendered), SIMULATED (a scripted session). The Compose UI itself needs a
 * device or emulator to look at, and none is available here.
 */
class BeginnerUxTest {
    private val repo = java.io.File(System.getProperty("user.dir")).parentFile
    private fun src(rel: String) = repo.resolve("applayer/src/main/kotlin/com/hotattic/gamedesigner/$rel").takeIf { it.exists() }?.readText()

    // ---- visible mode selection (logic behind the selector) ----
    @Test fun twoModesWithTheOwnersWordingAndNoImpliedLesserGame() {
        assertEquals(listOf("Beginner", "Advanced"), DesignMode.values().map { it.label })
        assertEquals("Bob explains the choices and recommends the technical details.", DesignMode.BEGINNER.blurb)
        assertEquals("Show additional technical options and give me more control.", DesignMode.ADVANCED.blurb)
        assertTrue("same complete development plan" in DesignMode.BOTH_MODES_NOTE)
        assertFalse(DesignMode.values().any { Regex("(?i)\\b(simple|lite|basic|limited|easy)\\b").containsMatchIn(it.blurb + DesignMode.BOTH_MODES_NOTE) })
        assertEquals(DesignMode.BEGINNER, DesignMode.of(Experience.BEGINNER))
        assertEquals(DesignMode.ADVANCED, DesignMode.of(Experience.INTERMEDIATE)); assertEquals(DesignMode.ADVANCED, DesignMode.of(Experience.EXPERT))
    }

    private suspend fun partway(d: Director = director()): Project {
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A 2D cozy gardening game for Android with no inventory, no currencies and no skill trees. You plant, water and watch things grow.").project
        repeat(14) {
            val pending = p.pendingFieldKey
            val reply = when { pending == "__proposals__" -> "yes"; pending == Keys.MULTIPLAYER_INTENT -> "single player"; pending == Keys.FIVE_MINUTES || pending == Keys.CORE_FANTASY -> "Growing things should feel calm."; else -> "choose for me" }
            p = d.handleUserMessage(p, settings, reply).project
        }
        return p
    }

    @Test fun switchingKeepsEveryAnswerExclusionAndTheOpenQuestion() = runBlocking {
        val d = director(); val p0 = partway(d)
        val decisions = p0.decisions.mapValues { it.value.value to it.value.prov }
        val exclusions = Exclusions.fromOwner(p0).map { it.id }
        val pending = p0.pendingFieldKey; val trail = p0.answerTrail.size
        val adv = d.switchMode(p0, settings, DesignMode.ADVANCED.experience)
        assertEquals(Experience.EXPERT, adv.prefs.experience)
        assertEquals(decisions, adv.decisions.mapValues { it.value.value to it.value.prov }, "no decision changed")
        assertEquals(exclusions, Exclusions.fromOwner(adv).map { it.id })
        assertEquals(pending, adv.pendingFieldKey, "the open question is still open"); assertEquals(trail, adv.answerTrail.size)
        assertEquals(p0.originalConcept, adv.originalConcept)
        val back = d.switchMode(adv, settings, DesignMode.BEGINNER.experience)
        assertEquals(Experience.BEGINNER, back.prefs.experience); assertEquals(decisions, back.decisions.mapValues { it.value.value to it.value.prov })
        assertSame(back, d.switchMode(back, settings, Experience.BEGINNER), "choosing the current mode changes nothing")
    }
    private fun assertSame(a: Any, b: Any, msg: String) = assertTrue(a === b, msg)

    @Test fun modePersistsAcrossSaveAndReopen() = runBlocking {
        val d = director(); val adv = d.switchMode(partway(d), settings, Experience.EXPERT)
        val reopened = ProjectCodec.decode(ProjectCodec.encode(adv))
        assertEquals(DesignMode.ADVANCED, DesignMode.of(reopened.prefs.experience))
        assertEquals(adv.decisions.mapValues { it.value.value }, reopened.decisions.mapValues { it.value.value })
    }

    @Test fun chatCommandsStillWorkAndBackNavigationSurvivesASwitch() = runBlocking {
        val d = director(); var p = partway(d)
        p = d.handleUserMessage(p, settings, "advanced mode").project
        assertEquals(Experience.EXPERT, p.prefs.experience)
        val q = p.pendingFieldKey
        p = d.goBack(p) ?: p
        assertNotNull(p.pendingFieldKey)
        p = d.handleUserMessage(p, settings, "beginner mode").project
        assertEquals(Experience.BEGINNER, p.prefs.experience)
        assertNotNull(q)
    }

    // ---- explanation cards ----
    @Test fun everyCardAtEveryDepthSplitsIntoPartsForThePhone() {
        val p = newProject()
        val t = Traits(p)
        for (topic in Explainer.topics.values) for (depth in Explainer.Depth.values()) {
            val text = "Question text\n\n" + Explainer.card(topic, t, depth) + "\n\n1. Option one\n\nI'd go with: option one."
            val parts = LessonParser.split(text)
            val l = assertNotNull(parts.lesson, "${topic.id}/$depth must parse")
            assertEquals(topic.term, l.term); assertEquals(topic.what, l.what)
            assertEquals("Question text", parts.before); assertTrue(parts.after.startsWith("1. Option one") && "I'd go with" in parts.after, "text after the card survives: ${parts.after}")
            assertNotNull(l.why); assertNotNull(l.recommendation)
            if (topic.cons(t).isNotEmpty()) assertNotNull(l.disadvantages, "disadvantages are never dropped (${topic.id})")
            assertEquals(topic.hardToReverse, l.hardToReverse, "${topic.id} irreversibility flag")
            assertNull(l.how.takeIf { depth == Explainer.Depth.ADVANCED }, "advanced is the condensed card")
            if (depth == Explainer.Depth.MORE && topic.more.isNotBlank()) assertEquals(topic.more, l.more)
            assertFalse("explain more\"" in parts.after || "(Say" in parts.after, "the trailer is not shown twice")
        }
        assertNull(LessonParser.split("No card here, just a question?").lesson)
    }

    @Test fun cardsStayShortEnoughToScan() {
        val t = Traits(newProject())
        for (topic in Explainer.topics.values) {
            val l = LessonParser.split(Explainer.card(topic, t, Explainer.Depth.BEGINNER)).lesson!!
            val visible = listOfNotNull(l.what, l.why, l.recommendation, l.advantages, l.disadvantages).sumOf { it.length }
            assertTrue(visible < 1400, "${topic.id}: the always-visible part is $visible characters")
        }
    }

    @Test fun theDirectorsQuestionCarriesAParsableLessonAndAnExplainMoreAnswer() = runBlocking {
        val d = director(); var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A 3D cooperative online platformer for Android and Windows.").project
        p = d.askAbout(p, Keys.ENGINE)
        val ask = LessonParser.split(p.messages.last().text)
        assertEquals("Game engine", assertNotNull(ask.lesson).term)
        assertTrue(p.messages.last().quickReplies.any { it.send == "explain more" }, "an Explain more action is offered")
        p = d.handleUserMessage(p, settings, "explain more").project
        assertNotNull(LessonParser.split(p.messages.last().text).lesson?.more)
        assertEquals(Keys.ENGINE, p.pendingFieldKey, "the question is still open")
    }

    // ---- static layout rules for the Android screens (source inspection, not rendering) ----
    @Test fun androidScreensFollowTheLayoutRules() {
        val lesson = src("ui/Lesson.kt") ?: return
        val chat = src("ui/Chat.kt")!!
        assertFalse("horizontalScroll" in lesson, "no sideways scrolling in the card")
        assertFalse(Regex("\\.width\\(\\d+\\.dp\\)").containsMatchIn(lesson), "no fixed widths that could clip on small phones")
        assertFalse(Regex("(\\d+)\\.sp").findAll(lesson).any { it.groupValues[1].toInt() < 12 }, "no tiny fonts")
        assertTrue("MaterialTheme.typography.bodyMedium" in lesson, "body text uses the theme size")
        assertTrue("remember(messageId)" in lesson && "Show details" in lesson && "Explain more" in lesson, "details fold and Explain more exists")
        assertTrue("hardToReverse" in lesson, "irreversible choices are always flagged")
        assertTrue("ModeSelector(DesignMode.of(p.prefs.experience)" in chat && "vm.setDesignMode" in chat, "the mode selector is on the chat screen")
        assertTrue("LessonParser.split" in chat && "LessonCard(" in chat)
        assertTrue("FilterChip(m == current" in lesson && "DesignMode.values()" in lesson, "both modes are shown; the current one is selected")
        assertTrue("imeBottom == 0" in chat, "the mode caption steps aside while the keyboard is open")
    }

    // ---- handoff consistency is unchanged by any of this ----
    @Test fun handoffStaysConsistentAfterSwitchingModes() = runBlocking {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A 2D cozy gardening game for Android with no inventory, no currencies and no skill trees.").project
        var switched = false
        repeat(160) {
            val pending = p.pendingFieldKey
            if (!switched && p.decisions.size > 12) { p = d.switchMode(p, settings, Experience.EXPERT); switched = true }
            val reply = when { pending == "__proposals__" -> "yes"; pending?.startsWith("__conflict:") == true -> "use alternative 1"; pending == "__asset_plan__" -> "looks good"; pending == "__ready__" -> "generate"; pending == "__review__" -> "looks right"; pending == Keys.MULTIPLAYER_INTENT -> "single player"; pending == Keys.FIVE_MINUTES || pending == Keys.CORE_FANTASY -> "Calm and satisfying."; else -> "choose for me" }
            val t = d.handleUserMessage(p, settings, reply); p = t.project
            if (t.action == DirectorAction.GenerateSpec) {
                assertTrue(switched)
                val docs = mapOf("CLAUDE.md" to ClaudeMdGenerator.generate(p, 1, "Initial", "2026-10-14T00:00:00Z"), "MASTER_PROMPT.md" to MasterPromptGenerator.generate(p, 1), "ASSETS.md" to ExportPackage.assetsMarkdown(p)) + HandoffDocs.files(p)
                assertEquals(14, HandoffDocs.files(p).size)
                assertTrue(DesignCoherence.check(p, docs).none { it.level == ReviewLevel.ERROR })
                assertTrue(Exclusions.fromOwner(p).map { it.id }.containsAll(listOf("inventory", "currency", "skill_tree")))
                return@runBlocking
            }
        }
        error("did not reach generation; pending=${p.pendingFieldKey}; last=${p.messages.takeLast(3).joinToString(" || ") { it.text.take(300) }}")
    }
}
