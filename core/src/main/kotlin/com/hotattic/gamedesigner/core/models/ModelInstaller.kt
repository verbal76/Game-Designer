package com.hotattic.gamedesigner.core.models

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.security.MessageDigest

/** An open HTTP response for a (possibly ranged) model download. */
class ModelResponse(val code: Int, val contentLength: Long, val stream: InputStream?, val close: () -> Unit = {})

/** Network boundary so the installer is testable with a fake. */
interface ModelTransport {
    /** Opens [url], asking for bytes from [rangeStart] when it is > 0. Throws IOException on connection problems. */
    fun open(url: String, rangeStart: Long, headers: Map<String, String>): ModelResponse
}

enum class InstallFailure { STORAGE, AUTH_REQUIRED, NOT_FOUND, NETWORK, SIZE_MISMATCH, HASH_MISMATCH, HTTP }

sealed class InstallResult {
    data class Installed(val file: File, val alreadyPresent: Boolean) : InstallResult()
    object Cancelled : InstallResult()
    data class Failed(val reason: InstallFailure, val message: String, /** Partial progress is kept and a retry resumes it. */ val resumable: Boolean) : InstallResult()
}

data class InstallProgress(val bytes: Long, val total: Long, val phase: String)

/**
 * Downloads a catalog model resumably and refuses to ever expose a partial or wrong file: bytes go to a `.part` file, the finished
 * length is checked, the SHA-256 of the WHOLE file (including resumed bytes) is computed from disk and must equal the catalog hash,
 * and only then is the file renamed into place. A mismatch deletes the partial so the next attempt starts clean.
 */
class ModelInstaller(
    private val transport: ModelTransport,
    private val modelDir: File,
    private val partialDir: File,
    private val freeBytes: () -> Long,
    private val token: () -> String? = { null },
) {
    fun finalFile(e: ModelEntry) = File(modelDir, e.file.substringAfterLast('/'))
    private fun partFile(e: ModelEntry) = File(partialDir, finalFile(e).name + ".part")
    private fun markerFile(e: ModelEntry) = File(modelDir, finalFile(e).name + ".verified")

    /** True if the installed file is exactly the catalog file (size + a hash verified at install time, re-checked cheaply by size/mtime). */
    fun isInstalled(e: ModelEntry): Boolean {
        val f = finalFile(e)
        if (!f.isFile || f.length() != e.sizeBytes) return false
        val m = markerFile(e)
        return m.isFile && m.readText().trim() == marker(e, f)
    }

    private fun marker(e: ModelEntry, f: File) = "${e.sha256}:${f.length()}:${f.lastModified()}"

    /** Full re-verification from disk (used by "verify" actions and when the marker is missing). */
    fun verifyInstalled(e: ModelEntry): Boolean {
        val f = finalFile(e)
        if (!f.isFile || f.length() != e.sizeBytes) return false
        val ok = sha256(f) == e.sha256
        if (ok) markerFile(e).writeText(marker(e, f)) else { f.delete(); markerFile(e).delete() }
        return ok
    }

    fun remove(e: ModelEntry) { finalFile(e).delete(); markerFile(e).delete(); partFile(e).delete() }

    fun partialBytes(e: ModelEntry): Long = partFile(e).takeIf { it.isFile }?.length() ?: 0L

    suspend fun install(e: ModelEntry, onProgress: (InstallProgress) -> Unit = {}): InstallResult {
        modelDir.mkdirs(); partialDir.mkdirs()
        if (isInstalled(e) || (finalFile(e).isFile && verifyInstalled(e))) return InstallResult.Installed(finalFile(e), alreadyPresent = true)
        val part = partFile(e)
        var have = if (part.isFile) part.length() else 0L
        if (have > e.sizeBytes) { part.delete(); have = 0L } // cannot be a prefix of the right file
        val need = e.sizeBytes - have
        if (freeBytes() < need + 64L * 1024 * 1024) return InstallResult.Failed(InstallFailure.STORAGE, "Not enough free storage: ${need / 1_000_000} MB more is needed.", resumable = true)
        val headers = buildMap<String, String> { token()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") } }
        val resp = try { transport.open(e.url, have, headers) } catch (ex: IOException) {
            return InstallResult.Failed(InstallFailure.NETWORK, "Couldn't reach the download host (${ex.message}). Progress is kept; try again to resume.", resumable = true)
        }
        try {
            when (resp.code) {
                401, 403 -> return InstallResult.Failed(InstallFailure.AUTH_REQUIRED, "The host requires you to accept the model's license and sign in with a Hugging Face token (HTTP ${resp.code}).", resumable = true)
                404 -> return InstallResult.Failed(InstallFailure.NOT_FOUND, "The host no longer serves this file (404).", resumable = false)
                416 -> { part.delete(); return InstallResult.Failed(InstallFailure.HTTP, "The partial download was out of range and was discarded; try again.", resumable = true) }
                200, 206 -> Unit
                else -> return InstallResult.Failed(InstallFailure.HTTP, "Download failed (HTTP ${resp.code}).", resumable = true)
            }
            if (resp.code == 200) have = 0L // the server ignored Range: start over
            val input = resp.stream ?: return InstallResult.Failed(InstallFailure.NETWORK, "The host sent no data.", resumable = true)
            try {
                RandomAccessFile(part, "rw").use { raf ->
                    raf.setLength(have); raf.seek(have)
                    val buf = ByteArray(256 * 1024)
                    var done = have
                    var last = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buf)
                        if (n < 0) break
                        raf.write(buf, 0, n); done += n
                        val now = System.nanoTime()
                        if (now - last > 300_000_000L) { onProgress(InstallProgress(done, e.sizeBytes, "Downloading")); last = now }
                    }
                    onProgress(InstallProgress(done, e.sizeBytes, "Downloading"))
                }
            } catch (ex: IOException) {
                return InstallResult.Failed(InstallFailure.NETWORK, "The connection dropped (${ex.message}). Progress is kept; try again to resume.", resumable = true)
            }
        } finally { runCatching { resp.close() } }
        if (part.length() != e.sizeBytes) {
            val got = part.length()
            return if (got < e.sizeBytes) InstallResult.Failed(InstallFailure.NETWORK, "The download ended early (${got / 1_000_000} of ${e.sizeBytes / 1_000_000} MB). Try again to resume.", resumable = true)
            else { part.delete(); InstallResult.Failed(InstallFailure.SIZE_MISMATCH, "The downloaded file is larger than expected and was discarded.", resumable = false) }
        }
        onProgress(InstallProgress(e.sizeBytes, e.sizeBytes, "Verifying"))
        if (sha256(part) != e.sha256) {
            part.delete()
            return InstallResult.Failed(InstallFailure.HASH_MISMATCH, "The downloaded file failed verification (SHA-256 mismatch) and was deleted. Nothing was installed.", resumable = false)
        }
        val target = finalFile(e)
        if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
        markerFile(e).writeText(marker(e, target))
        return InstallResult.Installed(target, alreadyPresent = false)
    }

    companion object {
        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> val b = ByteArray(1 shl 20); while (true) { val n = i.read(b); if (n < 0) break; md.update(b, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
