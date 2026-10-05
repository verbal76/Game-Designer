package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.llm.LlmMessage
import com.hotattic.gamedesigner.core.llm.LlmProvider
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.schema.Field
import com.hotattic.gamedesigner.core.schema.FieldKind
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Tag
import com.hotattic.gamedesigner.core.schema.Traits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

enum class AnswerIntent { SELECT, ALL, NONE, DELEGATE, POSTPONE, SKIP, AFFIRM, NEGATE, QUESTION, FREEFORM, UNCLEAR }

/** Where an interpretation came from; shown to the owner so rules are never passed off as AI. */
enum class InterpreterKind(val label: String) {
    RULES("Rules only"), LOCAL_LLM("On-device model"), CLOUD_LLM("Cloud model")
}

/**
 * The structured meaning of one owner message, produced by an LLM or by the deterministic fallback. It is a PROPOSAL:
 * the Director validates every id and key against the schema before anything touches project state.
 */
data class Interpretation(
    val intent: AnswerIntent,
    /** Option ids chosen for the question that was asked (already validated against its options). */
    val selected: List<String> = emptyList(),
    /** Free-form / numeric / boolean value for the question that was asked. */
    val value: String? = null,
    /** Other design decisions volunteered in the same message, keyed by schema field. */
    val edits: Map<String, String> = emptyMap(),
    val rejectedGenres: List<String> = emptyList(),
    val rejectedTags: List<Tag> = emptyList(),
    val affirmedTags: List<Tag> = emptyList(),
    val references: List<String> = emptyList(),
    /** Plain statements of owner intent worth remembering ("two characters, one descending and one climbing"). */
    val facts: List<String> = emptyList(),
    /** Keywords of earlier facts the owner just withdrew. */
    val retract: List<String> = emptyList(),
    val question: String? = null,
    val reason: String = "",
    val by: InterpreterKind = InterpreterKind.RULES,
) {
    val hasCorrections get() = rejectedGenres.isNotEmpty() || rejectedTags.isNotEmpty() || retract.isNotEmpty()
}

/** Deterministic interpreter: used offline, and as the fallback and cross-check for the LLM interpreter. */
object LocalInterpreter {

    fun interpret(project: Project, field: Field?, text: String): Interpretation {
        val ex = DecisionExtractor.extract(text)
        val t = Traits(project)
        val base = Interpretation(
            intent = AnswerIntent.UNCLEAR, edits = ex.values.filterKeys { it != field?.key },
            rejectedGenres = ex.negatedGenres, rejectedTags = ex.negatedTags, affirmedTags = ex.affirmedTags, references = ex.referenceGames,
            retract = retractKeywords(text, ex),
        )
        if (field == null) return base.copy(intent = if (ex.isEmpty) AnswerIntent.FREEFORM else AnswerIntent.FREEFORM, value = text.trim())
        return when (val a = AnswerParser.parse(field, t, text)) {
            is Answer.Value -> {
                if (field.kind.isSelect) base.copy(intent = AnswerIntent.SELECT, selected = a.value.split(Decision.LIST_SEPARATOR).filter { it.isNotBlank() }, value = a.value)
                else base.copy(intent = AnswerIntent.FREEFORM, value = a.value)
            }
            Answer.Delegate -> base.copy(intent = AnswerIntent.DELEGATE)
            Answer.Postpone -> base.copy(intent = AnswerIntent.POSTPONE)
            Answer.Skip -> base.copy(intent = AnswerIntent.SKIP)
            Answer.Affirm -> base.copy(intent = AnswerIntent.AFFIRM)
            Answer.Negate -> base.copy(intent = AnswerIntent.NEGATE)
            is Answer.Question -> base.copy(intent = AnswerIntent.QUESTION, question = a.text)
            is Answer.Invalid -> base.copy(intent = AnswerIntent.UNCLEAR, reason = a.reason)
            Answer.Unclear -> base.copy(intent = AnswerIntent.UNCLEAR)
        }
    }

    /** Nouns the owner just ruled out also withdraw earlier facts that used them (turn-based -> "plan each turn", "squad"). */
    private fun retractKeywords(text: String, ex: Extraction): List<String> = buildList {
        if (com.hotattic.gamedesigner.core.schema.Tag.TURN_BASED in ex.negatedTags) addAll(TURN_WORDS)
        ex.negatedGenres.forEach { g -> addAll(GenreKnowledge.resolve(g).keywords.filter { it.length >= 5 }) }
    }.distinct()

    val TURN_WORDS = listOf("turn-based", "turn based", "each turn", "every turn", "per turn", "turn order", "squad units", "squad tactics", "tactical grid", "grid tactics", "action points")
}

/**
 * LLM-backed interpreter. It receives a compact, explicit state (never the whole project, never assets or files) and must
 * answer with one JSON object. Everything it returns is validated against the schema and the question's real options.
 */
object LlmInterpreter {

    const val TIMEOUT_MS = 45_000L

    fun systemPrompt(directorName: String): String = buildString {
        appendLine("You are the language-understanding layer of $directorName, a game-design director. You do not chat; you convert the owner's latest message into JSON.")
        appendLine("Reply with ONLY one JSON object (no prose, no markdown) with these optional keys:")
        appendLine("- intent: select|all|none|delegate|postpone|skip|affirm|negate|question|freeform|unclear")
        appendLine("- selected: array of option ids from the CURRENT QUESTION options (only those the owner chose; for single-select at most one)")
        appendLine("- value: the owner's answer for a freeform/number/boolean question (boolean: \"true\"/\"false\")")
        appendLine("- edits: object of other decisions the message states, keys from the field list, values as option ids or text")
        appendLine("- reject_genres: genre ids the owner rules out; reject_tags: from [TURN_BASED]; affirm_tags: from [TURN_BASED]")
        appendLine("- retract: short keywords of earlier statements the owner is withdrawing")
        appendLine("- facts: short factual statements of owner intent worth remembering (their words, not yours)")
        appendLine("- references: game titles named as references")
        appendLine("Rules: the owner's latest message overrides everything earlier. Negations matter (\"not turn based\" means turn-based is REJECTED). \"All of those\", \"everything except X\", \"the third one\", \"option 3\" refer to the current question's options in order. \"You choose\"/\"whatever you recommend\" is intent delegate. Never invent option ids. Omit keys you are unsure about.")
    }

    fun userPrompt(project: Project, field: Field?, text: String): String = buildString {
        val t = Traits(project)
        appendLine("ORIGINAL CONCEPT: ${project.originalConcept.ifBlank { project.value(Keys.CONCEPT).orEmpty() }.take(600)}")
        val known = project.decisions.filter { it.value.value.isNotBlank() }.entries.take(40)
            .joinToString("; ") { (k, d) -> "$k=${d.value.take(40)}[${d.prov.name.lowercase()}${if (d.status.name == "PROPOSED") ",proposed" else ""}]" }
        appendLine("DECISIONS: ${known.ifBlank { "(none)" }}")
        if (project.rejected.any { it.value.isNotEmpty() }) appendLine("OWNER REJECTED: " + project.rejected.filter { it.value.isNotEmpty() }.entries.joinToString("; ") { "${it.key}: ${it.value.joinToString(",")}" })
        val active = project.activeFacts().takeLast(8)
        if (active.isNotEmpty()) appendLine("FACTS: " + active.joinToString(" | ") { it.text.take(100) })
        if (field != null) {
            appendLine("CURRENT QUESTION (${field.key}, ${field.kind.name}): ${field.prompt.take(220)}")
            val opts = field.options(t)
            if (opts.isNotEmpty()) appendLine("OPTIONS: " + opts.mapIndexed { i, o -> "${i + 1}. ${o.id} = ${o.label}" }.joinToString("; "))
        } else appendLine("CURRENT QUESTION: none (owner is volunteering information)")
        appendLine("FIELDS: " + Fields.all.filter { it.kind.isSelect || it.key in setOf(Keys.CONCEPT, Keys.CORE_FANTASY) }.take(60).joinToString(",") { it.key })
        val recent = project.messages.filter { it.role != Role.SYSTEM }.takeLast(4)
        if (recent.isNotEmpty()) appendLine("RECENT: " + recent.joinToString(" / ") { "${it.role.name.lowercase()}: ${it.text.take(140).replace('\n', ' ')}" })
        appendLine("OWNER'S LATEST MESSAGE: $text")
    }

    /** Parses and strictly validates the model's JSON. Returns null if it is unusable, so the caller falls back to rules. */
    fun parse(raw: String, project: Project, field: Field?, by: InterpreterKind): Interpretation? {
        val s = raw.indexOf('{'); val e = raw.lastIndexOf('}')
        if (s < 0 || e <= s) return null
        val obj = runCatching { Json.parseToJsonElement(raw.substring(s, e + 1)) as? JsonObject }.getOrNull() ?: return null
        fun strs(k: String): List<String> = when (val v = obj[k]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> v.content.split(',', '|').map { it.trim() }
            else -> emptyList()
        }.filter { it.isNotBlank() }
        val intent = when ((obj["intent"] as? JsonPrimitive)?.content?.lowercase()) {
            "select" -> AnswerIntent.SELECT; "all" -> AnswerIntent.ALL; "none" -> AnswerIntent.NONE; "delegate" -> AnswerIntent.DELEGATE
            "postpone" -> AnswerIntent.POSTPONE; "skip" -> AnswerIntent.SKIP; "affirm" -> AnswerIntent.AFFIRM; "negate" -> AnswerIntent.NEGATE
            "question" -> AnswerIntent.QUESTION; "freeform" -> AnswerIntent.FREEFORM; else -> AnswerIntent.UNCLEAR
        }
        val t = Traits(project)
        val options = field?.options(t).orEmpty()
        val valid = options.map { it.id }.toSet()
        var selected = strs("selected").filter { it in valid }
        if (field?.kind == FieldKind.SINGLE && selected.size > 1) selected = selected.take(1)
        var finalIntent = intent
        if (intent == AnswerIntent.ALL && field?.kind == FieldKind.MULTI) { selected = options.map { it.id }; finalIntent = AnswerIntent.SELECT }
        if (intent == AnswerIntent.SELECT && selected.isEmpty() && field?.kind?.isSelect == true) finalIntent = AnswerIntent.UNCLEAR
        val value = (obj["value"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val edits = (obj["edits"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()
        // Also accept the flat extraction format (genre/dimension/... at the top level) for compatibility with simple models.
        val (flat, flatRefs) = com.hotattic.gamedesigner.core.llm.DirectorPrompts.parseExtraction(raw.substring(s, e + 1))
        val cleanEdits = (DecisionExtractor.sanitize(flat) + DecisionExtractor.sanitize(edits)).filterKeys { it != field?.key }
        val tags = { k: String -> strs(k).mapNotNull { n -> runCatching { Tag.valueOf(n.uppercase().replace(' ', '_').replace('-', '_')) }.getOrNull() }.filter { it == Tag.TURN_BASED } }
        val genreIds = GenreKnowledge.all.map { it.id }.toSet()
        return Interpretation(
            intent = finalIntent, selected = selected, value = value, edits = cleanEdits,
            rejectedGenres = strs("reject_genres").filter { it in genreIds }, rejectedTags = tags("reject_tags"), affirmedTags = tags("affirm_tags"),
            references = (strs("references") + flatRefs).distinct(), facts = strs("facts").map { it.take(200) }.take(8), retract = strs("retract").map { it.take(40) }.take(8),
            question = if (finalIntent == AnswerIntent.QUESTION) (value ?: "") else null, by = by,
        )
    }

    suspend fun interpret(provider: LlmProvider, by: InterpreterKind, directorName: String, project: Project, field: Field?, text: String): Interpretation? {
        val r = runCatching {
            kotlinx.coroutines.withTimeoutOrNull(TIMEOUT_MS) {
                provider.complete(LlmRequest(systemPrompt(directorName), listOf(LlmMessage("user", userPrompt(project, field, text))), maxTokens = 400, temperature = 0f))
            }
        }.getOrNull()
        return if (r is LlmResult.Ok) parse(r.text, project, field, by) else null
    }
}
