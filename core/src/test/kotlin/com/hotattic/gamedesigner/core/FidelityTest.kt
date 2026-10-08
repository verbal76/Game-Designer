package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.engine.AssetSourceKind
import com.hotattic.gamedesigner.core.engine.AssetStrategy
import com.hotattic.gamedesigner.core.engine.DesignCoherence
import com.hotattic.gamedesigner.core.engine.ProceduralAuthorship
import com.hotattic.gamedesigner.core.engine.ProceduralMode
import com.hotattic.gamedesigner.core.engine.ReviewLevel
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.MasterPromptGenerator
import com.hotattic.gamedesigner.core.model.AssetResolution
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Asset strategy and procedural authorship: what the owner established survives to the export, and nothing stronger is invented. */
class FidelityTest {
    private val base = "A 2.5D platformer where you climb a tall tower using jumps and ropes."

    private suspend fun run(concept: String, a: Map<String, String> = emptyMap()): Project {
        val d: Director = director()
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, concept).project
        repeat(120) {
            val pending = p.pendingFieldKey
            val reply = when {
                pending == "__proposals__" -> "yes"
                pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__asset_plan__" -> "looks good"
                pending == "__ready__" -> "generate"
                pending == "__review__" -> "looks right"
                pending != null && a[pending] != null -> a[pending]!!
                else -> "choose for me"
            }
            val t = d.handleUserMessage(p, settings, reply)
            p = t.project
            if (t.action == DirectorAction.GenerateSpec) return p
        }
        return p
    }
    private fun spec(p: Project) = ClaudeMdGenerator.generate(p, 1, "Initial", "2026-10-08T00:00:00Z")
    private fun prompt(p: Project) = MasterPromptGenerator.generate(p, 1)
    private fun assets(p: Project) = ExportPackage.assetsMarkdown(p.copy(assets = p.assets + com.hotattic.gamedesigner.core.engine.AssetPlan.resolveMissing(p, 1L)))
    private fun strategy(p: Project) = AssetStrategy.of(p.value(Keys.ASSET_POLICY))
    private fun coherenceErrors(p: Project) = DesignCoherence.check(p.copy(assets = p.assets + com.hotattic.gamedesigner.core.engine.AssetPlan.resolveMissing(p, 1L)),
        mapOf("CLAUDE.md" to spec(p), "MASTER_PROMPT.md" to prompt(p), "ASSETS.md" to assets(p))).filter { it.level == ReviewLevel.ERROR }

    // ---- assets ------------------------------------------------------------------------------------------------------------

    @Test fun A_suppliedFirstMixedStrategyKeepsItsOrderEverywhere() = runBlocking {
        val said = "I want to be able to use asset packs that I give Claude with this prompt and any CC0 items you can find for me and original work done by Claude to fill in the gaps."
        val p = run(base, mapOf("asset_policy" to said))
        val s = strategy(p)
        assertEquals(listOf(AssetSourceKind.SUPPLIED, AssetSourceKind.FREE, AssetSourceKind.ORIGINAL), s.order); assertFalse(s.defaulted)
        assertEquals(Provenance.OWNER_EXPLICIT, p.decision(Keys.ASSET_POLICY)!!.prov)
        val sentence = s.describe()
        for (doc in listOf(spec(p), prompt(p), assets(p))) assertTrue(sentence in doc, "strategy sentence missing")
        assertTrue(sentence.indexOf("asset packs the owner supplies") < sentence.indexOf("CC0") && sentence.indexOf("CC0") < sentence.indexOf("original/procedural"))
        assertTrue(p.assets.isEmpty() || p.assets.all { it.resolution == AssetResolution.USER_SUPPLIED })
        assertTrue("user supplied" in assets(p) && "INSPECT THE SUPPLIED PACKS FIRST" in assets(p))
        assertTrue(coherenceErrors(p).isEmpty(), coherenceErrors(p).joinToString { it.message + " :: " + it.line })
    }

    @Test fun B_originalOnlyIntroducesNoExternalAssets() = runBlocking {
        val p = run(base, mapOf("asset_policy" to "Original artwork only."))
        assertEquals(listOf(AssetSourceKind.ORIGINAL), strategy(p).order)
        val a = assets(p)
        assertFalse("external cc0" in a || "user supplied" in a, a)
        assertFalse("use a cc0/public-domain set from external sources" in spec(p).lowercase())
        assertTrue(coherenceErrors(p).isEmpty(), coherenceErrors(p).joinToString { it.message })
    }

    @Test fun C_aNamedFreeSourcePreferenceDoesNotImposeSuppliedFirst() = runBlocking {
        val p = run(base, mapOf("asset_policy" to "Use Kenney assets wherever possible."))
        val s = strategy(p)
        assertEquals(AssetSourceKind.FREE, s.order.first()); assertEquals("Kenney", s.preferredFreeSource); assertFalse(s.usesSupplied)
        assertFalse("INSPECT THEM FIRST" in prompt(p) || "owner supplies asset packs" in prompt(p))
        assertTrue(assets(p).lowercase().let { "kenney" in it && "external cc0" in it })
        assertTrue(coherenceErrors(p).isEmpty(), coherenceErrors(p).joinToString { it.message })
    }

    @Test fun D_aSplitStrategyKeepsCategorySpecificIntent() = runBlocking {
        val p = run(base, mapOf("asset_policy" to "Use my character art, but generate the environments."))
        val s = strategy(p)
        assertEquals(AssetSourceKind.SUPPLIED, s.byCategory.getValue("characters").first())
        assertEquals(listOf(AssetSourceKind.ORIGINAL), s.byCategory.getValue("environment"))
        val records = (p.assets + com.hotattic.gamedesigner.core.engine.AssetPlan.resolveMissing(p, 1L)).associateBy { it.needId }
        assertEquals(AssetResolution.USER_SUPPLIED, records.getValue("characters").resolution)
        assertEquals(AssetResolution.PROCEDURAL, records.getValue("environment").resolution)
        assertEquals(AssetResolution.EXTERNAL_CC0, records.getValue("ui_kit").resolution, "unspecified needs fall to Bob's default, which the export says is a default")
        val md = spec(p)
        assertTrue("For characters:" in md && "For environment:" in md)
        assertTrue(coherenceErrors(p).isEmpty(), coherenceErrors(p).joinToString { it.message })
    }

    @Test fun E_chooseForMeIsARecommendationNotAnOwnerRequirement() = runBlocking {
        val p = run(base, mapOf("asset_policy" to "I don't care. Choose for me."))
        val d = p.decision(Keys.ASSET_POLICY)!!
        assertEquals(Provenance.OWNER_ACCEPTED_RECOMMENDATION, d.prov)
        assertTrue(strategy(p).order.size >= 2, "a sensible mixed strategy: ${strategy(p).order}")
        assertFalse("OWNER-SUPPLIED ASSET PACKS" in spec(p))
    }

    @Test fun climbUpsEarlierStatementAboutPacksStillSeedsTheStrategyAndNeverUsesProceduralGenerationWords() {
        val st = AssetStrategy.parse("It should use the asset packs that I give it along with the master prompt. And if it has to create animations for the player than it does.")!!
        assertEquals(AssetSourceKind.SUPPLIED, st.order.first()); assertTrue(st.defaulted, "the gap chain is Bob's default and says so")
        assertFalse(AssetStrategy.assetTalk.containsMatchIn("Procedurally generated stages with generated routes."), "no asset words, so the seeder never reads it as an asset preference")
    }

    @Test fun theCoherencePassCatchesAnExportThatReordersTheAssetStrategyOrInventsAProhibition() = runBlocking {
        val p = run(base, mapOf("asset_policy" to "Use my asset packs first, then CC0 for gaps, original for the rest.", "world_structure" to "Procedurally generated stages"))
        val good = mapOf("CLAUDE.md" to spec(p), "MASTER_PROMPT.md" to prompt(p), "ASSETS.md" to assets(p))
        assertTrue(DesignCoherence.check(p, good).none { it.level == ReviewLevel.ERROR })
        val bad = good + ("MASTER_PROMPT.md" to (prompt(p) + "\nUse CC0/public-domain assets first.\nStages are never authored by hand.\n"))
        val codes = DesignCoherence.check(p, bad).filter { it.level == ReviewLevel.ERROR }.map { it.code }
        assertTrue("asset_priority_contradiction" in codes && "invented_authoring_prohibition" in codes, codes.toString())
        val dropped = good + ("ASSETS.md" to assets(p).replace(strategy(p).describe(), "Assets: CC0 first."))
        assertTrue(DesignCoherence.check(p, dropped).any { it.code == "asset_strategy_missing" })
    }

    // ---- procedural authorship -------------------------------------------------------------------------------------------

    @Test fun F_aFullyAuthoredGameHasNoProceduralLanguage() = runBlocking {
        val p = run("A platformer with hand-authored levels, each designed by me.", mapOf("world_structure" to "Hand-authored levels"))
        assertEquals(ProceduralMode.FULLY_AUTHORED, ProceduralAuthorship.of(p)!!.mode)
        val all = spec(p) + prompt(p)
        for (w in listOf("Procedural generation constraints", "Authorship:", "Procedural stage generator", "procedurally generated")) assertFalse(w in all, w)
    }

    @Test fun G_proceduralAssemblyFromAuthoredChunksIsAllowedAndStated() = runBlocking {
        val p = run("$base The stages are procedurally generated from hand-made chunks and modules that I want you to design.", mapOf("world_structure" to "Procedurally generated stages"))
        assertEquals(ProceduralMode.ASSEMBLED_FROM_AUTHORED, ProceduralAuthorship.of(p)!!.mode)
        assertTrue("assembled at runtime from authored components" in spec(p))
        assertTrue(coherenceErrors(p).isEmpty(), coherenceErrors(p).joinToString { it.message })
    }

    @Test fun H_constrainedGenerationKeepsTheAuthoredRulesWithoutForbiddingAuthoredIngredients() = runBlocking {
        val p = run("$base Stages are procedurally generated, with fair jumps, rising difficulty and regular recovery points.", mapOf("world_structure" to "Procedurally generated stages"))
        assertEquals(ProceduralMode.CONSTRAINED_GENERATION, ProceduralAuthorship.of(p)!!.mode)
        val md = spec(p)
        assertTrue("generation runs under authored constraints" in md && "completable from start to goal" in md && "Challenge alternates with recovery" in md)
        assertTrue("Authored modules, patterns, templates, grammars and rules are all allowed ingredients" in md)
    }

    @Test fun I_aHighlyGenerativeDesignDoesNotGetAnAuthoredChunkRequirement() = runBlocking {
        val p = run("$base Everything is fully procedural: completely generated, no hand-made content at all.", mapOf("world_structure" to "Procedurally generated stages"))
        assertEquals(ProceduralMode.HIGHLY_GENERATIVE, ProceduralAuthorship.of(p)!!.mode)
        val md = spec(p)
        assertTrue("Do not require authored chunks" in md); assertFalse("Authoring those components is expected" in md)
        assertTrue(coherenceErrors(p).isEmpty(), coherenceErrors(p).joinToString { it.message })
    }

    @Test fun J_aHybridKeepsTheAuthoredAndProceduralPartsDistinct() = runBlocking {
        val p = run("$base The boss stages are handcrafted but the regular stages are procedurally generated.", mapOf("world_structure" to "Procedurally generated stages"))
        val pa = ProceduralAuthorship.of(p)!!
        assertEquals(ProceduralMode.HYBRID, pa.mode); assertNotNull(pa.ownerNote)
        assertTrue("some content is authored and some is procedural" in spec(p) && "handcrafted" in spec(p).substringAfter("Authorship:"))
    }

    @Test fun K_plainProceduralStagesNeverInventAProhibitionOnAuthoredWork() = runBlocking {
        val p = run("$base The stages are procedurally generated.", mapOf("world_structure" to "Procedurally generated stages"))
        val all = (spec(p) + prompt(p) + assets(p)).lowercase()
        for (ban in listOf("never authored by hand", "nothing is authored", "no hand-built", "not authored by hand")) assertFalse(ban in all, ban)
        assertTrue("has not forbidden hand-authored ingredients" in all)
        assertTrue(coherenceErrors(p).isEmpty())
    }
}
