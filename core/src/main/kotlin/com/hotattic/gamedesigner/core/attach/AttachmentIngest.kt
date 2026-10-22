package com.hotattic.gamedesigner.core.attach

import com.hotattic.gamedesigner.core.model.BrandingAsset
import com.hotattic.gamedesigner.core.model.BrandingMode
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

data class ImageInfo(val mime: String, val ext: String, val width: Int, val height: Int)

/** Reads just enough of an image's header to prove it is a supported, well-formed image and learn its size. Pure JVM. */
object ImageSniffer {
    private fun u16be(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    private fun u32be(b: ByteArray, i: Int) = (u16be(b, i).toLong() shl 16) or u16be(b, i + 2).toLong()
    private fun u24le(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or ((b[i + 2].toInt() and 0xFF) shl 16)

    fun sniff(b: ByteArray): ImageInfo? = png(b) ?: jpeg(b) ?: webp(b)

    private fun png(b: ByteArray): ImageInfo? {
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (b.size < 33 || !b.copyOfRange(0, 8).contentEquals(sig)) return null
        if (String(b, 12, 4, Charsets.ISO_8859_1) != "IHDR") return null
        val w = u32be(b, 16); val h = u32be(b, 20)
        if (w !in 1..65535 || h !in 1..65535) return null
        return ImageInfo("image/png", "png", w.toInt(), h.toInt())
    }

    private fun jpeg(b: ByteArray): ImageInfo? {
        if (b.size < 4 || b[0] != 0xFF.toByte() || b[1] != 0xD8.toByte()) return null
        var i = 2
        while (i + 9 < b.size) {
            if (b[i] != 0xFF.toByte()) { i++; continue }
            val m = b[i + 1].toInt() and 0xFF
            if (m == 0xFF) { i++; continue }
            if (m in 0xD0..0xD9 || m == 0x01) { i += 2; continue }
            val len = u16be(b, i + 2)
            if (m in 0xC0..0xCF && m != 0xC4 && m != 0xC8 && m != 0xCC) {
                val h = u16be(b, i + 5); val w = u16be(b, i + 7)
                return if (w > 0 && h > 0) ImageInfo("image/jpeg", "jpg", w, h) else null
            }
            i += 2 + len
        }
        return null
    }

    private fun webp(b: ByteArray): ImageInfo? {
        if (b.size < 30 || String(b, 0, 4, Charsets.ISO_8859_1) != "RIFF" || String(b, 8, 4, Charsets.ISO_8859_1) != "WEBP") return null
        return when (String(b, 12, 4, Charsets.ISO_8859_1)) {
            "VP8 " -> { val w = (u16le(b, 26) and 0x3FFF); val h = (u16le(b, 28) and 0x3FFF); if (w > 0 && h > 0) ImageInfo("image/webp", "webp", w, h) else null }
            "VP8L" -> { val bits = (b[21].toInt() and 0xFF) or ((b[22].toInt() and 0xFF) shl 8) or ((b[23].toInt() and 0xFF) shl 16) or ((b[24].toInt() and 0xFF) shl 24); ImageInfo("image/webp", "webp", (bits and 0x3FFF) + 1, ((bits shr 14) and 0x3FFF) + 1) }
            "VP8X" -> ImageInfo("image/webp", "webp", u24le(b, 24) + 1, u24le(b, 27) + 1)
            else -> null
        }
    }
    private fun u16le(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
}

enum class IngestFailure(val headline: String) {
    CANNOT_READ("I couldn't read that file"),
    PERMISSION("Android didn't give me permission to read that file"),
    EMPTY("That file is empty"),
    TOO_LARGE("That image is too large (limit 25 MB)"),
    UNSUPPORTED("That isn't a supported image (use PNG, JPEG or WebP)"),
    CORRUPT("That image looks damaged and can't be used"),
    STORAGE("I couldn't save the image on this phone"),
}

sealed class IngestResult {
    data class Ok(val asset: BrandingAsset, val file: File) : IngestResult()
    data class Failed(val kind: IngestFailure, val detail: String = "") : IngestResult() {
        val message: String get() = kind.headline + (if (detail.isNotBlank()) " ($detail)" else "") + "."
    }
}

/**
 * Durably ingests an image the owner picked. A picker returning a URI proves nothing, so success is only reported after the
 * ORIGINAL bytes were read completely, validated as an image, written to app-owned storage (temp file, fsync, atomic rename)
 * and read back with a matching SHA-256. [open] is called once, immediately, while the picker's URI grant is valid; document
 * providers may hand back pipes whose length and `available()` are unknown, so the stream is read to EOF in a loop.
 */
object AttachmentIngest {
    const val MAX_BYTES = 25 * 1024 * 1024

    fun ingest(slot: String, originalName: String, open: () -> InputStream?, dir: File, now: Long, relativeDir: String = "branding"): IngestResult {
        val bytes = try {
            val stream = open() ?: return IngestResult.Failed(IngestFailure.CANNOT_READ, "the provider returned no data")
            stream.use { readBounded(it) } ?: return IngestResult.Failed(IngestFailure.TOO_LARGE)
        } catch (e: SecurityException) {
            return IngestResult.Failed(IngestFailure.PERMISSION, e.message.orEmpty().take(80))
        } catch (e: IOException) {
            return IngestResult.Failed(IngestFailure.CANNOT_READ, e.message.orEmpty().take(80))
        }
        if (bytes.isEmpty()) return IngestResult.Failed(IngestFailure.EMPTY)
        val info = ImageSniffer.sniff(bytes) ?: return IngestResult.Failed(
            if (looksLikeImageButBroken(bytes)) IngestFailure.CORRUPT else IngestFailure.UNSUPPORTED)
        val sha = sha256(bytes)
        return try {
            val target = File(dir.also { it.mkdirs() }, "${slot}_${sha.take(8)}.${info.ext}")
            if (!(target.exists() && target.length() == bytes.size.toLong() && sha256(target.readBytes()) == sha)) {
                val tmp = File(dir, target.name + ".tmp")
                java.io.FileOutputStream(tmp).use { it.write(bytes); it.fd.sync() }
                if (!tmp.renameTo(target)) { tmp.delete(); return IngestResult.Failed(IngestFailure.STORAGE, "rename failed") }
            }
            if (sha256(target.readBytes()) != sha) { target.delete(); return IngestResult.Failed(IngestFailure.STORAGE, "verification failed") }
            IngestResult.Ok(BrandingAsset(slot, BrandingMode.UPLOADED, "$relativeDir/${target.name}", originalName.ifBlank { "image" }, sha, info.width, info.height, now), target)
        } catch (e: IOException) {
            IngestResult.Failed(IngestFailure.STORAGE, e.message.orEmpty().take(80))
        }
    }

    private fun looksLikeImageButBroken(b: ByteArray): Boolean =
        (b.size >= 8 && b[0] == 0x89.toByte() && b[1] == 0x50.toByte() && b[2] == 0x4E.toByte()) || (b.size >= 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte()) ||
            (b.size >= 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP")

    private fun readBounded(input: InputStream): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            if (out.size() + n > MAX_BYTES) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
}
