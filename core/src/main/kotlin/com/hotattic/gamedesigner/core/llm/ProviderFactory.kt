package com.hotattic.gamedesigner.core.llm

import com.hotattic.gamedesigner.core.net.HttpTransport

/** Stable ids for selectable cloud backends. Stored as plain strings in settings so adding one never needs a migration. */
object ProviderIds {
    const val NONE = ""
    const val ANTHROPIC = "anthropic"
    const val OPENAI_COMPATIBLE = "openai_compatible"
}

data class ProviderDescriptor(val id: String, val label: String, val needsBaseUrl: Boolean, val defaultModel: String, val secretName: String)

/** Registry of cloud LLM backends the app can configure. The Director only ever sees [LlmProvider]. */
object ProviderFactory {
    val available = listOf(
        ProviderDescriptor(ProviderIds.ANTHROPIC, "Anthropic Claude", false, "claude-sonnet-5-5", "anthropic_api_key"),
        ProviderDescriptor(ProviderIds.OPENAI_COMPATIBLE, "OpenAI-compatible service", true, "", "openai_compat_api_key"),
    )

    fun descriptor(id: String) = available.firstOrNull { it.id == id }

    fun create(id: String, http: HttpTransport, secret: (String) -> String?, baseUrl: () -> String, model: () -> String): LlmProvider? {
        val d = descriptor(id) ?: return null
        val m = { model().ifBlank { d.defaultModel } }
        return when (id) {
            ProviderIds.ANTHROPIC -> AnthropicProvider(http, { secret(d.secretName) }, m)
            ProviderIds.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(http, baseUrl, { secret(d.secretName) }, m)
            else -> null
        }
    }

    /** What an external LLM receives per owner message. Shown verbatim in Settings so the owner can see what leaves the device. */
    val PRIVACY_SENT = listOf(
        "The owner's latest message.",
        "The current question and its answer options.",
        "The original game concept text and a compact list of design decisions made so far.",
        "The last few chat turns (truncated).",
    )
    val PRIVACY_NOT_SENT = listOf(
        "Uploaded images or any other assets.",
        "Repository contents, files, or GitHub tokens.",
        "API keys, other secrets, or other projects.",
    )
}
