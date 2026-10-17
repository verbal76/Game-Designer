package com.hotattic.gamedesigner.core.knowledge

import com.hotattic.gamedesigner.core.model.KnowledgeStatus
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.Keys

/**
 * Deterministic, offline retrieval over the bundled design-principle library. Nothing here touches decisions: it reads the project to decide
 * what NOT to offer (rejected entries, mechanics the owner ruled out) and returns advisory matches.
 */
object KnowledgeLibrary {
    const val SOURCE_TITLE = "The 100 Games That Taught Me Game Design"
    const val SOURCE_CREATOR = "Game Maker's Toolkit"
    const val SOURCE_KIND = "video"
    const val MIN_SCORE = 4
    const val MAX_RESULTS = 2

    val entries: List<KnowledgeEntry> get() = KnowledgeLibraryData.entries
    val active: List<KnowledgeEntry> get() = entries.filter { it.pilot }
    fun byId(id: String) = entries.firstOrNull { it.id == id }

    data class Match(val entry: KnowledgeEntry, val score: Int, val reason: String)

    private val stop = setOf("about", "which", "their", "there", "would", "could", "should", "these", "those", "where", "being", "other", "games", "game", "player", "players", "want", "make", "making", "need", "some", "have", "with", "that", "this", "what", "from", "into", "more", "like", "help", "ideas", "idea", "examples", "example", "advice", "alternatives", "handle", "handles", "give", "show")

    /** Which design topics a schema field belongs to, so "Show an example" can be offered only where a topic applies. */
    private val fieldConcepts = listOf(
        "difficult|failure|win_loss" to listOf("difficulty"), "progress" to listOf("progression"), "world|explor" to listOf("exploration"),
        "combat|enemies" to listOf("combat"), "story|narrative" to listOf("narrative"), "tutorial|onboard" to listOf("onboarding"),
        "input|touch|control|hud|ui_" to listOf("interface", "game_feel"), "accessib" to listOf("accessibility"), "session|game_length|core_loop" to listOf("scope_structure", "pacing"),
        "multiplayer|mp_" to listOf("multiplayer"), "survival|inventory" to listOf("survival_resources"), "economy|automation" to listOf("simulation_building"),
        "movement" to listOf("movement", "game_feel"), "audio|art_|color|player_feeling|core_fantasy" to listOf("theme_message"),
    ).map { Regex(it.first) to it.second }

    fun conceptsOfField(fieldKey: String?): Set<String> =
        if (fieldKey == null) emptySet() else fieldConcepts.filter { it.first.containsMatchIn(fieldKey) }.flatMap { it.second }.toSet()

    fun conceptsOfText(text: String): Set<String> {
        val t = text.lowercase()
        return KnowledgeLibraryData.concepts.filter { c -> c.phrases.any { Regex("\\b" + Regex.escape(it)).containsMatchIn(t) } }.map { it.name }.toSet()
    }

    private fun words(text: String) = Regex("[a-z]{5,}").findAll(text.lowercase()).map { it.value }.filter { it !in stop }.toSet()
    private fun stem(w: String) = w.take(6)

    // Plain words an owner uses for a mechanic, and the wording entries use for the same thing.
    private val synonyms = mapOf("powerup" to listOf("pellet", "pickup", "boost", "perk", "power"), "combat" to listOf("fight", "enemy", "enemies", "weapon", "shoot"), "enemy" to listOf("enemies", "monster"))

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("(?<=power) (?=up)"), "").replace("-", "")

    /** Mechanics the owner ruled out: excluded aspects of any reference, and systems the owner confirmed are absent. */
    fun excludedTerms(p: Project): List<String> {
        val fromRefs = p.references.flatMap { it.excluded }.map { norm(it).trim() }.filter { it.isNotBlank() }
        val gates = buildList {
            if (p.decision(Keys.HAS_COMBAT)?.let { it.ownerAuthored && it.value == "no" } == true) add("combat")
            if (p.decision(Keys.HAS_PROGRESSION)?.let { it.ownerAuthored && it.value == "no" } == true) add("progression")
        }
        // The owner's own "no X" / "without X" / "do not want X", wherever the engine filed it: suppression errs on the side of leaving an example out.
        val said = p.activeFacts().filter { it.provenance.ownerAuthored }.map { it.text } + p.originalConcept
        val fromWords = said.flatMap { t -> negated.findAll(t).map { norm(it.groupValues[1]).trim() } }.filter { it.length >= 3 && it !in fillers }
        return (fromRefs + gates + fromWords).distinct()
    }

    private val negated = Regex("(?i)\\b(?:no|without|not want|n'?t want|do not need|don'?t need)\\s+(?:any\\s+|the\\s+|a\\s+|an\\s+)?([a-z][a-z-]*(?:\\s+[a-z-]+)?)")
    private val fillers = setOf("more", "one", "other", "much", "idea", "problem", "way", "thanks", "thank")

    private fun textOf(e: KnowledgeEntry) = norm(e.problem + " " + e.mechanic + " " + e.effect + " " + e.tags.joinToString(" "))

    /** True when [e] depends on something the owner excluded, or was already rejected here. Such entries are never offered again. */
    fun suppressed(p: Project, e: KnowledgeEntry): Boolean {
        if (p.knowledge.any { it.entryId == e.id && it.status == KnowledgeStatus.REJECTED }) return true
        val body = textOf(e)
        val about = norm(e.tags.joinToString(" "))
        for (term in excludedTerms(p)) {
            val key = term.removeSuffix("s").removeSuffix("es").replace(" ", "")
            val hits = listOf(key) + (synonyms[key] ?: emptyList<String>())
            val noCombatEntry = "nocombat" in about.replace(" ", "") || "combatalternatives" in about.replace(" ", "")
            if (key in setOf("combat", "progression") && (noCombatEntry)) continue
            if (hits.any { h -> body.contains(h) } && (key !in setOf("combat", "progression") || key in e.concepts.map { norm(it) } || body.contains(key))) return true
        }
        return false
    }

    /**
     * The best entries for [query] (what the owner said) and/or [fieldKey] (the open question). A match needs a topic hit plus a second
     * independent signal (another topic or a shared content word), so a vague request returns nothing rather than a stretched example.
     */
    fun retrieve(p: Project, query: String, fieldKey: String? = null, includeInactive: Boolean = false): List<Match> {
        val wanted = conceptsOfText(query) + conceptsOfField(fieldKey)
        if (wanted.isEmpty()) return emptyList()
        val ctxWords = words(query + " " + p.originalConcept).map(::stem).toSet()
        val shown = p.knowledge.filter { it.status == KnowledgeStatus.SHOWN }.map { it.entryId }.toSet()
        val scored = (if (includeInactive) entries else active).filterNot { suppressed(p, it) }.mapNotNull { e ->
            val topics = e.concepts.filter { it in wanted }
            if (topics.isEmpty()) return@mapNotNull null
            val body = words(e.problem + " " + e.mechanic + " " + e.effect + " " + e.tags.joinToString(" ")).map(::stem).toSet()
            val shared = ctxWords.filter { it in body }.take(3)
            val score = 3 * topics.size + shared.size - (if (e.id in shown) 1 else 0)
            if (score < MIN_SCORE) return@mapNotNull null
            val why = "it speaks to " + topics.joinToString(" and ") { it.replace('_', ' ') } + (if (shared.isNotEmpty()) " and touches on " + shared.joinToString(", ") else "")
            Match(e, score, why)
        }.sortedWith(compareByDescending<Match> { it.score }.thenBy { it.entry.id })
        val top = scored.firstOrNull() ?: return emptyList()
        return scored.filter { it.score * 10 >= top.score * 6 }.take(MAX_RESULTS)
    }

    /** True when "Show an example" is worth offering on this open question. */
    fun hasExampleFor(p: Project, fieldKey: String?) = fieldKey != null && retrieve(p, "", fieldKey).isNotEmpty()
}
