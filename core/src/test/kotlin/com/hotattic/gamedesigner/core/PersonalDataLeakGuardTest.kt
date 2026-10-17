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
}
