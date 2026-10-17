package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.engine.CodeUpdates
import com.hotattic.gamedesigner.core.engine.DesignCoherence
import com.hotattic.gamedesigner.core.engine.PrototypeLifecycle
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.ReviewLevel
import com.hotattic.gamedesigner.core.engine.StackAdvisor
import com.hotattic.gamedesigner.core.engine.UpdateStrategy
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.DesignReview
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.MasterPromptGenerator
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.QuestionSpec
import com.hotattic.gamedesigner.core.model.Role
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
 * Integration validation of the repaired architecture with a realistic owner. Evidence classes:
 * AUTOMATED (deterministic assertions), SIMULATED (scripted replay through the Director with rule-based interpretation; no LLM),
 * and UI-CONTRACT (the QuestionSpec payload the Android Chat screen renders; the Compose UI itself cannot be launched here).
 */
class IntegrationValidationTest {
    private val opening = "A 3D action roguelite where a wizard defends a moving castle. Android, iOS and Windows. I want a prototype first and then a full game, with multiplayer later."
    private class Run(val project: Project, val asked: List<String>, val specs: Map<String, QuestionSpec>)

    private suspend fun run(replies: Map<String, String>, text: String = opening): Run {
        val d = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, text).project
        val asked = mutableListOf<String>(); val specs = linkedMapOf<String, QuestionSpec>()
        repeat(120) {
            val pending = p.pendingFieldKey
            if (pending != null && (asked.isEmpty() || asked.last() != pending)) asked += pending
            val reply = replies[pending] ?: when {
                pending == "__proposals__" -> "yes"; pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__asset_plan__" -> "looks good"; pending == "__ready__" -> "generate"; pending == "__review__" -> "looks right"
                pending == Keys.FIVE_MINUTES || pending == Keys.CORE_FANTASY -> "I cast spells while my walking castle crosses the wasteland and monsters swarm it."
                else -> "choose for me"
            }
            val before = p.messages.size
            val t = d.handleUserMessage(p, settings, reply); p = t.project
            p.messages.drop(before).filter { it.role == Role.DIRECTOR }.forEach { m -> m.question?.let { q -> specs.putIfAbsent(q.fieldKey, q) } }
            if (t.action == DirectorAction.GenerateSpec) return Run(p, asked, specs)
        }
        error("did not reach generation; asked=${asked.takeLast(6)}")
    }

    private val owner = mapOf(Keys.MULTIPLAYER_INTENT to "online co-op", Keys.MULTIPLAYER_TIMING to "later phase", Keys.MULTIPLAYER_MODE to "co-op", Keys.PRIMARY_PLATFORM to "Android",
        Keys.OTA_UPDATES to "Yes - over-the-air updates (Mote architecture)", Keys.OTA_CONTENT to "gameplay code, assets, audio, data and complete new features")
    private fun spec(p: Project) = ClaudeMdGenerator.generate(p, 1, "Initial", "2026-10-10T00:00:00Z")
    private fun prompt(p: Project) = MasterPromptGenerator.generate(p, 1)
    private fun docs(p: Project) = mapOf("CLAUDE.md" to spec(p), "MASTER_PROMPT.md" to prompt(p), "ASSETS.md" to ExportPackage.assetsMarkdown(p))
    private fun errors(p: Project) = DesignCoherence.check(p, docs(p)).filter { it.level == ReviewLevel.ERROR }

    // 1. UI-CONTRACT + SIMULATED: the new questions carry renderable option lists and appear only when their dependencies hold.
    @Test fun newQuestionsCarryRenderableSpecsAndRespectDependencies() = runBlocking {
        val r = run(owner)
        for ((key, kind) in listOf(Keys.MULTIPLAYER_INTENT to "SINGLE", Keys.MULTIPLAYER_TIMING to "SINGLE", Keys.MULTIPLAYER_MODE to "SINGLE", Keys.PRIMARY_PLATFORM to "SINGLE", Keys.OTA_CONTENT to "MULTI", Keys.OTA_BEHAVIOR to "SINGLE")) {
            val q = assertNotNull(r.specs[key], "$key must be asked with a QuestionSpec the chat can render")
            assertEquals(kind, q.kind, key)
            assertTrue(q.options.size >= 2 && q.options.all { it.id.isNotBlank() && it.label.isNotBlank() }, key)
        }
        assertEquals(setOf("android", "ios", "windows"), r.specs.getValue(Keys.PRIMARY_PLATFORM).options.map { it.id }.toSet(), "options come from the selected platforms only")
        assertEquals(listOf("text", "levels", "scenes", "models", "textures", "audio", "ui", "scripts", "gameplay_logic", "engine_behavior", "native_code"), r.specs.getValue(Keys.OTA_CONTENT).options.map { it.id })
        assertTrue(r.asked.indexOf(Keys.MULTIPLAYER_INTENT) < r.asked.indexOf(Keys.MULTIPLAYER_TIMING) && r.asked.indexOf(Keys.MULTIPLAYER_TIMING) < r.asked.indexOf(Keys.MULTIPLAYER_MODE), "order ${r.asked}")
        assertTrue(r.asked.indexOf(Keys.OTA_UPDATES) < r.asked.indexOf(Keys.OTA_CONTENT) && r.asked.indexOf(Keys.OTA_CONTENT) < r.asked.indexOf(Keys.OTA_BEHAVIOR), "order ${r.asked}")
        // dependencies suppress questions
        val solo = run(owner + (Keys.MULTIPLAYER_INTENT to "single player"))
        assertTrue(Keys.MULTIPLAYER_TIMING !in solo.asked && Keys.MULTIPLAYER_MODE !in solo.asked, "solo ${solo.asked}")
        val local = run(owner + (Keys.MULTIPLAYER_INTENT to "local co-op"))
        assertTrue(Keys.MULTIPLAYER_TIMING in local.asked && Keys.MULTIPLAYER_MODE !in local.asked, "local ${local.asked}")
        val noOta = run(owner + (Keys.OTA_UPDATES to "No - updates ship as a normal new install"))
        assertTrue(Keys.OTA_CONTENT !in noOta.asked && Keys.OTA_BEHAVIOR !in noOta.asked, "noota ${noOta.asked}")
        val oneP = run(owner, opening.replace("Android, iOS and Windows", "Android"))
        assertTrue(Keys.PRIMARY_PLATFORM !in oneP.asked, "oneP ${oneP.asked}")
    }

    // The Chat composable renders any QuestionSpec generically by kind; it must not special-case the new keys.
    @Test fun chatScreenRendersQuestionsGenerically() {
        // Questions are drawn generically by the guided panel from the engine's question data; no field is special-cased in the screens.
        val ui = java.io.File(System.getProperty("user.dir")).parentFile.resolve("applayer/src/main/kotlin/com/hotattic/gamedesigner/ui")
        val chat = ui.resolve("Chat.kt").takeIf { it.exists() }?.readText() ?: return
        val panel = ui.resolve("QuestionPanel.kt").readText()
        val engine = java.io.File(System.getProperty("user.dir")).resolve("src/main/kotlin/com/hotattic/gamedesigner/core/director/GuidedPanel.kt").readText()
        assertTrue("\"SINGLE\"" in engine && "\"MULTI\"" in engine && "spec.options" in engine)
        assertTrue("GuidedPanel.model(" in chat && "ActiveQuestionCard(" in chat && "model.options" in panel)
        assertFalse(Regex("primary_platform|multiplayer_|ota_content|ota_behavior").containsMatchIn(chat + panel + engine))
    }

    @Test fun ordinaryOwnerPhrasesAreUnderstood() = runBlocking {
        val r = run(owner + mapOf(Keys.MULTIPLAYER_INTENT to "online", Keys.MULTIPLAYER_TIMING to "after the prototype", Keys.MULTIPLAYER_MODE to "pvp"))
        assertEquals("online", r.project.value(Keys.MULTIPLAYER_INTENT)); assertEquals("later", r.project.value(Keys.MULTIPLAYER_TIMING)); assertEquals("competitive", r.project.value(Keys.MULTIPLAYER_MODE))
    }

    // Regression: a long opening message containing "later" used to be read as "ask me later" and the whole description was discarded.
    @Test fun longOpeningWithLaterIsNotAPostponement() = runBlocking {
        val d = director()
        val p = d.handleUserMessage(d.start(newProject(), settings), settings, opening).project
        assertTrue(p.originalConcept.contains("wizard defends a moving castle") || p.value(Keys.CONCEPT)?.contains("wizard") == true)
        assertEquals("3D", p.decision(Keys.DIMENSION)?.value)
        assertEquals(setOf("android", "ios", "windows"), Traits(p).platforms.toSet())
        assertTrue(p.messages.none { "come back to that" in it.text })
    }

    // 2 + 3 + 4: the realistic configuration end to end.
    @Test fun realisticConfigurationYieldsACompleteHonestPackage() = runBlocking {
        val r = run(owner); val p = r.project; val md = spec(p); val mp = prompt(p)
        assertEquals("3D", p.value(Keys.DIMENSION)); assertEquals(Platforms.ANDROID, Traits(p).primaryPlatform)
        assertTrue(setOf("scripts", "gameplay_logic", "models", "textures", "audio", "text", "levels", "scenes").all { it in UpdateStrategy.content(p) }, UpdateStrategy.content(p).toString())
        assertFalse("native_code" in UpdateStrategy.content(p) || "engine_behavior" in UpdateStrategy.content(p))
        assertEquals("later", p.value(Keys.MULTIPLAYER_TIMING)); assertEquals("online", p.value(Keys.MULTIPLAYER_INTENT))
        assertTrue("prototype first and then a full game" in md, "the owner's phased plan is preserved")
        assertTrue("Android is built and tested first" in md && "implement no networking now" in md)
        for (c in listOf("scripts", "gameplay logic", "3D models", "textures and images", "audio and music", "text and translations", "levels and level data", "scenes")) assertTrue(c in md, c)
        assertFalse(StackAdvisor.incompatibilities(p).isNotEmpty()); assertTrue("Closest viable alternative" in md)
        assertTrue("Why this stack" in md && "Mac with Xcode" in md)
        val review = DesignReview.compose(p)
        assertTrue("Technology I'd use" in review && "Things to know before I generate anything" in review && "cannot be satisfied" in review, "the owner hears stack, limits and OTA constraints before approving")
        assertFalse(Regex("(?i)permanently|forever|under no circumstances").containsMatchIn(md + mp))
        assertTrue(md.lines().filter { "Not required for this prototype" in it && !it.startsWith("#") }.all { PrototypeLifecycle.PROTOTYPE_ONLY in it }, "every scope limit is tagged and therefore expires")
        assertFalse(Regex("(?i)later|prototype|multiplayer").containsMatchIn(Traits(p).let { com.hotattic.gamedesigner.core.schema.TitleSuggester.suggest(it) }), "the working title is drawn from the game, not from planning words")
        assertTrue(errors(p).isEmpty(), errors(p).joinToString { it.message })
    }

    // 5. A question or hypothetical never changes the lifecycle; only the documented explicit handoff does.
    @Test fun lifecycleChangesOnlyOnExplicitDirective() = runBlocking {
        val r = run(owner); var p = r.project; val d = director()
        val before = spec(p)
        for (msg in listOf("What if we added enemies that steal spells?", "Could we maybe do online matches someday?", "Should the full game have bosses?", "I'm thinking about a second wizard class.")) p = d.handleUserMessage(p, settings, msg).project
        assertEquals(PrototypeLifecycle.Phase.PROTOTYPE, PrototypeLifecycle.phase(spec(p)), "discussion does not leave the prototype phase")
        assertTrue(PrototypeLifecycle.restrictionsInForce(spec(p)).isNotEmpty())
        val sourceRoot = java.io.File(System.getProperty("user.dir")).resolve("src/main/kotlin")
        val callers = sourceRoot.walkTopDown().filter { it.extension == "kt" && it.name != "PrototypeLifecycle.kt" }.filter { Regex("PrototypeLifecycle\\.handoff\\(").containsMatchIn(it.readText()) }.toList()
        assertTrue(callers.isEmpty(), "no code path performs a handoff on its own: ${callers.map { it.name }}")
        assertTrue("These are NOT handoff triggers" in before && "Are we moving past the prototype?" in before)
        assertTrue("NOT a handoff trigger" in prompt(p) && "ask one short question" in prompt(p))
    }

    // 6. Agent coordination.
    @Test fun agentInstructionsKeepOneAuthority() = runBlocking {
        val mp = prompt(run(owner).project)
        val wm = mp.substringAfter("## WORKING MODEL").substringBefore("\n## ")
        assertTrue("ONE authoritative project" in wm && "lead and stay responsible" in wm && "optional resource, not a requirement" in wm)
        assertTrue("integrate their work yourself" in wm && "work sequentially" in wm && "Do not run disconnected per-platform agents" in wm)
        assertFalse(Regex("(?i)one agent per platform|spawn an agent for each|parallel(ize)? by platform").containsMatchIn(mp))
        assertTrue("Use subagents only when their value clearly exceeds their cost" in mp)
    }

    // 7. Capability vs distribution policy, and unmet requirements are stated, never downgraded.
    @Test fun otaSeparatesCapabilityFromPolicyAndReportsUnmetRequirements() = runBlocking {
        val p = run(owner).project
        val ios = UpdateStrategy.perPlatform(p).first { it.platform == Platforms.IOS }
        assertTrue(ios.capability.isNotBlank() && ios.policy.isNotBlank() && ios.capability != ios.policy)
        assertEquals(CodeUpdates.INTERPRETED_ONLY, ios.code)
        assertEquals(CodeUpdates.FULL, UpdateStrategy.perPlatform(p).first { it.platform == Platforms.ANDROID }.code)
        assertTrue(UpdateStrategy.unmet(p).any { "iPhone" in it }, "unmet=${UpdateStrategy.unmet(p)}")
        assertTrue(UpdateStrategy.unmet(p).none { "Android" in it }, "sideload Android is not falsely flagged ${UpdateStrategy.unmet(p)}")
        val md = spec(p)
        assertTrue("CANNOT be fully satisfied as asked" in md && "a distribution-policy limit, not a technical one" in md && "Distribution policy:" in md && "Technical capability:" in md)
        assertTrue("assumed: no channel chosen yet" in md, "channel=${p.value(Keys.STORE_PLAN)}")
        // choosing Google Play changes the answer for Android too
        val play = ProjectOps.setDecision(p, Keys.STORE_PLAN, "play_store", Provenance.OWNER_EXPLICIT, 5L, DecisionStatus.CONFIRMED, "t")
        assertTrue(UpdateStrategy.unmet(play).any { "Android" in it }, "play=${play.value(Keys.STORE_PLAN)} ${UpdateStrategy.unmet(play)}")
        // data-only requests have nothing unmet
        val dataOnly = ProjectOps.setDecision(p, Keys.OTA_CONTENT, "assets|audio|data", Provenance.OWNER_EXPLICIT, 6L, DecisionStatus.CONFIRMED, "t")
        assertTrue(UpdateStrategy.unmet(dataOnly).isEmpty(), "${dataOnly.list(Keys.OTA_CONTENT)}")
        // a package that hides the unmet requirement is rejected
        val hidden = mapOf("CLAUDE.md" to md.replace("CANNOT be fully satisfied as asked", "is fine"), "MASTER_PROMPT.md" to prompt(p))
        assertTrue(DesignCoherence.check(p, hidden).any { it.code == "ota_downgrade_silent" })
    }

    // Dependency changes after the fact: a withdrawn platform withdraws the primary-platform choice.
    @Test fun changingPlatformsWithdrawsPrimaryPlatform() = runBlocking {
        var p = run(owner).project
        p = ProjectOps.setDecision(p, Keys.PLATFORMS, "ios|windows", Provenance.OWNER_CORRECTION, 9L, DecisionStatus.CONFIRMED, "t")
        val (q, notes) = DesignCoherence.reconcile(p, 10L)
        assertTrue(q.decision(Keys.PRIMARY_PLATFORM) == null && notes.any { "primary-platform" in it }, notes.toString())
    }
}
