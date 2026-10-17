package com.hotattic.gamedesigner.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A personal project (an export, a backup, a restored project.json) must never become part of the source tree, a packaged asset, a workflow
 * artifact, the OTA bundle or the knowledge library. Importing only ever reads files the owner picks on the phone at run time.
 */
class PersonalDataLeakGuardTest {
    private val root: File = generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").exists() }
    private val skipDirs = setOf("build", ".gradle", ".git", "node_modules", ".idea", ".kotlin")

    private fun files(base: File = root): Sequence<File> = base.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isFile }
    private fun rel(f: File) = f.relativeTo(root).path.replace('\\', '/')

    @Test fun noProjectFilesBackupsOrArchivesAreInTheSourceTree() {
        val bad = files().map(::rel).filter { r ->
            val n = r.substringAfterLast('/')
            n == "project.json" || n.startsWith("project.json.") || r.endsWith(".zip") || r.endsWith(".ab") || r.endsWith(".aab") || r.endsWith(".apk") || n.contains("_restore_backup") || n.startsWith("game-designer-backup")
        }.toList()
        assertTrue(bad.isEmpty(), "personal-data-shaped files in the repository: $bad")
    }

    @Test fun noTextFileLooksLikeASavedProject() {
        val textExt = setOf("json", "txt", "md", "xml", "yml", "yaml", "properties", "csv", "html")
        val bad = files().filter { it.extension in textExt && it.length() < 20_000_000 }.filter { f ->
            val t = f.readText()
            t.contains("\"originalConcept\"") && t.contains("\"decisions\"") && t.contains("\"schemaVersion\"")
        }.map(::rel).toList()
        assertTrue(bad.isEmpty(), "a saved project appears to be committed: $bad")
    }

    @Test fun packagedAssetAndResourceFoldersHoldNoProjectDataOrArchives() {
        val roots = root.walkTopDown().onEnter { it.name !in skipDirs }.filter { it.isDirectory && it.parentFile?.name == "main" && it.name in setOf("assets", "resources", "res", "raw") }.toList() +
            listOf("otabundle", "ota").map { File(root, it) }.filter { it.isDirectory }
        val bad = roots.flatMap { r -> files(r).filter { f -> f.extension in setOf("zip", "ab", "aab", "apk", "bak", "corrupt") || (f.extension == "json" && f.readText().let { it.contains("\"messages\"") && it.contains("\"decisions\"") }) }.map(::rel).toList() }
        assertTrue(bad.isEmpty(), "personal data would be packaged: $bad")
    }

    @Test fun workflowsNeverTouchProjectStorageOrRestoreFiles() {
        val bad = files(File(root, ".github/workflows")).filter { f -> f.readText().let { it.contains("project.json") || it.contains("filesDir") || it.contains("recovery-out") || it.contains("restore_backup") } }.map(::rel).toList()
        assertTrue(bad.isEmpty(), "workflow steps that could publish project data: $bad")
    }

    @Test fun theKnowledgeLibraryAndGeneratedDataContainNoProjectContent() {
        val lib = files(File(root, "docs/knowledge-library")).toList() + files(File(root, "core/src/main/kotlin/com/hotattic/gamedesigner/core/knowledge")).toList()
        assertTrue(lib.isNotEmpty())
        assertTrue(lib.none { f -> f.readText().let { it.contains("\"originalConcept\"") || it.contains("rawAnswer") } })
    }

    @Test fun gitignoreBlocksTheUsualWaysPersonalFilesGetStaged() {
        val g = File(root, ".gitignore").readText()
        for (pattern in listOf("recovery-out/", "*_restore_backup.zip", "game-designer-backup*.zip", "*_spec_v*.zip", "**/projects/*/project.json")) assertTrue(g.contains(pattern), "missing .gitignore pattern $pattern")
    }

    @Test fun theImporterReadsOnlyWhatTheCallerHandsIt() {
        val src = File(root, "core/src/main/kotlin/com/hotattic/gamedesigner/core/persist/ProjectTransfer.kt").readText()
        for (forbidden in listOf("getResource", "classLoader", "System.getenv", "URL(", "openConnection", "HttpURLConnection", "Environment.getExternal")) assertTrue(!src.contains(forbidden), "importer must not read from anywhere but its input: $forbidden")
    }

    private fun block(text: String, header: String): String {
        val start = text.indexOf(header); require(start >= 0) { "no $header block" }
        var depth = 0; var i = text.indexOf('{', start); val begin = i
        while (i < text.length) { if (text[i] == '{') depth++ else if (text[i] == '}') { depth--; if (depth == 0) return text.substring(begin, i + 1) }; i++ }
        error("unbalanced $header")
    }

    @Test fun theDebugBuildIsASeparateTestAppAndProductionIsUnchanged() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        val debug = block(gradle, "debug {"); val release = block(gradle, "release {")
        assertTrue(debug.contains("applicationIdSuffix = \".\$testSuffix\""), "the test build needs its own application id")
        assertTrue(!release.contains("applicationIdSuffix"), "production keeps its application id")
        assertTrue(gradle.contains("applicationId = \"com.hotatticgames.gamedesigner\""), "the production application id is unchanged")
        assertTrue(File(root, "app/build.gradle.kts").readText().contains("Game Designer TEST") && File(root, "app/src/debug/res/values/strings.xml").readText().contains("gd_variant_label"), "the test app has a distinguishable label")
        for (wf in listOf("release-v4.yml", "release-v5.yml")) assertTrue(File(root, ".github/workflows/$wf").readText().contains("package: name='com.hotatticgames.gamedesigner'"), "$wf still insists on the production id")
    }

    @Test fun ciInspectsTheActualApkAndOtaBundleForPersonalData() {
        val ci = File(root, ".github/workflows/android.yml").readText()
        assertTrue(ci.contains("check-distribution-privacy.py") && ci.contains("otabundle-debug.apk") && ci.contains("ensure-test-key.sh"), "privacy audit and stable test key are wired into CI")
        assertTrue(File(root, "tools/check-distribution-privacy.py").readText().contains("originalConcept"))
    }
}
