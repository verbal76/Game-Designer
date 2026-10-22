package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.persist.PackageKind
import com.hotattic.gamedesigner.core.persist.ProjectTransfer
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Memory and decompression limits of the importer: boundary values and refusals. Synthetic data only. */
class ProjectTransferLimitsTest {
    private fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val b = ByteArrayOutputStream(); ZipOutputStream(b).use { z -> entries.forEach { (n, d) -> z.putNextEntry(ZipEntry(n)); z.write(d); z.closeEntry() } }; return b.toByteArray()
    }
    /** Compresses about 2.5:1: well under the expansion guard, so only the size limits are exercised. */
    private fun lowEntropy(n: Int) = ByteArray(n).also { val r = java.util.Random(7); for (i in it.indices) it[i] = (r.nextInt(8)).toByte() }
    private fun problems(b: ByteArray) = ProjectTransfer.inspect(b).problems.joinToString(" | ")

    @Test fun readLimitedAcceptsExactlyTheLimitAndRefusesOneByteMoreWithoutBufferingTheRest() {
        assertEquals(10, ProjectTransfer.readLimited(ByteArrayInputStream(ByteArray(10)), 10).size)
        assertFailsWith<ProjectTransfer.TooLargeException> { ProjectTransfer.readLimited(ByteArrayInputStream(ByteArray(11)), 10) }
        var served = 0L
        val endless = object : java.io.InputStream() { override fun read(): Int = 0; override fun read(b: ByteArray, o: Int, l: Int): Int { served += l; return l } }
        assertFailsWith<ProjectTransfer.TooLargeException> { ProjectTransfer.readLimited(endless, 1_000_000) }
        assertTrue(served < 1_200_000, "an endless stream is cut off right after the limit ($served bytes read)")
    }

    @Test fun anInputLargerThanTheFileLimitIsRefusedBeforeAnyUnpacking() {
        val big = ByteArray((ProjectTransfer.MAX_INPUT_BYTES + 1).toInt()); big[0] = 'P'.code.toByte(); big[1] = 'K'.code.toByte()
        val ins = ProjectTransfer.inspect(big); assertEquals(PackageKind.UNKNOWN, ins.kind); assertTrue(problems(big).contains("larger than the allowed"))
    }

    @Test fun aDecompressionBombIsRefused() {
        // ten 5 MB entries of zeros: tiny on disk, 50 MB unpacked (over 100x)
        val bomb = zip((1..10).map { "projects/p$it/assets/z.bin" to ByteArray(5 * 1024 * 1024) })
        assertTrue(bomb.size < 200_000, "the fixture really is small: ${bomb.size}")
        assertTrue(problems(bomb).contains("expands far beyond"), problems(bomb))
        assertTrue(ProjectTransfer.import(com.hotattic.gamedesigner.core.persist.FileProjectStore(java.nio.file.Files.createTempDirectory("gd-bomb").toFile()), bomb).imported.isEmpty())
    }

    @Test fun anOversizedSingleEntryIsRefused() {
        val b = zip(listOf("projects/p/assets/big.bin" to lowEntropy((ProjectTransfer.MAX_ENTRY_BYTES + 1).toInt())))
        assertTrue(problems(b).contains("unpacks to more than"), problems(b))
    }

    @Test fun aTotalOverTheUnpackedLimitIsRefused() {
        val chunk = lowEntropy(ProjectTransfer.MAX_ENTRY_BYTES.toInt() - 1)
        val b = zip((1..3).map { "projects/p/assets/c$it.bin" to chunk })
        assertTrue(b.size < ProjectTransfer.MAX_INPUT_BYTES, "the fixture must pass the file-size limit so the unpacked-size limit is what refuses it: ${b.size}")
        assertTrue(problems(b).contains("unpacks to more than the allowed"), problems(b))
    }

    @Test fun entriesThatAreNotNeededAreNotKeptInMemory() {
        val b = zip(listOf("random/junk.bin" to ByteArray(2_000_000) { (it % 7).toByte() }, "game-designer/project.json" to "{}".toByteArray()))
        val ins = ProjectTransfer.inspect(b)   // junk is counted against the limits and dropped; the result is just an unreadable project, not a crash
        assertEquals(PackageKind.SPEC_PACKAGE, ins.kind)
    }

    @Test fun anOversizedProjectJsonAndTooManyEntriesAreRefused() {
        val big = ByteArray((ProjectTransfer.MAX_PROJECT_JSON + 1).toInt()) { '1'.code.toByte() }; big[0] = '{'.code.toByte()
        assertTrue(ProjectTransfer.inspect(big).projects.single().problems.joinToString().contains("unreasonably large"))
        assertTrue(problems(zip((0..ProjectTransfer.MAX_ENTRIES + 3).map { "f$it.txt" to ByteArray(1) })).contains("too many files"))
    }

    @Test fun normalBackupsAndImportsAreUnaffected() {
        val s = com.hotattic.gamedesigner.core.persist.FileProjectStore(java.nio.file.Files.createTempDirectory("gd-lim").toFile())
        s.save(com.hotattic.gamedesigner.core.engine.ProjectOps.newProject("n", "Normal", com.hotattic.gamedesigner.core.model.ProjectMode.NEW_GAME, com.hotattic.gamedesigner.core.model.ProjectPrefs(), 1L))
        File2(s)
    }

    private fun File2(s: com.hotattic.gamedesigner.core.persist.FileProjectStore) {
        val png = ByteArray(3 * 1024 * 1024) { (it * 7).toByte() }
        java.io.File(s.assetsDir("n"), "branding/logo.png").also { it.parentFile.mkdirs() }.writeBytes(png)
        val bytes = ProjectTransfer.createBackup(s, "t", 1L)
        assertTrue(ProjectTransfer.verify(bytes).ok)
        val fresh = com.hotattic.gamedesigner.core.persist.FileProjectStore(java.nio.file.Files.createTempDirectory("gd-lim2").toFile())
        assertEquals(1, ProjectTransfer.import(fresh, bytes).imported.size)
        assertTrue(java.io.File(fresh.assetsDir("n"), "branding/logo.png").readBytes().contentEquals(png))
    }
}
