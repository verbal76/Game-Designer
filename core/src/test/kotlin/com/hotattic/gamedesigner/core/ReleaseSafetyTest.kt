package com.hotattic.gamedesigner.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Build-level regression checks for release safety. The Android Gradle build cannot run here (no SDK), so these read the build files; the guard itself
 * is exercised for real by the "Release signing guard" step in .github/workflows/android.yml.
 */
class ReleaseSafetyTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val gradle = File(root, "app/build.gradle.kts").readText()

    private fun block(header: String): String {
        val start = gradle.indexOf(header); require(start >= 0) { "no $header" }
        var depth = 0; var i = gradle.indexOf('{', start); val begin = i
        while (i < gradle.length) { if (gradle[i] == '{') depth++ else if (gradle[i] == '}') { depth--; if (depth == 0) return gradle.substring(begin, i + 1) }; i++ }
        error("unbalanced")
    }

    @Test fun theReleaseBuildNeverFallsBackToTheDebugKey() {
        val release = block("release {")
        assertFalse(release.contains("getByName(\"debug\")") || release.contains("signingConfigs.debug"), "release must not be signed with the debug key")
        assertTrue(release.contains("findByName(\"release\")"), "release uses only the permanent key (or nothing)")
        assertFalse(Regex("else\\s+signingConfigs").containsMatchIn(gradle), "no 'else <some other signing config>' fallback anywhere")
    }

    @Test fun releasePackagingWithoutThePermanentKeyFailsExplicitly() {
        assertTrue(gradle.contains("taskGraph.whenReady") && gradle.contains("Refusing to sign a release build without the permanent signing key"))
        for (v in listOf("GD_KEYSTORE", "GD_KEYSTORE_PASSWORD", "GD_KEY_ALIAS", "GD_KEY_PASSWORD")) assertTrue(gradle.contains("\"$v\""), "guard checks $v")
        assertTrue(gradle.contains("(assemble|bundle|package)Release"), "guard covers every way of packaging a release")
    }

    @Test fun productionIdentityAndTheSeparateTestIdentityAreBothPreserved() {
        assertTrue(gradle.contains("applicationId = \"com.hotatticgames.gamedesigner\""))
        assertTrue(block("debug {").contains("applicationIdSuffix = \".test\""))
        assertFalse(block("release {").contains("applicationIdSuffix"))
    }

    @Test fun releaseWorkflowsProvideTheKeyAndNoSecretIsInTheTree() {
        for (wf in listOf("release-v4.yml", "release-v5.yml")) {
            val t = File(root, ".github/workflows/$wf").readText()
            for (v in listOf("GD_KEYSTORE=", "GD_KEYSTORE_PASSWORD=", "GD_KEY_PASSWORD=", "GD_KEY_ALIAS=")) assertTrue(t.contains(v), "$wf must export $v before assembling the release")
        }
        val tracked = root.walkTopDown().onEnter { it.name !in setOf("build", ".gradle", ".git", "node_modules") }.filter { it.isFile }
        assertTrue(tracked.none { it.extension in setOf("keystore", "jks", "p12", "pfx") }, "a signing keystore is committed")
        assertTrue(File(root, ".github/workflows/android.yml").readText().contains("Release signing guard"), "CI exercises the guard")
    }
}
