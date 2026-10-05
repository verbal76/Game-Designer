package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.Answer
import com.hotattic.gamedesigner.core.engine.AnswerParser
import com.hotattic.gamedesigner.core.engine.CompletenessEngine
import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.engine.DecisionExtractor
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.ResourceLevel
import com.hotattic.gamedesigner.core.engine.ScopeEngine
import com.hotattic.gamedesigner.core.engine.ScopeTier
import com.hotattic.gamedesigner.core.engine.Severity
import com.hotattic.gamedesigner.core.model.AckChoice
import com.hotattic.gamedesigner.core.model.BrandingAsset
import com.hotattic.gamedesigner.core.model.BrandingMode
import com.hotattic.gamedesigner.core.model.BrandingSlot
import com.hotattic.gamedesigner.core.model.ClaudePlan
import com.hotattic.gamedesigner.core.model.DecisionSource
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectPrefs
import com.hotattic.gamedesigner.core.model.UsageStyle
import com.hotattic.gamedesigner.core.schema.ConceptText
import com.hotattic.gamedesigner.core.schema.EngineRecommender
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun Project.with(vararg kv: Pair<String, String>): Project =
    kv.fold(this) { p, (k, v) -> ProjectOps.setDecision(p, k, v, DecisionSource.USER, 1L) }

class ExtractorTest {
    @Test fun findsReferenceGamesAndGenres() {
        val ex = DecisionExtractor.extract("I want Risk of Rain 2 mixed with Vampire Survivors, but I'm a wizard defending a moving castle")
        assertEquals(listOf("Risk of Rain 2", "Vampire Survivors"), ex.referenceGames)
        assertTrue(ex.values.getValue(Keys.GENRE).contains("survivors_like"))
        assertNull(ex.values[Keys.DIMENSION]) // genuinely ambiguous: one reference is 3D, one is 2D - must be asked
    }
    @Test fun detectsDimensionPlatformArt() {
        val ex = DecisionExtractor.extract("A 3D low poly game for my phone and Windows PC")
        assertEquals("3D", ex.values[Keys.DIMENSION])
        assertEquals("low_poly", ex.values[Keys.ART_DIRECTION])
        assertEquals(setOf("android", "windows"), ex.values.getValue(Keys.PLATFORMS).split("|").toSet())
    }
    @Test fun sanitizeDropsUnknownKeysAndBadGenres() {
        val clean = DecisionExtractor.sanitize(mapOf("genre" to "puzzle|notagenre", "bogus" to "x", "dimension" to "3D", "core_fantasy" to " "))
        assertEquals(mapOf("genre" to "puzzle", "dimension" to "3D"), clean)
    }
    @Test fun noFalseReferencesFromPlainSentence() {
        assertTrue(DecisionExtractor.referenceGames("I want a cozy farming game on Android with Bob as my helper").isEmpty())
    }
}

class AnswerParserTest {
    private val p = newProject().with(Keys.GENRE to "puzzle", Keys.PLATFORMS to "android", Keys.DIMENSION to "2D")
    private val dim = Fields.get(Keys.DIMENSION)!!
    @Test fun ordinalsAndLabels() {
        assertEquals(Answer.Value("3D"), AnswerParser.parse(dim, Traits(p), "3"))
        assertEquals(Answer.Value("2.5D"), AnswerParser.parse(dim, Traits(p), "option 2"))
        assertEquals(Answer.Value("2D"), AnswerParser.parse(dim, Traits(p), "let's do 2D"))
    }
    @Test fun delegationVariants() {
        for (s in listOf("choose for me", "you pick", "Whatever you recommend", "up to you", "surprise me"))
            assertEquals(Answer.Delegate, AnswerParser.parse(dim, Traits(p), s), s)
    }
    @Test fun questionsDoNotBecomeAnswers() {
        assertTrue(AnswerParser.parse(dim, Traits(p), "what is 2.5D?") is Answer.Question)
    }
    @Test fun optionalSkipAndRequiredPostpone() {
        val feeling = Fields.get(Keys.COLOR_MOOD)!!
        assertEquals(Answer.Skip, AnswerParser.parse(feeling, Traits(p), "skip"))
        assertEquals(Answer.Postpone, AnswerParser.parse(dim, Traits(p), "ask me later"))
        assertEquals(Answer.Value("none"), AnswerParser.parse(Fields.get(Keys.REFERENCES)!!, Traits(p), "none"))
    }
    @Test fun invalidPackageIdRejected() {
        val pkg = Fields.get(Keys.PACKAGE_ID)!!
        assertTrue(AnswerParser.parse(pkg, Traits(p), "My Game!!") is Answer.Invalid)
        assertEquals(Answer.Value("com.hot.attic"), AnswerParser.parse(pkg, Traits(p), "com.hot.attic"))
    }
    @Test fun affirmNegate() {
        assertTrue(AnswerParser.isAffirm("yes that's right")); assertTrue(AnswerParser.isAffirm("yep"))
        assertFalse(AnswerParser.isAffirm("yes but make it 3D and add more guns and also change everything"))
        assertTrue(AnswerParser.isNegate("not quite"))
    }
}

class ConflictTest {
    private val base = newProject().with(Keys.GENRE to "puzzle", Keys.PLATFORMS to "android", Keys.DIMENSION to "2D")

    @Test fun engineThatCannotTargetPlatformIsNonOverridableBlocker() {
        val p = base.with(Keys.PLATFORMS to "android|ios", Keys.ENGINE to "android_native")
        val c = ConflictEngine.all(p).first { it.id == "engine_platform_unsupported" }
        assertEquals(Severity.BLOCKER, c.severity)
        assertFalse(c.overridable)
        // acknowledging cannot hide it
        val acked = ProjectOps.acknowledge(p, c.id, AckChoice.OVERRIDDEN, 2L)
        assertTrue(ConflictEngine.open(acked).any { it.id == c.id })
    }
    @Test fun overridableWarningIsRecordedAndNotRepeated() {
        val p = base.with(Keys.ORIENTATION to "portrait", Keys.GENRE to "platformer")
        val c = ConflictEngine.all(p).first { it.id == "portrait_vs_action" }
        assertTrue(c.overridable)
        val o = ProjectOps.overrideConflict(p, c, 5L)
        assertTrue(ConflictEngine.open(o).none { it.id == c.id })
        assertEquals(DecisionSource.OVERRIDE, o.decision(Keys.ORIENTATION)!!.source)
        assertNotNull(o.decision(Keys.ORIENTATION)!!.overrides)
    }
    @Test fun acceptingAlternativeAppliesChange() {
        val p = base.with(Keys.GENRE to "platformer", Keys.ORIENTATION to "portrait")
        val c = ConflictEngine.all(p).first { it.id == "portrait_vs_action" }
        val n = ProjectOps.applyAlternative(p, c.id, c.alternatives.first(), 9L)
        assertEquals("landscape", n.value(Keys.ORIENTATION))
        assertTrue(ConflictEngine.all(n).none { it.id == "portrait_vs_action" })
    }
    @Test fun perspectiveAndArtVs3D() {
        val p = base.with(Keys.DIMENSION to "2D", Keys.PERSPECTIVE to "first_person")
        assertTrue(ConflictEngine.all(p).any { it.id == "perspective_vs_2d" })
        val q = base.with(Keys.DIMENSION to "3D", Keys.ART_DIRECTION to "pixel_art")
        assertTrue(ConflictEngine.all(q).any { it.id == "art_vs_3d" })
    }
    @Test fun invalidPackageIdBlocks() {
        assertTrue(ConflictEngine.blockers(base.with(Keys.PACKAGE_ID to "Not Valid")).any { it.id == "package_id_invalid" })
    }
    @Test fun onlineIsFlaggedAsV1Deferred() {
        val p = base.with(Keys.NETWORK_POLICY to "online_required", Keys.CONCEPT to "co-op online multiplayer puzzle")
        assertTrue(ConflictEngine.all(p).any { it.id == "network_cost" })
        assertTrue(ConflictEngine.all(p).any { it.id == "multiplayer_deferred" })
    }
}

class ScopeTest {
    private fun proj(genre: String, dim: String, plan: ClaudePlan = ClaudePlan.MAX_5X, usage: UsageStyle = UsageStyle.BALANCED, plats: String = "android") =
        newProject(prefs = ProjectPrefs(claudePlan = plan, usageStyle = usage)).with(Keys.GENRE to genre, Keys.DIMENSION to dim, Keys.PLATFORMS to plats)

    @Test fun simpleGameGetsBiggestTier() = assertEquals(ScopeTier.EPIC, ScopeEngine.recommend(proj("puzzle", "2D")).recommendedTier)
    @Test fun complexGameGetsSmallerTierNotAPrototypeTargetList() {
        val r = ScopeEngine.recommend(proj("factory_automation", "3D", plats = "android|windows|web"))
        assertTrue(r.recommendedTier.ordinal <= ScopeTier.STANDARD.ordinal)
        assertTrue(r.targets.isNotEmpty() && r.targets.all { it.count >= 1 })
        assertTrue(r.resources.level >= ResourceLevel.HEAVY, r.resources.level.toString())
    }
    @Test fun conservativeProLowersTier() {
        val a = ScopeEngine.recommend(proj("action_roguelite", "2D", ClaudePlan.MAX_5X, UsageStyle.BALANCED)).recommendedTier
        val b = ScopeEngine.recommend(proj("action_roguelite", "2D", ClaudePlan.PRO, UsageStyle.CONSERVATIVE)).recommendedTier
        assertTrue(b.ordinal < a.ordinal)
    }
    @Test fun biggerAndSmallerMoveOneTierAndScaleContent() {
        val base = proj("platformer", "2D")
        val rec = ScopeEngine.recommend(base)
        val bigger = ScopeEngine.recommend(base.with(Keys.SCOPE_CHOICE to "bigger"))
        val smaller = ScopeEngine.recommend(base.with(Keys.SCOPE_CHOICE to "smaller"))
        assertEquals(rec.recommendedTier, bigger.recommendedTier)
        assertTrue(smaller.effectiveTier.ordinal == rec.effectiveTier.ordinal - 1)
        assertTrue(smaller.targets.first().count < rec.targets.first().count)
    }
    @Test fun hybridDoesNotDuplicateLabels() {
        val r = ScopeEngine.recommend(proj("survivors_like|action_roguelite", "2D"))
        assertEquals(r.targets.map { it.label }.size, r.targets.map { it.label }.distinct().size)
    }
}

class EngineRecommenderTest {
    @Test fun threeDAndroidExcludesTwoDOnly() {
        val ids = EngineRecommender.rank(setOf("android"), "3D", 5, true).map { it.engine.id }
        assertFalse("android_native" in ids); assertFalse("web_phaser" in ids); assertTrue("godot" in ids)
    }
    @Test fun iosExcludesAndroidOnlyEngines() {
        assertFalse(EngineRecommender.rank(setOf("android", "ios"), "2D", 2, true).any { it.engine.id == "android_native" })
    }
    @Test fun androidTwoDPrefersAutonomousCodeFirst() {
        val best = EngineRecommender.best(setOf("android"), "2D", 2, false)!!
        assertTrue(best.engine.autonomyFit >= 4, best.engine.id)
    }
    @Test fun unrealIsNeverTopForPhone() {
        assertTrue(EngineRecommender.best(setOf("android"), "3D", 6, false)!!.engine.id != "unreal")
    }
}

class CompletenessTest {
    @Test fun percentIsDerivedFromRealRequirements() {
        val empty = CompletenessEngine.compute(newProject())
        assertEquals(0, empty.percent)
        assertTrue(empty.missingRequired.any { it.key == Keys.CONCEPT })
        val some = CompletenessEngine.compute(newProject().with(Keys.CONCEPT to "a cozy puzzle game", Keys.GENRE to "puzzle", Keys.DIMENSION to "2D", Keys.PLATFORMS to "android"))
        assertTrue(some.percent in 1..99)
        assertTrue(some.missingRequired.size > empty.missingRequired.size - 4) // more fields become relevant as the design takes shape
    }
    @Test fun relevanceIsDynamicByGenre() {
        val action = CompletenessEngine.relevantFields(newProject().with(Keys.GENRE to "survivors_like", Keys.PLATFORMS to "android")).map { it.key }
        val puzzle = CompletenessEngine.relevantFields(newProject().with(Keys.GENRE to "puzzle", Keys.PLATFORMS to "android")).map { it.key }
        assertTrue(Keys.COMBAT_MODEL in action && Keys.COMBAT_MODEL !in puzzle)
        assertTrue(Keys.ECONOMY !in action)
        val factory = CompletenessEngine.relevantFields(newProject().with(Keys.GENRE to "factory_automation", Keys.PLATFORMS to "android")).map { it.key }
        assertTrue(Keys.ECONOMY in factory && Keys.AUTOMATION_SIM in factory && Keys.SURVIVAL_CRAFTING in factory)
        val pc = CompletenessEngine.relevantFields(newProject().with(Keys.GENRE to "puzzle", Keys.PLATFORMS to "windows")).map { it.key }
        assertTrue(Keys.ORIENTATION !in pc && Keys.ORIENTATION in puzzle)
    }
    @Test fun uploadChosenButMissingFileIsPending() {
        val p = newProject().with(Keys.GENRE to "puzzle", Keys.PLATFORMS to "android", Keys.BRAND_ICON to "upload")
        assertTrue(CompletenessEngine.compute(p).pendingUploads.any { it.key == Keys.BRAND_ICON })
        val done = p.copy(branding = mapOf(BrandingSlot.ICON to BrandingAsset(BrandingSlot.ICON, BrandingMode.UPLOADED, "icon.png", "icon.png", "abc", 512, 512, 1L)))
        assertTrue(CompletenessEngine.compute(done).pendingUploads.isEmpty())
    }
}

class ConceptTextTest {
    @Test fun fantasyStripsPlatformAndFirstPerson() {
        assertEquals("You are a wizard defending a moving castle.",
            ConceptText.fantasy("I want Risk of Rain 2 mixed with Vampire Survivors, but I'm a wizard defending a moving castle. I'll play it on my Android phone."))
    }
    @Test fun themeNeverCutsMidWord() {
        val t = ConceptText.theme("I want a game where " + "extraordinarily ".repeat(20) + "tiny dragons", 40)
        assertTrue(t.length <= 40 && !t.endsWith("extraordinaril"))
    }
}

class TraitsTest {
    @Test fun complexityGrowsWithDimensionAndHybrid() {
        val a = Traits(newProject().with(Keys.GENRE to "puzzle", Keys.DIMENSION to "2D")).complexity
        val b = Traits(newProject().with(Keys.GENRE to "puzzle|rpg", Keys.DIMENSION to "3D")).complexity
        assertTrue(b > a)
        assertTrue(Traits(newProject().with(Keys.GENRE to "rpg")).has(Tag.STORY))
    }
}
