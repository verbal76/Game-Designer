package com.hotattic.gamedesigner

import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.github.GitHubClient
import com.hotattic.gamedesigner.core.llm.AnthropicProvider
import com.hotattic.gamedesigner.core.llm.LlmProvider
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.llm.LlmTier
import com.hotattic.gamedesigner.core.llm.ProviderFactory
import com.hotattic.gamedesigner.core.llm.ProviderIds
import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.core.model.CloudProviderId
import com.hotattic.gamedesigner.core.net.JavaHttpTransport
import com.hotattic.gamedesigner.core.persist.FileProjectStore
import com.hotattic.gamedesigner.core.research.WebResearch
import com.hotattic.gamedesigner.shellapi.LlmFailure
import com.hotattic.gamedesigner.shellapi.LocalLlm
import com.hotattic.gamedesigner.shellapi.SecretStore
import com.hotattic.gamedesigner.shellapi.ShellServices
import kotlinx.coroutines.flow.MutableStateFlow

const val DEFAULT_CLOUD_MODEL = "claude-sonnet-5-5"

/** Adapts the shell's stable [LocalLlm] to the core [LlmProvider] interface (which may change over the air). */
class ShellLlmAdapter(private val llm: LocalLlm) : LlmProvider {
    override val id = "litert-lm"
    override val displayName = "On-device model"
    override val tier = LlmTier.LOCAL_SMALL
    override val isLocal = true
    override suspend fun isReady() = llm.isReady()
    override suspend fun complete(request: LlmRequest): LlmResult {
        val user = request.messages.lastOrNull { it.role == "user" }?.content ?: return LlmResult.Failure("Empty request")
        val r = llm.complete(request.system, user, request.maxTokens)
        return r.text?.let { LlmResult.Ok(it) }
            ?: LlmResult.Failure(r.error ?: "Local model returned nothing", retryable = r.failure == LlmFailure.TIMEOUT || r.failure == LlmFailure.BUSY)
    }
}

/** Application-layer dependency container. Everything native comes from [ShellServices]. */
class AppContainer(val shell: ShellServices) {
    val store = FileProjectStore(shell.application.filesDir)
    val secrets get() = shell.secrets
    val models get() = shell.models
    private val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    val http = JavaHttpTransport()
    val settings = MutableStateFlow(store.loadSettings())

    val local: LlmProvider = ShellLlmAdapter(shell.localLlm)
    /** Effective cloud backend id: the new additive setting, else the legacy Anthropic switch. */
    fun providerId(): String = settings.value.llmProviderId.ifBlank { if (settings.value.cloudProvider == CloudProviderId.ANTHROPIC) ProviderIds.ANTHROPIC else ProviderIds.NONE }

    /** The currently selected cloud provider (Anthropic when none is selected, so callers always have an object). */
    val cloud: LlmProvider
        get() = ProviderFactory.create(providerId().ifBlank { ProviderIds.ANTHROPIC }, http, { secrets.get(it) }, { settings.value.llmBaseUrl }, { settings.value.cloudModel })
            ?: AnthropicProvider(http, { secrets.get(SecretStore.ANTHROPIC_KEY) }, { settings.value.cloudModel.ifBlank { DEFAULT_CLOUD_MODEL } })
    val research = WebResearch(http)

    fun github() = GitHubClient(http) { secrets.get(SecretStore.GITHUB_TOKEN) }

    /** Acquire / verify / install / load for on-device models (catalog, device fit, resumable download, SHA-256). */
    val modelController: com.hotattic.gamedesigner.models.ModelController by lazy { com.hotattic.gamedesigner.models.ModelController(shell, settings, { f -> updateSettings(f) }, scope) }

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        val next = f(settings.value)
        settings.value = next
        store.saveSettings(next)
    }

    fun localModelAvailable() = modelController.localReady()
    fun cloudConfigured(): Boolean {
        val d = ProviderFactory.descriptor(providerId()) ?: return false
        if (!secrets.has(d.secretName)) return false
        return !d.needsBaseUrl || settings.value.llmBaseUrl.trim().startsWith("https://")
    }

    fun director(): Director = Director(DirectorDeps(
        local = if (settings.value.localModelEnabled && localModelAvailable()) local else null,
        cloud = if (cloudConfigured()) cloud else null,
        research = research,
    ))
}
