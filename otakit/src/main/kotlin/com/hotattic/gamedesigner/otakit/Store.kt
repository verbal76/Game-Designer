package com.hotattic.gamedesigner.otakit

import java.io.File

/**
 * On-disk layout under [root]:
 *   state.json                    atomically written OtaState
 *   versions/<n>/bundle.zip       verified bundle (made read-only; Android 14+ refuses writable dex)
 *   versions/<n>/manifest.json    the signed manifest that authorised it
 *   staging/                      partial downloads (never trusted)
 */
class OtaStore(val root: File) {
    private val stateFile get() = File(root, "state.json")
    private val versions get() = File(root, "versions")
    val staging get() = File(root, "staging").also { it.mkdirs() }

    /** Written by the shell's uncaught-exception handler while a trial layer is running and not yet healthy. */
    val crashFlag get() = File(root, "crash.flag")
    fun markCrash() { runCatching { root.mkdirs(); crashFlag.writeText("1") } }

    fun versionDir(v: Int) = File(versions, v.toString())
    fun bundleFile(v: Int) = File(versionDir(v), "bundle.zip")
    fun manifestFile(v: Int) = File(versionDir(v), "manifest.json")

    /** Returns the stored state, or the default state plus a note if it is missing/corrupt. Never throws. */
    fun readState(): Pair<OtaState, String?> {
        if (!stateFile.exists()) return OtaState() to null
        return try {
            OtaJson.decodeFromString(OtaState.serializer(), stateFile.readText()) to null
        } catch (e: Exception) {
            OtaState() to "OTA state was unreadable and has been reset (bundled layer in use)"
        }
    }

    /** Atomic: write a temp file then rename over the old one, so a crash can never leave half a state. */
    fun writeState(s: OtaState) {
        root.mkdirs()
        val tmp = File(root, "state.json.tmp")
        tmp.writeText(OtaJson.encodeToString(OtaState.serializer(), s))
        if (!tmp.renameTo(stateFile)) { tmp.copyTo(stateFile, overwrite = true); tmp.delete() }
    }

    /** Moves a verified download into place atomically and locks it read-only. */
    fun install(v: Int, verifiedPart: File, manifestBytes: ByteArray) {
        val dir = versionDir(v)
        dir.deleteRecursively()
        dir.mkdirs()
        val target = bundleFile(v)
        if (!verifiedPart.renameTo(target)) { verifiedPart.copyTo(target, overwrite = true); verifiedPart.delete() }
        manifestFile(v).writeBytes(manifestBytes)
        target.setReadOnly()
    }

    fun delete(v: Int) { versionDir(v).let { d -> d.walkBottomUp().forEach { it.setWritable(true); it.delete() } } }

    fun cleanup(keep: Set<Int>) {
        versions.listFiles()?.forEach { d -> d.name.toIntOrNull()?.let { if (it !in keep) delete(it) } }
        staging.listFiles()?.forEach { it.delete() }
    }

    /** Keeps every protected version plus the newest [limit] others; removes the rest and clears staging. */
    fun prune(protected: Set<Int>, limit: Int = 10) {
        val installed = installedVersions()
        val keep = protected + installed.filter { it !in protected }.sortedDescending().take(limit)
        installed.filter { it !in keep }.forEach { delete(it) }
        staging.listFiles()?.forEach { it.delete() }
    }

    fun installedVersions(): List<Int> = versions.listFiles()?.mapNotNull { it.name.toIntOrNull() }.orEmpty().sorted()
}
