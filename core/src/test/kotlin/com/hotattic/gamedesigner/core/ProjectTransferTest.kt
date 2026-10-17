package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.generate.ExportPackage
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.persist.FileProjectStore
import com.hotattic.gamedesigner.core.persist.PackageKind
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.persist.ProjectTransfer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Backup, import and recovery with SYNTHETIC projects only. No owner project is ever read by a test. Evidence: AUTOMATED. */
class ProjectTransferTest {
    private fun store() = FileProjectStore(Files.createTempDirectory("gd-xfer").toFile())
    private fun project(id: String, name: String, text: String = "A synthetic puzzle game about gardens.") =
        ProjectOps.addMessage(ProjectOps.newProject(id, name, com.hotattic.gamedesigner.core.model.ProjectMode.NEW_GAME, com.hotattic.gamedesigner.core.model.ProjectPrefs(), 1_700_000_000_000L), Role.USER, text, 1_700_000_001_000L)
            .let { ProjectOps.setOriginalConcept(it, text) }
    private fun zip(vararg entries: Pair<String, ByteArray>): ByteArray {
        val b = ByteArrayOutputStream(); ZipOutputStream(b).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }; return b.toByteArray()
    }
    private fun json(p: Project) = ProjectCodec.encode(p).toByteArray()

    @Test fun aBackupRoundTripsEveryProjectExactlyIntoAFreshInstall() {
        val a = store(); val p1 = project("p-one", "One"); val p2 = project("p-two", "Two", "A racing game.")
        a.save(p1); a.save(p2)
        val bytes = ProjectTransfer.createBackup(a, "test", 5L)
        assertTrue(ProjectTransfer.verify(bytes).ok)
        val fresh = store(); val r = ProjectTransfer.import(fresh, bytes)
        assertTrue(r.success, r.summary); assertEquals(2, r.imported.size)
        assertEquals(p1, fresh.load("p-one")); assertEquals(p2, fresh.load("p-two"))
        assertTrue(r.imported.all { it.verbatim && !it.renamed })
    }

    @Test fun aSpecPackageThatContainsTheProjectIsImportedAndOneWithoutIsRefused() {
        val p = project("spec-p", "Spec game")
        val full = ExportPackage.zip(mapOf("CLAUDE.md" to "# spec", "game-designer/project.json" to ProjectCodec.encode(p)))
        assertEquals(PackageKind.SPEC_PACKAGE, ProjectTransfer.inspect(full).kind)
        val s = store(); assertTrue(ProjectTransfer.import(s, full).success); assertEquals(p, s.load("spec-p"))
        val onlySpec = ExportPackage.zip(mapOf("CLAUDE.md" to "# spec", "MASTER_PROMPT.md" to "go"))
        val pv = ProjectTransfer.preview("spec.zip", onlySpec)
        assertEquals(PackageKind.SPEC_ONLY, pv.kind); assertEquals(0, pv.importable); assertTrue(pv.headline.contains("cannot be imported"))
        val s2 = store(); assertFalse(ProjectTransfer.import(s2, onlySpec).success); assertTrue(s2.list().isEmpty())
        assertEquals(PackageKind.SPEC_ONLY, ProjectTransfer.inspect("# Spec\nCLAUDE.md says".toByteArray()).kind)
    }

    @Test fun aBareProjectFileImports() {
        val p = project("bare", "Bare"); val s = store()
        assertEquals(PackageKind.BARE_PROJECT, ProjectTransfer.inspect(json(p)).kind)
        assertTrue(ProjectTransfer.import(s, json(p)).success); assertEquals(p, s.load("bare"))
    }

    @Test fun importNeverOverwritesAndNameClashesGetSeparateCopies() {
        val s = store(); val mine = project("same-id", "My Game", "my own words")
        s.save(mine); val before = File(s.projectDir("same-id"), "project.json").readBytes()
        val incoming = project("same-id", "My Game", "someone else's words, same id")
        val r = ProjectTransfer.import(s, json(incoming))
        assertTrue(r.success); val copy = r.imported.single()
        assertTrue(copy.renamed && copy.id != "same-id" && copy.name != "My Game", copy.toString())
        assertTrue(File(s.projectDir("same-id"), "project.json").readBytes().contentEquals(before), "the existing project is byte-identical")
        assertEquals("my own words", s.load("same-id")!!.originalConcept)
        assertEquals("someone else's words, same id", s.load(copy.id)!!.originalConcept)
        val again = ProjectTransfer.import(s, json(incoming)).imported.single()
        assertNotEquals(copy.id, again.id); assertNotEquals(copy.name, again.name)
        // same name, different id: a numbered name, id kept
        val named = ProjectTransfer.import(s, json(project("other-id", "My Game"))).imported.single()
        assertEquals("other-id", named.id); assertTrue(named.name.startsWith("My Game (")); assertEquals(4, s.list().size)
    }

    @Test fun malformedDamagedAndUnsupportedFilesAreRejectedWithoutWritingAnything() {
        val s = store()
        val bad = listOf(
            "not a zip".toByteArray(), ByteArray(0), "{ broken json".toByteArray(), zip("readme.txt" to "hi".toByteArray()),
            zip("game-designer/project.json" to "{\"id\":\"x\"".toByteArray()),
            zip("projects/x/project.json" to "[]".toByteArray()),
        )
        for (b in bad) { val r = ProjectTransfer.import(s, b); assertFalse(r.success, r.summary) }
        val newer = ProjectCodec.encode(project("n", "N")).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        val rn = ProjectTransfer.import(s, newer.toByteArray()); assertFalse(rn.success); assertTrue(rn.summary.contains("newer Game Designer"), rn.summary)
        assertTrue(s.list().isEmpty(), "nothing was written")
        assertTrue(File(s.root, "projects").listFiles().orEmpty().isEmpty(), "no stray folders")
    }

    @Test fun aTamperedBackupIsDetectedAndItsDamagedProjectIsSkippedWhileGoodOnesImport() {
        val a = store(); a.save(project("good", "Good")); a.save(project("bad", "Bad"))
        val bytes = ProjectTransfer.createBackup(a, "t", 1L)
        val tampered = zip(*java.util.zip.ZipInputStream(bytes.inputStream()).let { z -> generateSequence { z.nextEntry }.map { e ->
            val d = z.readBytes(); e.name to (if (e.name == "projects/bad/project.json") String(d).replace("Bad", "Evil").toByteArray() else d) }.toList() }.toTypedArray())
        assertFalse(ProjectTransfer.verify(tampered).ok)
        val s = store(); val r = ProjectTransfer.import(s, tampered)
        assertEquals(listOf("Good"), r.imported.map { it.name }); assertTrue(r.skipped.any { it.contains("checksum") }, r.summary)
        assertNull(s.load("bad"))
    }

    @Test fun olderBackupsWithoutAManifestStillImportAndAreReportedAsUnverified() {
        val p = project("old", "Old backup")
        val legacy = zip("projects/old/project.json" to json(p), "projects/old/project.json.bak" to json(p))
        val pv = ProjectTransfer.preview("old.zip", legacy)
        assertEquals(PackageKind.BACKUP, pv.kind); assertTrue(pv.headline.contains("older backup")); assertEquals(1, pv.importable)
        val s = store(); assertTrue(ProjectTransfer.import(s, legacy).success); assertEquals(p, s.load("old"))
    }

    @Test fun unsafePathsAndOversizedPackagesAreRefused() {
        val slip = zip("projects/../../evil/project.json" to json(project("e", "E")), "projects/ok/project.json" to json(project("ok", "Ok")))
        val s = store(); val r = ProjectTransfer.import(s, slip)
        assertFalse(File(s.root.parentFile, "evil").exists())
        assertTrue(r.errors.any { it.contains("unsafe path") }, r.summary)
        val many = zip(*Array(ProjectTransfer.MAX_ENTRIES + 5) { "f$it.txt" to ByteArray(1) })
        assertTrue(ProjectTransfer.inspect(many).problems.any { it.contains("too many files") })
    }

    @Test fun uploadedImagesTravelWithTheProjectAndAChecksumMismatchIsNotImported() {
        val png = ByteArray(64) { it.toByte() }
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        val p = project("img", "Img").copy(branding = mapOf("studio_splash" to com.hotattic.gamedesigner.core.model.BrandingAsset("studio_splash", com.hotattic.gamedesigner.core.model.BrandingMode.UPLOADED, "branding/logo.png", "logo.png", sha, 1, 1, 1L)))
        val s = store(); val ok = ProjectTransfer.import(s, zip("game-designer/project.json" to json(p), "branding/master/logo.png" to png)); assertTrue(ok.success)
        assertTrue(File(s.assetsDir("img"), "branding/logo.png").readBytes().contentEquals(png))
        val s2 = store(); val bad = ProjectTransfer.import(s2, zip("game-designer/project.json" to json(p), "branding/master/logo.png" to ByteArray(64) { 9 }))
        assertTrue(bad.success && bad.imported.single().warnings.any { it.contains("checksum") }); assertFalse(File(s2.assetsDir("img"), "branding/logo.png").exists())
        val bk = ProjectTransfer.createBackup(s, "t", 1L); val s3 = store(); ProjectTransfer.import(s3, bk)
        assertTrue(File(s3.assetsDir("img"), "branding/logo.png").readBytes().contentEquals(png), "backups carry the uploaded masters")
    }

    @Test fun deletingOneProjectNeverTouchesAnother_andAFreshStoreOnTheSameFolderSeesEverything() {
        val s = store(); val a = project("a", "A"); val b = project("b", "B"); s.save(a); s.save(b)
        assertTrue(s.delete("a")); assertNull(s.load("a")); assertEquals(b, s.load("b"))
        // an in-place APK update recreates the process: a new store over the same private folder reads the same projects
        val after = FileProjectStore(s.root); assertEquals(listOf("B"), after.list().map { it.name }); assertEquals(b, after.load("b"))
        // hostile ids cannot reach outside the projects folder
        s.save(project("../escape", "Escape")); assertFalse(File(s.root.parentFile, "escape").exists())
    }

    @Test fun backupsHoldNoSecretsAndSettingsAreNotPartOfThem() {
        val s = store(); s.save(project("p", "P")); s.saveSettings(com.hotattic.gamedesigner.core.model.AppSettings(directorName = "Glaxor"))
        File(s.root, "secrets.txt").writeText("sk-ant-SECRET")
        val names = java.util.zip.ZipInputStream(ProjectTransfer.createBackup(s, "t", 1L).inputStream()).let { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertTrue(names.all { it.startsWith("projects/") || it == ProjectTransfer.MANIFEST }, names.toString())
    }
}
