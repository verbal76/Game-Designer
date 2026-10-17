package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys

/**
 * The single answer to "can this be the name of a reference game?". Every entry point that records or researches a reference
 * (free-text extraction, the references question, a model's proposal, research, the approval fingerprint, the generated spec) asks
 * here, so an instruction such as "Add PC as the primary platform" can never become a game title or trigger research.
 */
object ReferenceGuard {

    /** Platform, engine, tool and role words: a name made only of these (and function words) is never a game. */
    private val vocabulary = setOf(
        "pc", "android", "ios", "iphone", "ipad", "windows", "mac", "macos", "linux", "web", "browser", "steam", "deck", "desktop", "mobile", "phone", "tablet",
        "unity", "unreal", "godot", "engine", "github", "claude", "google", "play", "apple", "store", "platform", "platforms", "primary", "main", "secondary",
        "version", "build", "game", "app", "bob", "kevin",
    )
    private val functionWords = setOf("a", "an", "the", "as", "to", "and", "or", "of", "for", "in", "on", "with", "now", "too", "only", "first", "also", "it", "is", "be", "not", "no")
    private val trailingStops = setOf("a", "an", "the", "as", "to", "and", "or", "of", "for", "in", "on", "with", "now", "too", "only", "also", "is", "be")
    private val instructionVerbs = setOf(
        "add", "make", "change", "switch", "set", "use", "remove", "drop", "forget", "keep", "build", "target", "support", "include", "let", "put", "turn", "go", "please",
        "delete", "ignore", "stop", "start", "skip", "scrap", "swap", "replace", "choose", "pick", "select", "prefer", "need", "want", "move", "convert",
    )

    fun isPlausibleTitle(name: String): Boolean {
        val s = name.trim().trim('.', ',', ':', '-', '"', '\'', ' ')
        if (s.length !in 2..60) return false
        val words = s.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty() || words.size > 8) return false
        val lower = words.map { it.lowercase().trim('.', ',', ':', '\'', '"') }
        if (lower.all { it in vocabulary || it in functionWords }) return false
        if (lower.last() in trailingStops) return false
        // "Add PC a", "Switch to PC", "Use Unity for": an instruction verb followed by a platform or function word.
        if (lower.first() in instructionVerbs && (words.size == 1 || lower.drop(1).any { it in vocabulary || it in functionWords })) return false
        return true
    }

    /** Removes implausible references stored by an older build, and rewrites the references decision to match. */
    fun purge(p: Project): Project {
        val (keep, drop) = p.references.partition { isPlausibleTitle(it.name) }
        if (drop.isEmpty()) return p
        val dec = p.decision(Keys.REFERENCES)
        val decisions = when {
            dec == null || dec.value == "none" -> p.decisions
            keep.isEmpty() -> p.decisions - Keys.REFERENCES
            else -> p.decisions + (Keys.REFERENCES to dec.copy(value = keep.joinToString(", ") { it.name }))
        }
        return p.copy(references = keep, decisions = decisions)
    }

    /** What the approval fingerprint sees: references that count, in a stable order. */
    fun fingerprintNames(p: Project): List<String> = p.references.map { it.name }.filter { isPlausibleTitle(it) }.map { it.lowercase() }.sorted()
}
