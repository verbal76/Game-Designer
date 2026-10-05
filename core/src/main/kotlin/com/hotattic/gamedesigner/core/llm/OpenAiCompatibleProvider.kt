package com.hotattic.gamedesigner.core.llm

import com.hotattic.gamedesigner.core.net.HttpTransport
import com.hotattic.gamedesigner.core.net.NetworkUnavailableException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Any service that speaks the OpenAI-style chat-completions protocol (many hosted and self-hosted LLM backends do).
 * The base URL must be https; the key comes from secure storage at call time and is never logged or persisted here.
 */
class OpenAiCompatibleProvider(
    private val http: HttpTransport,
    private val baseUrl: () -> String,
    private val apiKey: () -> String?,
    private val model: () -> String,
    override val displayName: String = "Custom LLM (cloud)",
) : LlmProvider {
    override val id = "openai_compatible"
    override val tier = LlmTier.CLOUD_STRONG
    override val isLocal = false

    private fun endpoint(): String? {
        val b = baseUrl().trim().trimEnd('/')
        if (!b.startsWith("https://")) return null
        return if (b.endsWith("/chat/completions")) b else "$b/chat/completions"
    }

    override suspend fun isReady(): Boolean = endpoint() != null && !apiKey().isNullOrBlank() && model().isNotBlank()

    override suspend fun complete(request: LlmRequest): LlmResult {
        val url = endpoint() ?: return LlmResult.Failure("Base URL must start with https://")
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return LlmResult.Failure("No API key configured")
        return try {
            val r = http.post(url, buildBody(request, model()), mapOf("Authorization" to "Bearer $key", "content-type" to "application/json"))
            when {
                r.ok -> parseText(r.body)?.let { LlmResult.Ok(it) } ?: LlmResult.Failure("Unexpected response shape")
                r.code == 401 || r.code == 403 -> LlmResult.Failure("The API key was rejected (${r.code})")
                r.code == 429 || r.code >= 500 -> LlmResult.Failure("Provider busy (${r.code})", retryable = true)
                else -> LlmResult.Failure("Provider error ${r.code}")
            }
        } catch (e: NetworkUnavailableException) { LlmResult.Failure("Offline: ${e.message}", retryable = true) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun buildBody(req: LlmRequest, model: String): String = buildJsonObject {
            put("model", model)
            put("max_tokens", req.maxTokens)
            put("temperature", req.temperature.toDouble())
            put("messages", buildJsonArray {
                if (req.system.isNotBlank()) add(buildJsonObject { put("role", "system"); put("content", req.system) })
                req.messages.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) }
            })
        }.toString()

        fun parseText(body: String): String? = runCatching {
            json.parseToJsonElement(body).jsonObject["choices"]!!.jsonArray.first().jsonObject["message"]!!.jsonObject["content"]!!.jsonPrimitive.content.ifBlank { null }
        }.getOrNull()
    }
}
