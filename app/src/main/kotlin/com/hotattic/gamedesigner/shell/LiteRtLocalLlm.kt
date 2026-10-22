package com.hotattic.gamedesigner.shell

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.hotattic.gamedesigner.shellapi.LlmFailure
import com.hotattic.gamedesigner.shellapi.LlmStatus
import com.hotattic.gamedesigner.shellapi.LocalLlm
import com.hotattic.gamedesigner.shellapi.LocalLlmResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * On-device inference through Google's LiteRT-LM runtime. The application layer selects the model file; the engine is created
 * on [load] (or lazily on first use), reloaded when the selection changes, and CPU-backed for maximum device compatibility.
 * Every failure (missing file, out of memory, native error, timeout) is returned as a result; nothing here may crash the app or
 * corrupt the project, and a busy engine fails fast instead of queueing behind a slow generation.
 */
class LiteRtLocalLlm(private val context: Context, private val runtimeVersion: String) : LocalLlm {

    private val lock = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var engine: Engine? = null
    @Volatile private var selected: String? = null
    @Volatile private var loadedPath: String? = null
    @Volatile private var loadMillis = 0L
    @Volatile private var lastError: String? = null
    @Volatile private var inferences = 0
    @Volatile private var lastLatency = 0L

    private val backendName = "CPU"

    override fun status(): LlmStatus {
        val p = selected
        return LlmStatus("LiteRT-LM $runtimeVersion ($backendName)", p != null && File(p).exists(), engine != null && loadedPath == p, p?.let { File(it).name }, p,
            backendName, loadMillis, lastError, inferences, lastLatency)
    }

    override fun select(modelPath: String?) {
        if (modelPath != selected) { selected = modelPath; lastError = null }
    }

    override suspend fun isReady(): Boolean = selected?.let { File(it).exists() } == true

    private fun ensureEngineLocked(path: String) {
        if (engine != null && loadedPath == path) return
        engine?.close(); engine = null; loadedPath = null
        val t0 = System.currentTimeMillis()
        val e = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = context.cacheDir.path))
        e.initialize()
        engine = e; loadedPath = path; loadMillis = System.currentTimeMillis() - t0
    }

    override suspend fun load(): LlmStatus = withContext(Dispatchers.Default) {
        val path = selected?.takeIf { File(it).exists() }
        if (path == null) { lastError = "No model file selected"; return@withContext status() }
        if (!lock.tryLock()) return@withContext status()
        try { ensureEngineLocked(path); lastError = null }
        catch (t: OutOfMemoryError) { dropEngine(); lastError = "Not enough memory to load this model" }
        catch (t: Throwable) { dropEngine(); lastError = "Could not load the model: ${t.message ?: t.javaClass.simpleName}" }
        finally { lock.unlock() }
        status()
    }

    override fun unload() { dropEngine() }

    private fun dropEngine() { try { engine?.close() } catch (_: Throwable) { } ; engine = null; loadedPath = null }

    override suspend fun complete(system: String, user: String, maxTokens: Int, timeoutMs: Long): LocalLlmResult {
        val path = selected?.takeIf { File(it).exists() } ?: return LocalLlmResult(null, "No local model installed", LlmFailure.NOT_LOADED)
        if (!lock.tryLock()) {
            // A previous generation that overran its time is still inside native code: report busy instead of queueing behind it.
            return LocalLlmResult(null, "The local model is still busy", LlmFailure.BUSY)
        }
        val t0 = System.currentTimeMillis()
        // The worker, not the caller, releases the lock: a timed-out generation keeps the engine marked busy until native code
        // really returns, so two generations can never run on one engine at once.
        val worker = scope.async {
            try {
                ensureEngineLocked(path)
                engine!!.createConversation(ConversationConfig(systemInstruction = Contents.of(system))).use { conv ->
                    val reply = conv.sendMessage(user).toString().trim()
                    if (reply.isBlank()) LocalLlmResult(null, "The model returned nothing", LlmFailure.EMPTY) else LocalLlmResult(reply, null)
                }
            } catch (t: OutOfMemoryError) {
                dropEngine(); LocalLlmResult(null, "Not enough memory for the local model", LlmFailure.OUT_OF_MEMORY)
            } catch (t: Throwable) {
                dropEngine(); LocalLlmResult(null, "Local model error: ${t.message ?: t.javaClass.simpleName}", LlmFailure.RUNTIME)
            } finally {
                lock.unlock()
            }
        }
        val result = withTimeoutOrNull(timeoutMs) { worker.await() }
        val latency = System.currentTimeMillis() - t0
        lastLatency = latency
        if (result == null) { lastError = "Generation timed out after ${timeoutMs / 1000}s"; return LocalLlmResult(null, lastError, LlmFailure.TIMEOUT, latency) }
        if (result.text != null) { inferences++; lastError = null } else lastError = result.error
        return result.copy(latencyMillis = latency)
    }

    fun close() = dropEngine()
}
