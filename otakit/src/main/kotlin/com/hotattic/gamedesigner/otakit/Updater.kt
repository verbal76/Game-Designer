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
    /**
     * Looks at the channel's latest build. [stage] true (dev line): schedule it for the next start. [stage] false (stable line or a
     * pinned build): download and verify it into the local cache only; nothing is ever scheduled.
     */
    fun check(channel: String, stage: Boolean = true): CheckResult {
        if (channel == "off" || trustedKeysB64.isEmpty()) return finish(CheckResult.Disabled)
        if (!channel.matches(Regex("[a-z0-9-]{1,20}"))) return finish(CheckResult.Rejected("invalid channel name"))
        val dir = "$baseUrl/ota-$channel"
        return acquire(channel, dir, "manifest.json", "manifest.sig", { "bundle.zip" }, userChosen = false, stage = stage, wanted = null)
    }

    /** Downloads one specific published build (the owner picked it from the list) into the cache; never schedules it. */
    fun fetchBuild(channel: String, version: Int): CheckResult {
        if (channel == "off" || trustedKeysB64.isEmpty()) return finish(CheckResult.Disabled)
        if (!channel.matches(Regex("[a-z0-9-]{1,20}"))) return finish(CheckResult.Rejected("invalid channel name"))
        val dir = "$baseUrl/ota-$channel"
        val versioned = acquire(channel, dir, "manifest-$version.json", "manifest-$version.sig", { "bundle-$version.zip" }, userChosen = true, stage = false, wanted = version)
        if (versioned is CheckResult.Failed) {
            // Builds published before history existed are only available as the channel's latest files.
            val latest = acquire(channel, dir, "manifest.json", "manifest.sig", { "bundle.zip" }, userChosen = true, stage = false, wanted = version)
            if (latest !is CheckResult.Failed) return latest
        }
        return versioned
    }

    /** Lists the channel's recent builds that can run on THIS shell, newest first (at most [limit]). Falls back to the cached list offline. */
    fun builds(channel: String, limit: Int = 10): List<OtaIndexEntry> {
        val fetched = runCatching {
            val bytes = fetcher.getBytes("$baseUrl/ota-$channel/index.json", 512 * 1024)
            OtaJson.decodeFromString(OtaIndex.serializer(), String(bytes, Charsets.UTF_8)).builds
        }.getOrNull()
        val list = fetched ?: runCatching {
            // No index (older channel): the single latest manifest is still a valid, verifiable entry.
            val mb = fetcher.getBytes("$baseUrl/ota-$channel/manifest.json", 256 * 1024)
            val sig = String(fetcher.getBytes("$baseUrl/ota-$channel/manifest.sig", 4 * 1024), Charsets.UTF_8)
            if (!Crypto.verifyAny(mb, sig, trustedKeysB64)) return@runCatching emptyList<OtaIndexEntry>()
            val m = OtaJson.decodeFromString(OtaManifest.serializer(), String(mb, Charsets.UTF_8))
            listOf(OtaIndexEntry(m.bundleVersion, m.versionName, m.sourceSha, m.createdAt, m.shellApiLevel, m.runtimeFingerprint))
        }.getOrNull()
        val file = File(store.root, "index-$channel.json")
        if (list != null) runCatching { store.root.mkdirs(); file.writeText(OtaJson.encodeToString(OtaIndex.serializer(), OtaIndex(channel = channel, builds = list))) }
        val source = list ?: runCatching { OtaJson.decodeFromString(OtaIndex.serializer(), file.readText()).builds }.getOrDefault(emptyList())
        return source.filter { it.shellApiLevel == shell.shellApiLevel && it.runtimeFingerprint == shell.runtimeFingerprint }
            .distinctBy { it.version }.sortedByDescending { it.version }.take(limit)
    }

    /** The last list fetched for [channel], without touching the network. */
    fun cachedBuilds(channel: String, limit: Int = 10): List<OtaIndexEntry> =
        runCatching { OtaJson.decodeFromString(OtaIndex.serializer(), File(store.root, "index-$channel.json").readText()).builds }.getOrDefault(emptyList())
            .filter { it.shellApiLevel == shell.shellApiLevel && it.runtimeFingerprint == shell.runtimeFingerprint }
            .distinctBy { it.version }.sortedByDescending { it.version }.take(limit)

    private fun finish(r: CheckResult, event: String? = null): CheckResult {
        val (s, _) = store.readState()
        store.writeState(s.copy(lastCheckAt = clock(), lastCheckResult = r.summary, lastEvent = event ?: s.lastEvent))
        return r
    }

    private fun acquire(channel: String, dir: String, manifestName: String, sigName: String, bundleName: (OtaManifest) -> String, userChosen: Boolean, stage: Boolean, wanted: Int?): CheckResult {
        val (state0, _) = store.readState()
        val manifestBytes: ByteArray
        val sig: String
        try {
            manifestBytes = fetcher.getBytes("$dir/$manifestName", 256 * 1024)
            sig = String(fetcher.getBytes("$dir/$sigName", 4 * 1024), Charsets.UTF_8)
        } catch (e: Exception) { return finish(CheckResult.Failed(e.message ?: e.javaClass.simpleName)) }

        // 1. Authenticity: signature over the exact manifest bytes by a pinned key.
        if (!Crypto.verifyAny(manifestBytes, sig, trustedKeysB64)) return finish(CheckResult.Rejected("manifest signature is not valid"), "rejected: bad signature")
        val m = try { OtaJson.decodeFromString(OtaManifest.serializer(), String(manifestBytes, Charsets.UTF_8)) } catch (e: Exception) { return finish(CheckResult.Rejected("manifest is malformed"), "rejected: malformed manifest") }
        if (wanted != null && m.bundleVersion != wanted) return finish(CheckResult.Failed("that build is not available on the channel any more"))

        // 2. Compatibility gate: a bundle can only run on the shell generation it was built for.
        validate(m, channel, state0, userChosen)?.let { reason -> return finish(if (reason == UP_TO_DATE) CheckResult.UpToDate else CheckResult.Rejected(reason), if (reason == UP_TO_DATE) null else "rejected v${m.bundleVersion}: $reason") }

        // Already downloaded and intact: no second download.
        if (m.bundleVersion in store.installedVersions() && intact(m)) {
            val (cur, _) = store.readState()
            if (stage && cur.pinned == null && cur.pending != m.bundleVersion && cur.active != m.bundleVersion && cur.trial != m.bundleVersion && m.bundleVersion > maxOf(cur.active ?: 0, shell.bundledLayerVersion)) {
                store.writeState(cur.copy(pending = m.bundleVersion, lastCheckAt = clock(), lastCheckResult = CheckResult.Staged(m).summary, lastEvent = "staged v${m.bundleVersion}"))
                return CheckResult.Staged(m)
            }
            return finish(if (userChosen) CheckResult.Cached(m) else CheckResult.UpToDate)
        }

        // 3. Download into staging, hash while writing, never trust until verified.
        val part = File(store.staging, "${m.bundleVersion}.part")
        try {
            fetcher.download("$dir/${bundleName(m)}", part, minOf(m.bundle.size, maxBundleBytes))
        } catch (e: Exception) { part.delete(); return finish(CheckResult.Failed(e.message ?: e.javaClass.simpleName)) }
        if (part.length() != m.bundle.size) { part.delete(); return finish(CheckResult.Rejected("downloaded size does not match the manifest"), "rejected v${m.bundleVersion}: size mismatch") }
        if (Crypto.sha256Hex(part) != m.bundle.sha256.lowercase()) { part.delete(); return finish(CheckResult.Rejected("bundle hash does not match the signed manifest"), "rejected v${m.bundleVersion}: hash mismatch") }

        // 4. Install atomically into the cache; the running layer is untouched.
        store.install(m.bundleVersion, part, manifestBytes)
        val (cur, _) = store.readState()
        // Only the dev line schedules automatically, and never while an owner-pinned build is set.
        val schedule = stage && !userChosen && cur.pinned == null
        val next = if (schedule) cur.copy(pending = m.bundleVersion, lastCheckAt = clock(), lastCheckResult = CheckResult.Staged(m).summary, lastEvent = "staged v${m.bundleVersion}")
        else cur.copy(lastCheckAt = clock(), lastCheckResult = CheckResult.Cached(m).summary, lastEvent = "downloaded v${m.bundleVersion}")
        store.writeState(next)
        store.prune(setOfNotNull(next.active, next.trial, next.pending, next.pinned, m.bundleVersion), CACHE_LIMIT)
        return if (schedule) CheckResult.Staged(m) else CheckResult.Cached(m)
    }

    private fun intact(m: OtaManifest): Boolean = runCatching {
        val f = store.bundleFile(m.bundleVersion)
        f.exists() && f.length() == m.bundle.size && Crypto.sha256Hex(f) == m.bundle.sha256.lowercase()
    }.getOrDefault(false)

    /** Returns null if acceptable, else the reason. */
    internal fun validate(m: OtaManifest, channel: String, s: OtaState, userChosen: Boolean = false): String? {
        if (m.schema != 1) return "unsupported manifest schema ${m.schema}"
        if (m.channel != channel) return "manifest is for channel '${m.channel}', not '$channel'"
        if (m.shellApiLevel != shell.shellApiLevel) return "built for shell API ${m.shellApiLevel}, this app has ${shell.shellApiLevel} (needs a new APK)"
        if (m.runtimeFingerprint != shell.runtimeFingerprint) return "built for a different runtime generation (needs a new APK)"
        if (shell.versionCode < m.minShellVersionCode) return "requires app build ${m.minShellVersionCode} or newer"
        if (m.bundle.size <= 0 || m.bundle.size > maxBundleBytes) return "bundle size out of range"
        if (!m.bundle.sha256.matches(Regex("[0-9a-fA-F]{64}"))) return "bundle hash is malformed"
        if (!m.entryClass.matches(Regex("[A-Za-z0-9_.]+"))) return "entry class is malformed"
        if (m.bundle.name != "bundle.zip") return "unexpected bundle file name"
        // Anti-rollback guards AUTOMATIC updates. A build the owner deliberately picked from the published list may be older.
        if (!userChosen) {
            val newest = listOfNotNull(s.active, s.pending, s.trial).plus(shell.bundledLayerVersion).plus(s.highWater).max()
            if (m.bundleVersion <= newest) return UP_TO_DATE
        }
        if (s.bad.any { it.version == m.bundleVersion && it.sha256.equals(m.bundle.sha256, true) }) return "this exact bundle previously failed on this device"
        return null
    }

    companion object { const val UP_TO_DATE = "__up_to_date__"; const val CACHE_LIMIT = 10 }
}
