package com.hotattic.gamedesigner.core.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One downloadable on-device model package. Everything that identifies and verifies it is explicit: exact repository file, size,
 * SHA-256 (read from the host's LFS metadata by CI, never typed by hand), license and whether the host gates it behind a login.
 *
 * Reusable boundary: this package (catalog, device profile, recommender, installer, verifier) depends only on the Kotlin standard
 * library, coroutines and serialization, so it can later be lifted into shared Hot Attic Games infrastructure unchanged.
 */
@Serializable
data class ModelEntry(
    val id: String,
    val family: String,
    val name: String,
    val variant: String,
    val format: String = "litertlm",
    val quantization: String,
    val repo: String,
    val file: String,
    val url: String,
    val sizeBytes: Long,
    val sha256: String,
    val license: String,
    val licenseUrl: String = "",
    /** The host requires accepting the license and signing in (a Hugging Face token) before it will serve the file. */
    val gated: Boolean = false,
    /** Total device RAM below which this model is not offered. */
    val minTotalRamMb: Int,
    /** Rough working-set memory while loaded. */
    val runtimeRamMb: Int,
    val contextTokens: Int,
    /** 1 (slowest) .. 5 (fastest) relative to the other catalog entries on the same device. */
    val speed: Int,
    /** 1 .. 5 relative reasoning/instruction-following quality. */
    val quality: Int,
    val note: String = "",
) {
    val sizeMb: Int get() = (sizeBytes / 1_000_000).toInt()
    val runtime: String get() = "LiteRT-LM"
}

@Serializable
data class ModelCatalogFile(val schema: Int = 1, val generatedAt: String = "", val models: List<ModelEntry>)

object ModelCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String): List<ModelEntry> = json.decodeFromString(ModelCatalogFile.serializer(), text).models.filter { it.sha256.length == 64 && it.sizeBytes > 0 }

    /** The catalog compiled into the app. Entries are only ever added after CI verified their size and hash on the host. */
    val builtIn: List<ModelEntry> get() = ModelCatalogData.entries
}
