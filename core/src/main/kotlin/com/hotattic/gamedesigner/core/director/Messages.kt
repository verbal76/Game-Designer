package com.hotattic.gamedesigner.core.director

import com.hotattic.gamedesigner.core.engine.CompletenessEngine
import com.hotattic.gamedesigner.core.engine.Conflict
import com.hotattic.gamedesigner.core.engine.ScopeEngine
import com.hotattic.gamedesigner.core.engine.Severity
import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.schema.EngineRecommender
import com.hotattic.gamedesigner.core.schema.Field
import com.hotattic.gamedesigner.core.schema.FieldKind
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Traits

/** Deterministic, phone-sized wording for Director messages. */
object Messages {

    /** Human-readable form of a stored value (option labels, genre names, platform names). */
    fun display(p: Project, key: String, value: String): String {
        val f = Fields.get(key) ?: return value
        if (f.kind == FieldKind.TEXT) return value
        val t = Traits(p)
        val opts = f.options(t)
        return value.split(Decision.LIST_SEPARATOR).filter { it.isNotBlank() }.joinToString(", ") { id ->
            opts.firstOrNull { it.id == id }?.label ?: when (key) {
                Keys.GENRE -> GenreKnowledge.resolve(id).label
                Keys.PLATFORMS -> Platforms.labels[id] ?: id
                else -> id
            }
        }.ifEmpty { value }
    }

    /** The question worded for THIS game: the same information need, phrased the way a designer who knows the game would ask it. */
    fun promptFor(field: Field, t: Traits): String = when (field.key) {
        Keys.HAS_COMBAT -> if (t.genres.size == 1) "Does your ${t.genres.first().label.substringBefore(" (").substringBefore(" /").lowercase()} have combat - fighting enemies? Yes or no." else field.prompt
        Keys.COMBAT_MODEL -> when {
            t.hasGenre("shooter") -> "How should aiming and shooting feel? Pick every style that applies, or describe your own."
            t.hasGenre("fighting") -> "How should the fighting work - spacing, combos, grabs? Pick every style that applies, or describe your own."
            t.hasGenre("survivors_like") || t.hasGenre("action_roguelite") -> "How does your character fight? Pick every style that applies, or describe your own."
            t.gate(com.hotattic.gamedesigner.core.schema.Tag.COMBAT) == true -> "You want combat in this - how should it work? Pick every style that applies, or describe your own."
            else -> field.prompt
        }
        else -> field.prompt
    }

    fun clarify(field: Field, t: Traits): String {
        val opts = field.options(t)
        return if (opts.isEmpty()) "I didn't quite catch that. ${field.prompt}"
        else "I didn't quite catch that - pick one of the options, or say \"choose for me\" and I'll pick the best fit."
    }

    fun explain(field: Field, t: Traits): String {
        val sb = StringBuilder(field.why)
        val opts = field.options(t)
        if (opts.isNotEmpty()) {
            sb.append("\n\nOptions:")
            opts.take(8).forEach { sb.append("\n- ${it.label}").append(if (it.description.isNotBlank()) ": ${it.description}" else "") }
        }
        field.suggest(t)?.let { sb.append("\n\nMy recommendation: ${it.value.let { v -> if (field.kind == FieldKind.TEXT) v else v }} - ${it.rationale}") }
        sb.append("\n\n").append(reask(field))
        return sb.toString()
    }

    fun reask(field: Field) = "Back to the question: ${field.prompt}"

    fun conflict(c: Conflict): String = buildString {
        append(when (c.severity) { Severity.BLOCKER -> "That won't work: "; else -> "Heads up: " }).append(c.title).append("\n")
        append(c.message).append("\n\n")
        append("My recommendation: ").append(c.recommendation)
        if (c.overridable) append("\nIt's your call - if you know the tradeoff and still want it, say \"keep my choice\" and I'll build it that way.")
    }

    /** Extra context before specific questions (scope recommendation, engine recommendation). */
    fun preface(p: Project, field: Field): String? = when (field.key) {
        Keys.SCOPE_CHOICE -> {
            val r = ScopeEngine.recommend(p)
            buildString {
                append("Recommended scope: ${r.recommendedTier.label}. ")
                append(r.targets.take(5).joinToString(", ") { "${it.count} ${it.label.lowercase()}" })
                append(".\nClaude usage estimate: ${r.resources.level.label}. ${r.resources.explanation}")
            }
        }
        Keys.ENGINE -> {
            val t = Traits(p)
            val ranked = EngineRecommender.rank(t.platforms, t.dimension, t.complexity, t.beginner, t.tags).take(3)
            if (ranked.isEmpty()) null
            else "Engine options for your game: " + ranked.joinToString("; ") { it.engine.name } + ". The first is my recommendation: " + ranked.first().engine.strengths
        }
        else -> null
    }

    fun status(p: Project, director: String): String {
        val c = CompletenessEngine.compute(p)
        val sb = StringBuilder("${c.percent}% of required decisions resolved (${c.resolvedRequired}/${c.totalRequired}).\n")
        c.categories.forEach { sb.append("- ${it.category.label}: ${it.resolved}/${it.total}\n") }
        if (c.missingRequired.isNotEmpty()) sb.append("\nStill open: ").append(c.missingRequired.take(6).joinToString { it.title })
        if (c.proposed.isNotEmpty()) sb.append("\nNeed your confirmation: ").append(c.proposed.joinToString { it.title })
        if (c.readyForGeneration) sb.append("\nReady to generate.")
        return sb.toString().trim()
    }
}
