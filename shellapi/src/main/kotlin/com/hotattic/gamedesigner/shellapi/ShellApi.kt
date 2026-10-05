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

data class LocalLlmResult(val text: String?, val error: String?)

/** On-device model access. The native runtime (LiteRT-LM) lives in the shell because it needs native libraries. */
interface LocalLlm {
    suspend fun isReady(): Boolean
    suspend fun complete(system: String, user: String, maxTokens: Int): LocalLlmResult
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
