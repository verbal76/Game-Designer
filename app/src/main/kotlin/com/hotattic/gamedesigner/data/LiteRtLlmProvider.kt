package com.hotattic.gamedesigner.data

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.hotattic.gamedesigner.core.llm.LlmProvider
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.llm.LlmTier
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
class LiteRtLlmProvider(private val context: Context, private val modelPath: () -> String?) : LlmProvider {
    override val id = "litert-lm"
    override val displayName = "On-device model"
    override val tier = LlmTier.LOCAL_SMALL
    override val isLocal = true

    private val lock = Mutex()
    private var engine: Engine? = null
    private var loadedPath: String? = null

    override suspend fun isReady(): Boolean = modelPath()?.let { File(it).exists() } == true

    override suspend fun complete(request: LlmRequest): LlmResult = withContext(Dispatchers.Default) {
        val path = modelPath()?.takeIf { File(it).exists() } ?: return@withContext LlmResult.Failure("No local model installed")
        val prompt = request.messages.lastOrNull { it.role == "user" }?.content ?: return@withContext LlmResult.Failure("Empty request")
        lock.withLock {
            try {
                if (engine == null || loadedPath != path) {
                    engine?.close()
                    val e = Engine(EngineConfig(modelPath = path, backend = Backend.CPU(), cacheDir = context.cacheDir.path))
                    e.initialize()
                    engine = e; loadedPath = path
                }
                val cfg = ConversationConfig(systemInstruction = Contents.of(request.system))
                engine!!.createConversation(cfg).use { conv ->
                    val reply = conv.sendMessage(prompt).toString().trim()
                    if (reply.isBlank()) LlmResult.Failure("The model returned nothing") else LlmResult.Ok(reply)
                }
            } catch (t: Throwable) {
                // Native/runtime failures must never take the app down; the deterministic Director continues without the model.
                engine = null; loadedPath = null
                LlmResult.Failure("Local model error: ${t.message ?: t.javaClass.simpleName}")
            }
        }
    }

    override fun close() { engine?.close(); engine = null; loadedPath = null }
}
