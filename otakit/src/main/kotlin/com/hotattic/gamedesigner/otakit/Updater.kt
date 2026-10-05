package com.hotattic.gamedesigner.otakit

import java.io.File
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Network boundary, faked in tests. */
interface Fetcher {
    /** Fetches a small resource fully (throws if larger than [maxBytes] or on a non-200 status). */
    fun getBytes(url: String, maxBytes: Long): ByteArray
    /** Streams a resource into [dest], aborting if it exceeds [maxBytes]. */
    fun download(url: String, dest: File, maxBytes: Long)
}

class FetchException(message: String) : RuntimeException(message)

class JavaFetcher(private val userAgent: String = "GameDesigner-OTA/1", private val timeoutMs: Int = 20_000) : Fetcher {
    private fun open(url: String): HttpURLConnection {
        require(url.startsWith("https://")) { "OTA only uses https" }
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs * 3
        c.setRequestProperty("User-Agent", userAgent)
        c.instanceFollowRedirects = true
        return c
    }
    override fun getBytes(url: String, maxBytes: Long): ByteArray {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw FetchException("HTTP ${c.responseCode} for ${url.substringAfterLast('/')}")
            return readCapped(c.inputStream, maxBytes)
        } finally { c.disconnect() }
    }
    override fun download(url: String, dest: File, maxBytes: Long) {
        val c = open(url)
        try {
            if (c.responseCode != 200) throw FetchException("HTTP ${c.responseCode} for ${url.substringAfterLast('/')}")
            dest.outputStream().use { out ->
                var total = 0L
                val buf = ByteArray(64 * 1024)
                c.inputStream.use { i -> while (true) { val n = i.read(buf); if (n < 0) break; total += n; if (total > maxBytes) throw FetchException("download exceeds the size limit"); out.write(buf, 0, n) } }
            }
        } finally { c.disconnect() }
    }
    private fun readCapped(i: InputStream, max: Long): ByteArray {
        val bos = java.io.ByteArrayOutputStream(); val buf = ByteArray(8192); var total = 0L
        while (true) { val n = i.read(buf); if (n < 0) break; total += n; if (total > max) throw FetchException("response too large"); bos.write(buf, 0, n) }
        return bos.toByteArray()
    }
}

/**
 * Discover -> verify manifest signature -> compatibility gate -> download -> verify hash -> stage as pending.
 * Never touches the layer that is currently running; activation happens only at the next cold start ([OtaSelector]).
 */
class OtaUpdater(
    private val store: OtaStore,
    private val shell: ShellIdentity,
    private val trustedKeysB64: List<String>,
    private val fetcher: Fetcher,
    /** e.g. https://github.com/OWNER/REPO/releases/download */
    private val baseUrl: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxBundleBytes: Long = 40L * 1024 * 1024,
) {
    fun check(channel: String): CheckResult {
        val (state0, _) = store.readState()
        fun finish(r: CheckResult, event: String? = null): CheckResult {
            val (s, _) = store.readState()
            store.writeState(s.copy(lastCheckAt = clock(), lastCheckResult = r.summary, lastEvent = event ?: s.lastEvent))
            return r
        }
        if (channel == "off" || trustedKeysB64.isEmpty()) return finish(CheckResult.Disabled)
        if (!channel.matches(Regex("[a-z0-9-]{1,20}"))) return finish(CheckResult.Rejected("invalid channel name"))
        val dir = "$baseUrl/ota-$channel"
        val manifestBytes: ByteArray
        val sig: String
        try {
            manifestBytes = fetcher.getBytes("$dir/manifest.json", 256 * 1024)
            sig = String(fetcher.getBytes("$dir/manifest.sig", 4 * 1024), Charsets.UTF_8)
        } catch (e: Exception) { return finish(CheckResult.Failed(e.message ?: e.javaClass.simpleName)) }

        // 1. Authenticity: signature over the exact manifest bytes by a pinned key.
        if (!Crypto.verifyAny(manifestBytes, sig, trustedKeysB64)) return finish(CheckResult.Rejected("manifest signature is not valid"), "rejected: bad signature")
        val m = try { OtaJson.decodeFromString(OtaManifest.serializer(), String(manifestBytes, Charsets.UTF_8)) } catch (e: Exception) { return finish(CheckResult.Rejected("manifest is malformed"), "rejected: malformed manifest") }

        // 2. Compatibility gate: a bundle can only run on the shell generation it was built for.
        validate(m, channel, state0)?.let { reason -> return finish(if (reason == UP_TO_DATE) CheckResult.UpToDate else CheckResult.Rejected(reason), if (reason == UP_TO_DATE) null else "rejected v${m.bundleVersion}: $reason") }

        // 3. Download into staging, hash while writing, never trust until verified.
        val part = File(store.staging, "${m.bundleVersion}.part")
        try {
            fetcher.download("$dir/${m.bundle.name}", part, minOf(m.bundle.size, maxBundleBytes))
        } catch (e: Exception) { part.delete(); return finish(CheckResult.Failed(e.message ?: e.javaClass.simpleName)) }
        if (part.length() != m.bundle.size) { part.delete(); return finish(CheckResult.Rejected("downloaded size does not match the manifest"), "rejected v${m.bundleVersion}: size mismatch") }
        if (Crypto.sha256Hex(part) != m.bundle.sha256.lowercase()) { part.delete(); return finish(CheckResult.Rejected("bundle hash does not match the signed manifest"), "rejected v${m.bundleVersion}: hash mismatch") }

        // 4. Stage atomically; the running layer is untouched.
        store.install(m.bundleVersion, part, manifestBytes)
        val (cur, _) = store.readState()
        val old = cur.pending
        store.writeState(cur.copy(pending = m.bundleVersion, lastCheckAt = clock(), lastCheckResult = CheckResult.Staged(m).summary, lastEvent = "staged v${m.bundleVersion}"))
        store.cleanup(setOfNotNull(cur.active, cur.trial, m.bundleVersion).also { if (old != null && old != m.bundleVersion) store.delete(old) })
        return CheckResult.Staged(m)
    }

    /** Returns null if acceptable, else the reason. */
    internal fun validate(m: OtaManifest, channel: String, s: OtaState): String? {
        if (m.schema != 1) return "unsupported manifest schema ${m.schema}"
        if (m.channel != channel) return "manifest is for channel '${m.channel}', not '$channel'"
        if (m.shellApiLevel != shell.shellApiLevel) return "built for shell API ${m.shellApiLevel}, this app has ${shell.shellApiLevel} (needs a new APK)"
        if (m.runtimeFingerprint != shell.runtimeFingerprint) return "built for a different runtime generation (needs a new APK)"
        if (shell.versionCode < m.minShellVersionCode) return "requires app build ${m.minShellVersionCode} or newer"
        if (m.bundle.size <= 0 || m.bundle.size > maxBundleBytes) return "bundle size out of range"
        if (!m.bundle.sha256.matches(Regex("[0-9a-fA-F]{64}"))) return "bundle hash is malformed"
        if (!m.entryClass.matches(Regex("[A-Za-z0-9_.]+"))) return "entry class is malformed"
        if (m.bundle.name != "bundle.zip") return "unexpected bundle file name"
        val newest = listOfNotNull(s.active, s.pending, s.trial).plus(shell.bundledLayerVersion).plus(s.highWater).max()
        if (m.bundleVersion <= newest) return UP_TO_DATE
        if (s.bad.any { it.version == m.bundleVersion && it.sha256.equals(m.bundle.sha256, true) }) return "this exact bundle previously failed on this device"
        return null
    }

    companion object { const val UP_TO_DATE = "__up_to_date__" }
}
