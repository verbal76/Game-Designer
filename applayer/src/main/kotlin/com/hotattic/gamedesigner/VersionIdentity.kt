package com.hotattic.gamedesigner

import com.hotattic.gamedesigner.applayer.AppLayerEntry
import com.hotattic.gamedesigner.shellapi.OtaDiagnostics

/**
 * Owner-facing identities, kept apart on purpose:
 *  - the NATIVE APK (v3 = a real installed APK),
 *  - the APPLICATION LAYER (v3.0 is the layer built into that APK; v3.1, v3.2... are updates delivered over the air),
 *  - the OTA SEQUENCE (#6, #7...: a counter on the update channel, NOT a version of the app).
 * An OTA never creates a new native major version. The layer minor is the OTA sequence minus the sequence the native
 * generation started from ([GENERATION_BASE_SEQ]); see docs/VERSIONING.md for the full history.
 */
object VersionIdentity {
    /** OTA sequence of the layer built into the current native generation (v3 APK ships sequence 5 as application layer v3.0). */
    const val GENERATION_BASE_SEQ = 5

    fun nativeMajor(nativeVersionName: String) = nativeVersionName.substringBefore('.').ifBlank { "?" }

    fun layerName(nativeVersionName: String, seq: Int): String = "v${nativeMajor(nativeVersionName)}.${(seq - GENERATION_BASE_SEQ).coerceAtLeast(0)}"

    fun lines(d: OtaDiagnostics, aiMode: String, model: String): List<String> {
        val ota = d.layerSource == "ota"
        return listOf(
            "Game Designer v${nativeMajor(d.nativeVersionName)} (APK ${d.nativeVersionName}, build ${d.nativeVersionCode})",
            "Application layer: ${layerName(d.nativeVersionName, d.layerVersion)}${if (ota) " (over the air)" else " (built into the APK)"}",
            if (ota) "Update: OTA #${d.layerVersion} - ${d.layerLabel}" else "Update: none (running the layer built into the APK)",
            "Runtime: ${d.runtimeFingerprint}  |  shell API ${d.shellApiLevel}",
            "Source: ${AppLayerEntry.SOURCE_SHA.take(10)}",
            "AI mode: $aiMode",
            "Model: $model",
        )
    }
}
