package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.models.ChoiceLabel
import com.hotattic.gamedesigner.core.models.DeviceProfile
import com.hotattic.gamedesigner.core.models.InstallFailure
import com.hotattic.gamedesigner.core.models.InstallResult
import com.hotattic.gamedesigner.core.models.ModelCatalog
import com.hotattic.gamedesigner.core.models.ModelEntry
import com.hotattic.gamedesigner.core.models.ModelInstaller
import com.hotattic.gamedesigner.core.models.ModelRecommender
import com.hotattic.gamedesigner.core.models.ModelResponse
import com.hotattic.gamedesigner.core.models.ModelTransport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private class FakeHost(var data: ByteArray, var honorRange: Boolean = true, var failAfter: Int? = null, var code: Int = 200) : ModelTransport {
    val requests = mutableListOf<Pair<Long, Map<String, String>>>()
    override fun open(url: String, rangeStart: Long, headers: Map<String, String>): ModelResponse {
        requests += rangeStart to headers
        if (code != 200) return ModelResponse(code, 0, null)
        val start = if (honorRange) rangeStart.toInt() else 0
        val body = data.copyOfRange(start, data.size)
        val fail = failAfter
        val stream: InputStream = if (fail != null && fail < body.size) object : InputStream() {
            var pos = 0
            override fun read(): Int = throw IOException("connection reset")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= fail) throw IOException("connection reset")
                val n = minOf(len, fail - pos, 4096); System.arraycopy(body, pos, b, off, n); pos += n; return n
            }
        } else ByteArrayInputStream(body)
        return ModelResponse(if (honorRange && rangeStart > 0) 206 else 200, body.size.toLong(), stream)
    }
}

class ModelManagementTest {
    private val payload = ByteArray(3_000_000) { (it * 31 + 7).toByte() }
    private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }
    private val entry = ModelEntry("t", "Test", "Test model", "tiny", "litertlm", "int4", "x/y", "t.litertlm", "https://example.test/t.litertlm", payload.size.toLong(), sha(payload),
        "apache-2.0", minTotalRamMb = 1000, runtimeRamMb = 500, contextTokens = 1024, speed = 3, quality = 3)

    private fun installer(host: ModelTransport, free: Long = 10_000_000_000L, token: String? = null): Triple<ModelInstaller, File, File> {
        val md = Files.createTempDirectory("gd-models").toFile(); val pd = Files.createTempDirectory("gd-part").toFile()
        return Triple(ModelInstaller(host, md, pd, { free }, { token }), md, pd)
    }

    @Test fun installsVerifiesAndIsIdempotent() = runBlocking {
        val (inst, dir, _) = installer(FakeHost(payload))
        val r = inst.install(entry) as InstallResult.Installed
        assertFalse(r.alreadyPresent); assertTrue(inst.isInstalled(entry)); assertEquals(entry.sizeBytes, r.file.length())
        assertTrue((inst.install(entry) as InstallResult.Installed).alreadyPresent)
        // tampering is detected: size/mtime change invalidates the verification marker, full re-verification deletes it
        File(dir, "t.litertlm").appendBytes(byteArrayOf(1))
        assertFalse(inst.isInstalled(entry))
    }

    @Test fun interruptedDownloadResumesAndStillVerifies() = runBlocking {
        val host = FakeHost(payload, failAfter = 1_000_000)
        val (inst, dir, part) = installer(host)
        val first = inst.install(entry) as InstallResult.Failed
        assertEquals(InstallFailure.NETWORK, first.reason); assertTrue(first.resumable)
        val kept = inst.partialBytes(entry); assertTrue(kept in 900_000..1_100_000, "kept $kept")
        assertFalse(File(dir, "t.litertlm").exists(), "a partial file is never exposed as the model")
        host.failAfter = null
        val second = inst.install(entry) as InstallResult.Installed
        assertEquals(kept, host.requests.last().first, "the retry asked only for the missing bytes")
        assertTrue(inst.isInstalled(entry)); assertTrue(part.listFiles().orEmpty().none { it.name.endsWith(".part") })
    }

    @Test fun hashMismatchDeletesEverythingAndInstallsNothing() = runBlocking {
        val bad = payload.copyOf().also { it[1234] = (it[1234] + 1).toByte() }
        val (inst, dir, part) = installer(FakeHost(bad))
        val r = inst.install(entry) as InstallResult.Failed
        assertEquals(InstallFailure.HASH_MISMATCH, r.reason); assertFalse(r.resumable)
        assertFalse(File(dir, "t.litertlm").exists()); assertEquals(0L, inst.partialBytes(entry))
        assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".litertlm") })
    }

    @Test fun corruptPartialIsCaughtByTheWholeFileHash() = runBlocking {
        val host = FakeHost(payload)
        val (inst, dir, part) = installer(host)
        File(part, "t.litertlm.part").writeBytes(ByteArray(500_000) { 9 }) // garbage that is not a prefix of the real file
        val r = inst.install(entry) as InstallResult.Failed
        assertEquals(InstallFailure.HASH_MISMATCH, r.reason)
        assertTrue(inst.install(entry) is InstallResult.Installed, "next attempt starts clean and succeeds")
    }

    @Test fun serverIgnoringRangeRestartsCorrectly() = runBlocking {
        val host = FakeHost(payload, honorRange = false)
        val (inst, _, part) = installer(host)
        File(part, "t.litertlm.part").writeBytes(payload.copyOf(700_000))
        assertTrue(inst.install(entry) is InstallResult.Installed); assertTrue(inst.isInstalled(entry))
    }

    @Test fun storageAuthAndNotFoundAreReportedHonestly() = runBlocking {
        assertEquals(InstallFailure.STORAGE, ((installer(FakeHost(payload), free = 1_000_000).first.install(entry)) as InstallResult.Failed).reason)
        val auth = FakeHost(payload, code = 401)
        val (inst, _, _) = installer(auth, token = "hf_secret")
        assertEquals(InstallFailure.AUTH_REQUIRED, (inst.install(entry) as InstallResult.Failed).reason)
        assertEquals("Bearer hf_secret", auth.requests.last().second["Authorization"])
        assertEquals(InstallFailure.NOT_FOUND, (installer(FakeHost(payload, code = 404)).first.install(entry) as InstallResult.Failed).reason)
    }

    @Test fun cancellationKeepsProgressAndInstallsNothing() = runBlocking {
        val (inst, dir, _) = installer(FakeHost(payload))
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            inst.install(entry) { p -> if (p.bytes > 0) throw CancellationException("user paused") }
        }
        job.join()
        assertFalse(File(dir, "t.litertlm").exists()); assertFalse(inst.isInstalled(entry))
        assertTrue(inst.install(entry) is InstallResult.Installed, "and a later attempt completes")
    }

    private fun phone(ramGb: Double, freeGb: Double, abi: String = "arm64-v8a", sdk: Int = 36) = DeviceProfile("Test", "Phone", sdk, (ramGb * 1024).toInt(), (ramGb * 512).toInt(), (freeGb * 1024).toLong(), 8, listOf(abi))

    @Test fun recommenderOffersOnlyWhatThePhoneCanRun() {
        val cat = ModelCatalog.builtIn
        val low = ModelRecommender.choices(phone(4.0, 20.0), cat)
        assertTrue(low.isNotEmpty() && low.all { ModelRecommender.blocker(phone(4.0, 20.0), it.entry) == null })
        assertTrue(low.none { it.entry.id in setOf("gemma-4-e2b", "gemma-4-e4b", "qwen3-4b-instruct") }, "a 4 GB phone is not offered 2.6 GB+ models: ${low.map { it.entry.id }}")
        val mid = ModelRecommender.choices(phone(8.0, 30.0), cat)
        assertTrue(mid.any { it.entry.id == "gemma-4-e2b" } && mid.none { it.entry.id == "qwen3-4b-instruct" })
        val high = ModelRecommender.choices(phone(16.0, 100.0), cat)
        assertEquals(ChoiceLabel.FAST, high.first().label)
        assertEquals("gemma-4-e4b", high.first { it.label == ChoiceLabel.BEST }.entry.id)
        assertEquals("gemma-4-e2b", high.single { it.recommended }.entry.id, "balanced is the suggested default")
        assertTrue(ModelRecommender.choices(phone(16.0, 0.2), cat).isEmpty(), "no storage, no offers")
        assertTrue(ModelRecommender.choices(phone(16.0, 50.0, abi = "armeabi-v7a"), cat).isEmpty(), "32-bit phones cannot run these")
        assertTrue(ModelRecommender.choices(phone(16.0, 50.0, sdk = 26), cat).isEmpty())
    }

    @Test fun catalogEntriesAreVerifiableAndSafe() {
        val cat = ModelCatalog.builtIn
        assertTrue(cat.size >= 4)
        assertEquals(cat.size, cat.map { it.id }.toSet().size)
        for (e in cat) {
            assertEquals(64, e.sha256.length, e.id); assertTrue(e.sha256.all { it in "0123456789abcdef" }, e.id)
            assertTrue(e.url.startsWith("https://huggingface.co/${e.repo}/resolve/main/"), e.id)
            assertTrue(e.sizeBytes > 100_000_000 && e.license.isNotBlank() && !e.gated && e.format == "litertlm", e.id)
            assertTrue(e.minTotalRamMb >= e.runtimeRamMb, e.id)
            assertFalse(Regex("(?i)(qualcomm|mediatek|mt69|sm8|tensor|npu|_gpu|-gpu|web)").containsMatchIn(e.file), "vendor-specific build leaked into the generic catalog: ${e.file}")
        }
        assertNotNull(ModelCatalog.parse("""{"models":[{"id":"a","family":"f","name":"n","variant":"v","quantization":"q","repo":"r","file":"x.litertlm","url":"https://h/x","sizeBytes":5,"sha256":"${"a".repeat(64)}","license":"mit","minTotalRamMb":1,"runtimeRamMb":1,"contextTokens":1,"speed":1,"quality":1}]}""").firstOrNull())
    }
}
