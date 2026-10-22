package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys

/** Where an asset may come from. */
enum class AssetSourceKind(val phrase: String) {
    SUPPLIED("the owner-supplied asset material"),
    FREE("appropriately licensed free assets (CC0/public domain)"),
    ORIGINAL("original/procedural work created by the builder"),
}

/**
 * The asset strategy the owner actually established: an ordered preference over sources, optionally narrowed per asset category,
 * with a named preferred free source. It is derived from the stored `asset_policy` value (legacy preset ids and the compact
 * `strategy:` form are both understood), so nothing extra is persisted and old projects keep working.
 *
 *   `strategy:supplied>free>original|src=Kenney|cat.characters=supplied>original|cat.environment=original|gaps=default`
 */
data class AssetStrategy(
    val order: List<AssetSourceKind>,
    val preferredFreeSource: String? = null,
    val byCategory: Map<String, List<AssetSourceKind>> = emptyMap(),
    /** The owner expressed no preference for (some) needs; this chain is Bob's default and says so. */
    val defaulted: Boolean = false,
    val attribution: Boolean = false,
) {
    val usesSupplied: Boolean get() = SUPPLIED in order || byCategory.values.any { SUPPLIED in it }
    val allowsFree: Boolean get() = FREE in order || byCategory.values.any { FREE in it }
    fun tiersFor(needId: String): List<AssetSourceKind> = byCategory[needId] ?: order

    fun encode(): String {
        if (preferredFreeSource == null && byCategory.isEmpty() && !defaulted) when {
            order == listOf(FREE, ORIGINAL) -> return if (attribution) "cc0_or_cc_by" else "cc0_default"
            order == listOf(ORIGINAL) -> return "original_only"
            order == listOf(SUPPLIED, FREE, ORIGINAL) && !attribution -> return "supplied_cc0"
            order == listOf(SUPPLIED, ORIGINAL) -> return "supplied_original"
            order == listOf(SUPPLIED) -> return "supplied_only"
        }
        return buildString {
            append("strategy:").append(order.joinToString(">") { it.name.lowercase() })
            preferredFreeSource?.let { append("|src=").append(it) }
            byCategory.forEach { (c, t) -> append("|cat.").append(c).append('=').append(t.joinToString(">") { it.name.lowercase() }) }
            if (defaulted) append("|gaps=default")
            if (attribution) append("|attribution")
        }
    }

    /** One sentence for the builder, generated from the strategy (never a canned policy paragraph). */
    fun describe(): String {
        fun chain(t: List<AssetSourceKind>) = t.mapIndexed { i, s ->
            val lead = if (i == 0) "" else if (i == t.lastIndex) "for whatever remains uncovered, " else "for needs the previous source does not cover, "
            val src = when (s) {
                AssetSourceKind.SUPPLIED -> "the asset packs the owner supplies (inspect them first and use suitable contents)"
                AssetSourceKind.FREE -> "appropriately licensed free assets (CC0/public domain${if (attribution) ", or CC-BY with attribution" else ""}; verify every license${preferredFreeSource?.let { "; the owner prefers $it" } ?: ""})"
                AssetSourceKind.ORIGINAL -> "original/procedural work created by the builder"
            }
            "${i + 1}) $lead$src"
        }.joinToString("; ")
        val main = "Asset sources in priority order: ${chain(order)}."
        val cats = byCategory.entries.joinToString(" ") { (c, t) -> "For $c: ${t.joinToString(" then ") { s -> s.phrase }}." }
        val note = if (defaulted) " The owner did not say what to use for the gaps; that part is Bob's default and can be changed." else ""
        return (main + (if (cats.isNotBlank()) " $cats" else "") + note)
    }

    fun shortLabel(): String = order.joinToString(", then ") { when (it) { AssetSourceKind.SUPPLIED -> "your supplied packs"; AssetSourceKind.FREE -> preferredFreeSource?.let { s -> "free assets ($s first)" } ?: "free/CC0 assets"; AssetSourceKind.ORIGINAL -> "original work" } } +
        if (byCategory.isNotEmpty()) " (with exceptions for ${byCategory.keys.joinToString()})" else ""

    companion object {
        private val SUPPLIED = AssetSourceKind.SUPPLIED
        private val FREE = AssetSourceKind.FREE
        private val ORIGINAL = AssetSourceKind.ORIGINAL

        private val legacy = mapOf(
            "cc0_default" to AssetStrategy(listOf(FREE, ORIGINAL)),
            "cc0_or_cc_by" to AssetStrategy(listOf(FREE, ORIGINAL), attribution = true),
            "original_only" to AssetStrategy(listOf(ORIGINAL)),
            "supplied_cc0" to AssetStrategy(listOf(SUPPLIED, FREE, ORIGINAL)),
            "supplied_original" to AssetStrategy(listOf(SUPPLIED, ORIGINAL)),
            "supplied_only" to AssetStrategy(listOf(SUPPLIED)),
        )
        val DEFAULT = legacy.getValue("cc0_default")

        fun of(value: String?): AssetStrategy {
            if (value == null) return DEFAULT
            legacy[value]?.let { return it }
            if (!value.startsWith("strategy:")) return DEFAULT
            val parts = value.removePrefix("strategy:").split('|')
            fun tiers(s: String) = s.split('>').mapNotNull { n -> AssetSourceKind.entries.firstOrNull { it.name.equals(n, true) } }
            val cats = linkedMapOf<String, List<AssetSourceKind>>()
            var src: String? = null; var defaulted = false; var attribution = false
            for (x in parts.drop(1)) when {
                x.startsWith("src=") -> src = x.removePrefix("src=")
                x.startsWith("cat.") -> x.removePrefix("cat.").split('=', limit = 2).let { cats[it[0]] = tiers(it.getOrElse(1) { "" }) }
                x == "gaps=default" -> defaulted = true
                x == "attribution" -> attribution = true
            }
            return AssetStrategy(tiers(parts[0]).ifEmpty { listOf(FREE, ORIGINAL) }, src, cats, defaulted, attribution)
        }

        /** Short label for any stored value (preset or strategy), used wherever a decision is shown. */
        fun label(value: String): String? = if (value.startsWith("strategy:")) of(value).shortLabel() else null

        private val suppliedRx = Regex("(?i)\\b(?:asset packs?|packs?|assets?|art|artwork|models?|characters?)\\b[^.;]{0,50}\\b(?:i|we)\\b[^.;]{0,25}\\b(?:give|gave|supply|supplied|provide|provided|send|sent|upload(?:ed)?|have|made)\\b|\\b(?:my|our)\\s+(?:own\\s+)?(?:asset\\s+|art\\s+|character\\s+)?(?:packs?|assets?|art|artwork|models?|characters?)\\b|\\bsupplied\\b|\\bi(?:'ll| will)\\s+(?:give|supply|provide)\\b|\\bthat i give\\b")
        private val freeRx = Regex("(?i)\\bcc0\\b|public[- ]domain|\\bfree\\s+(?:assets?|packs?|items?|sources?|stuff)|freely licen[sc]ed|open[- ]licen[sc]ed|creative commons|\\bkenney\\b|quaternius|poly ?haven|ambientcg|opengameart|\\bitch(?:\\.io)?\\b|\\bcc[- ]by\\b|anything free")
        private val originalRx = Regex("(?i)\\boriginal\\b|\\bprocedural\\w*|\\bgenerat(?:e|ed|ing)\\b|\\bmade by claude|\\bclaude (?:can )?(?:make|create|build|do)\\b|\\bcreate (?:your|its) own|\\bfrom scratch|\\bhand-?made\\b|\\bwork done by claude|\\byou (?:make|create|build)\\b")
        private val namedSources = listOf("Kenney", "Quaternius", "Poly Haven", "ambientCG", "OpenGameArt", "itch.io")
        private val categories = linkedMapOf(
            "characters" to Regex("(?i)\\b(?:characters?|player|hero|protagonist|avatar|creatures?)\\b"),
            "environment" to Regex("(?i)\\b(?:environments?|levels?|world|tiles?|props|backgrounds?|scenery|terrain|platforms?)\\b"),
            "materials" to Regex("(?i)\\b(?:textures?|materials?)\\b"),
            "ui_kit" to Regex("(?i)\\b(?:ui|interface|menus?|hud|icons?)\\b"),
            "vfx" to Regex("(?i)\\b(?:vfx|visual effects?|particles?)\\b"),
            "font" to Regex("(?i)\\bfonts?\\b"),
            "sfx" to Regex("(?i)\\b(?:sfx|sound effects?|sounds?)\\b"),
            "music" to Regex("(?i)\\b(?:music|soundtrack|songs?)\\b"),
        )
        private val onlyCue = Regex("(?i)\\b(?:only|just|exclusively)\\b")
        /** Sentences that talk about assets at all; used so 'procedurally generated stages' is never read as an asset preference. */
        val assetTalk = Regex("(?i)\\b(?:assets?|packs?|art|artwork|models?|textures?|sprites?|cc0|kenney|quaternius|sounds?|music|audio|animations?)\\b")

        private fun tiersIn(s: String): List<Pair<Int, AssetSourceKind>> = buildList {
            suppliedRx.find(s)?.let { add(it.range.first to SUPPLIED) }
            freeRx.find(s)?.let { add(it.range.first to FREE) }
            originalRx.find(s)?.let { add(it.range.first to ORIGINAL) }
        }.sortedBy { it.first }

        /** Reads an owner statement about assets. Null when it expresses no source preference (e.g. "choose for me"). */
        fun parse(text: String): AssetStrategy? {
            val clauses = text.split(Regex("[.;!?]|,\\s*(?:but|and|while)\\b|\\bbut\\b|\\bwhile\\b|\\band then\\b")).map { it.trim() }.filter { it.isNotEmpty() }
            val cats = linkedMapOf<String, List<AssetSourceKind>>()
            val globalClauses = mutableListOf<String>()
            for (c in clauses) {
                val found0 = tiersIn(c)
                // A category counts only when its word sits right next to a source word ("my character art", "generate the environments").
                val cs = categories.filterValues { rx -> rx.findAll(c).any { m -> found0.any { (pos, _) -> kotlin.math.abs(pos - m.range.first) <= 40 } } }.keys
                val ts = found0.map { it.second }
                if (cs.isNotEmpty() && ts.isNotEmpty() && cs.size <= 3) cs.forEach { cats[it] = ts + (if (ORIGINAL in ts) emptyList() else listOf(ORIGINAL)) }
                else globalClauses += c
            }
            val g = globalClauses.joinToString(". ")
            val found = tiersIn(g)
            if (found.isEmpty() && cats.isEmpty()) return null
            val attribution = Regex("(?i)\\bcc[- ]by\\b|attribution").containsMatchIn(text)
            val src = namedSources.firstOrNull { Regex("(?i)\\b${Regex.escape(it)}\\b").containsMatchIn(text) }
            var order = found.map { it.second }.distinct().toMutableList()
            var defaulted = false
            val only = onlyCue.containsMatchIn(g) && order.size == 1
            if (order.isEmpty()) { order = mutableListOf(FREE, ORIGINAL); defaulted = true }
            else if (order == listOf(SUPPLIED) && !only) { order = mutableListOf(SUPPLIED, FREE, ORIGINAL); defaulted = true }  // gaps unstated: Bob's default chain
            else if (order.last() != ORIGINAL && !(only && order == listOf(SUPPLIED))) order += ORIGINAL
            return AssetStrategy(order, src.takeIf { FREE in order }, cats, defaulted, attribution)
        }
    }
}
