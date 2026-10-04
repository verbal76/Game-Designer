package com.hotattic.gamedesigner.core.research

import com.hotattic.gamedesigner.core.model.ReferenceGame
import com.hotattic.gamedesigner.core.model.ResearchKind
import com.hotattic.gamedesigner.core.model.ResearchNote
import com.hotattic.gamedesigner.core.model.SourceRef
import com.hotattic.gamedesigner.core.net.HttpTransport
import com.hotattic.gamedesigner.core.net.NetworkUnavailableException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/**
 * Research from open, terms-friendly sources only: Wikipedia (CC BY-SA, attribution kept) for reference games and
 * official version endpoints for toolchain facts. Every result carries source URL + retrieval time.
 */
class WebResearch(private val http: HttpTransport, private val clock: () -> Long = System::currentTimeMillis) : ResearchProvider {

    private val json = Json { ignoreUnknownKeys = true }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    override suspend fun researchReferenceGame(name: String): ResearchOutcome<ReferenceGame> {
        return try {
            val title = findTitle(name) ?: return ResearchOutcome.NotFound(name)
            val r = http.get("https://en.wikipedia.org/api/rest_v1/page/summary/${enc(title.replace(' ', '_')).replace("+", "%20")}", mapOf("Accept" to "application/json"))
            if (!r.ok) return ResearchOutcome.Unavailable("Wikipedia answered ${r.code}")
            val parsed = parseSummary(r.body) ?: return ResearchOutcome.NotFound(name)
            val now = clock()
            ResearchOutcome.Found(ReferenceGame(
                name = name,
                summary = shorten(parsed.extract),
                traits = detectTraits(parsed.extract),
                sources = listOf(SourceRef(parsed.title, parsed.url, now, "CC BY-SA 4.0 (Wikipedia)")),
            ))
        } catch (e: NetworkUnavailableException) { ResearchOutcome.Unavailable(e.message ?: "offline") }
        catch (e: Exception) { ResearchOutcome.Unavailable(e.message ?: e.javaClass.simpleName) }
    }

    private suspend fun findTitle(name: String): String? {
        for (q in listOf("$name (video game)", name)) {
            val r = http.get("https://en.wikipedia.org/w/api.php?action=opensearch&limit=1&format=json&search=${enc(q)}")
            if (!r.ok) continue
            parseOpenSearch(r.body)?.let { return it }
        }
        return null
    }

    override suspend fun toolchainFacts(engineId: String): ResearchOutcome<List<ResearchNote>> {
        return try {
            val notes = mutableListOf<ResearchNote>()
            val now = clock()
            suspend fun add(topic: String, url: String, parse: (String) -> String?) {
                val r = http.get(url, mapOf("Accept" to "application/json"))
                if (r.ok) parse(r.body)?.let { notes += ResearchNote("tv_${topic.lowercase().replace(' ', '_')}_$now", topic, it, listOf(SourceRef(topic, url, now)), ResearchKind.TOOLCHAIN_VERSION, now) }
            }
            when (engineId) {
                "godot" -> add("Godot stable", "https://api.github.com/repos/godotengine/godot/releases/latest") { jsonString(it, "tag_name")?.let { v -> "latest stable release tag $v" } }
                "libgdx" -> add("libGDX", "https://repo.maven.apache.org/maven2/com/badlogicgames/gdx/gdx/maven-metadata.xml") { mavenRelease(it)?.let { v -> "latest release $v on Maven Central" } }
                "web_phaser" -> add("Phaser", "https://registry.npmjs.org/phaser/latest") { jsonString(it, "version")?.let { v -> "latest npm version $v" } }
                "web_three" -> add("three.js", "https://registry.npmjs.org/three/latest") { jsonString(it, "version")?.let { v -> "latest npm version $v" } }
                "bevy" -> add("Bevy", "https://crates.io/api/v1/crates/bevy") { runCatching { json.parseToJsonElement(it).jsonObject["crate"]!!.jsonObject["max_stable_version"]!!.jsonPrimitive.content }.getOrNull()?.let { v -> "latest stable crate $v" } }
                else -> Unit
            }
            if (engineId == "android_native" || engineId == "libgdx")
                add("Gradle", "https://services.gradle.org/versions/current") { jsonString(it, "version")?.let { v -> "current Gradle release $v" } }
            if (notes.isEmpty()) ResearchOutcome.NotFound(engineId) else ResearchOutcome.Found(notes)
        } catch (e: NetworkUnavailableException) { ResearchOutcome.Unavailable(e.message ?: "offline") }
        catch (e: Exception) { ResearchOutcome.Unavailable(e.message ?: e.javaClass.simpleName) }
    }

    // ---- Pure parsing (unit tested) ----

    data class Summary(val title: String, val extract: String, val url: String)

    fun parseOpenSearch(body: String): String? = runCatching {
        val arr = json.parseToJsonElement(body).jsonArray
        (arr[1] as JsonArray).firstOrNull()?.jsonPrimitive?.content
    }.getOrNull()

    fun parseSummary(body: String): Summary? = runCatching {
        val o: JsonObject = json.parseToJsonElement(body).jsonObject
        val extract = o["extract"]?.jsonPrimitive?.content ?: return null
        val url = o["content_urls"]?.jsonObject?.get("desktop")?.jsonObject?.get("page")?.jsonPrimitive?.content ?: return null
        Summary(o["title"]?.jsonPrimitive?.content ?: "", extract, url)
    }.getOrNull()

    fun shorten(extract: String): String {
        val sentences = extract.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }
        return sentences.take(2).joinToString(" ").take(400)
    }

    private val traitWords = listOf(
        "roguelike", "roguelite", "bullet hell", "survival", "tower defense", "city-building", "city builder", "factory", "automation", "open world", "procedurally generated",
        "third-person", "first-person", "top-down", "isometric", "side-scrolling", "platform", "puzzle", "turn-based", "real-time strategy", "deck-building", "multiplayer",
        "co-op", "3d", "2d", "pixel", "sandbox", "crafting", "metroidvania",
    )

    fun detectTraits(text: String): List<String> = text.lowercase().let { t -> traitWords.filter { Regex("(^|[^a-z0-9])${Regex.escape(it)}").containsMatchIn(t) } }

    fun jsonString(body: String, key: String): String? = runCatching { json.parseToJsonElement(body).jsonObject[key]?.jsonPrimitive?.content }.getOrNull()
    fun mavenRelease(xml: String): String? = Regex("<release>([^<]+)</release>").find(xml)?.groupValues?.get(1)
        ?: Regex("<latest>([^<]+)</latest>").find(xml)?.groupValues?.get(1)
}
