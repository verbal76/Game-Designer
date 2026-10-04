package com.hotattic.gamedesigner.core

import com.hotattic.gamedesigner.core.github.GitHubClient
import com.hotattic.gamedesigner.core.github.GitHubException
import com.hotattic.gamedesigner.core.github.RepoInspector
import com.hotattic.gamedesigner.core.llm.AnthropicProvider
import com.hotattic.gamedesigner.core.llm.LlmMessage
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.net.HttpResponse
import com.hotattic.gamedesigner.core.net.HttpTransport
import com.hotattic.gamedesigner.core.net.NetworkUnavailableException
import com.hotattic.gamedesigner.core.research.ResearchOutcome
import com.hotattic.gamedesigner.core.research.WebResearch
import kotlinx.coroutines.runBlocking
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FakeHttp(private val routes: List<Pair<(String, String) -> Boolean, HttpResponse>>, var offline: Boolean = false) : HttpTransport {
    val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
    val bodies = mutableListOf<String>()
    private fun route(m: String, url: String, h: Map<String, String>, body: String = ""): HttpResponse {
        if (offline) throw NetworkUnavailableException("offline"); calls += Triple(m, url, h); bodies += body
        return routes.firstOrNull { it.first(m, url) }?.second ?: HttpResponse(404, "{}")
    }
    override suspend fun get(url: String, headers: Map<String, String>) = route("GET", url, headers)
    override suspend fun post(url: String, body: String, headers: Map<String, String>) = route("POST", url, headers, body)
    override suspend fun put(url: String, body: String, headers: Map<String, String>) = route("PUT", url, headers, body)
}

class WebResearchTest {
    private val summary = """{"title":"Vampire Survivors","extract":"Vampire Survivors is a 2022 roguelite video game. The player survives hordes with auto-attack weapons. Third sentence.","content_urls":{"desktop":{"page":"https://en.wikipedia.org/wiki/Vampire_Survivors"}}}"""
    private fun http() = FakeHttp(listOf(
        { _: String, u: String -> "opensearch" in u } to HttpResponse(200, """["q",["Vampire Survivors"],[""],["https://en.wikipedia.org/wiki/Vampire_Survivors"]]"""),
        { _: String, u: String -> "page/summary" in u } to HttpResponse(200, summary),
        { _: String, u: String -> "godotengine" in u } to HttpResponse(200, """{"tag_name":"4.9-stable"}"""),
        { _: String, u: String -> "services.gradle.org" in u } to HttpResponse(200, """{"version":"9.8.0"}"""),
        { _: String, u: String -> "maven-metadata" in u } to HttpResponse(200, "<metadata><versioning><release>1.14.0</release></versioning></metadata>"),
    ))

    @Test fun referenceGameResearchKeepsProvenance() = runBlocking {
        val r = WebResearch(http(), { 42L }).researchReferenceGame("Vampire Survivors") as ResearchOutcome.Found
        assertTrue(r.value.summary.contains("roguelite") && !r.value.summary.contains("Third sentence"))
        assertTrue("roguelite" in r.value.traits)
        assertEquals("https://en.wikipedia.org/wiki/Vampire_Survivors", r.value.sources.single().url)
        assertTrue(r.value.sources.single().license.contains("CC BY-SA"))
    }
    @Test fun offlineAndMissingAreDistinguished() = runBlocking {
        assertTrue(WebResearch(FakeHttp(emptyList(), offline = true)).researchReferenceGame("X") is ResearchOutcome.Unavailable)
        assertTrue(WebResearch(FakeHttp(emptyList())).researchReferenceGame("Nonexistent Game") is ResearchOutcome.NotFound)
    }
    @Test fun toolchainFactsAreRecordedWithSources() = runBlocking {
        val g = WebResearch(http(), { 1L }).toolchainFacts("godot") as ResearchOutcome.Found
        assertTrue(g.value.single().summary.contains("4.9-stable")); assertTrue(g.value.single().sources.single().url.startsWith("https://api.github.com"))
        val l = WebResearch(http(), { 1L }).toolchainFacts("libgdx") as ResearchOutcome.Found
        assertEquals(setOf("libGDX", "Gradle"), l.value.map { it.topic }.toSet())
    }
}

class AnthropicProviderTest {
    private val ok = HttpResponse(200, """{"content":[{"type":"text","text":"Hello "},{"type":"text","text":"there"}]}""")
    @Test fun sendsKeyAndVersionAndParsesText() = runBlocking {
        val h = FakeHttp(listOf({ _: String, _: String -> true } to ok))
        val p = AnthropicProvider(h, { "sk-test" }, { "some-model" })
        val r = p.complete(LlmRequest("sys", listOf(LlmMessage("user", "hi"))))
        assertEquals(LlmResult.Ok("Hello there"), r)
        val (_, url, headers) = h.calls.single()
        assertEquals("https://api.anthropic.com/v1/messages", url); assertEquals("sk-test", headers["x-api-key"]); assertEquals("2023-06-01", headers["anthropic-version"])
        assertTrue(h.bodies.single().contains("\"model\":\"some-model\"") && h.bodies.single().contains("\"system\":\"sys\""))
    }
    @Test fun noKeyOfflineAndErrorsAreGraceful() = runBlocking {
        assertFalse(AnthropicProvider(FakeHttp(emptyList()), { null }, { "m" }).isReady())
        assertTrue(AnthropicProvider(FakeHttp(emptyList()), { null }, { "m" }).complete(LlmRequest("", emptyList())) is LlmResult.Failure)
        val off = AnthropicProvider(FakeHttp(emptyList(), offline = true), { "k" }, { "m" }).complete(LlmRequest("", listOf(LlmMessage("user", "x"))))
        assertTrue(off is LlmResult.Failure && off.retryable)
        val bad = AnthropicProvider(FakeHttp(listOf({ _: String, _: String -> true } to HttpResponse(401, "{}"))), { "k" }, { "m" }).complete(LlmRequest("", listOf(LlmMessage("user", "x"))))
        assertTrue(bad is LlmResult.Failure && !bad.retryable && bad.reason.contains("401"))
    }
}

class GitHubTest {
    private fun b64(s: String) = Base64.getEncoder().encodeToString(s.toByteArray())
    @Test fun inspectGodotRepoSeparatesFactsFromAssumptions() = runBlocking {
        val h = FakeHttp(listOf(
            { _: String, u: String -> u.endsWith("/repos/o/r") } to HttpResponse(200, """{"default_branch":"main"}"""),
            { _: String, u: String -> "git/trees" in u } to HttpResponse(200, """{"truncated":false,"tree":[{"type":"blob","path":"project.godot"},{"type":"blob","path":".github/workflows/ci.yml"},{"type":"blob","path":"scripts/a.gd"},{"type":"tree","path":"scripts"}]}"""),
            { _: String, u: String -> "/commits" in u } to HttpResponse(200, """[{"sha":"abc123def456","commit":{"message":"Add player\n\nbody"}}]"""),
            { _: String, u: String -> "contents/project.godot" in u } to HttpResponse(200, """{"content":"${b64("config/features=PackedStringArray(\"4.3\", \"Forward Plus\")")}"}"""),
        ))
        val ins = GitHubClient(h, { "tok" }).inspect("o", "r", 7L)
        assertEquals("godot", ins.detectedEngine); assertEquals("4.3", ins.detectedEngineVersion); assertEquals("abc123def456", ins.headSha)
        assertTrue(ins.hasCi && !ins.hasClaudeMd); assertEquals(listOf("Add player"), ins.lastCommitSummaries)
        assertTrue(ins.assumptions.any { it.contains("NOT been verified") })
        assertTrue(h.calls.all { it.third["Authorization"] == "Bearer tok" })
    }
    @Test fun tokenMissingAndHttpErrorsAreExplained() {
        assertFailsWith<GitHubException> { runBlocking { GitHubClient(FakeHttp(emptyList()), { null }).whoAmI() } }
        val e = assertFailsWith<GitHubException> { runBlocking { GitHubClient(FakeHttp(listOf({ _: String, _: String -> true } to HttpResponse(404, "{}"))), { "t" }).inspect("o", "x", 1L) } }
        assertTrue(e.message!!.contains("404"))
    }
    @Test fun detectsOtherEnginesFromFiles() {
        fun i(paths: List<String>, gradle: String? = null, pkg: String? = null) = RepoInspector.inspect(1, "main", "", paths, false, emptyList(), null, null, gradle, pkg, null, null)
        assertEquals("libgdx", i(listOf("build.gradle"), gradle = "implementation 'com.badlogicgames.gdx:gdx'").detectedEngine)
        assertEquals("android_native", i(listOf("app/src/main/AndroidManifest.xml", "app/build.gradle.kts")).detectedEngine)
        assertEquals("web_phaser", i(listOf("package.json"), pkg = """{"dependencies":{"phaser":"^3"}}""").detectedEngine)
        assertEquals("", i(listOf("README.md")).detectedEngine)
    }
    @Test fun writesRequireExistingShaWhenReplacing() = runBlocking {
        val h = FakeHttp(listOf(
            { m: String, u: String -> m == "GET" && "contents/CLAUDE.md" in u } to HttpResponse(200, """{"sha":"oldsha"}"""),
            { m: String, _: String -> m == "PUT" } to HttpResponse(200, "{}"),
        ))
        GitHubClient(h, { "t" }).putFile("o", "r", "CLAUDE.md", "main", "msg", "hello")
        assertTrue(h.bodies.last().contains("\"sha\":\"oldsha\""))
    }
}
