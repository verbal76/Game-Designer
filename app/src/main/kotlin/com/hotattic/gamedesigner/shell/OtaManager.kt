package com.hotattic.gamedesigner.shell

import android.app.Application
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
import com.hotattic.gamedesigner.shellapi.OtaControl
import com.hotattic.gamedesigner.shellapi.OtaDiagnostics
import java.io.File
import java.util.concurrent.Executors

/**
 * Native-shell side of OTA: decides which layer runs, checks for updates in the background (never at launch, never
 * more than every six hours automatically), and records crashes so a bad update rolls itself back.
 * Updates are only ever activated at the next cold start, so there is no mid-session swap and no restart loop.
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

    /** Opportunistic check, run on a background thread after the UI is up. */
    fun autoCheckIfDue() {
        if (channel() == "off" || trustedKeys.isEmpty()) return
        val last = store.readState().first.lastCheckAt
        if (System.currentTimeMillis() - last < SIX_HOURS) return
        exec.execute {
            Thread.sleep(20_000) // let the first screen settle; also keeps this clear of the health commit
            runCatching { synchronized(lock) { updater.check(channel()) } }
        }
    }

    override fun checkNow(onResult: (String) -> Unit) {
        exec.execute {
            val msg = try { synchronized(lock) { updater.check(channel()).summary } } catch (t: Throwable) { "Update check failed: ${t.message}" }
            main.post { onResult(msg) }
        }
    }

    override fun markHealthy() {
        healthy = true
        exec.execute { runCatching { synchronized(lock) { selector.markHealthy() } } }
    }

    override fun setChannel(channel: String) {
        if (channel in setOf("off", "dev", "stable")) prefs.edit().putString("channel", channel).apply()
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
        )
    }

    companion object {
        /** This build is a physical-test build; production would default to "stable" once a stable channel is promoted. */
        const val DEFAULT_CHANNEL = "dev"
        private const val SIX_HOURS = 6L * 60 * 60 * 1000
    }
}
