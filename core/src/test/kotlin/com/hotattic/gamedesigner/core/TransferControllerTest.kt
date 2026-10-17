package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.persist.FileProjectStore
import com.hotattic.gamedesigner.core.persist.PackageKind
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.persist.ProjectTransfer
import com.hotattic.gamedesigner.core.persist.TransferController
import com.hotattic.gamedesigner.core.persist.TransferController.Picked
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour behind the Import and Backup screens: file-picker results, preview, confirmation, cancellation, collision-safe import, backup destination,
 * success and failure reporting, and warnings for damaged projects. SYNTHETIC projects only. Evidence: AUTOMATED on the JVM. The Compose widgets and
 * the Android Uri adapters are NOT covered here (see docs/DEVICE_TEST_PROCEDURE.md).
 */
class TransferControllerTest {
    private fun store() = FileProjectStore(Files.createTempDirectory("gd-tc").toFile())
    private fun project(id: String, name: String, text: String = "A synthetic puzzle game about gardens.") =
        ProjectOps.setOriginalConcept(ProjectOps.addMessage(ProjectOps.newProject(id, name, com.hotattic.gamedesigner.core.model.ProjectMode.NEW_GAME, com.hotattic.gamedesigner.core.model.ProjectPrefs(), 1_700_000_000_000L), Role.USER, text, 1_700_000_001_000L), text)
    private fun ctl(s: FileProjectStore) = TransferController(s, "test", { 5L })
    private fun pick(name: String, bytes: ByteArray?) = Picked(name) { bytes?.let { ByteArrayInputStream(it) } }
    private fun json(p: Project) = ProjectCodec.encode(p).toByteArray()
    private fun sha(f: File) = MessageDigest.getInstance("SHA-256").digest(f.readBytes()).joinToString("") { "%02x".format(it) }
    private fun snapshot(root: File) = root.walkTopDown().filter { it.isFile }.associate { it.relativeTo(root).path to sha(it) }

    private class MemorySink(var written: ByteArray = ByteArray(0), val failOpen: Boolean = false, val failWriteAfter: Int = -1, val corruptReadBack: Boolean = false, val unreadable: Boolean = false) : TransferController.BackupSink {
        override fun open(): OutputStream {
            if (failOpen) throw java.io.IOException("destination is not writable")
            val buf = ByteArrayOutputStream()
            return object : OutputStream() {
                override fun write(b: Int) { if (failWriteAfter in 0..buf.size()) throw java.io.IOException("storage full"); buf.write(b) }
                override fun write(b: ByteArray, off: Int, len: Int) { if (failWriteAfter >= 0 && buf.size() + len > failWriteAfter) throw java.io.IOException("storage full"); buf.write(b, off, len) }
                override fun close() { written = buf.toByteArray() }
            }
        }
        override fun read(): InputStream? = if (unreadable) null else ByteArrayInputStream(if (corruptReadBack) written.copyOf(written.size / 2) else written)
    }

    // ---- file-picker results, preview, confirmation ------------------------------------------------------------------------------------------

    @Test fun backingOutOfThePickerChangesNothing() {
        val s = store(); val c = ctl(s)
        assertNull(c.preview(emptyList())); assertFalse(c.hasPending)
        val o = c.confirm(); assertEquals(0, o.imported); assertFalse(o.success); assertTrue(o.message.contains("nothing to import"))
    }

    @Test fun previewWritesNothingAndDescribesEveryFile() {
        val s = store(); val c = ctl(s)
        val spec = ProjectTransfer.createBackup(store().also { it.save(project("a", "A")) }, "t", 1L)
        val pv = c.preview(listOf(pick("backup.zip", spec), pick("notes.txt", "hello".toByteArray()), pick("gone.zip", null), pick("spec.md", "# Spec\nCLAUDE.md".toByteArray())))!!
        assertEquals(1, pv.importable)
        assertEquals(PackageKind.BACKUP, pv.files[0].kind); assertEquals(PackageKind.UNKNOWN, pv.files[1].kind)
        assertTrue(pv.files[2].headline.contains("could not be opened")); assertEquals(PackageKind.SPEC_ONLY, pv.files[3].kind)
        assertTrue(s.list().isEmpty(), "a preview never saves anything")
    }

    @Test fun confirmImportsOnlyWhatWasPreviewedAndOnlyOnce() {
        val s = store(); val c = ctl(s)
        c.preview(listOf(pick("p.json", json(project("x", "X")))))
        val first = c.confirm(); assertTrue(first.success); assertEquals(1, first.imported)
        val second = c.confirm(); assertEquals(0, second.imported); assertEquals(1, s.list().size, "a second confirm cannot import again")
    }

    @Test fun cancelDiscardsThePreview() {
        val s = store(); val c = ctl(s)
        c.preview(listOf(pick("p.json", json(project("x", "X"))))); assertTrue(c.hasPending)
        c.cancel(); assertFalse(c.hasPending)
        assertEquals(0, c.confirm().imported); assertTrue(s.list().isEmpty())
    }

    @Test fun aNewPreviewReplacesTheOldOne() {
        val s = store(); val c = ctl(s)
        c.preview(listOf(pick("a.json", json(project("a", "A"))))); c.preview(listOf(pick("b.json", json(project("b", "B")))))
        c.confirm(); assertEquals(listOf("B"), s.list().map { it.name })
    }

    @Test fun collisionSafeImportThroughTheController() {
        val s = store(); s.save(project("same", "Mine", "my words")); val before = snapshot(s.root)
        val c = ctl(s); c.preview(listOf(pick("p.json", json(project("same", "Mine", "other words")))))
        val o = c.confirm(); assertTrue(o.success)
        assertEquals(2, s.list().size); assertEquals("my words", s.load("same")!!.originalConcept)
        assertTrue(snapshot(s.root).filterKeys { it in before }.all { (k, v) -> before[k] == v }, "every pre-existing file is byte-identical")
    }

    @Test fun oneBadFileDoesNotStopTheGoodOnesAndIsReported() {
        val s = store(); val c = ctl(s)
        c.preview(listOf(pick("bad.json", "{ broken".toByteArray()), pick("good.json", json(project("g", "Good"))), pick("unreadable.zip", null)))
        val o = c.confirm(); assertEquals(1, o.imported); assertFalse(o.success, "partial success is not full success"); assertTrue(o.problems.size >= 2, o.problems.toString())
        assertEquals(listOf("Good"), s.list().map { it.name })
    }

    @Test fun anOversizedPickIsRefusedWithoutBeingBuffered() {
        val s = store(); val c = ctl(s)
        val huge = Picked("huge.zip") { object : InputStream() { var n = 0L; override fun read(): Int { n++; return 0 }; override fun read(b: ByteArray, o: Int, l: Int): Int { n += l; return l } } }
        val pv = c.preview(listOf(huge))!!
        assertEquals(0, pv.importable); assertTrue(pv.files[0].headline.contains("MB"), pv.files[0].headline)
        assertTrue(Picked("io.zip") { object : InputStream() { override fun read(): Int = throw java.io.IOException("provider died") } }.let { c.preview(listOf(it))!!.files[0].headline.contains("could not be read") })
    }

    @Test fun checkingAFileReportsIntactAndDamagedWithoutWriting() {
        val s = store(); val c = ctl(s); val src = store().also { it.save(project("a", "A")) }
        val good = ProjectTransfer.createBackup(src, "t", 1L)
        assertTrue(c.check(pick("g.zip", good)).startsWith("This file is intact"))
        assertTrue(c.check(pick("b.zip", good.copyOf(good.size / 2))).startsWith("Problem"))
        assertTrue(c.check(pick("n.zip", null)).contains("could not be opened"))
        assertTrue(s.list().isEmpty())
    }

    // ---- backup destination, success and failure reporting, corrupt-project warnings ---------------------------------------------------------

    @Test fun aCleanBackupIsReportedAsSuccessfulAndRestoresEverything() {
        val s = store(); s.save(project("a", "A")); s.save(project("b", "B")); val sink = MemorySink()
        val r = ctl(s).backup(sink)
        assertTrue(r.success && r.countsAsBackup, r.headline + r.lines); assertTrue(r.headline.contains("verified")); assertTrue(r.lines.isEmpty())
        val fresh = store(); assertEquals(2, ProjectTransfer.import(fresh, sink.written).imported.size)
    }

    @Test fun anUnwritableDestinationIsAFailureAndSaysNothingChanged() {
        val s = store(); s.save(project("a", "A")); val before = snapshot(s.root)
        val r = ctl(s).backup(MemorySink(failOpen = true))
        assertFalse(r.success); assertTrue(r.headline.contains("could not be written")); assertEquals(before, snapshot(s.root))
    }

    @Test fun aWriteThatFailsHalfwayIsAFailure() {
        val s = store(); s.save(project("a", "A"))
        val r = ctl(s).backup(MemorySink(failWriteAfter = 200)); assertFalse(r.success); assertTrue(r.lines.joinToString().contains("storage full"))
    }

    @Test fun aBackupThatCannotBeReadBackOrDoesNotMatchIsNeverReportedAsVerified() {
        val s = store(); s.save(project("a", "A"))
        val a = ctl(s).backup(MemorySink(corruptReadBack = true)); assertFalse(a.success); assertTrue(a.headline.contains("NOT verified")); assertTrue(a.lines.joinToString().contains("does not match"))
        val b = ctl(s).backup(MemorySink(unreadable = true)); assertFalse(b.success); assertTrue(b.lines.joinToString().contains("could not be read back"))
    }

    @Test fun aDamagedProjectWithAGoodBakIsSavedFromItsBakAndTheBackupIsNotCalledComplete() {
        val s = store(); val good = project("ok", "Fine"); s.save(good); val damaged = project("hurt", "Hurt", "the owner's words"); s.save(damaged)
        val dir = s.projectDir("hurt"); File(dir, "project.json.bak").writeText(ProjectCodec.encode(damaged)); File(dir, "project.json").writeText("{ truncated")
        val before = snapshot(s.root); val sink = MemorySink()
        val r = ctl(s).backup(sink)
        assertFalse(r.success, "a backup with a recovered project is not fully successful"); assertTrue(r.headline.contains("NOT complete"), r.headline)
        assertTrue(r.lines.any { it.contains("Hurt") && it.contains("recovered from bak") }, r.lines.toString())
        assertEquals(2, r.result!!.included)
        assertEquals(before, snapshot(s.root), "the backup never changed a file on the phone, recovery copies included")
        val fresh = store(); val imp = ProjectTransfer.import(fresh, sink.written)
        assertEquals(2, imp.imported.size); assertEquals("the owner's words", fresh.load("hurt")!!.originalConcept)
        val names = java.util.zip.ZipInputStream(sink.written.inputStream()).let { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertTrue("recovery/hurt/project.json.damaged" in names, "the damaged file is kept in the backup for inspection")
        assertTrue(ProjectTransfer.preview("b.zip", sink.written).headline.startsWith("INCOMPLETE"))
        assertFalse(ProjectTransfer.verify(sink.written).ok)
    }

    @Test fun aProjectWithNoReadableCopyIsNamedAndItsRawFilesAreKept() {
        val s = store(); s.save(project("ok", "Fine")); val dir = s.projectDir("lost"); File(dir, "project.json").writeText("not json at all"); File(dir, "notes.txt").writeText("raw")
        val before = snapshot(s.root); val sink = MemorySink()
        val r = ctl(s).backup(sink)
        assertFalse(r.success); assertTrue(r.lines.any { it.contains("lost") && it.contains("no readable copy") }, r.lines.toString())
        assertEquals(1, r.result!!.included); assertEquals(before, snapshot(s.root))
        val names = java.util.zip.ZipInputStream(sink.written.inputStream()).let { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertTrue(names.containsAll(listOf("unreadable/lost/project.json", "unreadable/lost/notes.txt")), names.toString())
        val imp = ProjectTransfer.import(store(), sink.written); assertEquals(1, imp.imported.size)
    }

    @Test fun aProjectFromANewerVersionIsKeptRawAndReportedNotDropped() {
        val s = store(); val p = project("future", "From the future"); s.save(p)
        val f = File(s.projectDir("future"), "project.json"); f.writeText(f.readText().replace("\"schemaVersion\": 1", "\"schemaVersion\": 99"))
        val sink = MemorySink(); val r = ctl(s).backup(sink)
        assertFalse(r.success); assertTrue(r.lines.any { it.contains("newer format") }, r.lines.toString())
        val names = java.util.zip.ZipInputStream(sink.written.inputStream()).let { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }
        assertTrue("projects/future/project.json" in names, "kept raw so a newer Game Designer can read it")
    }

    @Test fun nothingToBackUpIsSaidPlainly() {
        val r = ctl(store()).backup(MemorySink()); assertFalse(r.success); assertTrue(r.headline.contains("nothing to back up"))
    }

    @Test fun anOversizedAssetIsReportedNotSilentlySkipped() {
        val s = store(); s.save(project("a", "A"))
        val big = File(s.assetsDir("a"), "branding/huge.bin"); big.parentFile.mkdirs()
        java.io.RandomAccessFile(big, "rw").use { it.setLength(ProjectTransfer.MAX_ENTRY_BYTES + 1) }
        val r = ctl(s).backup(MemorySink()); assertFalse(r.success); assertTrue(r.lines.any { it.contains("larger than") }, r.lines.toString())
        big.delete()
    }
}
