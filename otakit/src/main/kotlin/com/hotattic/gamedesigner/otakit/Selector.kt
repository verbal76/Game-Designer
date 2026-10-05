package com.hotattic.gamedesigner.otakit

/**
 * Decides which application layer a cold start runs, and owns rollback.
 *
 * Lifecycle of an update:   staged (pending) -> trial (this start) -> committed (active) once the layer reports healthy.
 * A start that finds a trial still set means the previous start never became healthy => that version is blacklisted
 * and the previous committed layer (or the bundled one) is used. A corrupt/missing file or load error also falls back.
 * The bundled layer is always available, so no OTA state can prevent the app from starting.
 */
class OtaSelector(private val store: OtaStore, private val shell: ShellIdentity, private val clock: () -> Long = System::currentTimeMillis) {

    companion object { const val MAX_TRIAL_BOOTS = 3 }

    private fun bundled(note: String) = Selection(LayerSource.BUNDLED, shell.bundledLayerVersion, shell.bundledLayerLabel, null, null, note)

    fun select(): Selection {
        var (s, stateNote) = store.readState()
        var note = stateNote ?: ""
        fun mark(v: Int, why: String): OtaState {
            val sha = runCatching { OtaJson.decodeFromString(OtaManifest.serializer(), store.manifestFile(v).readText()).bundle.sha256 }.getOrDefault("")
            store.delete(v)
            return s.copy(bad = (s.bad + BadVersion(v, sha, why, clock())).takeLast(20), lastEvent = "rolled back v$v: $why")
        }

        // A trial that never became healthy. A recorded crash rolls back at once; a silent death (user swipe, OOM kill)
        // gets the trial a couple more starts before it is abandoned.
        s.trial?.let { t ->
            val crashed = store.crashFlag.exists()
            store.crashFlag.delete()
            if (crashed || s.trialBoots >= MAX_TRIAL_BOOTS) {
                s = mark(t, if (crashed) "crashed before becoming healthy" else "never became healthy after $MAX_TRIAL_BOOTS starts").copy(trial = null, trialBoots = 0)
                note = "Rolled back update $t: it did not start cleanly."
            } else s = s.copy(trialBoots = s.trialBoots + 1, lastEvent = "retrying trial v$t")
        } ?: store.crashFlag.delete()
        // Promote a staged update to a trial for this start (only when no trial is in flight).
        if (s.trial == null) s.pending?.let { p ->
            s = if (verifyOnDisk(p)) s.copy(pending = null, trial = p, trialBoots = 1, lastEvent = "trying v$p")
            else mark(p, "staged files failed verification").copy(pending = null).also { note = "Staged update $p was corrupt and was discarded." }
        }
        // Make sure whatever we are about to load is intact.
        var version: Int? = s.trial ?: s.active
        if (version != null && !verifyOnDisk(version)) {
            s = mark(version, "bundle failed integrity check at start").copy(trial = null, trialBoots = 0, active = if (s.active == version) null else s.active)
            note = "Update $version failed its integrity check; using the previous layer."
            version = s.active?.takeIf { verifyOnDisk(it) }
        }
        store.writeState(s) // persisted BEFORE loading, so a crash during load counts as a failed trial
        if (version == null) return bundled(note.ifBlank { "Bundled layer" })
        val m = runCatching { OtaJson.decodeFromString(OtaManifest.serializer(), store.manifestFile(version).readText()) }.getOrNull()
            ?: return reportLoadFailure(version, "manifest unreadable")
        return Selection(LayerSource.OTA, version, m.versionName, store.bundleFile(version).absolutePath, m.entryClass, note.ifBlank { "OTA layer $version" })
    }

    /** Called by the loader if class loading/instantiation throws. Falls back to the bundled layer in the same start. */
    fun reportLoadFailure(version: Int, error: String): Selection {
        val (s0, _) = store.readState()
        val sha = runCatching { OtaJson.decodeFromString(OtaManifest.serializer(), store.manifestFile(version).readText()).bundle.sha256 }.getOrDefault("")
        store.delete(version)
        store.writeState(s0.copy(trial = null, trialBoots = 0, pending = s0.pending.takeIf { it != version }, active = s0.active.takeIf { it != version },
            bad = (s0.bad + BadVersion(version, sha, "load failed: ${error.take(160)}", clock())).takeLast(20), lastEvent = "load failed v$version"))
        return bundled("Update $version could not be loaded ($error); using the bundled layer.")
    }

    /** The layer proved itself (first frame composed). Commits a trial version as the active one. */
    fun markHealthy() {
        val (s, _) = store.readState()
        val t = s.trial ?: return
        val old = s.active
        store.writeState(s.copy(active = t, trial = null, trialBoots = 0, highWater = maxOf(s.highWater, t), lastEvent = "committed v$t"))
        if (old != null && old != t) store.delete(old)
    }

    /** Owner action: abandon the current OTA layer and run the bundled one; that bundle will not be re-offered. */
    fun resetToBundled(reason: String = "reset by user") {
        val (s0, _) = store.readState()
        var s = s0
        listOfNotNull(s.active, s.trial, s.pending).distinct().forEach { v ->
            val sha = runCatching { OtaJson.decodeFromString(OtaManifest.serializer(), store.manifestFile(v).readText()).bundle.sha256 }.getOrDefault("")
            s = s.copy(bad = (s.bad + BadVersion(v, sha, reason, clock())).takeLast(20)); store.delete(v)
        }
        store.writeState(s.copy(active = null, trial = null, trialBoots = 0, pending = null, lastEvent = "reset to bundled"))
    }

    private fun verifyOnDisk(v: Int): Boolean = try {
        val m = OtaJson.decodeFromString(OtaManifest.serializer(), store.manifestFile(v).readText())
        val f = store.bundleFile(v)
        f.exists() && f.length() == m.bundle.size && Crypto.sha256Hex(f) == m.bundle.sha256.lowercase() &&
            m.runtimeFingerprint == shell.runtimeFingerprint && m.shellApiLevel == shell.shellApiLevel
    } catch (e: Exception) { false }
}
