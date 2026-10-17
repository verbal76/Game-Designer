package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.director.LessonParser
import com.hotattic.gamedesigner.core.director.Messages
import com.hotattic.gamedesigner.core.engine.CompletenessEngine
import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.engine.DesignStages
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.StageState
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.HandoffDocs
import com.hotattic.gamedesigner.core.generate.HandoffPackage
import com.hotattic.gamedesigner.core.generate.HandoffStage
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Experience
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectPrefs
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.persist.FileProjectStore
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The game-juice pass for a design tool. Evidence: AUTOMATED (pure logic behind what the screens show), STATIC INSPECTION (the Compose sources are read as
 * text, not rendered), SIMULATED (scripted sessions). Feel, motion quality, haptics and real layout need a physical device.
 */
class InteractionPolishTest {
    private val repo = java.io.File(System.getProperty("user.dir")).parentFile
    private fun ui(rel: String) = repo.resolve("applayer/src/main/kotlin/com/hotattic/gamedesigner/$rel").takeIf { it.exists() }?.readText()

    private class Session(val project: Project, val said: List<String>, val stagesSeen: List<String>)

    private suspend fun session(text: String, replies: Map<String, String> = emptyMap(), experience: Experience = Experience.BEGINNER, stopAtGenerate: Boolean = true): Session {
        val d = director(); var p = d.start(newProject(prefs = ProjectPrefs(experience = experience)), settings)
        val said = mutableListOf<String>(); val seen = mutableListOf<String>()
        var before = p.messages.size
        p = d.handleUserMessage(p, settings, text).project; p.messages.drop(before).filter { it.role == Role.DIRECTOR }.forEach { said += it.text }
        repeat(180) {
            seen += DesignStages.of(p).summary
            val pending = p.pendingFieldKey
            val reply = replies[pending] ?: when {
                pending == "__proposals__" -> "yes"; pending?.startsWith("__conflict:") == true -> "use alternative 1"; pending == "__asset_plan__" -> "looks good"
                pending == "__ready__" -> "generate"; pending == "__review__" -> "looks right"; pending == Keys.MULTIPLAYER_INTENT -> "single player"
                pending == Keys.FIVE_MINUTES || pending == Keys.CORE_FANTASY -> "Calm and satisfying."
                else -> "choose for me"
            }
            before = p.messages.size
            val t = d.handleUserMessage(p, settings, reply); p = t.project
            p.messages.drop(before).filter { it.role == Role.DIRECTOR }.forEach { said += it.text }
            if (t.action == DirectorAction.GenerateSpec) return Session(p, said, seen)
        }
        error("did not reach the handoff")
    }

    // ---- progress feedback ----
    @Test fun stagesAreMeaningfulHonestAndReopenWhenRequirementsGrow() = runBlocking {
        val fresh = DesignStages.of(newProject())
        assertTrue(fresh.stages.size == 9 && fresh.current?.id == "concept" && fresh.completed == 0, fresh.stages.joinToString { "${it.id}:${it.state}" })
        val s = session("A 2D cozy gardening game for Android. You plant, water and watch things grow.")
        val done = DesignStages.of(s.project.let { SpecVersioning.generate(it, VersionKind.INITIAL, 9L, "x").project })
        assertTrue(done.stages.all { it.state == StageState.COMPLETE } && done.summary.startsWith("All "), done.stages.joinToString { "${it.id}:${it.state}:${it.open}" })
        assertTrue(s.stagesSeen.all { !it.contains("%") }, "no percentages anywhere")
        assertTrue(s.stagesSeen.distinct().size >= 3, "the strip really moves through stages: ${s.stagesSeen.distinct()}")
        // a new requirement reopens what it touches
        val grown = ProjectOps.setDecision(SpecVersioning.generate(s.project, VersionKind.INITIAL, 9L, "x").project, Keys.MULTIPLAYER_INTENT, "online", Provenance.OWNER_CORRECTION, 11L, DecisionStatus.CONFIRMED, "t")
        val after = DesignStages.of(grown)
        assertTrue(after.stages.first { it.id == "multiplayer" }.state != StageState.COMPLETE, "online play reopens the multiplayer stage")
        assertTrue(after.current != null)
    }

    // ---- Bob's voice ----
    @Test fun bobExplainsConsequencesOnceAndNeverGushes() = runBlocking {
        val s = session("I want a 3D adventure for Android and Windows.", mapOf(Keys.PRIMARY_PLATFORM to "Android"))
        val ack = s.said.filter { "Starting with Android" in it }
        assertEquals(1, ack.size, "the consequence of the primary platform is explained exactly once")
        assertTrue("APK" in ack.single() && "touch" in ack.single() && "Windows PC stays supported" in ack.single(), ack.single())
        assertFalse(s.said.any { Regex("(?i)\\b(great choice|excellent choice|awesome|fantastic|amazing|nice one|perfect choice)\\b").containsMatchIn(it) }, "no cheerleading")
        val claim = Regex("(?i)\\b(I have built|I've built|your (?:test )?build is ready|has been published)\\b"); assertFalse(s.said.any { claim.containsMatchIn(it) }, "Bob never claims work that has not happened: " + s.said.filter { claim.containsMatchIn(it) }.joinToString { t -> claim.find(t)!!.let { m -> t.substring(maxOf(0, m.range.first - 80), minOf(t.length, m.range.last + 80)) } })
    }

    // ---- recommended options are marked ----
    @Test fun questionsMarkBobsRecommendation() = runBlocking {
        val d = director(); var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, "A 3D game for Android and Windows.").project
        p = d.askAbout(p, Keys.PRIMARY_PLATFORM)
        val q = assertNotNull(p.messages.last().question)
        assertEquals(listOf("android"), q.recommended)
        assertTrue(q.recommended.all { id -> q.options.any { it.id == id } })
        p = d.askAbout(p, Keys.AUDIENCE)
        assertEquals(listOf("general"), p.messages.last().question!!.recommended)
    }

    // ---- conflicts feel like conversation, not errors ----
    @Test fun conflictsAreCalmExplainWhatTheyTouchAndKeepOtherAnswers() = runBlocking {
        val s = session("A 3D adventure for Android.", mapOf(Keys.MULTIPLAYER_INTENT to "online co-op", Keys.MULTIPLAYER_TIMING to "in the first prototype", Keys.NETWORK_POLICY to "Fully offline"), Experience.EXPERT)
        val hard = s.said.first { it.startsWith(Messages.CONFLICT_HARD) }
        assertEquals(Messages.Tone.CONFLICT_HARD, Messages.toneOf(hard))
        assertTrue("This touches:" in hard && "Your other answers stay exactly as they are." in hard && "My recommendation:" in hard, hard)
        assertFalse(Regex("(?i)\\b(error|failed|invalid|wrong)\\b").containsMatchIn(hard), "no error vocabulary for a design disagreement")
        val soft = ConflictEngine.all(ProjectOps.setDecision(ProjectOps.setDecision(newProject(), Keys.BUDGET, "free_only", Provenance.OWNER_EXPLICIT, 1L), Keys.MP_HOSTING, "dedicated_servers", Provenance.OWNER_EXPLICIT, 2L)).first { it.id == "budget_vs_servers" }
        assertEquals(Messages.Tone.CONFLICT_SOFT, Messages.toneOf(Messages.conflict(soft)))
        assertEquals(Messages.Tone.NORMAL, Messages.toneOf("Which platform do you want to build first?"))
    }

    // ---- handoff generation tells the truth ----
    @Test fun handoffStagesAreRealOrderedAndOnlySucceedAfterTheFilesExist() = runBlocking {
        val s = session("A 2D cozy gardening game for Android. You plant and water.")
        val stages = mutableListOf<HandoffStage>()
        val g = SpecVersioning.generate(s.project, VersionKind.INITIAL, 9L, "2026-10-14", onStage = { stages += it })
        assertEquals(listOf(HandoffStage.PREPARING, HandoffStage.GENERATING, HandoffStage.VALIDATING), stages)
        assertTrue(!g.blocked && g.project.versions.size == 1)
        val v = g.project.versions.single()
        val docs = HandoffPackage.documents(g.project, v)
        assertTrue(docs.size >= 17 && docs.all { it.title.isNotBlank() && it.text.isNotBlank() }, docs.map { it.path }.toString())
        assertTrue(docs.map { it.path }.containsAll(HandoffDocs.files(g.project).keys), "every one of the 14 handoff documents is listed")
        assertTrue(docs.first().path == "CLAUDE.md" && docs.any { it.path == "MASTER_PROMPT.md" })
        // a blocked generation never reports success and never appends a version
        val broken = ProjectOps.setDecision(g.project, Keys.PLATFORMS, "android|ios|windows", Provenance.OWNER_CORRECTION, 12L, DecisionStatus.CONFIRMED, "t")
        val noApproval = SpecVersioning.generate(broken.copy(designApproval = null), VersionKind.REVISION, 13L, "x", onStage = { stages += it })
        assertTrue(noApproval.blocked && noApproval.project.versions.size == 1, "no new version while blocked")
    }

    // ---- project cards ----
    @Test fun projectCardsDistinguishProjectsAndNeverTouchTheFiles() = runBlocking {
        val s = session("A 3D adventure for Android and Windows.", mapOf(Keys.PRIMARY_PLATFORM to "Android"))
        val dir = java.nio.file.Files.createTempDirectory("gd-cards").toFile()
        try {
            val store = FileProjectStore(dir); store.save(s.project)
            val file = java.io.File(dir, "projects/${s.project.id}/project.json"); val bytes = file.readBytes()
            val card = store.list().single()
            assertEquals("Android", card.primaryPlatform); assertNotNull(card.genre); assertNotNull(card.stage); assertTrue(card.stageCount >= 8)
            assertTrue(file.readBytes().contentEquals(bytes), "listing never rewrites a project")
            java.io.File(dir, "projects/broken").also { it.mkdirs(); java.io.File(it, "project.json").writeText("{not json") }
            assertTrue(store.list().any { it.corrupt }, "a damaged project is flagged, not hidden or deleted")
            assertTrue(java.io.File(dir, "projects/broken/project.json").exists())
        } finally { dir.deleteRecursively() }
    }

    // ---- performance: the work behind the screens stays cheap ----
    @Test fun screenWorkIsFastEnoughToNeverBlockATap() = runBlocking {
        val s = session("A 3D adventure for Android and Windows.", mapOf(Keys.PRIMARY_PLATFORM to "Android"))
        val p = s.project
        fun ms(block: () -> Unit): Long { block(); val t = System.nanoTime(); repeat(5) { block() }; return (System.nanoTime() - t) / 5_000_000 }
        val stages = ms { DesignStages.of(p) }; val completeness = ms { CompletenessEngine.compute(p) }; val spec = ms { ClaudeMdGenerator.generate(p, 1, "x", "2026-10-14") }; val docs = ms { HandoffDocs.files(p) }
        println("PERF stages=${stages}ms completeness=${completeness}ms spec=${spec}ms handoffDocs=${docs}ms messages=${p.messages.size}")
        assertTrue(stages < 250 && completeness < 250, "stage/completeness work: $stages / $completeness ms")
        assertTrue(spec < 1500 && docs < 1500, "generation work: $spec / $docs ms")
        val longMessage = p.messages.last().text.repeat(40)
        assertTrue(ms { LessonParser.split(longMessage) } < 50, "parsing a long message is cheap")
    }

    // ---- Advanced user, switching modes, ends in a clean handoff ----
    @Test fun advancedSessionWithModeSwitchesReachesAHandoffAndKeepsEverything() = runBlocking {
        val d = director()
        var p = d.start(newProject(prefs = ProjectPrefs(experience = Experience.EXPERT)), settings)
        p = d.handleUserMessage(p, settings, "A 2D cozy gardening game for Android with no inventory, no currencies and no skill trees.").project
        var switches = 0
        repeat(220) {
            val pending = p.pendingFieldKey
            if (switches == 0 && p.decisions.size > 10) { val keep = p.decisions.mapValues { it.value.value }; p = d.switchMode(p, settings, Experience.BEGINNER); assertEquals(keep, p.decisions.mapValues { it.value.value }); switches++ }
            if (switches == 1 && p.decisions.size > 20) { val keep = p.decisions.mapValues { it.value.value }; p = d.switchMode(p, settings, Experience.EXPERT); assertEquals(keep, p.decisions.mapValues { it.value.value }); switches++ }
            val reply = when { pending == "__proposals__" -> "yes"; pending?.startsWith("__conflict:") == true -> "use alternative 1"; pending == "__asset_plan__" -> "looks good"; pending == "__ready__" -> "generate"; pending == "__review__" -> "looks right"; pending == Keys.MULTIPLAYER_INTENT -> "single player"; pending == Keys.FIVE_MINUTES || pending == Keys.CORE_FANTASY -> "Calm and satisfying."; else -> "choose for me" }
            val t = d.handleUserMessage(p, settings, reply); p = t.project
            if (t.action == DirectorAction.GenerateSpec) {
                assertEquals(2, switches)
                val g = SpecVersioning.generate(p, VersionKind.INITIAL, 20L, "x"); assertTrue(!g.blocked, g.review.errors.joinToString { it.message })
                assertTrue(com.hotattic.gamedesigner.core.engine.Exclusions.fromOwner(g.project).map { it.id }.containsAll(listOf("inventory", "currency", "skill_tree")))
                assertEquals(14, HandoffDocs.files(g.project).size)
                return@runBlocking
            }
        }
        error("no handoff")
    }

    // ---- static inspection of the Android sources: motion, haptics, accessibility, recovery ----
    @Test fun androidSourcesHonourMotionHapticsAccessibilityAndRecovery() {
        val polish = ui("ui/Polish.kt") ?: return
        val lesson = ui("ui/Lesson.kt")!!; val chat = ui("ui/Chat.kt")!!; val vm = ui("AppViewModel.kt")!!; val spec = ui("ui/Spec.kt")!!; val root = ui("ui/Root.kt")!!; val home = ui("ui/Home.kt")!!
        assertTrue("ANIMATOR_DURATION_SCALE" in polish && "if (reduced) 0" in polish, "animations become instant when the system turns them off")
        assertTrue("LocalHapticFeedback" in polish, "haptics go through the system, so the user's touch-feedback setting applies")
        assertTrue("rememberReducedMotion()" in lesson && "tween(if (reduced) 0" in lesson)
        for (f in listOf(polish, lesson, chat, spec, home)) { assertFalse("Thread.sleep" in f || Regex("\\bdelay\\(").containsMatchIn(f), "nothing waits before responding") ; assertFalse("horizontalScroll" in f) }
        assertFalse(Regex("(\\d+)\\.sp").findAll(polish + lesson).any { it.groupValues[1].toInt() < 12 })
        assertTrue("contentDescription" in polish && "mergeDescendants = true" in polish, "the stage strip and handoff panel have spoken descriptions")
        assertTrue("Send your message" in chat && "Confirm your typed answer" in chat && "StageStrip(" in chat && "StageDialog(" in chat, "icon buttons are labelled and progress is on screen")
        assertTrue("Dispatchers.Default" in chat, "stage progress is computed off the main thread")
        val panel = ui("ui/QuestionPanel.kt")!!
        assertTrue("o.recommended" in panel && "Bob recommends" in panel, "Bob's recommendation is marked in words, never by colour alone")
        assertTrue("fun ActionBar(" in panel && "\"Back\"" in panel && "\"Next\"" in panel && "confirmLabel" in panel, "BACK | CONFIRM | NEXT are on screen in every state")
        assertTrue(Regex("heightIn\\(min = 48\\.dp\\)").findAll(panel).count() >= 4, "touch targets are at least 48dp")
        assertFalse("horizontalScroll" in panel || "Thread.sleep" in panel || Regex("\\bdelay\\(").containsMatchIn(panel))
        assertFalse("Dialog(" in panel || "ModalBottomSheet" in panel || "AlertDialog" in panel, "no nested popups")
        assertTrue("RestoreInput" in vm && "RestoreInput" in chat && "RestoreInput" in root, "a message that cannot be saved goes back in the box")
        assertTrue("HandoffStage.FAILED" in vm && "failHandoffIfRunning" in vm, "an interrupted generation ends in an honest failed state")
        assertTrue("HandoffStage.COMPLETED" in vm && vm.indexOf("HandoffStage.COMPLETED") > vm.indexOf("session.mutate"), "success is reported only after the validated version was saved")
        assertTrue("HandoffPanel(" in spec && "Documents" in spec && "HandoffPackage.documents" in spec, "the package can be found and read in the app")
        assertTrue("p.stage" in home && "p.primaryPlatform" in home, "project cards show where each project stands")
    }
}
