package com.hotattic.gamedesigner.otakit

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What the native shell is, as seen by the OTA system. An OTA bundle must match this to be accepted. */
data class ShellIdentity(
    val versionCode: Int,
    val versionName: String,
    val shellApiLevel: Int,
    /** Encodes the Kotlin/Compose/AGP/shell-API generation the shell was built with. Bundles must match exactly. */
    val runtimeFingerprint: String,
    /** Version number of the application layer compiled into the APK (the known-good fallback). */
    val bundledLayerVersion: Int,
    val bundledLayerLabel: String,
)

@Serializable
data class BundleFile(val name: String, val sha256: String, val size: Long)

/** Signed description of one OTA bundle. The exact bytes of manifest.json are what the signature covers. */
@Serializable
data class OtaManifest(
    val schema: Int = 1,
    val channel: String,
    val bundleVersion: Int,
    val versionName: String,
    val sourceSha: String = "",
    val createdAt: String = "",
    val shellApiLevel: Int,
    val runtimeFingerprint: String,
    val minShellVersionCode: Int = 1,
    val entryClass: String,
    val bundle: BundleFile,
)

@Serializable
data class BadVersion(val version: Int, val sha256: String, val reason: String, val at: Long)

/** Persisted OTA state. Written atomically; any unreadable state degrades to the bundled layer. */
@Serializable
data class OtaState(
    /** Last committed (proven healthy) OTA version; null means the bundled layer. */
    val active: Int? = null,
    /** Verified and staged; becomes a trial at the next cold start. */
    val pending: Int? = null,
    /** Currently on trial: activated but not yet confirmed healthy. A start that finds this set means it crashed. */
    val trial: Int? = null,
    /** Cold starts spent on the current trial without being confirmed healthy. */
    val trialBoots: Int = 0,
    /** Highest version ever committed; new updates must be newer than this and than anything active/pending. */
    val highWater: Int = 0,
    val bad: List<BadVersion> = emptyList(),
    /** Owner-chosen build (stable line). While set, nothing is ever staged automatically; downloads continue into the cache. */
    val pinned: Int? = null,
    val channel: String = "dev",
    val lastCheckAt: Long = 0,
    val lastCheckResult: String = "never",
    val lastEvent: String = "",
)

/** One published build as listed in a channel's index.json (a discovery hint only: every download is still verified against its signed manifest). */
@Serializable
data class OtaIndexEntry(
    val version: Int,
    val versionName: String,
    val sourceSha: String = "",
    val createdAt: String = "",
    val shellApiLevel: Int,
    val runtimeFingerprint: String,
)

@Serializable
data class OtaIndex(val schema: Int = 1, val channel: String, val builds: List<OtaIndexEntry> = emptyList())

val OtaJson = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

enum class LayerSource { BUNDLED, OTA }

/** Which application layer this process should run. */
data class Selection(
    val source: LayerSource,
    val version: Int,
    val label: String,
    /** Path to the verified, read-only bundle zip when [source] is OTA. */
    val bundlePath: String?,
    val entryClass: String?,
    val note: String,
)

sealed class CheckResult(val summary: String) {
    object Disabled : CheckResult("OTA disabled (channel off or no trusted key in this build)")
    object UpToDate : CheckResult("Up to date")
    data class Staged(val manifest: OtaManifest) : CheckResult("Update ${manifest.bundleVersion} (${manifest.versionName}) downloaded and verified; it activates on the next app start")
    /** Downloaded and verified into the local cache, but deliberately NOT scheduled (stable line / pinned build). */
    data class Cached(val manifest: OtaManifest) : CheckResult("Build ${manifest.bundleVersion} (${manifest.versionName}) downloaded and verified; it will not be applied unless you choose it")
    data class Rejected(val reason: String) : CheckResult("Update rejected: $reason")
    data class Failed(val reason: String) : CheckResult("Update check failed: $reason")
}
