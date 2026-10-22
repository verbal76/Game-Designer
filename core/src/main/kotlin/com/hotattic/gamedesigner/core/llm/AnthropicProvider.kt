package com.hotattic.gamedesigner.core.llm

import com.hotattic.gamedesigner.core.net.HttpTransport
import com.hotattic.gamedesigner.core.net.NetworkUnavailableException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Optional stronger cloud reasoning via the Anthropic Messages API. The key comes from secure storage at call time. */
class AnthropicProvider(
    private val http: HttpTransport,
    private val apiKey: () -> String?,
    private val model: () -> String,
) : LlmProvider {
    override val id = "anthropic"
    override val displayName = "Claude (cloud)"
    override val tier = LlmTier.CLOUD_STRONG
    override val isLocal = false

    override suspend fun isReady(): Boolean = !apiKey().isNullOrBlank()

    override suspend fun complete(request: LlmRequest): LlmResult {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return LlmResult.Failure("No API key configured")
        val body = buildBody(request, model())
        return try {
            val r = http.post("https://api.anthropic.com/v1/messages", body,
                mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01", "content-type" to "application/json"))
            when {
                r.ok -> parseText(r.body)?.let { LlmResult.Ok(it) } ?: LlmResult.Failure("Unexpected response shape")
                r.code == 401 -> LlmResult.Failure("The API key was rejected (401)")
                r.code == 429 || r.code >= 500 -> LlmResult.Failure("Provider busy (${r.code})", retryable = true)
                else -> LlmResult.Failure("Provider error ${r.code}: ${errorMessage(r.body)}")
            }
        } catch (e: NetworkUnavailableException) { LlmResult.Failure("Offline: ${e.message}", retryable = true) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun buildBody(req: LlmRequest, model: String): String = buildJsonObject {
            put("model", model)
            put("max_tokens", req.maxTokens)
            put("temperature", req.temperature.toDouble())
            if (req.system.isNotBlank()) put("system", req.system)
            put("messages", buildJsonArray {
                req.messages.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) }
            })
        }.toString()

        fun parseText(body: String): String? = runCatching {
            val content: JsonArray = json.parseToJsonElement(body).jsonObject["content"]!!.jsonArray
            content.mapNotNull { (it as? JsonObject)?.takeIf { o -> o["type"]?.jsonPrimitive?.content == "text" }?.get("text")?.jsonPrimitive?.content }.joinToString("").ifBlank { null }
        }.getOrNull()

        fun errorMessage(body: String): String = runCatching { json.parseToJsonElement(body).jsonObject["error"]!!.jsonObject["message"]!!.jsonPrimitive.content }.getOrDefault("")
    }
}
