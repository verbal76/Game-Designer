package com.hotattic.gamedesigner.core.llm

import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys

data class LlmMessage(val role: String, val content: String) // role: "user" | "assistant"

data class LlmRequest(
    val system: String,
    val messages: List<LlmMessage>,
    val maxTokens: Int = 512,
    val temperature: Float = 0.2f,
)

sealed class LlmResult {
    data class Ok(val text: String) : LlmResult()
    data class Failure(val reason: String, val retryable: Boolean = false) : LlmResult()
}

/** Capability tier used to decide what work is routed where. */
enum class LlmTier { LOCAL_SMALL, CLOUD_STRONG }

/**
 * Boundary for every language model (on-device runtime, cloud API, a test fake). The Director depends only on this,
 * so models/providers are replaceable without touching interview logic.
 */
interface LlmProvider {
    val id: String
    val displayName: String
    val tier: LlmTier
    /** True if prompts never leave the device. */
    val isLocal: Boolean
    /** Cheap readiness check (model file present, key configured, ...). Must not block on network. */
    suspend fun isReady(): Boolean
    suspend fun complete(request: LlmRequest): LlmResult
    /** Releases native resources, if any. */
    fun close() {}
}

/** Prompt builders shared by all providers. */
object DirectorPrompts {

    fun extractionSystem(): String = buildString {
        appendLine("You extract game design decisions from a user's message. Reply with ONLY one JSON object, no prose, no markdown.")
        appendLine("Use only these keys, and only when the user clearly stated or strongly implied the value:")
        appendLine("- genre: array of ids from [${GenreKnowledge.all.joinToString(",") { it.id }},other]")
        appendLine("- dimension: \"2D\" | \"2.5D\" | \"3D\"")
        appendLine("- platforms: array from [android,ios,windows,mac,linux,web]")
        appendLine("- art_direction: pixel_art|low_poly|voxel|hand_drawn|vector_flat|minimal_geometric|stylized_3d")
        appendLine("- orientation: landscape|portrait|both")
        appendLine("- core_fantasy: one sentence")
        appendLine("- player_feeling: short phrase")
        appendLine("- reference_games: array of game titles the user wants to draw from")
        appendLine("Omit keys you are unsure about. Never invent values.")
    }

    /** Parses the extraction JSON leniently; returns a schema-keyed string map (before sanitisation). */
    fun parseExtraction(text: String): Pair<Map<String, String>, List<String>> {
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return emptyMap<String, String>() to emptyList()
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(text.substring(start, end + 1)) as kotlinx.serialization.json.JsonObject
            val out = linkedMapOf<String, String>()
            var refs = emptyList<String>()
            for ((k, v) in obj) {
                when (k) {
                    "reference_games" -> refs = (v as? kotlinx.serialization.json.JsonArray)?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }.orEmpty().filter { it.isNotBlank() }
                    Keys.GENRE, Keys.PLATFORMS -> {
                        val items = when (v) {
                            is kotlinx.serialization.json.JsonArray -> v.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            is kotlinx.serialization.json.JsonPrimitive -> v.content.split(',').map { it.trim() }
                            else -> emptyList()
                        }.filter { it.isNotBlank() }
                        if (items.isNotEmpty()) out[k] = Decision.joinList(items)
                    }
                    else -> if (Fields.get(k) != null && v is kotlinx.serialization.json.JsonPrimitive && v.content.isNotBlank()) out[k] = v.content
                }
            }
            out to refs
        } catch (e: Exception) { emptyMap<String, String>() to emptyList() }
    }

    fun answerSystem(directorName: String, beginner: Boolean): String =
        "You are $directorName, a practical game-design director helping an owner design a complete game on their phone. " +
            "Answer the owner's question in 2-4 short sentences, in plain language${if (beginner) " (they are a beginner; avoid jargon)" else ""}. " +
            "Be concrete, make a recommendation when asked, and do not ask more than one follow-up question."
}
