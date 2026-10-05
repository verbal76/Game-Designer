package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.engine.BuildObjective
import com.hotattic.gamedesigner.core.engine.ConsistencyReview
import com.hotattic.gamedesigner.core.engine.DesignDimensions
import com.hotattic.gamedesigner.core.engine.DimState
import com.hotattic.gamedesigner.core.engine.ProjectObjective
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.ReviewGate
import com.hotattic.gamedesigner.core.generate.AntiSlop
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.generate.VerificationPlan
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.schema.DimId
import com.hotattic.gamedesigner.core.schema.DimensionLexicon
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DesignerIntelligenceTest {

    private val sparse = "I want a relaxing puzzle game about arranging glowing flowers on a hex grid, for my Android phone."

    private val rich = "I want to make a game called Tidewalker. It's a 2D side-scrolling action platformer for Android, set on a series of floating islands connected by wind currents. " +
        "The core loop is: glide between islands, fight the corrupted spirits guarding each shrine, collect light shards, and spend them on new abilities. " +
        "Movement should feel floaty and graceful with a glide and a double jump. The mood is hopeful and serene, like a watercolor storybook. " +
        "If you die you respawn at the last shrine checkpoint with no penalty. The world is a connected open map of islands rather than separate levels. " +
        "Progression comes from unlocking new abilities like a dash and a grapple. " +
        "I want a fully functional prototype with the first three islands playable and a boss at the end. Hand-drawn watercolor art and a gentle orchestral soundtrack."

    private fun script(pending: String?): String = when (pending) {
        "__proposals__" -> "yes"; "__asset_plan__" -> "looks good"; "__review__" -> "looks right"; "__ready__" -> "generate"
        Keys.ASSET_POLICY -> "CC0 only"
        else -> if (pending?.startsWith("__conflict:") == true) "use alternative 1" else "choose for me"
    }

    private suspend fun run(d: Director, concept: String, stopAt: (Project) -> Boolean = { false }): Project {
        var p = d.handleUserMessage(d.start(newProject(), settings), settings, concept).project
        repeat(150) {
            if (stopAt(p)) return p
            val t = d.handleUserMessage(p, settings, script(p.pendingFieldKey)); p = t.project
            if (t.action == DirectorAction.GenerateSpec) return p
        }
        error("stuck at ${p.pendingFieldKey}: " + p.messages.takeLast(4).joinToString(" / ") { "[" + it.role + "] " + it.text.take(700) })
    }

    private fun asked(p: Project) = p.messages.filter { it.role == Role.DIRECTOR && it.question != null }.mapNotNull { it.question!!.fieldKey }
    private fun questionCount(p: Project) = asked(p).size

    @Test fun richConceptAsksFarFewerQuestionsAndStillReachesFullCompleteness() = runBlocking {
        val sparseP = run(director(), sparse)
        val richP = run(director(), rich)
        val richAsked = asked(richP)
        // Everything the owner already said is not asked again.
        for (k in listOf(Keys.WORLD_STRUCTURE, Keys.CORE_LOOP, Keys.PLAYER_FEELING, Keys.MOVEMENT_CAMERA, Keys.DIFFICULTY_FAILURE, Keys.PROGRESSION, Keys.FIRST_SLICE, Keys.DONE, Keys.DISPLAY_NAME, Keys.FIVE_MINUTES))
            assertFalse(k in richAsked, "asked $k although the concept answered it; asked=$richAsked")
        assertTrue(questionCount(richP) + 4 <= questionCount(sparseP), "rich=${questionCount(richP)} sparse=${questionCount(sparseP)}")
        // Completeness measures dimensions, not question count: both reach 100 once approved.
        for (p in listOf(sparseP, richP)) { assertTrue(ReviewGate.approved(p)); assertEquals(100, DesignDimensions.percent(p)) }
        assertEquals(BuildObjective.PROTOTYPE, ProjectObjective.of(richP))
        assertEquals("open_map", richP.value(Keys.WORLD_STRUCTURE))
        assertEquals("checkpoint_retry", richP.value(Keys.DIFFICULTY_FAILURE))
        assertEquals("abilities_gear", richP.value(Keys.PROGRESSION))
        assertTrue(richP.value(Keys.PLAYER_FEELING)!!.contains("hopeful"))
        assertTrue(richP.value(Keys.MOVEMENT_CAMERA)!!.contains("floaty"))
        val gen = SpecVersioning.generate(richP, VersionKind.INITIAL, 10L, "2026-10-05")
        assertFalse(gen.blocked, gen.review.findings.joinToString { it.message + it.line })
        File("build/sample").also { it.mkdirs() }.let { dir ->
            dir.resolve("tidewalker-CLAUDE.md").writeText(gen.project.versions.single().claudeMd); dir.resolve("tidewalker-MASTER_PROMPT.md").writeText(gen.project.versions.single().masterPrompt)
            dir.resolve("tidewalker-conversation.txt").writeText(richP.messages.joinToString("\n\n") { "[${it.role}] ${it.text}" })
            dir.resolve("hexgarden-CLAUDE.md").writeText(SpecVersioning.generate(sparseP, VersionKind.INITIAL, 10L, "2026-10-05").project.versions.single().claudeMd)
            dir.resolve("hexgarden-MASTER_PROMPT.md").writeText(SpecVersioning.generate(sparseP, VersionKind.INITIAL, 10L, "2026-10-05").project.versions.single().masterPrompt)
        }
    }

    @Test fun percentIsCappedUntilTheOwnerApprovesTheReview() = runBlocking {
        val d = director()
        val atReview = run(d, rich) { it.pendingFieldKey == "__review__" }
        assertEquals("__review__", atReview.pendingFieldKey)
        assertEquals(99, DesignDimensions.percent(atReview))
        val review = atReview.messages.last().text
        assertTrue("Here's the game I think we're making" in review && "Is that the game you have in your head?" in review)
        assertTrue("Tidewalker" in review && "connected map" in review.lowercase() || "large connected map" in review.lowercase() || "One large connected map" in review)
        // Not approved => no authoritative spec.
        val blocked = SpecVersioning.generate(atReview, VersionKind.INITIAL, 10L, "2026-10-05")
        assertTrue(blocked.blocked && blocked.project.versions.isEmpty())
        // A negation is not an approval.
        val notYet = d.handleUserMessage(atReview, settings, "that's not right").project
        assertFalse(ReviewGate.approved(notYet)); assertEquals("__review__", notYet.pendingFieldKey)
        // The owner can edit one part; the earlier review state no longer counts.
        val editing = d.handleUserMessage(atReview, settings, "change the world")
        assertEquals(Keys.WORLD_STRUCTURE, editing.project.pendingFieldKey)
        val changed = d.handleUserMessage(editing.project, settings, "one enormous vertical shaft").project
        assertEquals("vertical_shaft", changed.value(Keys.WORLD_STRUCTURE))
        assertEquals(Provenance.OWNER_CORRECTION, changed.decision(Keys.WORLD_STRUCTURE)!!.prov)
        assertEquals("__review__", changed.pendingFieldKey, "the changed design is reviewed again")
        assertTrue("single shaft" in changed.messages.last().text)
        // Delegated approval is allowed.
        val delegated = d.handleUserMessage(changed, settings, "you decide")
        assertEquals(DirectorAction.GenerateSpec, delegated.action)
        assertEquals("delegated", delegated.project.designApproval!!.by)
    }

    @Test fun fiveMinutesAnswerIsExtractedIntoStructureNotJustStored() = runBlocking {
        val d = director()
        var p = d.handleUserMessage(d.start(newProject(), settings), settings, "I want a 2D action platformer for my Android phone about a lone climber.").project
        p = d.handleUserMessage(p, settings, "yes").project
        var guard = 0
        while (p.pendingFieldKey != Keys.FIVE_MINUTES && guard++ < 10) p = d.handleUserMessage(p, settings, script(p.pendingFieldKey)).project
        assertEquals(Keys.FIVE_MINUTES, p.pendingFieldKey, "little was said about the loop, so Bob asks for one great five minutes")
        val before = DesignDimensions.status(p).count { it.state == DimState.UNRESOLVED }
        p = d.handleUserMessage(p, settings, "I climb a frozen tower, dodging falling ice and grabbing ledges, then fight a frost guardian at each landing and unlock a new climbing tool. The mood is lonely and tense. If I fall I respawn at the last campfire checkpoint. The world is one huge continuous tower.").project
        assertTrue(DimensionLexicon.sentencesFor(p, DimId.LOOP).isNotEmpty() && DimensionLexicon.sentencesFor(p, DimId.FEELING).isNotEmpty())
        assertEquals("vertical_shaft", p.value(Keys.WORLD_STRUCTURE))
        assertEquals("checkpoint_retry", p.value(Keys.DIFFICULTY_FAILURE))
        assertTrue(p.value(Keys.PLAYER_FEELING)!!.contains("lonely"))
        assertTrue(p.value(Keys.CORE_LOOP)!!.contains("climb"))
        assertTrue(DesignDimensions.status(p).count { it.state == DimState.UNRESOLVED } < before)
        assertFalse(Keys.CORE_LOOP in asked(p) || Keys.WORLD_STRUCTURE in asked(p) || Keys.PLAYER_FEELING in asked(p))
    }

    @Test fun routineEngineeringIsDerivedNotAsked() = runBlocking {
        val p = run(director(), sparse)
        val askedKeys = asked(p).toSet()
        for (k in listOf(Keys.SAVE_SYSTEM, Keys.TUTORIAL, Keys.PERFORMANCE, Keys.ENGINE, Keys.CI_BUILD, Keys.TESTING, Keys.PACKAGE_ID, Keys.VERSION_STRATEGY, Keys.MENUS_SETTINGS, Keys.ACCESSIBILITY, Keys.INPUT_METHODS))
            assertFalse(k in askedKeys, "$k is engineering, Bob derives it")
        val derived = SpecVersioning.generate(p, VersionKind.INITIAL, 10L, "x").project
        assertEquals(Provenance.DEFAULT, derived.decision(Keys.ENGINE)!!.prov)
        assertTrue(derived.versions.single().claudeMd.contains("Engineering decisions Bob made"))
    }

    @Test fun contradictionsAreDetectedBeforeExport() {
        fun base() = ProjectOps.setDecision(newProject(), Keys.GENRE, "platformer", Provenance.OWNER_EXPLICIT, 1L)
        var p = ProjectOps.addFacts(base(), listOf("There is no combat in this game at all."), "owner", Provenance.OWNER_EXPLICIT, 2L)
        p = ProjectOps.addFacts(p, listOf("The player fights a boss fight at the bottom."), "owner", Provenance.OWNER_EXPLICIT, 3L)
        assertTrue(ConsistencyReview.review(p, "").errors.any { it.code == "no_combat_vs_combat" })
        var m = ProjectOps.setDecision(base(), Keys.PLATFORMS, "android", Provenance.OWNER_EXPLICIT, 2L)
        m = ProjectOps.setDecision(m, Keys.INPUT_METHODS, "keyboard_mouse", Provenance.OWNER_EXPLICIT, 3L)
        assertTrue(ConsistencyReview.review(m, "").errors.any { it.code == "mobile_vs_keyboard" })
        var inv = ProjectOps.addFacts(base(), listOf("There is no inventory in the game.", "Progression is inventory-based with backpack upgrades."), "owner", Provenance.OWNER_EXPLICIT, 2L)
        assertTrue(ConsistencyReview.review(inv, "").errors.any { it.code == "no_inventory_vs_inventory" })
        var w = ProjectOps.setDecision(base(), Keys.WORLD_STRUCTURE, "vertical_shaft", Provenance.OWNER_EXPLICIT, 2L)
        w = ProjectOps.addFacts(w, listOf("Beat a stage to unlock the next level from the level select."), "owner", Provenance.OWNER_EXPLICIT, 3L)
        assertTrue(ConsistencyReview.review(w, "").errors.any { it.code == "continuous_vs_levels" })
        // Negated statements are not contradictions.
        val ok = ProjectOps.addFacts(ProjectOps.setDecision(base(), Keys.WORLD_STRUCTURE, "vertical_shaft", Provenance.OWNER_EXPLICIT, 2L), listOf("There is no level select anywhere; it is one shaft."), "owner", Provenance.OWNER_EXPLICIT, 3L)
        assertTrue(ConsistencyReview.review(ok, "").errors.none { it.code == "continuous_vs_levels" })
        // Uploaded logo vs generate-a-generic-one.
        val up = base().copy(branding = mapOf("studio_splash" to com.hotattic.gamedesigner.core.model.BrandingAsset("studio_splash", com.hotattic.gamedesigner.core.model.BrandingMode.UPLOADED, "branding/x.png", "x.png", "abc", 10, 10, 1L)))
        val wrong = ProjectOps.setDecision(up, Keys.BRAND_STUDIO, "generate_original", Provenance.OWNER_EXPLICIT, 2L)
        assertTrue(ConsistencyReview.review(wrong, "").errors.any { it.code == "uploaded_asset_ignored" })
    }

    @Test fun antiSlopAndVerificationAreSpecificToTheGame() = runBlocking {
        val shaft = ProjectOps.setDecision(ProjectOps.setDecision(newProject(), Keys.GENRE, "platformer", Provenance.OWNER_EXPLICIT, 1L), Keys.WORLD_STRUCTURE, "vertical_shaft", Provenance.OWNER_EXPLICIT, 2L)
            .let { ProjectOps.addFacts(ProjectOps.setOriginalConcept(it, "A giant jungle shaft game with two characters, either one descending or climbing."), listOf("A giant jungle shaft game with two characters, either one descending or climbing."), "concept", Provenance.OWNER_EXPLICIT, 3L) }
        val slop = AntiSlop.derive(shaft).joinToString("\n")
        assertTrue("single continuous vertical world" in slop && "jungle" in slop && "Implementing only one of the described characters" in slop)
        val steps = VerificationPlan.steps(shaft).joinToString("\n")
        assertTrue("entire continuous world" in steps && "Play every playable character" in steps && "NOT gameplay verification" in steps)
        // A puzzle game gets none of the combat/shaft machinery.
        val puzzle = run(director(), sparse)
        assertFalse("Fight every enemy" in VerificationPlan.steps(puzzle).joinToString())
        assertFalse("single continuous vertical world" in AntiSlop.derive(puzzle).joinToString())
    }

    @Test fun cc0OnlyIsNeverOverwrittenByARecommendation() {
        val owner = ProjectOps.setDecision(newProject(), Keys.ASSET_POLICY, "cc0_default", Provenance.OWNER_EXPLICIT, 1L, raw = "CC0 only")
        val rec = ProjectOps.setDecision(owner, Keys.ASSET_POLICY, "original_only", Provenance.OWNER_ACCEPTED_RECOMMENDATION, 2L)
        val sys = ProjectOps.setDecision(rec, Keys.ASSET_POLICY, "original_only", Provenance.SYSTEM_INFERENCE, 3L, DecisionStatus.PROPOSED)
        val dflt = ProjectOps.setDecision(sys, Keys.ASSET_POLICY, "original_only", Provenance.DEFAULT, 4L)
        assertEquals("cc0_default", dflt.value(Keys.ASSET_POLICY))
        // ...and the typed words resolve to the policy the owner meant.
        val opts = com.hotattic.gamedesigner.core.schema.Fields.get(Keys.ASSET_POLICY)!!.options(com.hotattic.gamedesigner.core.schema.Traits(newProject()))
        for ((phrase, id) in listOf("CC0 only" to "cc0_default", "cc0" to "cc0_default", "original assets only" to "original_only", "cc by is fine" to "cc0_or_cc_by", "only original" to "original_only"))
            assertEquals(listOf(id), com.hotattic.gamedesigner.core.engine.OptionResolver.resolve(opts, false, phrase).ids, phrase)
    }

    @Test fun prototypeNeverEscalatesAndMetaTalkNeverBecomesRequirement() = runBlocking {
        val d = director()
        var p = d.handleUserMessage(d.start(newProject(), settings), settings, rich).project
        p = d.handleUserMessage(p, settings, "yes").project
        val before = p.activeFacts().size
        p = d.handleUserMessage(p, settings, "Why are you asking again? I already answered that.").project
        assertEquals(before, p.activeFacts().size)
        assertTrue(p.activeFacts().none { it.text.contains("asking again", true) })
        assertEquals(BuildObjective.PROTOTYPE, ProjectObjective.of(p))
    }
}
