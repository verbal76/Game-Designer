package com.hotattic.gamedesigner.core

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled library data (KnowledgeLibraryData.kt) is generated from docs/knowledge-library. This fails when someone edits the JSON and forgets to
 * regenerate (or edits the generated file by hand), so the app can never ship data that differs from its reviewed source.
 */
class KnowledgeFreshnessTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }

    @Test fun theGeneratedKotlinMatchesTheLibraryJson() {
        val py = listOf("python3", "python").firstOrNull { runCatching { ProcessBuilder(it, "--version").redirectErrorStream(true).start().waitFor(10, TimeUnit.SECONDS) }.getOrDefault(false) }
        if (py == null) { println("KnowledgeFreshnessTest: no python available; the check is skipped on this machine (CI runners have python3)"); return }
        val p = ProcessBuilder(py, File(root, "tools/knowledge/generate.py").path, "--check").directory(root).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); assertTrue(p.waitFor(60, TimeUnit.SECONDS), "generate.py timed out")
        assertEquals(0, p.exitValue(), "KnowledgeLibraryData.kt is out of date. Run: python3 tools/knowledge/generate.py\n$out")
    }

    @Test fun everyActiveEntryIsListedInPilotJsonAndNothingElseIsActive() {
        val pilot = File(root, "docs/knowledge-library/pilot.json").readText()
        val active = com.hotattic.gamedesigner.core.knowledge.KnowledgeLibrary.active.map { it.id }
        assertTrue(active.all { pilot.contains("\"$it\"") }, "an active entry is missing from pilot.json")
        assertEquals(active.size, Regex("\"(gmtk|karh)-[^\"]+\"").findAll(pilot).map { it.value }.toSet().size, "pilot.json lists entries that are not active (or the reverse)")
    }
}
