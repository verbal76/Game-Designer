package com.hotattic.gamedesigner.models

import com.hotattic.gamedesigner.core.llm.LlmMessage
import com.hotattic.gamedesigner.core.models.DeviceProfile
import com.hotattic.gamedesigner.core.models.InstallFailure
import com.hotattic.gamedesigner.core.models.InstallResult
import com.hotattic.gamedesigner.core.models.ModelCatalog
import com.hotattic.gamedesigner.core.models.ModelChoice
import com.hotattic.gamedesigner.core.models.ModelEntry
import com.hotattic.gamedesigner.core.models.ModelInstaller
import com.hotattic.gamedesigner.core.models.ModelRecommender
import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.shellapi.LlmStatus
import com.hotattic.gamedesigner.shellapi.SecretStore
import com.hotattic.gamedesigner.shellapi.ShellServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class ModelUi {
    object Absent : ModelUi()
    data class Working(val phase: String, val bytes: Long, val total: Long) : ModelUi()
    data class Failed(val message: String, val resumable: Boolean, val needsToken: Boolean) : ModelUi()
    object Installed : ModelUi()
}

/**
 * Owns the acquire -> verify -> install -> load path for on-device models. The shell only runs the model file this controller
 * selects; catalog, device fit, download, resume and SHA-256 verification all live here (in the over-the-air application layer).
 */
class ModelController(
    private val shell: ShellServices,
    private val settings: StateFlow<AppSettings>,
    private val updateSettings: ((AppSettings) -> AppSettings) -> Unit,
    private val scope: CoroutineScope,
) {
    val catalog: List<ModelEntry> = ModelCatalog.builtIn
    private val installer = ModelInstaller(HttpModelTransport(), shell.models.directory, shell.models.partialDirectory, { shell.models.freeBytes() }) { shell.secrets.get(SecretStore.HF_TOKEN) }
    private val jobs = HashMap<String, Job>()

    private val _states = MutableStateFlow<Map<String, ModelUi>>(emptyMap())
    val states: StateFlow<Map<String, ModelUi>> = _states
    private val _profile = MutableStateFlow(profileNow())
    val profile: StateFlow<DeviceProfile> = _profile
    private val _status = MutableStateFlow(shell.localLlm.status())
    val status: StateFlow<LlmStatus> = _status
    private val _imported = MutableStateFlow<List<String>>(emptyList())
    val imported: StateFlow<List<String>> = _imported
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private fun profileNow() = DeviceProfiler.profile(shell.application, shell.models.directory)

    fun choices(): List<ModelChoice> = ModelRecommender.choices(_profile.value, catalog)
    fun blocker(e: ModelEntry): String? = ModelRecommender.blocker(_profile.value, e)

    init { refresh(); restoreSelection() }

    fun refresh() {
        _profile.value = profileNow()
        val files = shell.models.list().map { it.name }
        _imported.value = files.filter { f -> catalog.none { it.file.substringAfterLast('/') == f } }
        _states.value = _states.value.filterValues { it is ModelUi.Working } + catalog.filter { _states.value[it.id] !is ModelUi.Working }.associate { e ->
            e.id to (if (installer.isInstalled(e)) ModelUi.Installed else (_states.value[e.id] as? ModelUi.Failed ?: ModelUi.Absent))
        }
        _status.value = shell.localLlm.status()
    }

    /** The model Bob uses: a verified catalog install, or a file the owner imported (flagged unverified in the UI). */
    fun activeEntry(): ModelEntry? = catalog.firstOrNull { it.id == settings.value.localModelId }
    fun activeLabel(): String = activeEntry()?.name ?: settings.value.localModelId.takeIf { it.isNotBlank() }?.let { "$it (imported, unverified)" } ?: "none"

    private fun restoreSelection() {
        val id = settings.value.localModelId
        if (id.isBlank() || !settings.value.localModelEnabled) return
        val entry = activeEntry()
        val path = if (entry != null) installer.finalFile(entry).takeIf { installer.isInstalled(entry) }?.absolutePath else shell.models.file(id).takeIf { it.isFile }?.absolutePath
        if (path != null) { shell.localLlm.select(path); scope.launch { shell.localLlm.load(); _status.value = shell.localLlm.status() } }
        _status.value = shell.localLlm.status()
    }

    fun localReady(): Boolean = settings.value.localModelEnabled && shell.localLlm.status().ready

    private fun setState(id: String, s: ModelUi) { _states.value = _states.value + (id to s) }

    fun install(e: ModelEntry) {
        if (jobs[e.id]?.isActive == true) return
        setState(e.id, ModelUi.Working("Starting", installer.partialBytes(e), e.sizeBytes))
        jobs[e.id] = scope.launch(Dispatchers.IO) {
            val r = installer.install(e) { p -> setState(e.id, ModelUi.Working(p.phase, p.bytes, p.total)) }
            when (r) {
                is InstallResult.Installed -> { setState(e.id, ModelUi.Installed); use(e) }
                InstallResult.Cancelled -> setState(e.id, ModelUi.Absent)
                is InstallResult.Failed -> setState(e.id, ModelUi.Failed(r.message, r.resumable, r.reason == InstallFailure.AUTH_REQUIRED))
            }
        }.also { j -> j.invokeOnCompletion { ex -> if (ex is kotlinx.coroutines.CancellationException) setState(e.id, ModelUi.Failed("Paused. Progress is kept; tap Resume to continue.", true, false)) } }
    }

    fun cancel(id: String) { jobs[id]?.cancel() }

    /** Makes [e] Bob's model and loads it now, so a failure shows up here and not in the middle of a conversation. */
    fun use(e: ModelEntry) {
        scope.launch {
            if (!installer.isInstalled(e) && !withContext(Dispatchers.IO) { installer.verifyInstalled(e) }) { _message.value = "That model is not installed or failed verification."; refresh(); return@launch }
            shell.localLlm.select(installer.finalFile(e).absolutePath)
            updateSettings { it.copy(localModelId = e.id, localModelEnabled = true) }
            val st = withContext(Dispatchers.Default) { shell.localLlm.load() }
            _status.value = st
            _message.value = if (st.loaded) "${e.name} is loaded (${st.loadMillis / 1000.0}s). Bob now runs on this phone." else (st.lastError ?: "The model could not be loaded.")
        }
    }

    fun useImported(fileName: String) {
        scope.launch {
            shell.localLlm.select(shell.models.file(fileName).absolutePath)
            updateSettings { it.copy(localModelId = fileName, localModelEnabled = true) }
            val st = withContext(Dispatchers.Default) { shell.localLlm.load() }
            _status.value = st
            _message.value = if (st.loaded) "$fileName loaded. It is an unverified import: its integrity and license were not checked." else (st.lastError ?: "The model could not be loaded.")
        }
    }

    fun remove(e: ModelEntry) {
        cancel(e.id)
        if (settings.value.localModelId == e.id) { shell.localLlm.unload(); shell.localLlm.select(null); updateSettings { it.copy(localModelId = "") } }
        installer.remove(e); setState(e.id, ModelUi.Absent); refresh()
    }

    fun removeImported(fileName: String) {
        if (settings.value.localModelId == fileName) { shell.localLlm.unload(); shell.localLlm.select(null); updateSettings { it.copy(localModelId = "") } }
        shell.models.delete(fileName); refresh()
    }

    fun clearMessage() { _message.value = null }
    fun say(m: String) { _message.value = m }

    /** Actual inference proof: asks the loaded model something small and reports the reply and latency. */
    suspend fun testBob(): String {
        val r = shell.localLlm.complete("You are Bob, a friendly game-design director. Answer in one short sentence.", "Say hello and name one thing you can help me design.", 60, 120_000)
        _status.value = shell.localLlm.status()
        return if (r.text != null) "Local model replied in ${r.latencyMillis / 1000.0}s: ${r.text}" else "No reply: ${r.error ?: "unknown error"}"
    }
}
