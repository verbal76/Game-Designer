package com.hotattic.gamedesigner.shellapi

import android.app.Application
import androidx.compose.runtime.Composable

/**
 * THE contract between the native shell (installed with the APK) and the application layer (bundled, and replaceable
 * over the air). Everything here is stable: it uses only platform types and the types defined in this module, never
 * types from the OTA-able code. Any incompatible change must bump `shellApi` in gradle/libs.versions.toml, which makes
 * existing OTA bundles incompatible and therefore requires a new APK.
 */
const val SHELL_API_LEVEL_DOC = "see BuildConfig.SHELL_API_LEVEL"

enum class LlmFailure { NOT_LOADED, TIMEOUT, OUT_OF_MEMORY, BUSY, EMPTY, RUNTIME }

data class LocalLlmResult(
    val text: String?,
    val error: String?,
    val failure: LlmFailure? = null,
    val latencyMillis: Long = 0,
)

/** What the on-device runtime is actually doing right now. Used for diagnostics and to prove real local inference. */
data class LlmStatus(
    /** e.g. "LiteRT-LM 0.16.0 (CPU)". */
    val runtime: String,
    /** A model file is selected and exists. */
    val ready: Boolean,
    /** The engine has loaded that file into memory. */
    val loaded: Boolean,
    val modelFile: String?,
    val modelPath: String?,
    val backend: String,
    val loadMillis: Long,
    val lastError: String?,
    val inferences: Int,
    val lastLatencyMillis: Long,
)

/**
 * On-device model access. The native runtime (LiteRT-LM) lives in the shell because it needs native libraries.
 * The application layer decides WHICH model file to use (it owns the catalog, download and verification); the shell only runs it.
 */
interface LocalLlm {
    /** Cheap, non-blocking snapshot. */
    fun status(): LlmStatus
    /** Selects the model file to use (null clears the selection). Does not load it. */
    fun select(modelPath: String?)
    /** Loads the selected model now (so a failure is reported at install time, not mid-conversation). */
    suspend fun load(): LlmStatus
    /** Releases the engine and its memory. */
    fun unload()
    suspend fun isReady(): Boolean
    /** One stateless generation. Never throws; failures are reported in the result. */
    suspend fun complete(system: String, user: String, maxTokens: Int, timeoutMs: Long = 90_000): LocalLlmResult
}

data class OtaDiagnostics(
    val nativeVersionName: String,
    val nativeVersionCode: Int,
    val shellApiLevel: Int,
    val runtimeFingerprint: String,
    /** "bundled" or "ota" */
    val layerSource: String,
    val layerVersion: Int,
    val layerLabel: String,
    val channel: String,
    val trustedKeyPresent: Boolean,
    val lastCheckAt: Long,
    val lastCheckResult: String,
    val lastEvent: String,
    val pendingVersion: Int?,
    val badVersions: List<String>,
    val startupNote: String,
)

interface OtaControl {
    fun diagnostics(): OtaDiagnostics
    /** Runs an update check off the main thread; [onResult] is invoked on the main thread with a human-readable result. */
    fun checkNow(onResult: (String) -> Unit)
    /** Called by the layer once it has proven healthy; commits a trial update. */
    fun markHealthy()
    /** "off", "dev" or "stable". */
    fun setChannel(channel: String)
    /** Abandons any OTA layer; the bundled layer runs from the next start and the abandoned bundle is not re-offered. */
    fun resetToBundled()
}

interface ShellServices {
    val application: Application
    val secrets: SecretStore
    val models: ModelManager
    val localLlm: LocalLlm
    /** Drawable resource id of the Hot Attic Games logo, owned by the shell (resources are not OTA-updatable). */
    val splashLogoRes: Int
    val nativeVersionName: String
    val nativeVersionCode: Int
    val ota: OtaControl
}

/** Implemented by the application layer; loaded either from the APK (fallback) or from a verified OTA bundle. */
interface AppLayer {
    @Composable
    fun Content(shell: ShellServices)
}
