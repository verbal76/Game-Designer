package com.hotattic.gamedesigner.shellapi

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * App-private storage for on-device model files. It deliberately knows nothing about catalogs, URLs or hashes: the application
 * layer (which can be updated over the air) owns the catalog, downloading and verification, and writes finished files here.
 * Models are never bundled in the APK.
 */
class ModelManager(private val context: Context) {

    /** Directory holding finished model files (`*.litertlm`). Partial downloads live in [partialDirectory]. */
    val directory: File get() = File(context.filesDir, "models").also { it.mkdirs() }
    val partialDirectory: File get() = File(context.filesDir, "models-partial").also { it.mkdirs() }

    fun list(): List<File> = directory.listFiles { f -> f.isFile && f.name.endsWith(".litertlm") }?.sortedBy { it.name }.orEmpty()

    fun file(name: String): File = File(directory, File(name).name)

    fun freeBytes(): Long = context.filesDir.usableSpace

    /** Copies a model the owner picked from storage. The stream is read to the end (document providers report no length). */
    suspend fun importFrom(uri: Uri, displayName: String): String = withContext(Dispatchers.IO) {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".litertlm")) it else "$it.litertlm" }
        val tmp = File(partialDirectory, "$safe.import")
        val stream = context.contentResolver.openInputStream(uri) ?: throw java.io.IOException("The file could not be opened")
        stream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        val out = File(directory, safe)
        if (!tmp.renameTo(out)) { tmp.copyTo(out, overwrite = true); tmp.delete() }
        safe
    }

    fun delete(fileName: String) {
        File(directory, File(fileName).name).delete()
        File(partialDirectory, File(fileName).name + ".part").delete()
    }
}
