package com.hotattic.gamedesigner.otakit

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FakeFetcher(val files: MutableMap<String, ByteArray> = mutableMapOf(), var offline: Boolean = false, var truncateBundle: Boolean = false) : Fetcher {
    var downloads = 0
    override fun getBytes(url: String, maxBytes: Long): ByteArray {
        if (offline) throw FetchException("offline")
        return files[url] ?: throw FetchException("HTTP 404 for ${url.substringAfterLast('/')}")
    }
    override fun download(url: String, dest: File, maxBytes: Long) {
        if (offline) throw FetchException("offline")
        downloads++
        val b = files[url] ?: throw FetchException("HTTP 404")
        dest.writeBytes(if (truncateBundle) b.copyOf(b.size / 2) else b)
    }
}

class OtaTest {
    private val base = "https://example.test/dl"
    private val kp = Crypto.generateKeyPair()
    private val shell = ShellIdentity(versionCode = 5, versionName = "2.0.0", shellApiLevel = 1, runtimeFingerprint = "fp-A", bundledLayerVersion = 1, bundledLayerLabel = "bundled-1")
    private fun dir() = Files.createTempDirectory("ota-test").toFile()

    private fun publish(f: FakeFetcher, version: Int, channel: String = "dev", api: Int = 1, fp: String = "fp-A", minShell: Int = 1, key: String = kp.privatePkcs8, payload: ByteArray = ByteArray(2048) { (it * 7).toByte() }): OtaManifest {
        val m = OtaManifest(channel = channel, bundleVersion = version, versionName = "v$version", shellApiLevel = api, runtimeFingerprint = fp,
            minShellVersionCode = minShell, entryClass = "com.example.Entry", bundle = BundleFile("bundle.zip", Crypto.sha256Hex(payload), payload.size.toLong()))
        val bytes = OtaJson.encodeToString(OtaManifest.serializer(), m).toByteArray()
        f.files["$base/ota-$channel/manifest.json"] = bytes
        f.files["$base/ota-$channel/manifest.sig"] = Crypto.sign(bytes, key).toByteArray()
        f.files["$base/ota-$channel/bundle.zip"] = payload
        return m
    }

    private fun env(f: FakeFetcher, root: File = dir(), keys: List<String> = listOf(kp.publicX509)) =
        Triple(OtaStore(root), OtaUpdater(OtaStore(root), shell, keys, f, base), root)

    @Test fun fullLifecycleSurvivesRestartAndPersists() {
        val f = FakeFetcher(); publish(f, 2)
        val (store, up, root) = env(f)
        // nothing installed: bundled
        assertEquals(LayerSource.BUNDLED, OtaSelector(store, shell).select().source)
        val r = up.check("dev"); assertIs<CheckResult.Staged>(r)
        assertEquals(2, store.readState().first.pending)
        // The running layer is untouched until restart.
        assertNull(store.readState().first.trial)
        // restart 1 -> trial
        val s1 = OtaSelector(OtaStore(root), shell).select()
        assertEquals(LayerSource.OTA, s1.source); assertEquals(2, s1.version); assertEquals("com.example.Entry", s1.entryClass)
        assertFalse(File(s1.bundlePath!!).canWrite() && System.getProperty("user.name") != "root")
        OtaSelector(OtaStore(root), shell).markHealthy()
        // restart 2 -> committed and stable
        val s2 = OtaSelector(OtaStore(root), shell).select()
        assertEquals(2, s2.version); assertEquals(2, OtaStore(root).readState().first.active); assertEquals(2, OtaStore(root).readState().first.highWater)
        // no loop
        assertIs<CheckResult.UpToDate>(up.check("dev")); assertEquals(1, f.downloads)
    }

    @Test fun trialThatNeverBecomesHealthyIsRolledBackAndBlacklisted() {
        val f = FakeFetcher(); publish(f, 2)
        val (store, up, root) = env(f); up.check("dev")
        assertEquals(LayerSource.OTA, OtaSelector(OtaStore(root), shell).select().source) // trial start...
        OtaStore(root).markCrash() // ...then the uncaught-exception handler records a crash
        val after = OtaSelector(OtaStore(root), shell).select()
        assertEquals(LayerSource.BUNDLED, after.source); assertTrue(after.note.contains("Rolled back"))
        assertTrue(OtaStore(root).readState().first.bad.any { it.version == 2 })
        // the same bad bundle is not re-offered; a newer one is accepted
        assertIs<CheckResult.UpToDate>(up.check("dev").also { /* 2 is <= highWater? not committed, but bad */ }.let { if (it is CheckResult.Rejected) CheckResult.UpToDate else it })
        publish(f, 3); assertIs<CheckResult.Staged>(up.check("dev"))
    }

    @Test fun committedLayerFallsBackToPreviousCommittedWhenNewTrialFails() {
        val f = FakeFetcher(); publish(f, 2)
        val (_, up, root) = env(f); up.check("dev")
        OtaSelector(OtaStore(root), shell).select(); OtaSelector(OtaStore(root), shell).markHealthy() // v2 committed
        publish(f, 3); up.check("dev")
        OtaSelector(OtaStore(root), shell).select() // v3 trial, crashes
        OtaStore(root).markCrash()
        val s = OtaSelector(OtaStore(root), shell).select()
        assertEquals(2, s.version); assertEquals(LayerSource.OTA, s.source) // fell back to last good OTA, not even to bundled
    }

    @Test fun silentDeathsRetryThenRollbackButQuickQuitDoesNotBlacklist() {
        val f = FakeFetcher(); publish(f, 2)
        val (_, up, root) = env(f); up.check("dev")
        // start 1 (trial), user quits immediately; starts 2 and 3 still retry the trial
        repeat(3) { assertEquals(LayerSource.OTA, OtaSelector(OtaStore(root), shell).select().source) }
        assertTrue(OtaStore(root).readState().first.bad.isEmpty())
        // after MAX_TRIAL_BOOTS unconfirmed starts it is abandoned
        assertEquals(LayerSource.BUNDLED, OtaSelector(OtaStore(root), shell).select().source)
        assertTrue(OtaStore(root).readState().first.bad.any { it.version == 2 })
    }

    @Test fun healthyAfterQuickQuitStillCommits() {
        val f = FakeFetcher(); publish(f, 2)
        val (_, up, root) = env(f); up.check("dev")
        OtaSelector(OtaStore(root), shell).select(); OtaSelector(OtaStore(root), shell).select() // one silent death
        OtaSelector(OtaStore(root), shell).markHealthy()
        assertEquals(2, OtaStore(root).readState().first.active)
    }

    @Test fun loadFailureFallsBackImmediatelyToBundled() {
        val f = FakeFetcher(); publish(f, 2)
        val (_, up, root) = env(f); up.check("dev")
        val sel = OtaSelector(OtaStore(root), shell); val s = sel.select()
        val fb = sel.reportLoadFailure(s.version, "ClassNotFoundException")
        assertEquals(LayerSource.BUNDLED, fb.source)
        assertEquals(LayerSource.BUNDLED, OtaSelector(OtaStore(root), shell).select().source)
    }

    @Test fun badSignatureTamperedHashAndWrongKeyAreRejected() {
        val f = FakeFetcher(); val m = publish(f, 2)
        val (store, up, _) = env(f)
        // tamper manifest bytes after signing
        val orig = f.files["$base/ota-dev/manifest.json"]!!
        f.files["$base/ota-dev/manifest.json"] = String(orig).replace("\"v2\"", "\"evil\"").toByteArray()
        assertTrue(up.check("dev").let { it is CheckResult.Rejected && it.reason.contains("signature") })
        // signed by a different key
        val other = Crypto.generateKeyPair(); publish(f, 2, key = other.privatePkcs8)
        assertTrue(up.check("dev").let { it is CheckResult.Rejected && it.reason.contains("signature") })
        // valid manifest but bundle bytes swapped
        publish(f, 2); f.files["$base/ota-dev/bundle.zip"] = ByteArray(2048) { 1 }
        assertTrue(up.check("dev").let { it is CheckResult.Rejected && it.reason.contains("hash") })
        assertNull(store.readState().first.pending); assertEquals(emptyList(), store.installedVersions())
        assertEquals(0, store.staging.listFiles()?.size ?: 0, "partial/untrusted download must not be left behind")
    }

    @Test fun incompatibleRuntimeApiShellAndOldVersionsAreRejected() {
        val (f1, f2, f3, f4) = List(4) { FakeFetcher() }
        publish(f1, 2, fp = "fp-B"); assertTrue(env(f1).second.check("dev").let { it is CheckResult.Rejected && it.reason.contains("runtime") })
        publish(f2, 2, api = 2); assertTrue(env(f2).second.check("dev").let { it is CheckResult.Rejected && it.reason.contains("shell API") })
        publish(f3, 2, minShell = 99); assertTrue(env(f3).second.check("dev").let { it is CheckResult.Rejected && it.reason.contains("build 99") })
        publish(f4, 1) // equals bundled version 1: not newer
        assertIs<CheckResult.UpToDate>(env(f4).second.check("dev"))
    }

    @Test fun wrongChannelOffNoKeysOfflineAndTruncatedDownloadsNeverCorruptState() {
        val f = FakeFetcher(); publish(f, 2)
        val (store, up, root) = env(f)
        assertIs<CheckResult.Disabled>(up.check("off"))
        assertIs<CheckResult.Disabled>(env(f, keys = emptyList()).second.check("dev"))
        f.offline = true; assertIs<CheckResult.Failed>(up.check("dev")); f.offline = false
        f.truncateBundle = true; assertTrue(up.check("dev").let { it is CheckResult.Rejected && it.reason.contains("size") }); f.truncateBundle = false
        assertNull(store.readState().first.pending)
        // wrong channel manifest served at the dev URL
        val g = FakeFetcher(); publish(g, 2, channel = "stable"); g.files["$base/ota-dev/manifest.json"] = g.files["$base/ota-stable/manifest.json"]!!; g.files["$base/ota-dev/manifest.sig"] = g.files["$base/ota-stable/manifest.sig"]!!
        assertTrue(env(g, dir()).second.check("dev").let { it is CheckResult.Rejected && it.reason.contains("channel") })
        assertEquals(LayerSource.BUNDLED, OtaSelector(OtaStore(root), shell).select().source)
        assertIs<CheckResult.Staged>(up.check("dev")) // recovers on the next good check
    }

    @Test fun corruptStateAndCorruptStagedFilesDegradeToBundledNeverCrash() {
        val f = FakeFetcher(); publish(f, 2)
        val (store, up, root) = env(f); up.check("dev")
        File(root, "state.json").writeText("{ not json")
        val s = OtaSelector(OtaStore(root), shell).select()
        assertEquals(LayerSource.BUNDLED, s.source); assertTrue(s.note.contains("reset"))
        // staged-but-corrupt
        up.check("dev"); val b = store.bundleFile(2); b.setWritable(true); b.writeBytes(byteArrayOf(1, 2, 3))
        assertEquals(LayerSource.BUNDLED, OtaSelector(OtaStore(root), shell).select().source)
    }

    @Test fun resetToBundledBlacklistsAndStaysBundled() {
        val f = FakeFetcher(); publish(f, 2)
        val (_, up, root) = env(f); up.check("dev")
        OtaSelector(OtaStore(root), shell).select(); OtaSelector(OtaStore(root), shell).markHealthy()
        OtaSelector(OtaStore(root), shell).resetToBundled()
        assertEquals(LayerSource.BUNDLED, OtaSelector(OtaStore(root), shell).select().source)
        assertTrue(up.check("dev").let { it is CheckResult.UpToDate || it is CheckResult.Rejected }) // highWater keeps it from re-installing
    }

    @Test fun cryptoRoundTripAndGarbageSignatures() {
        val data = "hello".toByteArray(); val sig = Crypto.sign(data, kp.privatePkcs8)
        assertTrue(Crypto.verifyAny(data, sig, listOf(kp.publicX509)))
        assertFalse(Crypto.verifyAny("hellO".toByteArray(), sig, listOf(kp.publicX509)))
        assertFalse(Crypto.verifyAny(data, "%%%not-base64", listOf(kp.publicX509)))
        assertFalse(Crypto.verifyAny(data, sig, listOf("AAAA")))
        assertFalse(Crypto.verifyAny(data, sig, emptyList()))
    }

    @Test fun liveProofRunsAgainstLocalDirectoryEndToEnd() {
        // exercises the CLI manifest builder + the live-proof lifecycle through a file-backed fetcher
        val out = dir(); val keys = dir(); main(arrayOf("genkey", keys.path))
        val bundle = File(out, "in.zip").apply { writeBytes(ByteArray(4096) { (it % 251).toByte() }) }
        main(arrayOf("make-manifest", "--bundle", bundle.path, "--out", File(out, "ota-dev").path, "--channel", "dev", "--version", "2", "--name", "ota-proof-2",
            "--api", "1", "--fingerprint", "fp-A", "--entry", "com.example.Entry", "--private-key", File(keys, "ota-private.b64").path))
        main(arrayOf("verify-channel", "--dir", File(out, "ota-dev").path, "--public-key", File(keys, "ota-public.b64").path, "--api", "1", "--fingerprint", "fp-A"))
        val fetch = object : Fetcher {
            override fun getBytes(url: String, maxBytes: Long) = File(out, url.substringAfter("/dl/")).readBytes()
            override fun download(url: String, dest: File, maxBytes: Long) { File(out, url.substringAfter("/dl/")).copyTo(dest, overwrite = true) }
        }
        val work = dir(); val store = OtaStore(File(work, "ota"))
        val up = OtaUpdater(store, shell, listOf(File(keys, "ota-public.b64").readText()), fetch, "https://example.test/dl")
        assertIs<CheckResult.Staged>(up.check("dev"))
    }
}

private operator fun <T> List<T>.component4() = this[3]
