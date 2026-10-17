package com.hotattic.gamedesigner.core.persist

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * The behaviour behind the Import and Backup screens, with no Android types so it can be tested exhaustively on the JVM: what a file-picker result
 * becomes, the preview, confirmation and cancellation, and an honest backup report. The Android layer only turns a Uri into a [Picked] or a
 * [BackupSink] and shows the result.
 */
class TransferController(private val store: FileProjectStore, private val appVersion: String, private val clock: () -> Long = { System.currentTimeMillis() }) {

    /** One file the owner picked. [open] returns null when the file can no longer be opened. */
    class Picked(val name: String, val open: () -> InputStream?)

    /** Where a backup goes. [open] is the destination chosen by the owner; [read] reads the same destination back for verification. */
    interface BackupSink { fun open(): OutputStream; fun read(): InputStream? }

    data class Preview(val files: List<ProjectTransfer.FilePreview>) { val importable get() = files.sumOf { it.importable } }

    data class ImportOutcome(val imported: Int, val success: Boolean, val message: String, val problems: List<String>)

    /** [success] is true only for a backup that holds every project as a normal readable project AND was read back and matched. */
    data class BackupSummary(val success: Boolean, val headline: String, val lines: List<String>, val result: ProjectTransfer.BackupResult?) { val countsAsBackup get() = success }

    private var pending: List<ByteArray?> = emptyList()
    val hasPending get() = synchronized(this) { pending.isNotEmpty() }

    // ---- import -----------------------------------------------------------------------------------------------------------------------------

    private fun read(p: Picked): Pair<ByteArray?, String?> = try {
        val s = p.open()
        if (s == null) null to "This file could not be opened (it may have been moved or deleted)"
        else s.use { ProjectTransfer.readLimited(it) to null }
    } catch (e: ProjectTransfer.TooLargeException) { null to "This file is ${e.message}, so it was not read" }
    catch (e: Exception) { null to "This file could not be read: ${e.message ?: e.javaClass.simpleName}" }

    /** Step 1: read and check the picked files. Writes nothing. An empty pick (the owner backed out of the picker) changes nothing at all. */
    fun preview(picks: List<Picked>): Preview? = synchronized(this) {
        if (picks.isEmpty()) return null
        val read = picks.map { it to read(it) }
        pending = read.map { it.second.first }
        Preview(read.map { (p, r) ->
            val (bytes, err) = r
            if (bytes == null) ProjectTransfer.FilePreview(p.name, PackageKind.UNKNOWN, err ?: "Unreadable", emptyList(), 0, emptyList())
            else ProjectTransfer.preview(p.name, bytes)
        })
    }

    /** The owner backed out of the preview: nothing was or will be imported from it. */
    fun cancel() = synchronized(this) { pending = emptyList() }

    /** Step 2: import every valid project from the previewed files. Existing projects are never overwritten; a clash is saved as a separate copy. */
    fun confirm(): ImportOutcome = synchronized(this) {
        val bytes = pending; pending = emptyList()
        if (bytes.isEmpty()) return ImportOutcome(0, false, "There is nothing to import. Choose the files again.", emptyList())
        var count = 0; val problems = mutableListOf<String>()
        for (b in bytes) {
            if (b == null) { problems += "a file could not be read"; continue }
            try { val r = ProjectTransfer.import(store, b); count += r.imported.size; problems += r.errors + r.skipped }
            catch (e: Exception) { problems += "a file failed while importing: ${e.message ?: e.javaClass.simpleName}" }
        }
        val msg = if (count == 0) "Nothing was imported. " + problems.joinToString(" ").take(300)
        else "Imported $count project(s). Nothing existing was changed." + if (problems.isNotEmpty()) " Some items were skipped: " + problems.joinToString(" ").take(200) else ""
        ImportOutcome(count, count > 0 && problems.isEmpty(), msg, problems)
    }

    /** Integrity check of one picked file; writes nothing. */
    fun check(pick: Picked): String {
        val (bytes, err) = read(pick)
        if (bytes == null) return err ?: "That file could not be read."
        val v = ProjectTransfer.verify(bytes)
        return if (v.ok) "This file is intact: ${v.projects} project(s)" + (if (v.hasManifest) ", checksums match." else ", readable (older backup without checksums).")
        else "Problem: " + v.problems.joinToString(" ").ifBlank { "this is not a project backup." }
    }

    // ---- backup -----------------------------------------------------------------------------------------------------------------------------

    private fun digestOf(input: InputStream, limit: Long): Pair<String, Long> {
        val d = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(64 * 1024); var total = 0L; var n: Int
        while (input.read(buf).also { n = it } > 0) { total += n; if (total > limit) throw ProjectTransfer.TooLargeException(limit); d.update(buf, 0, n) }
        return d.digest().joinToString("") { "%02x".format(it) } to total
    }

    /**
     * Writes a backup to [sink], reads it back and compares it, and reports honestly. Success means every project folder is in it as a normal readable
     * project and the stored file matches what was written. A damaged project that was saved from its last good copy, a project kept raw, or one with
     * no readable copy are each named, and make the backup a partial one.
     */
    fun backup(sink: BackupSink): BackupSummary {
        val result = try { sink.open().use { ProjectTransfer.createBackup(store, appVersion, clock(), it) } }
        catch (e: Exception) { return BackupSummary(false, "The backup could not be written", listOf("${e.message ?: e.javaClass.simpleName}. Nothing on this phone was changed. Choose another location and try again. If a partial file was left at the destination, do not rely on it."), null) }
        val lines = mutableListOf<String>()
        val back = try { sink.read()?.use { digestOf(it, ProjectTransfer.MAX_INPUT_BYTES * 4) } } catch (e: Exception) { null }
        val verified = back != null && back.first == result.sha256 && back.second == result.bytesWritten
        if (!verified) lines += if (back == null) "The saved file could not be read back, so it could not be verified." else "The saved file does not match what was written (it may have been cut short or altered by the storage app)."
        for (i in result.attention) lines += "${i.name.ifBlank { i.folder }}: ${i.status.name.lowercase().replace('_', ' ')} - ${i.detail}"
        if (result.items.isEmpty()) lines += "There were no projects to back up."
        val ok = verified && result.complete
        val headline = when {
            ok -> "Backup saved and verified: ${result.included} project(s), with checksums"
            result.items.isEmpty() -> "There was nothing to back up"
            !verified -> "Backup written but NOT verified"
            else -> "Backup saved, but NOT complete: ${result.attention.size} of ${result.items.size} project(s) need attention"
        }
        return BackupSummary(ok, headline, lines, result)
    }
}
