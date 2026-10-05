package com.hotattic.gamedesigner.shell

import android.app.Application
import com.hotattic.gamedesigner.BuildConfig
import com.hotattic.gamedesigner.R
import com.hotattic.gamedesigner.applayer.AppLayerEntry
import com.hotattic.gamedesigner.otakit.ShellIdentity
import com.hotattic.gamedesigner.shellapi.AppLayer
import com.hotattic.gamedesigner.shellapi.LocalLlm
import com.hotattic.gamedesigner.shellapi.ModelManager
import com.hotattic.gamedesigner.shellapi.OtaControl
import com.hotattic.gamedesigner.shellapi.SecretStore
import com.hotattic.gamedesigner.shellapi.ShellServices

/** The native shell: the stable part of the app that is only ever replaced by installing a new APK. */
class GameDesignerApp : Application(), ShellServices {
    override lateinit var secrets: SecretStore
    override lateinit var models: ModelManager
    override lateinit var localLlm: LocalLlm
    lateinit var otaManager: OtaManager private set
    private var layer: AppLayer? = null

    override val application: Application get() = this
    override val splashLogoRes: Int get() = R.drawable.hot_attic_logo
    override val nativeVersionName: String get() = BuildConfig.VERSION_NAME
    override val nativeVersionCode: Int get() = BuildConfig.VERSION_CODE
    override val ota: OtaControl get() = otaManager

    override fun onCreate() {
        super.onCreate()
        secrets = SecretStore(this)
        models = ModelManager(this) { secrets.get(SecretStore.HF_TOKEN) }
        localLlm = LiteRtLocalLlm(this) { models.anyPath() }
        val identity = ShellIdentity(BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME, BuildConfig.SHELL_API_LEVEL, BuildConfig.RUNTIME_FINGERPRINT,
            AppLayerEntry.LAYER_VERSION, AppLayerEntry.LAYER_LABEL)
        otaManager = OtaManager(this, identity, readTrustedKeys())
        otaManager.start()
    }

    /** Public keys that may sign OTA manifests, baked into this APK at build time. Absent in local builds => OTA is off. */
    private fun readTrustedKeys(): List<String> = try {
        assets.open("ota/trusted_keys.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
    } catch (e: java.io.IOException) { emptyList() }

    /** The application layer for this process (OTA if a verified bundle is active, else the bundled one). */
    fun layer(): AppLayer = layer ?: OtaLoader.load(classLoader, otaManager.currentSelection(), otaManager).also { layer = it }
}
