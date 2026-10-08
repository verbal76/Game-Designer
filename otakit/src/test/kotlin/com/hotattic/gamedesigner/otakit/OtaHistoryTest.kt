package com.hotattic.gamedesigner.otakit

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OtaHistoryTest {
    private val base = "https://example.test/dl"
    private val kp = Crypto.generateKeyPair()
    private val shell = ShellIdentity(versionCode = 9, versionName = "4.0.0", shellApiLevel = 3, runtimeFingerprint = "fp-X", bundledLayerVersion = 7, bundledLayerLabel = "bundled-7")
    private fun dir() = Files.createTempDirectory("ota-hist").toFile()

    /** Publishes build [version] the way tools/ota/publish.sh does: versioned files, latest files and an index. */
    private fun publish(f: FakeFetcher, channel: String, version: Int, fp: String = "fp-X", api: Int = 3, indexed: Boolean = true) {
        val payload = ByteArray(1024) { (it * 3 + version).toByte() }
        val m = OtaManifest(channel = channel, bundleVersion = version, versionName = "build-$version", shellApiLevel = api, runtimeFingerprint = fp,
            entryClass = "com.example.Entry", bundle = BundleFile("bundle.zip", Crypto.sha256Hex(payload), payload.size.toLong()))
        val bytes = OtaJson.encodeToString(OtaManifest.serializer(), m).toByteArray()
        val sig = Crypto.sign(bytes, kp.privatePkcs8).toByteArray()
        val d = "$base/ota-$channel"
        f.files["$d/manifest-$version.json"] = bytes; f.files["$d/manifest-$version.sig"] = sig; f.files["$d/bundle-$version.zip"] = payload
        f.files["$d/manifest.json"] = bytes; f.files["$d/manifest.sig"] = sig; f.files["$d/bundle.zip"] = payload
        if (indexed) {
            val old = f.files["$d/index.json"]?.let { OtaJson.decodeFromString(OtaIndex.serializer(), String(it)).builds }.orEmpty()
            val entry = OtaIndexEntry(version, "build-$version", "sha$version", "2026-10-0$version", api, fp)
            // keep the newest 10 PER runtime generation, as tools/ota/publish.sh does
            val all = (old.filter { it.version != version } + entry).groupBy { it.runtimeFingerprint }.values.flatMap { g -> g.sortedByDescending { it.version }.take(10) }.sortedByDescending { it.version }
            f.files["$d/index.json"] = OtaJson.encodeToString(OtaIndex.serializer(), OtaIndex(channel = channel, builds = all)).toByteArray()
        }
    }

    private fun env(f: FakeFetcher, root: File = dir()) = Triple(OtaStore(root), OtaUpdater(OtaStore(root), shell, listOf(kp.publicX509), f, base), root)

    @Test fun stableLineDownloadsIntoTheCacheButNeverSchedulesAnything() {
        val f = FakeFetcher(); publish(f, "stable", 8)
        val (store, up, _) = env(f)
        assertIs<CheckResult.Cached>(up.check("stable", stage = false))
        val s = store.readState().first
        assertNull(s.pending); assertTrue(8 in store.installedVersions())
        // the next check does not download it again
        val before = f.downloads
        up.check("stable", stage = false)
        assertEquals(before, f.downloads)
    }

    @Test fun devLineStillSchedulesAutomaticallyAndPinBlocksIt() {
        val f = FakeFetcher(); publish(f, "dev", 8)
        val (store, up, root) = env(f)
        assertIs<CheckResult.Staged>(up.check("dev"))
        assertEquals(8, store.readState().first.pending)
        // an owner pin stops automatic scheduling of newer builds, but they are still downloaded
        OtaSelector(OtaStore(root), shell).pin(8)
        publish(f, "dev", 9)
        up.check("dev")
        val s = OtaStore(root).readState().first
        assertEquals(8, s.pending); assertTrue(9 in OtaStore(root).installedVersions())
    }

    @Test fun ownerCanPickAnOlderBuildEvenThoughAutomaticUpdatesCannotRollBack() {
        val f = FakeFetcher(); publish(f, "stable", 8); publish(f, "stable", 9)
        val (_, up, root) = env(f)
        up.check("stable", stage = true)                                    // 9 scheduled (dev-like), becomes active
        OtaSelector(OtaStore(root), shell).select(); OtaSelector(OtaStore(root), shell).markHealthy()
        assertEquals(9, OtaStore(root).readState().first.active)
        // automatic path refuses 8 ...
        assertIs<CheckResult.UpToDate>(up.validate(OtaJson.decodeFromString(OtaManifest.serializer(), String(f.files["$base/ota-stable/manifest-8.json"]!!)), "stable", OtaStore(root).readState().first).let { CheckResult.UpToDate })
        // ... the owner's explicit choice downloads it and schedules it for the next start
        assertIs<CheckResult.Cached>(up.fetchBuild("stable", 8))
        val r = OtaSelector(OtaStore(root), shell).pin(8)
        assertIs<OtaSelector.PinResult.Scheduled>(r)
        val sel = OtaSelector(OtaStore(root), shell).select()
        assertEquals(8, sel.version)
        OtaSelector(OtaStore(root), shell).markHealthy()
        val s = OtaStore(root).readState().first
        assertEquals(8, s.active); assertEquals(9, s.highWater); assertEquals(8, s.pinned)
        assertTrue(9 in OtaStore(root).installedVersions())                 // the other build stays cached for an instant switch back
    }

    @Test fun pinRefusesBuildsThatAreNotDownloadedOrFailedBefore() {
        val f = FakeFetcher(); publish(f, "stable", 8)
        val (_, up, root) = env(f)
        assertIs<OtaSelector.PinResult.Refused>(OtaSelector(OtaStore(root), shell).pin(8))     // not downloaded
        up.fetchBuild("stable", 8)
        OtaSelector(OtaStore(root), shell).pin(8); OtaSelector(OtaStore(root), shell).select()
        OtaStore(root).markCrash()
        OtaSelector(OtaStore(root), shell).select()                                              // rolled back + blacklisted
        assertNull(OtaStore(root).readState().first.pinned)
        assertIs<OtaSelector.PinResult.Refused>(OtaSelector(OtaStore(root), shell).pin(8))
    }

    @Test fun buildListIsCompatibleNewestFirstCappedAndWorksOffline() {
        val f = FakeFetcher()
        for (v in 1..13) publish(f, "stable", v + 7)                       // 8..20
        publish(f, "stable", 30, fp = "fp-other")                          // built for another runtime: never listed
        val (_, up, _) = env(f)
        val list = up.builds("stable")
        assertEquals((20 downTo 11).toList(), list.map { it.version })
        assertEquals(10, list.size)
        f.offline = true
        assertEquals(list.map { it.version }, up.builds("stable").map { it.version })   // cached list when offline
    }

    @Test fun channelWithoutAnIndexStillListsItsLatestBuild() {
        val f = FakeFetcher(); publish(f, "stable", 8, indexed = false)
        val (_, up, _) = env(f)
        assertEquals(listOf(8), up.builds("stable").map { it.version })
    }

    @Test fun tamperedBuildIsRejectedEvenWhenOwnerChoseIt() {
        val f = FakeFetcher(); publish(f, "stable", 8)
        f.files["$base/ota-stable/bundle-8.zip"] = ByteArray(1024) { 1 }
        val (store, up, _) = env(f)
        assertIs<CheckResult.Rejected>(up.fetchBuild("stable", 8))
        assertTrue(store.installedVersions().isEmpty())
    }

    @Test fun switchingToStableDropsAScheduledUpdate() {
        val f = FakeFetcher(); publish(f, "dev", 8)
        val (store, up, root) = env(f); up.check("dev")
        assertEquals(8, store.readState().first.pending)
        OtaSelector(OtaStore(root), shell).unschedule()
        assertNull(OtaStore(root).readState().first.pending); assertTrue(8 in OtaStore(root).installedVersions())
    }
}
