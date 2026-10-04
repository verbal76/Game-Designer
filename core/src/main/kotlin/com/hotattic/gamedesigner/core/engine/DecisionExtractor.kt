package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms

data class Extraction(
    /** Proposed values keyed by schema field. Multi-valued fields are already joined with [Decision.LIST_SEPARATOR]. */
    val values: Map<String, String>,
    val referenceGames: List<String>,
) {
    val isEmpty get() = values.isEmpty() && referenceGames.isEmpty()
}

/**
 * Pulls structured decisions out of free text. Deterministic so the app works with no model installed, and used to
 * sanity-check anything an LLM extractor returns.
 */
object DecisionExtractor {

    private val platformWords = mapOf(
        Platforms.ANDROID to listOf("android", "my phone", "phone game", "mobile game", "on mobile", "on my phone", "google play"),
        Platforms.IOS to listOf("iphone", "ios", "ipad", "app store", "apple"),
        Platforms.WINDOWS to listOf("windows", "pc game", "on pc", "steam", "desktop"),
        Platforms.MAC to listOf("macos", "mac os", "on mac", "macbook"),
        Platforms.LINUX to listOf("linux", "steam deck"),
        Platforms.WEB to listOf("web browser", "in the browser", "browser game", "webgl", "html5", "web game"),
    )

    private val artWords = mapOf(
        "pixel_art" to listOf("pixel art", "pixel-art", "pixelated", "8-bit", "16-bit", "retro pixel"),
        "low_poly" to listOf("low poly", "low-poly", "lowpoly"),
        "voxel" to listOf("voxel", "blocky"),
        "hand_drawn" to listOf("hand-drawn", "hand drawn", "painterly", "watercolor", "watercolour", "cartoon"),
        "vector_flat" to listOf("vector art", "flat design", "flat art", "clean vector"),
        "minimal_geometric" to listOf("minimalist", "minimal", "geometric", "abstract shapes"),
        "stylized_3d" to listOf("stylized 3d", "stylised 3d", "stylized"),
    )

    private val perspectiveWords = mapOf(
        "top_down" to listOf("top-down", "top down", "overhead"),
        "side_view" to listOf("side-scroller", "side scroller", "side-scrolling", "side view"),
        "isometric_2d" to listOf("isometric"),
        "first_person" to listOf("first-person", "first person", "fps view"),
        "third_person" to listOf("third-person", "third person", "over the shoulder", "over-the-shoulder"),
    )

    private val trigger = Regex("(?i)\\b(?:like|inspired by|similar to|mix(?:ed)? (?:of|with)|cross(?:ed)? with|combined with|combine|meets|mashup of|plus|crossed with)\\s+")
    private val titleSeq = Regex("\\b([A-Z][\\w'’:\\-]*(?:\\s+(?:of|the|and|in|for|to|a|&|[A-Z0-9][\\w'’:\\-]*))*)")
    private val notGames = setOf("I", "I'm", "Im", "Android", "iPhone", "Windows", "Linux", "Steam", "Kevin", "Bob", "Claude", "Google", "Play", "Apple", "Mac", "Unity", "Unreal", "Godot", "GitHub", "The", "A", "An", "My", "And", "But", "Or", "So", "It", "This", "That", "We", "You", "They", "Yes", "No", "Hi", "Hello", "Okay", "OK", "Well")
    private val knownSingleWord = GenreKnowledge.all.flatMap { it.keywords }.filter { !it.contains(' ') && it.first().isLetter() }.toSet()

    fun extract(text: String): Extraction {
        val t = text.lowercase()
        val out = linkedMapOf<String, String>()

        val genres = GenreKnowledge.detect(text)
        // Mechanical genre keywords such as "roguelike" are only reliable when no better match exists, but keep all hits.
        if (genres.isNotEmpty()) out[Keys.GENRE] = Decision.joinList(genres.map { it.id }.distinct().take(3))

        when {
            Regex("\\b2\\.5\\s?d\\b").containsMatchIn(t) -> out[Keys.DIMENSION] = "2.5D"
            Regex("\\b3\\s?d\\b").containsMatchIn(t) && !Regex("\\b2\\s?d\\b").containsMatchIn(t) -> out[Keys.DIMENSION] = "3D"
            Regex("\\b2\\s?d\\b").containsMatchIn(t) && !Regex("\\b3\\s?d\\b").containsMatchIn(t) -> out[Keys.DIMENSION] = "2D"
            perspectiveWords["first_person"]!!.any { it in t } || perspectiveWords["third_person"]!!.any { it in t } -> out[Keys.DIMENSION] = "3D"
        }

        val plats = platformWords.filter { (_, ws) -> ws.any { GenreKnowledge.containsWord(t, it) } }.keys
        if (plats.isNotEmpty()) out[Keys.PLATFORMS] = Decision.joinList(plats)

        artWords.entries.firstOrNull { (_, ws) -> ws.any { it in t } }?.let { out[Keys.ART_DIRECTION] = it.key }
        perspectiveWords.entries.firstOrNull { (_, ws) -> ws.any { it in t } }?.let { out[Keys.PERSPECTIVE] = it.key }

        when {
            "portrait" in t -> out[Keys.ORIENTATION] = "portrait"
            "landscape" in t -> out[Keys.ORIENTATION] = "landscape"
        }
        when {
            Regex("\\b(offline|no internet|without internet)\\b").containsMatchIn(t) -> out[Keys.NETWORK_POLICY] = "fully_offline"
        }
        if (Regex("\\b(touch ?screen|touch controls?)\\b").containsMatchIn(t)) out[Keys.INPUT_METHODS] = "touch"

        return Extraction(out, referenceGames(text))
    }

    fun referenceGames(text: String): List<String> {
        val found = linkedSetOf<String>()
        val triggerEnds = trigger.findAll(text).map { it.range.last + 1 }.toList()
        fun consider(seq: String, afterTrigger: Boolean) {
            val s = seq.trim().trimEnd('.', ',', ':', '-')
            if (s.isEmpty()) return
            val first = s.split(' ').first()
            if (first in notGames && s.split(' ').size == 1) return
            val words = s.split(' ')
            val multi = words.count { it.firstOrNull()?.let { c -> c.isUpperCase() || c.isDigit() } == true } >= 2
            val single = words.size == 1 && (afterTrigger || s.lowercase() in knownSingleWord)
            if ((multi || single) && s !in notGames) found += s
        }
        titleSeq.findAll(text).forEach { m ->
            val afterTrigger = triggerEnds.any { it == m.range.first }
            // Skip a leading sentence-initial common word (e.g. "I want Risk of Rain 2" matches "Risk of Rain 2" only).
            consider(m.value, afterTrigger)
        }
        return found.toList()
    }

    /** Validates an LLM-produced map against the schema; unknown keys, invalid option ids and invalid values are dropped. */
    fun sanitize(raw: Map<String, String>): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for ((k, v) in raw) {
            val f = Fields.get(k) ?: continue
            val value = v.trim()
            if (value.isEmpty()) continue
            when (f.kind) {
                com.hotattic.gamedesigner.core.schema.FieldKind.TEXT -> out[k] = value
                else -> {
                    // Option lists depend on traits, so validate against the union of known ids for static option sets.
                    val ids = (if (k == Keys.GENRE) Fields.genreOptions.map { it.id } else null)
                    if (ids != null) {
                        val kept = value.split(Decision.LIST_SEPARATOR, ",").map { it.trim() }.filter { it in ids }
                        if (kept.isNotEmpty()) out[k] = Decision.joinList(kept)
                    } else out[k] = value.replace(",", Decision.LIST_SEPARATOR)
                }
            }
        }
        return out
    }
}
