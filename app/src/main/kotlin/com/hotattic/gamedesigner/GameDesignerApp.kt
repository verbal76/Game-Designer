package com.hotattic.gamedesigner

import android.app.Application
import com.hotattic.gamedesigner.core.director.Director
import com.hotattic.gamedesigner.core.director.DirectorDeps
import com.hotattic.gamedesigner.core.github.GitHubClient
import com.hotattic.gamedesigner.core.llm.AnthropicProvider
import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.core.model.CloudProviderId
import com.hotattic.gamedesigner.core.net.JavaHttpTransport
import com.hotattic.gamedesigner.core.persist.FileProjectStore
import com.hotattic.gamedesigner.core.research.WebResearch
import com.hotattic.gamedesigner.data.LiteRtLlmProvider
import com.hotattic.gamedesigner.data.ModelManager
import com.hotattic.gamedesigner.data.SecretStore
import kotlinx.coroutines.flow.MutableStateFlow

const val DEFAULT_CLOUD_MODEL = "claude-sonnet-5-5"

/** Manual dependency container: one place that wires core abstractions to Android implementations. */
class AppContainer(app: Application) {
    val store = FileProjectStore(app.filesDir)
    val secrets = SecretStore(app)
    val http = JavaHttpTransport()
    val settings = MutableStateFlow(store.loadSettings())
    val models = ModelManager(app) { secrets.get(SecretStore.HF_TOKEN) }

    val local = LiteRtLlmProvider(app) {
        val id = settings.value.localModelId
        (if (id.isNotBlank()) models.activePath(id) else null) ?: models.anyPath()
    }
    val cloud = AnthropicProvider(http, { secrets.get(SecretStore.ANTHROPIC_KEY) }, { settings.value.cloudModel.ifBlank { DEFAULT_CLOUD_MODEL } })
    val research = WebResearch(http)

    fun github() = GitHubClient(http) { secrets.get(SecretStore.GITHUB_TOKEN) }

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        val next = f(settings.value)
        settings.value = next
        store.saveSettings(next)
    }

    fun localModelAvailable() = models.anyPath() != null
    fun cloudConfigured() = settings.value.cloudProvider == CloudProviderId.ANTHROPIC && secrets.has(SecretStore.ANTHROPIC_KEY)

    fun director(): Director = Director(DirectorDeps(
        local = if (settings.value.localModelEnabled && localModelAvailable()) local else null,
        cloud = if (cloudConfigured()) cloud else null,
        research = research,
    ))
}

class GameDesignerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
