package com.hotattic.gamedesigner.data

import android.content.Context
import android.net.Uri
import com.hotattic.gamedesigner.core.net.NetworkUnavailableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/** A downloadable on-device model package. `verified=false` means the URL was not confirmed from the build sandbox. */
data class ModelSpec(
    val id: String,
    val name: String,
    val approxSizeMb: Int,
    val url: String,
    val fileName: String,
    val license: String,
    val note: String,
    val verified: Boolean,
)

sealed class ModelState {
    object None : ModelState()
    data class Downloading(val bytes: Long, val total: Long) : ModelState()
    data class Failed(val reason: String) : ModelState()
    object Ready : ModelState()
}

/**
 * Manages replaceable local model packages (LiteRT-LM `.litertlm` files). Models are never bundled in the APK; they are
 * downloaded (resumable) or imported from device storage.
 */
class ModelManager(private val context: Context, private val tokenProvider: () -> String?) {

    private val dir get() = File(context.filesDir, "models").also { it.mkdirs() }

    val catalog: List<ModelSpec> = listOf(
        ModelSpec("gemma-4-e2b", "Gemma 4 E2B (small, fastest)", 2600,
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm", "gemma-4-E2B-it.litertlm",
            "Apache-2.0", "Best balance for most phones with 6 GB+ RAM.", verified = false),
        ModelSpec("gemma-4-e4b", "Gemma 4 E4B (larger, smarter)", 3700,
            "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm", "gemma-4-E4B-it.litertlm",
            "Apache-2.0", "Higher quality; wants 8 GB+ RAM and more storage.", verified = false),
    )

    private val _state = MutableStateFlow<Map<String, ModelState>>(catalog.associate { it.id to (if (fileFor(it.fileName).exists()) ModelState.Ready else ModelState.None) })
    val state: StateFlow<Map<String, ModelState>> get() = _state

    private val _imported = MutableStateFlow(importedFiles())
    val imported: StateFlow<List<String>> get() = _imported

    fun fileFor(fileName: String) = File(dir, fileName)
    private fun importedFiles() = dir.listFiles { f -> f.name.endsWith(".litertlm") && catalog.none { c -> c.fileName == f.name } }?.map { it.name }.orEmpty()

    /** Absolute path of the active model file if present. */
    fun activePath(selectedId: String): String? {
        val spec = catalog.firstOrNull { it.id == selectedId }
        val f = if (spec != null) fileFor(spec.fileName) else fileFor(selectedId)
        return f.takeIf { it.exists() && it.length() > 1_000_000 }?.absolutePath
    }

    /** Any installed model, preferring catalog order; used when no model has been explicitly selected. */
    fun anyPath(): String? =
        (catalog.map { fileFor(it.fileName) } + (dir.listFiles { f -> f.name.endsWith(".litertlm") }?.toList().orEmpty()))
            .firstOrNull { it.exists() && it.length() > 1_000_000 }?.absolutePath

    private fun set(id: String, s: ModelState) { _state.value = _state.value + (id to s) }

    suspend fun download(spec: ModelSpec) = withContext(Dispatchers.IO) {
        val target = fileFor(spec.fileName)
        val part = File(dir, spec.fileName + ".part")
        try {
            var have = if (part.exists()) part.length() else 0L
            val c = URL(spec.url).openConnection() as HttpURLConnection
            c.connectTimeout = 20_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", "GameDesigner/0.1")
            tokenProvider()?.takeIf { it.isNotBlank() }?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            if (have > 0) c.setRequestProperty("Range", "bytes=$have-")
            val code = c.responseCode
            if (code == 401 || code == 403) { set(spec.id, ModelState.Failed("The host requires you to accept the model license and provide a Hugging Face token (HTTP $code). Add a token in Settings, or import the file manually.")); return@withContext }
            if (code == 404) { set(spec.id, ModelState.Failed("This catalog entry was not found (404). Import a .litertlm file from storage instead.")); return@withContext }
            if (code != 200 && code != 206) { set(spec.id, ModelState.Failed("Download failed (HTTP $code).")); return@withContext }
            if (code == 200) have = 0 // server ignored Range
            val total = have + c.contentLengthLong.coerceAtLeast(0)
            RandomAccessFile(part, "rw").use { raf ->
                if (have == 0L) raf.setLength(0)
                raf.seek(have)
                c.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var read: Int
                    var done = have
                    var lastEmit = 0L
                    while (input.read(buf).also { read = it } >= 0) {
                        raf.write(buf, 0, read); done += read
                        val now = System.currentTimeMillis()
                        if (now - lastEmit > 400) { set(spec.id, ModelState.Downloading(done, total)); lastEmit = now }
                    }
                }
            }
            if (!part.renameTo(target)) { part.copyTo(target, overwrite = true); part.delete() }
            set(spec.id, ModelState.Ready)
        } catch (e: java.io.IOException) {
            set(spec.id, ModelState.Failed("Network problem: ${e.message}. Progress is kept; try again to resume."))
        }
    }

    suspend fun importFrom(uri: Uri, displayName: String): String = withContext(Dispatchers.IO) {
        val safe = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").let { if (it.endsWith(".litertlm")) it else "$it.litertlm" }
        val out = File(dir, safe)
        context.contentResolver.openInputStream(uri)!!.use { i -> out.outputStream().use { o -> i.copyTo(o) } }
        _imported.value = importedFiles()
        safe
    }

    fun delete(id: String) {
        val spec = catalog.firstOrNull { it.id == id }
        if (spec != null) { fileFor(spec.fileName).delete(); File(dir, spec.fileName + ".part").delete(); set(id, ModelState.None) }
        else { fileFor(id).delete(); _imported.value = importedFiles() }
    }
}
