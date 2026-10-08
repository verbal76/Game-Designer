package com.hotattic.gamedesigner.core.models

/** The facts about a phone that decide which models it can comfortably run. Gathered by the Android layer; pure data here. */
data class DeviceProfile(
    val manufacturer: String,
    val model: String,
    val sdk: Int,
    val totalRamMb: Int,
    val availRamMb: Int,
    val freeStorageMb: Long,
    val cpuCores: Int,
    val abis: List<String>,
    val socModel: String = "",
) {
    val arm64: Boolean get() = abis.any { it == "arm64-v8a" }
    fun summary(): String = "$manufacturer $model, Android API $sdk, ${totalRamMb / 1024.0}".let { "$manufacturer $model - Android API $sdk, %.1f GB RAM, %.1f GB free storage, $cpuCores cores".format(totalRamMb / 1024.0, freeStorageMb / 1024.0) }
}
