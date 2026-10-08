package com.hotattic.gamedesigner.otakit

import java.io.File
import kotlin.system.exitProcess

/** Build-time/CI tooling. Usage: see docs/OTA.md. The signing key is read from a file and never printed. */
private fun args(a: Array<String>, from: Int): Map<String, String> =
    a.drop(from).chunked(2).associate { (k, v) -> k.removePrefix("--") to v }

private fun need(m: Map<String, String>, k: String) = m[k] ?: error("missing --$k")

fun main(a: Array<String>) {
    if (a.isEmpty()) { System.err.println("commands: genkey | make-manifest | verify-channel | live-proof"); exitProcess(2) }
    try {
        when (a[0]) {
            "genkey" -> {
                val dir = File(a[1]).also { it.mkdirs() }
                val kp = Crypto.generateKeyPair()
                File(dir, "ota-public.b64").writeText(kp.publicX509)
                File(dir, "ota-private.b64").writeText(kp.privatePkcs8)
                println("generated key pair in ${dir.path} (private key not printed)")
            }
            "make-manifest" -> {
                val m = args(a, 1)
                val bundle = File(need(m, "bundle"))
                val out = File(need(m, "out")).also { it.mkdirs() }
                val manifest = OtaManifest(
                    channel = need(m, "channel"), bundleVersion = need(m, "version").toInt(), versionName = need(m, "name"),
                    sourceSha = m["source-sha"].orEmpty(), createdAt = java.time.Instant.now().toString(),
                    shellApiLevel = need(m, "api").toInt(), runtimeFingerprint = need(m, "fingerprint"),
                    minShellVersionCode = m["min-shell"]?.toInt() ?: 1, entryClass = need(m, "entry"),
                    bundle = BundleFile("bundle.zip", Crypto.sha256Hex(bundle), bundle.length()),
                )
                val bytes = OtaJson.encodeToString(OtaManifest.serializer(), manifest).toByteArray(Charsets.UTF_8)
                File(out, "manifest.json").writeBytes(bytes)
                File(out, "manifest.sig").writeText(Crypto.sign(bytes, File(need(m, "private-key")).readText()))
                bundle.copyTo(File(out, "bundle.zip"), overwrite = true)
                println("manifest v${manifest.bundleVersion} '${manifest.versionName}' sha256=${manifest.bundle.sha256} size=${manifest.bundle.size}")
            }
            "verify-channel" -> {
                val m = args(a, 1)
                val dir = File(need(m, "dir"))
                val bytes = File(dir, "manifest.json").readBytes()
                val ok = Crypto.verifyAny(bytes, File(dir, "manifest.sig").readText(), listOf(File(need(m, "public-key")).readText()))
                check(ok) { "manifest signature INVALID" }
                val mf = OtaJson.decodeFromString(OtaManifest.serializer(), String(bytes))
                check(mf.shellApiLevel == need(m, "api").toInt()) { "shell API mismatch" }
                check(mf.runtimeFingerprint == need(m, "fingerprint")) { "runtime fingerprint mismatch" }
                check(Crypto.sha256Hex(File(dir, "bundle.zip")) == mf.bundle.sha256) { "bundle hash mismatch" }
                println("channel dir OK: v${mf.bundleVersion} ${mf.versionName} signature valid, hash valid, runtime compatible")
            }
            "live-proof" -> exitProcess(if (LiveProof.run(args(a, 1))) 0 else 1)
            else -> { System.err.println("unknown command ${a[0]}"); exitProcess(2) }
        }
    } catch (e: Exception) { System.err.println("ERROR: ${e.message}"); exitProcess(1) }
}

/** Full lifecycle against a real published channel, in a scratch directory, with restart simulation and rollback. */
object LiveProof {
    fun run(m: Map<String, String>): Boolean {
        val work = File(need(m, "workdir")).also { it.deleteRecursively(); it.mkdirs() }
        val shell = ShellIdentity(need(m, "shell-code").toInt(), "proof", need(m, "api").toInt(), need(m, "fingerprint"), need(m, "bundled-version").toInt(), "bundled")
        val key = File(need(m, "public-key")).readText()
        val channel = need(m, "channel")
        var ok = true
        fun step(name: String, pass: Boolean, detail: String = "") { println("${if (pass) "PASS" else "FAIL"}  $name ${detail}"); if (!pass) ok = false }
        val store = OtaStore(File(work, "ota"))
        val updater = OtaUpdater(store, shell, listOf(key), JavaFetcher(), need(m, "base-url"))

        val r1 = updater.check(channel)
        step("discover + download + verify + stage", r1 is CheckResult.Staged, r1.summary)
        val staged = (r1 as? CheckResult.Staged)?.manifest ?: return false
        step("running layer untouched by staging", store.readState().first.let { it.active == null && it.trial == null && it.pending == staged.bundleVersion })

        val sel1 = OtaSelector(store, shell).select()
        step("restart #1 activates staged bundle as trial", sel1.source == LayerSource.OTA && sel1.version == staged.bundleVersion, sel1.note)
        step("bundle file is read-only (Android 14+ requirement)", !File(sel1.bundlePath!!).canWrite() || System.getProperty("user.name") == "root")
        OtaSelector(store, shell).markHealthy()
        val sel2 = OtaSelector(store, shell).select()
        step("restart #2 keeps the committed OTA layer", sel2.source == LayerSource.OTA && sel2.version == staged.bundleVersion)

        val again = updater.check(channel)
        step("re-check does not loop or re-download", again is CheckResult.UpToDate, again.summary)

        // Rollback: corrupt the committed bundle, as disk corruption or tampering would.
        val f = store.bundleFile(staged.bundleVersion); f.setWritable(true); f.appendBytes(byteArrayOf(0))
        val sel3 = OtaSelector(store, shell).select()
        step("corrupted bundle falls back to bundled layer", sel3.source == LayerSource.BUNDLED, sel3.note)
        val st = store.readState().first
        step("corrupted bundle is blacklisted", st.bad.any { it.version == staged.bundleVersion })
        return ok
    }
}
