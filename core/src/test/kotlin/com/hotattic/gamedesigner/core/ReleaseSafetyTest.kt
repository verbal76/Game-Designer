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

    // ---- the TEST app keeps one signing certificate across builds ------------------------------------------------------------------------------------

    private val workflow = File(root, ".github/workflows/android.yml").readText()
    private val testKeyScript = File(root, "tools/ota/ensure-test-key.sh").readText()

    @Test fun theTestBuildIsSignedWithAPersistentTestKeyNeverTheReleaseKey() {
        val debug = block("debug {")
        assertTrue(debug.contains("findByName(\"testStable\")"), "debug (the TEST app) uses the persistent test key when CI provides it")
        assertFalse(debug.contains("\"release\""), "the test build never uses the release signing config")
        assertTrue(block("release {").contains("findByName(\"release\")") && !block("release {").contains("testStable"), "release never uses the test key")
        for (v in listOf("GD_TEST_KEYSTORE", "GD_TEST_KEYSTORE_PASSWORD", "GD_TEST_KEY_ALIAS")) assertTrue(gradle.contains("\"$v\""), v)
    }

    @Test fun theTestKeyLivesInItsOwnPrivateDraftReleaseNotAnEvictableCache() {
        assertTrue(testKeyScript.contains("gd-test-signing-key") && testKeyScript.contains("NAME=\"gd-test-signing-key\""), "a separate store from production and OTA")
        assertTrue(testKeyScript.contains("--draft"), "private draft release")
        assertTrue(testKeyScript.contains("does not match its recorded fingerprint"), "a corrupted or swapped keystore stops the build")
        assertTrue(testKeyScript.contains("sort_by(.id)"), "concurrent first runs agree on one key")
        assertFalse(workflow.contains("actions/cache@") && workflow.contains("debug.keystore"), "the evictable cache is no longer the key store")
        assertTrue(workflow.contains("tools/ota/ensure-test-key.sh"))
    }

    @Test fun ciFailsIfTheApkIsNotSignedWithThePersistentTestCertificate() {
        assertTrue(workflow.contains("certificate SHA-256 digest: \${GD_TEST_CERT_SHA256}"), "the built APK is compared with the recorded test certificate")
        assertTrue(workflow.indexOf("ensure-test-key.sh") < workflow.indexOf("assembleDebug"), "the key is loaded before the APK is built")
    }
}
