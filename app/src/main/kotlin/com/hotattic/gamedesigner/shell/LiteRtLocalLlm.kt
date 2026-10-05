package com.hotattic.gamedesigner.shell

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.hotattic.gamedesigner.shellapi.LocalLlm
import com.hotattic.gamedesigner.shellapi.LocalLlmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * On-device inference through Google's LiteRT-LM runtime. The model file is chosen by [modelPath]; the engine is created
 * lazily on first use (loading can take several seconds) and reloaded if the model changes. CPU backend for maximum
 * device compatibility.
 */
class LiteRtLocalLlm(private val context: Context, private val modelPath: () -> String?) : LocalLlm {

    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null

    override suspend fun isReady(): Boolean = modelPath()?.let { File(it).exists() } == true

    override suspend fun complete(system: String, user: String, maxTokens: Int): LocalLlmResult = withContext(Dispatchers.Default) {
        val path = modelPath()?.takeIf { File(it).exists() } ?: return@withContext LocalLlmResult(null, "No local model installed")
        lock.withLock {
            try {
                if (engine == null || loadedPath != path) {
                    engine?.close()
                    val e = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = context.cacheDir.path))
                    e.initialize()
                    engine = e; loadedPath = path
                }
                engine!!.createConversation(ConversationConfig(systemInstruction = Contents.of(system))).use { conv ->
                    val reply = conv.sendMessage(user).toString().trim()
                    if (reply.isBlank()) LocalLlmResult(null, "The model returned nothing") else LocalLlmResult(reply, null)
                }
            } catch (t: Throwable) {
                // Native/runtime failures must never take the app down; the deterministic Director continues without the model.
                engine = null; loadedPath = null
                LocalLlmResult(null, "Local model error: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    fun close() { engine?.close(); engine = null; loadedPath = null }
}
