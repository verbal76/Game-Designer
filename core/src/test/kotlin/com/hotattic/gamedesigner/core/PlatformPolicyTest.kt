package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorAction
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.engine.DesignCoherence
import com.hotattic.gamedesigner.core.engine.DesignModel
import com.hotattic.gamedesigner.core.engine.PlatformPolicy
import com.hotattic.gamedesigner.core.engine.ReviewLevel
import com.hotattic.gamedesigner.core.engine.SystemId
import com.hotattic.gamedesigner.core.engine.SystemState
import com.hotattic.gamedesigner.core.generate.ClaudeMdGenerator
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.generate.MasterPromptGenerator
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.VersionKind
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Permanent Hot Attic Games platform policy: Android Target API 36 and the explicit OTA decision with Mote as its reference. */
class PlatformPolicyTest {
    private val base = "A 2.5D platformer where you climb a tall tower using jumps and ropes."
    private val otaYes = "Yes - over-the-air updates (Mote architecture)"
    private val otaNo = "No - updates ship as a normal new install"

    private class Done(val project: Project, val reachedReady: Boolean)

    private suspend fun run(platform: String, ota: String?, d: Director = director()): Done {
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, base).project
        var ready = false
        repeat(150) {
            val pending = p.pendingFieldKey
            val reply = when {
                pending == "__proposals__" -> "yes"
                pending?.startsWith("__conflict:") == true -> "use alternative 1"
                pending == "__asset_plan__" -> "looks good"
                pending == "__ready__" -> "generate"
                pending == "__review__" -> "looks right"
                pending == Keys.PLATFORMS -> platform
                pending == Keys.OTA_UPDATES -> ota ?: "ask me later"
                else -> "choose for me"
            }
            val t = d.handleUserMessage(p, settings, reply)
            p = t.project
            if (t.action == DirectorAction.GenerateSpec) { ready = true; return Done(p, true) }
        }
        return Done(p, ready)
    }
    private fun spec(p: Project) = ClaudeMdGenerator.generate(p, 1, "Initial", "2026-10-08T00:00:00Z")
    private fun prompt(p: Project) = MasterPromptGenerator.generate(p, 1)
    private fun docs(p: Project) = mapOf("CLAUDE.md" to spec(p), "MASTER_PROMPT.md" to prompt(p), "ASSETS.md" to ExportPackage.assetsMarkdown(p))
    private fun errors(p: Project) = DesignCoherence.check(p, docs(p)).filter { it.level == ReviewLevel.ERROR }
    private fun generate(p: Project) = SpecVersioning.generate(p, VersionKind.INITIAL, 1L, "2026-10-08T00:00:00Z", requireApproval = false)

    @Test fun A_androidWithOtaYesExportsApi36AndTheMoteReference() = runBlocking {
        val done = run("Android", otaYes); assertTrue(done.reachedReady)
        val p = done.project
        assertEquals("36", DesignModel.of(p).project.let { com.hotattic.gamedesigner.core.engine.DerivedDefaults.apply(it).value(Keys.ANDROID_TARGET_API) })
        assertEquals(SystemState.PRESENT, DesignModel.of(p).state(SystemId.OTA_UPDATES))
        val md = spec(p); val mp = prompt(p)
        for (doc in listOf(md, mp)) assertTrue("API 36" in doc && "Mote" in doc)
        assertTrue("targetSdk and compileSdk to 36" in md && "Never keep an older Android target" in md && "pinning older dependencies solely to preserve an obsolete target" in md)
        for (concept in listOf("native shell/runtime boundary", "OTA-safe or native-change", "compatibility identity/fingerprint", "channel/update pointer", "integrity and signature verification", "staging before application", "rollback and recovery", "repeated update loops", "owner-controlled publication", "exact-source release discipline"))
            assertTrue(concept in md, concept)
        assertTrue("does NOT mean every future change can be delivered over the air" in md)
        assertTrue("Never substitute another OTA provider silently" in md && "obtain the owner's approval" in md)
        assertTrue(errors(p).isEmpty(), errors(p).joinToString { it.message })
        assertTrue(generate(p).review.clean, generate(p).review.errors.joinToString { it.message })
    }

    @Test fun B_androidWithOtaNoExportsApi36ButNoOtaInfrastructure() = runBlocking {
        val p = run("Android", otaNo).project
        assertEquals(SystemState.ABSENT, DesignModel.of(p).state(SystemId.OTA_UPDATES))
        val all = spec(p) + prompt(p)
        assertTrue("API 36" in all)
        assertFalse("Mote" in all.replace("Mote\u0000", ""), "no Mote text for an OTA-less project")
        assertTrue("OTA: NO. Do not add OTA infrastructure." in prompt(p) && "ABSENT (owner decision)" in spec(p))
        assertTrue(errors(p).isEmpty(), errors(p).joinToString { it.message })
    }

    @Test fun C_androidWithOtaUnknownIsResolvedBeforeTheDesignIsComplete() = runBlocking {
        val first = run("Android", null)
        assertFalse(first.reachedReady, "never completes while OTA is undecided")
        assertEquals(Keys.OTA_UPDATES, first.project.pendingFieldKey)
        assertEquals(SystemState.UNKNOWN, DesignModel.of(first.project).state(SystemId.OTA_UPDATES))
        assertTrue(PlatformPolicy.otaUnresolved(first.project))
        assertTrue(generate(first.project).review.errors.any { it.code == "ota_unresolved" })
        val d = director()
        val after = d.handleUserMessage(first.project, settings, otaNo).project
        assertEquals("none", after.value(Keys.OTA_UPDATES)); assertFalse(PlatformPolicy.otaUnresolved(after))
    }

    @Test fun D_nonAndroidWithOtaNoGetsNeitherPolicy() = runBlocking {
        val p = run("Windows PC", otaNo).project
        val all = spec(p) + prompt(p)
        assertFalse("API 36" in all || "Mote" in all)
        assertNull(com.hotattic.gamedesigner.core.engine.DerivedDefaults.apply(p).decision(Keys.ANDROID_TARGET_API))
        assertTrue(errors(p).isEmpty(), errors(p).joinToString { it.message })
    }

    @Test fun E_nonAndroidWithOtaYesKeepsTheDecisionAndMoteWithoutAndroid() = runBlocking {
        val p = run("Windows PC", otaYes).project
        val md = spec(p); val mp = prompt(p)
        assertFalse("API 36" in md + mp)
        assertEquals(SystemState.PRESENT, DesignModel.of(p).state(SystemId.OTA_UPDATES))
        assertTrue("Mote" in md && "Mote" in mp && "native shell/runtime boundary" in md)
        assertTrue(errors(p).isEmpty(), errors(p).joinToString { it.message })
    }

    @Test fun F_explicitOwnerDecisionsOutrankLaterInference() = runBlocking {
        val llm = object : com.hotattic.gamedesigner.core.llm.LlmProvider {
            override val id = "m"; override val displayName = "Scripted"; override val tier = com.hotattic.gamedesigner.core.llm.LlmTier.LOCAL_SMALL; override val isLocal = true
            override suspend fun isReady() = true
            override suspend fun complete(request: LlmRequest): LlmResult = if ("some day" !in request.messages.last().content) LlmResult.Ok("{}") else LlmResult.Ok("""{"intent":"freeform","inferences":[{"key":"ota_updates","value":"content_ota","confidence":"high","evidence":"over-the-air updates"}]}""")
        }
        val d = director(DirectorDeps(local = llm, clock = FakeClock()))
        var p = d.start(newProject(), settings)
        p = d.handleUserMessage(p, settings, base).project
        p = d.handleUserMessage(d.askAbout(p, Keys.PLATFORMS), settings, "Android").project
        p = d.handleUserMessage(d.askAbout(p, Keys.OTA_UPDATES), settings, otaNo).project
        assertEquals(Provenance.OWNER_EXPLICIT, p.decision(Keys.OTA_UPDATES)!!.prov)
        p = d.handleUserMessage(p, settings, "We might talk about over-the-air updates some day, but not now.").project
        assertEquals("none", p.value(Keys.OTA_UPDATES), "an inference never overrides the owner's explicit answer")
    }

    @Test fun G_theImplementationDocumentsExposeThePoliciesWithoutProjectJson() = runBlocking {
        val p = run("Android", otaYes).project
        val md = spec(p); val mp = prompt(p)
        assertTrue("A0. Hot Attic Games platform policy" in md)
        assertTrue("PLATFORM POLICY" in mp && "Target API 36 is REQUIRED" in mp && "Hot Attic Games Mote OTA architecture" in mp)
        // the concise prompt lines are short; the long text lives once, in CLAUDE.md
        assertTrue(mp.lines().first { "Android: Target API 36" in it }.length < 600)
        assertTrue(md.indexOf("A0.") < md.indexOf("A1."), "the policy comes before the owner's requirements so it cannot be missed")
        assertTrue("Exercise the Mote-style update pipeline" in mp && "targets API 36" in mp, "verification steps carry both policies")
    }

    @Test fun H_noExportOrPolicyTextMakesEasTheDefaultOtaRoute() = runBlocking {
        val p = run("Android", otaYes).project
        val foreign = Regex("(?i)eas update|codepush|code push")
        val negated = Regex("(?i)\\b(do not|never|no|not)\\b")
        for ((name, text) in docs(p)) for (line in text.lines()) if (foreign.containsMatchIn(line)) assertTrue(negated.containsMatchIn(line), "$name suggests a hosted OTA default: $line")
        for (t in PlatformPolicy.moteRequirements + PlatformPolicy.androidRequirements + PlatformPolicy.otaAbsent) if (foreign.containsMatchIn(t)) assertTrue(negated.containsMatchIn(t))
        // and the checker would catch it
        val bad = docs(p) + ("MASTER_PROMPT.md" to (prompt(p) + "\nUse Expo EAS Update for OTA.\n"))
        assertTrue(DesignCoherence.check(p, bad).any { it.code == "foreign_ota_default" })
    }
}
