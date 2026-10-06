package com.hotattic.gamedesigner.shell

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.hotattic.gamedesigner.BuildConfig
import com.hotattic.gamedesigner.otakit.CheckResult
import com.hotattic.gamedesigner.otakit.JavaFetcher
import com.hotattic.gamedesigner.otakit.LayerSource
import com.hotattic.gamedesigner.otakit.OtaSelector
import com.hotattic.gamedesigner.otakit.OtaStore
import com.hotattic.gamedesigner.otakit.OtaUpdater
import com.hotattic.gamedesigner.otakit.Selection
import com.hotattic.gamedesigner.otakit.ShellIdentity
import com.hotattic.gamedesigner.shellapi.OtaBuild
import com.hotattic.gamedesigner.shellapi.OtaControl
import com.hotattic.gamedesigner.shellapi.OtaDiagnostics
import java.io.File
import java.util.concurrent.Executors

/**
 * Native-shell side of OTA: decides which layer runs, checks for updates in the background, and records crashes so a bad
 * update rolls itself back.
 *
 *  - dev line: checks on every launch/return to the app (at most every 15 minutes), downloads, and schedules the update for
 *    the next start by itself. The layer shows an "Update ready - Restart" banner so it can be applied at once.
 *  - stable line: NOT an automatic line. The owner picks one of the last 10 published builds; nothing is ever scheduled
 *    automatically, but newer builds keep downloading into the cache so they are ready when chosen.
 * A build is only ever activated at a cold start, so there is no mid-session swap and no restart loop.
 */
class OtaManager(private val app: Application, private val identity: ShellIdentity, private val trustedKeys: List<String>) : OtaControl {
    private val store = OtaStore(File(app.filesDir, "ota"))
    private val selector = OtaSelector(store, identity)
    private val updater = OtaUpdater(store, identity, trustedKeys, JavaFetcher(), "https://github.com/${BuildConfig.OTA_REPO}/releases/download")
    private val prefs = app.getSharedPreferences("gd_ota", Application.MODE_PRIVATE)
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ota").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()

    @Volatile private var selection: Selection? = null
    @Volatile private var running: Selection? = null
    @Volatile private var startupNote = ""
    @Volatile private var healthy = false

    fun channel(): String = prefs.getString("channel", DEFAULT_CHANNEL) ?: DEFAULT_CHANNEL

    private fun autoApply() = channel() == "dev"
    private val checking = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Called once per process, before the UI is created. Never throws. */
    fun start(): Selection {
        val sel = synchronized(lock) { runCatching { selector.select() }.getOrElse { Selection(LayerSource.BUNDLED, identity.bundledLayerVersion, identity.bundledLayerLabel, null, null, "OTA selection failed (${it.message}); bundled layer") } }
        selection = sel
        startupNote = sel.note
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            // A crash while an update is still on trial is recorded so the next start rolls it back immediately.
            if (running?.source == LayerSource.OTA && !healthy) store.markCrash()
            previous?.uncaughtException(t, e)
        }
        return sel
    }

    fun currentSelection(): Selection = selection ?: start()

    fun layerLoaded(sel: Selection?) { running = sel }

    fun layerLoadFailed(sel: Selection, error: String) {
        val fb = synchronized(lock) { selector.reportLoadFailure(sel.version, error) }
        running = null
        startupNote = fb.note
    }

    /** Opportunistic check, run on a background thread after the UI is up. Cheap: at most one every 15 minutes. */
    fun autoCheckIfDue() {
        val ch = channel()
        if (ch == "off" || trustedKeys.isEmpty()) return
        val last = store.readState().first.lastCheckAt
        if (System.currentTimeMillis() - last < CHECK_INTERVAL) return
        if (!checking.compareAndSet(false, true)) return
        exec.execute {
            try {
                Thread.sleep(5_000) // let the first screen settle; also keeps this clear of the health commit
                runCatching { synchronized(lock) { updater.check(ch, stage = autoApply()) } }
                if (ch == "stable") runCatching { updater.builds("stable") }
            } finally { checking.set(false) }
        }
    }

    override fun checkNow(onResult: (String) -> Unit) {
        exec.execute {
            val ch = channel()
            val msg = try { synchronized(lock) { updater.check(ch, stage = autoApply()).summary } } catch (t: Throwable) { "Update check failed: ${t.message}" }
            if (ch == "stable") runCatching { updater.builds("stable") }
            main.post { onResult(msg) }
        }
    }

    override fun builds(): List<OtaBuild> {
        val s = store.readState().first
        val have = store.installedVersions().toSet()
        val running = (running?.takeIf { it.source == LayerSource.OTA }?.version) ?: s.active
        return updater.cachedBuilds("stable").map { e ->
            OtaBuild(e.version, e.versionName, e.sourceSha, e.createdAt, downloaded = e.version in have, running = e.version == running, chosen = e.version == s.pinned)
        }
    }

    override fun refreshBuilds(onResult: (String) -> Unit) {
        exec.execute {
            val msg = try {
                val list = synchronized(lock) { updater.builds("stable") }
                // keep the newest build ready in the cache (never scheduled on this line)
                if (channel() == "stable") synchronized(lock) { updater.check("stable", stage = false) }
                if (list.isEmpty()) "No stable builds have been published for this app version yet." else "${list.size} stable build(s) available."
            } catch (t: Throwable) { "Could not load the build list: ${t.message}" }
            main.post { onResult(msg) }
        }
    }

    override fun chooseBuild(version: Int?, onResult: (String) -> Unit) {
        exec.execute {
            val msg = try {
                synchronized(lock) {
                    when {
                        channel() != "stable" -> "Switch to the stable line first."
                        version == null -> selector.pin(null).message
                        else -> {
                            if (version !in store.installedVersions()) {
                                when (val r = updater.fetchBuild("stable", version)) {
                                    is CheckResult.Cached, is CheckResult.Staged -> Unit
                                    else -> return@synchronized "Could not download build $version: ${r.summary}"
                                }
                            }
                            selector.pin(version).message
                        }
                    }
                }
            } catch (t: Throwable) { "Could not switch builds: ${t.message}" }
            main.post { onResult(msg) }
        }
    }

    override fun restartNow() {
        val intent = app.packageManager.getLaunchIntentForPackage(app.packageName)?.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) } ?: return
        app.startActivity(intent)
        main.postDelayed({ Runtime.getRuntime().exit(0) }, 200)
    }

    override fun markHealthy() {
        healthy = true
        exec.execute { runCatching { synchronized(lock) { selector.markHealthy() } } }
    }

    override fun setChannel(channel: String) {
        if (channel !in setOf("off", "dev", "stable")) return
        prefs.edit().putString("channel", channel).apply()
        synchronized(lock) {
            when (channel) {
                "dev" -> selector.pin(null)            // the dev line follows the latest build again
                "stable" -> selector.unschedule()      // stable never applies anything by itself
            }
        }
    }

    override fun resetToBundled() { synchronized(lock) { selector.resetToBundled() } }

    override fun diagnostics(): OtaDiagnostics {
        val s = store.readState().first
        val r = running
        return OtaDiagnostics(
            nativeVersionName = identity.versionName, nativeVersionCode = identity.versionCode, shellApiLevel = identity.shellApiLevel,
            runtimeFingerprint = identity.runtimeFingerprint,
            layerSource = if (r?.source == LayerSource.OTA) "ota" else "bundled",
            layerVersion = r?.version ?: identity.bundledLayerVersion, layerLabel = r?.label ?: identity.bundledLayerLabel,
            channel = channel(), trustedKeyPresent = trustedKeys.isNotEmpty(),
            lastCheckAt = s.lastCheckAt, lastCheckResult = s.lastCheckResult, lastEvent = s.lastEvent,
            pendingVersion = s.pending, badVersions = s.bad.map { "v${it.version} (${it.reason})" }, startupNote = startupNote,
            pinnedVersion = s.pinned, autoApply = autoApply(), downloadedVersions = store.installedVersions(),
            pendingName = s.pending?.let { v -> runCatching { com.hotattic.gamedesigner.otakit.OtaJson.decodeFromString(com.hotattic.gamedesigner.otakit.OtaManifest.serializer(), store.manifestFile(v).readText()).versionName }.getOrNull() },
        )
    }

    companion object {
        /** This build is a physical-test build; production would default to "stable" once a stable channel is promoted. */
        const val DEFAULT_CHANNEL = "dev"
        private const val CHECK_INTERVAL = 15L * 60 * 1000
    }
}
