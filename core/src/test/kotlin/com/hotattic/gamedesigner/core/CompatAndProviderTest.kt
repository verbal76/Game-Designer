package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.llm.LlmMessage
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.llm.OpenAiCompatibleProvider
import com.hotattic.gamedesigner.core.llm.ProviderFactory
import com.hotattic.gamedesigner.core.llm.ProviderIds
import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.core.model.DecisionSource
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.net.HttpResponse
import com.hotattic.gamedesigner.core.net.HttpTransport
import com.hotattic.gamedesigner.core.persist.ProjectCodec
import com.hotattic.gamedesigner.core.schema.Keys
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CompatAndProviderTest {

    private fun strip(e: JsonElement, drop: Set<String>): JsonElement = when (e) {
        is JsonObject -> JsonObject(e.filterKeys { it !in drop }.mapValues { strip(it.value, drop) })
        is JsonArray -> JsonArray(e.map { strip(it, drop) })
        else -> e
    }

    @Test fun dataWrittenByTheOldLayerStillLoads() {
        var p = ProjectOps.setDecision(newProject(), Keys.GENRE, "platformer", Provenance.OWNER_EXPLICIT, 1L, raw = "a platformer")
        p = ProjectOps.setOriginalConcept(p, "concept")
        p = ProjectOps.rejectTag(p, com.hotattic.gamedesigner.core.schema.Tag.TURN_BASED, 2L).first
        val json = ProjectCodec.encode(p)
        val old = strip(Json.parseToJsonElement(json), setOf("provenance", "rawAnswer", "originalConcept", "facts", "rejected", "question")).toString()
        val loaded = ProjectCodec.decode(old)
        assertEquals("platformer", loaded.value(Keys.GENRE))
        assertEquals(Provenance.OWNER_EXPLICIT, loaded.decision(Keys.GENRE)!!.prov, "provenance is derived from the legacy source")
        assertEquals("", loaded.originalConcept)
    }

    @Test fun newDataKeepsLegacySourceForRollback() {
        val p = ProjectOps.setDecision(newProject(), Keys.GENRE, "platformer", Provenance.OWNER_ACCEPTED_RECOMMENDATION, 1L)
        assertEquals(DecisionSource.DIRECTOR_CHOICE, p.decision(Keys.GENRE)!!.source)
        val s = AppSettings(llmProviderId = ProviderIds.OPENAI_COMPATIBLE, llmBaseUrl = "https://x.example/v1")
        assertEquals(s, kotlinx.serialization.json.Json.decodeFromString(AppSettings.serializer(), kotlinx.serialization.json.Json.encodeToString(AppSettings.serializer(), s)))
    }

    private class Fake(val code: Int, val body: String) : HttpTransport {
        var url = ""; var sentBody = ""; var headers = emptyMap<String, String>()
        override suspend fun get(url: String, headers: Map<String, String>) = HttpResponse(code, body)
        override suspend fun post(url: String, body: String, headers: Map<String, String>): HttpResponse { this.url = url; sentBody = body; this.headers = headers; return HttpResponse(code, this.body) }
        override suspend fun put(url: String, body: String, headers: Map<String, String>) = HttpResponse(code, this.body)
    }

    @Test fun openAiCompatibleProviderSpeaksChatCompletions() = runBlocking {
        val http = Fake(200, """{"choices":[{"message":{"content":"hello"}}]}""")
        val prov = ProviderFactory.create(ProviderIds.OPENAI_COMPATIBLE, http, { "sk-test" }, { "https://llm.example/v1" }, { "m1" })
        assertNotNull(prov); assertTrue(prov.isReady())
        val r = prov.complete(LlmRequest("sys", listOf(LlmMessage("user", "hi"))))
        assertEquals(LlmResult.Ok("hello"), r)
        assertEquals("https://llm.example/v1/chat/completions", http.url)
        assertEquals("Bearer sk-test", http.headers["Authorization"])
        assertTrue("\"system\"" in http.sentBody)
        // non-https endpoints and missing keys are never ready, and nothing is sent
        val insecure = OpenAiCompatibleProvider(http, { "http://x" }, { "k" }, { "m" })
        assertEquals(false, insecure.isReady())
        assertTrue(insecure.complete(LlmRequest("s", emptyList())) is LlmResult.Failure)
        assertTrue(ProviderFactory.PRIVACY_NOT_SENT.any { it.contains("assets") })
    }
}
