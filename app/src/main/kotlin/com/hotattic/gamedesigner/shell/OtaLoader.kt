package com.hotattic.gamedesigner.shell

import com.hotattic.gamedesigner.applayer.AppLayerEntry
import com.hotattic.gamedesigner.otakit.LayerSource
import com.hotattic.gamedesigner.otakit.Selection
import com.hotattic.gamedesigner.shellapi.AppLayer
import dalvik.system.DexClassLoader

/**
 * Loads our own classes from the verified bundle first, and everything else (Compose, AndroidX, Kotlin, the shell API,
 * the OTA kit) from the shell. The shell API must always come from the shell so both sides share one interface identity.
 */
class ChildFirstDexClassLoader(dexPath: String, parent: ClassLoader) : DexClassLoader(dexPath, null, null, parent) {
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        synchronized(this) {
            findLoadedClass(name)?.let { return it }
            if (name.startsWith(OWN_PREFIX) && SHELL_PREFIXES.none { name.startsWith(it) }) {
                try {
                    val c = findClass(name)
                    if (resolve) resolveClass(c)
                    return c
                } catch (_: ClassNotFoundException) { /* not in the bundle: fall through to the shell */ }
            }
            return super.loadClass(name, resolve)
        }
    }

    private companion object {
        const val OWN_PREFIX = "com.hotattic.gamedesigner."
        val SHELL_PREFIXES = listOf("com.hotattic.gamedesigner.shellapi.", "com.hotattic.gamedesigner.shell.", "com.hotattic.gamedesigner.otakit.")
    }
}

object OtaLoader {
    /** Never throws: any failure falls back to the bundled layer compiled into this APK. */
    fun load(parent: ClassLoader, selection: Selection, ota: OtaManager): AppLayer {
        if (selection.source == LayerSource.OTA) {
            try {
                val cl = ChildFirstDexClassLoader(selection.bundlePath!!, parent)
                val layer = cl.loadClass(selection.entryClass!!).getDeclaredConstructor().newInstance() as AppLayer
                ota.layerLoaded(selection)
                return layer
            } catch (t: Throwable) {
                ota.layerLoadFailed(selection, "${t.javaClass.simpleName}: ${t.message}")
            }
        }
        ota.layerLoaded(null)
        return AppLayerEntry()
    }
}
