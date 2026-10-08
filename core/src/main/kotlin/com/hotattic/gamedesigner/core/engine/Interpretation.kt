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
    /** Softer wishes ("I'd like", "maybe"): remembered, but not hard requirements. */
    val preferences: List<String> = emptyList(),
    /** Things that must never change ("never turn it into levels"); they join the must-not-change list. */
    val constraints: List<String> = emptyList(),
    /** Statements that could mean two different games: Bob asks instead of guessing. */
    val ambiguities: List<String> = emptyList(),
    /** Field keys the owner handed to Bob inside a longer reply ("you pick the audio, but I want pixel art"). */
    val delegated: List<String> = emptyList(),
    /** The owner changed what the first build must be (their words). */
    val scope: String? = null,
    /** Field keys the model thinks this statement may have invalidated; only a hint, the Reconciler decides. */
    val stale: List<String> = emptyList(),
    val acceptsRecommendation: Boolean = false,
    /** "That's not what I meant": undo the last interpretation and re-ask. */
    val misunderstood: Boolean = false,
    /** What the message implies about decisions the owner has not stated, each with a confidence and a quote from the owner's words. */
    val inferences: List<Inference> = emptyList(),
    /** One short follow-up the model thinks would materially improve the design, if any. */
    val followUp: String? = null,
    /** The model's reading of a short or colourful answer as design intent ("Lucky!" -> improbable-success moments). */
    val gloss: String? = null,
) {
    val hasCorrections get() = rejectedGenres.isNotEmpty() || rejectedTags.isNotEmpty() || retract.isNotEmpty()

    /** The typed changes this message proposes. The Director validates and commits each one deterministically; none is trusted as written. */
    val proposals: List<Proposal> get() = buildList {
        facts.forEach { add(Proposal(ProposalKind.OWNER_FACT, null, it)) }
        preferences.forEach { add(Proposal(ProposalKind.OWNER_PREFERENCE, null, it)) }
        (rejectedGenres + rejectedTags.map { it.name }).forEach { add(Proposal(ProposalKind.OWNER_REJECTION, null, it)) }
        retract.forEach { add(Proposal(ProposalKind.OWNER_CORRECTION, null, it)) }
        constraints.forEach { add(Proposal(ProposalKind.NEW_CONSTRAINT, null, it)) }
        delegated.forEach { add(Proposal(ProposalKind.OWNER_DELEGATION, it, "")) }
        ambiguities.forEach { add(Proposal(ProposalKind.UNRESOLVED_AMBIGUITY, null, it)) }
        scope?.let { add(Proposal(ProposalKind.SCOPE_CHANGE, null, it)) }
        if (acceptsRecommendation) add(Proposal(ProposalKind.ACCEPTED_RECOMMENDATION, null, ""))
        edits.forEach { (k, v) -> add(Proposal(if (hasCorrections) ProposalKind.OWNER_CORRECTION else ProposalKind.OWNER_FACT, k, v)) }
    }
}

enum class Confidence { HIGH, MEDIUM, LOW }

/** A decision the owner implied but did not state. HIGH is recorded, MEDIUM is held for a quick confirmation, LOW is only a reason to ask. */
data class Inference(val key: String, val value: String, val confidence: Confidence, val evidence: String)

enum class ProposalKind { OWNER_FACT, OWNER_CORRECTION, OWNER_PREFERENCE, OWNER_REJECTION, OWNER_DELEGATION, ACCEPTED_RECOMMENDATION, UNRESOLVED_AMBIGUITY, NEW_CONSTRAINT, SCOPE_CHANGE }

data class Proposal(val kind: ProposalKind, val key: String?, val text: String)

/** Deterministic interpreter: used offline, and as the fallback and cross-check for the LLM interpreter. */
object LocalInterpreter {

    fun interpret(project: Project, field: Field?, text: String): Interpretation {
        val ex = DecisionExtractor.extract(text)
        val t = Traits(project)
        val base = Interpretation(
            intent = AnswerIntent.UNCLEAR, edits = ex.values.filterKeys { it != field?.key },
            rejectedGenres = ex.negatedGenres, rejectedTags = ex.negatedTags, affirmedTags = ex.affirmedTags, references = ex.referenceGames,
            retract = retractKeywords(text, ex),
            misunderstood = Regex("(?i)\\b(that'?s not what i (meant|said)|not what i meant|you (misunderstood|got (it|that) wrong)|that'?s wrong|no,? i meant)\\b").containsMatchIn(text),
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
 * LLM-backed interpreter: the language layer in front of the structured design state. It receives a compact, explicit state
 * (never the whole project, never assets or files) and answers with ONE JSON object of typed proposals. Everything it returns is
 * validated against the schema and the question's real options; malformed or invalid output is discarded (after one stricter
 * retry) and the deterministic rules take over, so a model can never corrupt the project.
 */
object LlmInterpreter {

    const val TIMEOUT_MS = 60_000L

    fun systemPrompt(directorName: String): String = """
You are the language layer of $directorName, a game-design director. Convert the owner's LATEST message into ONE JSON object. Output the JSON only: no prose, no markdown, no reasoning text.
Punctuation, capitalisation and exclamation marks never change the meaning of a reply: "Lucky!" means the same as "lucky". If the reply is short or odd but plausibly answers the current question, treat it as an answer (freeform), not unclear.
Keys (omit any that do not apply):
"intent": what the owner does with the CURRENT QUESTION: select|delegate|postpone|skip|affirm|negate|question|freeform|unclear
"selected": option ids chosen from the CURRENT QUESTION (a single-choice question allows at most one)
"value": the answer text for a freeform, number or boolean question (boolean: "true" or "false")
"edits": {"field_key": "option id or text"} for other decisions the message states (use only keys from FIELDS; for choice fields use only the listed option ids)
"facts": things the owner says about THEIR game, in their own words
"preferences": softer wishes ("I'd like", "maybe")
"constraints": things that must never change ("never", "always", "must stay")
"reject_genres": genre ids ruled out; "reject_tags"/"affirm_tags": TURN_BASED only
"retract": short keywords of earlier statements the owner withdraws
"delegate": field keys the owner hands to you ("you pick the audio")
"scope": what the owner now wants the first build to be, in their words
"ambiguous": short clarifying questions, only when the message could mean two different games
"accept_recommendation": true when the owner accepts what you just recommended
"inferences": [{"key":"field_key","value":"option id or text","confidence":"high|medium|low","evidence":"short quote of the owner's words"}] - what the owner's words IMPLY for decisions they did not state, including the GATES listed under GATES (use value "yes"/"no"). High = nearly certain from their words (a peaceful climbing game has no combat). Medium = likely. Never invent major creative choices.
"follow_up": ONE short question (max 20 words) about an interesting detail the owner just said that would materially change the design and that no listed field covers; omit it unless it is clearly valuable
"gloss": for a short or colourful answer to a freeform question, one sentence of the design intent it expresses, as the owner would mean it
"misunderstood": true when the owner says you got it wrong
"stale": field keys the new statement probably invalidates
"references": game titles named as references
Rules: the owner's latest words override everything earlier. Negation matters ("not turn based" REJECTS turn-based). Ordinal and list answers ("the third one", "option 3", "1, 3 and 5", "all of them", "everything except X", "both") refer to the CURRENT QUESTION's options in order. "You decide" or "whatever you recommend" is intent delegate. Never invent option ids or field keys. The examples inside a question are only examples: any answer that makes sense is an answer. The owner may be dictating, so repair obvious speech-to-text slips from context ("the wom" = "the win") but never guess when it is genuinely ambiguous. Never copy the owner's chat remarks about the app ("I already told you") into facts.
Example: owner says "Actually no, forget turn based. Make it real time and keep the shaft." -> {"intent":"freeform","reject_tags":["TURN_BASED"],"facts":["The game is real time"],"edits":{"world_structure":"vertical_shaft"},"constraints":["Keep the single shaft"]}
Example: CURRENT QUESTION options "1. bite_sized=1-5 minutes; 2. short_runs=10-20 minutes; 3. medium_sessions=30-60 minutes", owner says "the third one" -> {"intent":"select","selected":["medium_sessions"]}
""".trim()

    /** The few fields a message might touch, with their option ids, so the model can only name real choices. */
    private fun fieldMenu(project: Project, current: Field?): String {
        val t = Traits(project)
        val menu = Fields.all.filter { it.isRelevant(t) && !it.derived && it.key != current?.key && it.key != Keys.CONCEPT && (project.decision(it.key)?.ownerAuthored != true) }
            .sortedBy { it.priority }.take(14)
        return (listOfNotNull(current).map { it.key } + menu.map { it.key }).distinct().joinToString("; ") { k ->
            val f = Fields.get(k)!!
            val opts = if (f.kind.isSelect) f.options(t).joinToString("|") { it.id }.take(220) else ""
            if (f.kind.isSelect) "$k[${f.kind.name.lowercase()}:$opts]" else "$k(${f.kind.name.lowercase()})"
        }
    }

    fun userPrompt(project: Project, field: Field?, text: String): String = buildString {
        val t = Traits(project)
        appendLine("ORIGINAL CONCEPT: ${project.originalConcept.ifBlank { project.value(Keys.CONCEPT).orEmpty() }.take(700)}")
        val known = project.decisions.filter { it.value.value.isNotBlank() }.entries.take(40)
            .joinToString("; ") { (k, d) -> "$k=${d.value.take(40)}[${d.prov.name.lowercase()}${if (d.status.name == "PROPOSED") ",proposed" else ""}]" }
        appendLine("DECISIONS: ${known.ifBlank { "(none)" }}")
        if (project.rejected.any { it.value.isNotEmpty() }) appendLine("OWNER REJECTED: " + project.rejected.filter { it.value.isNotEmpty() }.entries.joinToString("; ") { "${it.key}: ${it.value.joinToString(",")}" })
        val active = project.activeFacts().takeLast(8)
        if (active.isNotEmpty()) appendLine("FACTS: " + active.joinToString(" | ") { it.text.take(100) })
        if (field != null) {
            appendLine("CURRENT QUESTION (${field.key}, ${field.kind.name}): ${field.prompt.take(220)}")
            val opts = field.options(t)
            if (opts.isNotEmpty()) appendLine("OPTIONS: " + opts.mapIndexed { i, o -> "${i + 1}. ${o.id}=${o.label}" }.joinToString("; "))
            field.suggest(t)?.let { appendLine("BOB'S RECOMMENDATION WAS: ${it.value.take(120)}") }
        } else appendLine("CURRENT QUESTION: none (the owner is volunteering information)")
        appendLine("FIELDS: " + fieldMenu(project, field))
        val gates = com.hotattic.gamedesigner.core.schema.Gates.all.filter { g -> project.decision(g.key) == null && Fields.get(g.key)?.isRelevant(t) == true }
        if (gates.isNotEmpty()) appendLine("GATES (does this design have the system? answer yes/no in inferences only if their words make it clear): " + gates.joinToString("; ") { "${it.key}=${it.noun}" })
        val recent = project.messages.filter { it.role != Role.SYSTEM }.takeLast(4)
        if (recent.isNotEmpty()) appendLine("RECENT: " + recent.joinToString(" / ") { "${it.role.name.lowercase()}: ${it.text.take(140).replace('\n', ' ')}" })
        appendLine("OWNER'S LATEST MESSAGE: $text")
    }

    /** Extracts the first complete JSON object from model text, tolerating code fences, reasoning blocks and trailing commas. */
    fun extractJson(raw: String): JsonObject? {
        val cleaned = raw.replace(Regex("(?s)<think>.*?</think>"), " ").replace(Regex("(?s)<think>.*"), " ").replace("```json", " ").replace("```", " ")
        var i = cleaned.indexOf('{')
        while (i >= 0) {
            var depth = 0; var inStr = false; var esc = false; var j = i
            while (j < cleaned.length) {
                val c = cleaned[j]
                if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false }
                else when (c) { '"' -> inStr = true; '{' -> depth++; '}' -> { depth--; if (depth == 0) break } }
                j++
            }
            if (depth == 0 && j < cleaned.length) {
                val candidate = cleaned.substring(i, j + 1).replace(Regex(",\\s*([}\\]])"), "$1")
                (runCatching { Json.parseToJsonElement(candidate) as? JsonObject }.getOrNull())?.let { return it }
            }
            i = cleaned.indexOf('{', i + 1)
        }
        return null
    }

    /** Keeps only edits that name a real field and, for choice fields, only real option ids (a MULTI field may hold several). */
    fun validateEdits(project: Project, raw: Map<String, String>, exclude: String?): Map<String, String> {
        val t = Traits(project)
        val out = linkedMapOf<String, String>()
        for ((k, v0) in raw) {
            if (k == exclude || k == Keys.CONCEPT) continue
            val f = Fields.get(k) ?: continue
            val v = v0.trim().ifEmpty { continue }
            when {
                k == Keys.GENRE -> { val ids = Fields.genreOptions.map { it.id }.toSet(); val kept = v.split('|', ',').map { it.trim() }.filter { it in ids }; if (kept.isNotEmpty()) out[k] = Decision.joinList(kept.take(3)) }
                f.kind.isSelect -> {
                    val valid = f.options(t).map { it.id }.toSet()
                    val kept = v.split('|', ',').map { it.trim() }.filter { it in valid }.distinct()
                    if (kept.isNotEmpty()) out[k] = Decision.joinList(if (f.kind == FieldKind.SINGLE) kept.take(1) else kept)
                }
                f.kind == FieldKind.BOOLEAN -> if (v.lowercase() in setOf("true", "false")) out[k] = v.lowercase()
                f.kind == FieldKind.NUMBER -> if (Regex("-?\\d+(?:\\.\\d+)?").matches(v)) out[k] = v
                else -> out[k] = v.take(500)
            }
        }
        return out
    }

    /** Parses and strictly validates the model's JSON. Returns null if it is unusable, so the caller retries or falls back to rules. */
    fun parse(raw: String, project: Project, field: Field?, by: InterpreterKind): Interpretation? {
        val obj = extractJson(raw) ?: return null
        fun strs(k: String): List<String> = when (val v = obj[k]) {
            is JsonArray -> v.mapNotNull { (it as? JsonPrimitive)?.content }
            is JsonPrimitive -> if (v.isString) v.content.split(',', '|').map { it.trim() } else emptyList()
            else -> emptyList()
        }.filter { it.isNotBlank() }
        fun bool(k: String) = (obj[k] as? JsonPrimitive)?.content?.lowercase() == "true"
        val intent = when ((obj["intent"] as? JsonPrimitive)?.content?.lowercase()) {
            "select" -> AnswerIntent.SELECT; "all" -> AnswerIntent.ALL; "none" -> AnswerIntent.NONE; "delegate" -> AnswerIntent.DELEGATE
            "postpone" -> AnswerIntent.POSTPONE; "skip" -> AnswerIntent.SKIP; "affirm" -> AnswerIntent.AFFIRM; "negate" -> AnswerIntent.NEGATE
            "question" -> AnswerIntent.QUESTION; "freeform" -> AnswerIntent.FREEFORM; else -> AnswerIntent.UNCLEAR
        }
        val t = Traits(project)
        val options = field?.options(t).orEmpty()
        val valid = options.map { it.id }.toSet()
        var selected = strs("selected").filter { it in valid }.distinct()
        if (field?.kind == FieldKind.SINGLE && selected.size > 1) selected = selected.take(1)
        var finalIntent = intent
        if (intent == AnswerIntent.ALL && field?.kind == FieldKind.MULTI) { selected = options.map { it.id }; finalIntent = AnswerIntent.SELECT }
        if (intent == AnswerIntent.SELECT && selected.isEmpty() && field?.kind?.isSelect == true) finalIntent = AnswerIntent.UNCLEAR
        val value = (obj["value"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val edits = (obj["edits"] as? JsonObject)?.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.content?.let { k to it } }?.toMap().orEmpty()
        // Also accept the flat extraction format (genre/dimension/... at the top level) for compatibility with simple models.
        val (flat, flatRefs) = com.hotattic.gamedesigner.core.llm.DirectorPrompts.parseExtraction(obj.toString())
        val cleanEdits = validateEdits(project, flat + edits, field?.key)
        val tags = { k: String -> strs(k).mapNotNull { n -> runCatching { Tag.valueOf(n.uppercase().replace(' ', '_').replace('-', '_')) }.getOrNull() }.filter { it == Tag.TURN_BASED } }
        val genreIds = GenreKnowledge.all.map { it.id }.toSet()
        fun fieldKeys(k: String) = strs(k).filter { Fields.get(it) != null && it != Keys.CONCEPT }.distinct().take(8)
        val inferences = (obj["inferences"] as? JsonArray).orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.content?.trim().orEmpty()
            val conf = when (str("confidence").lowercase()) { "high" -> Confidence.HIGH; "medium" -> Confidence.MEDIUM; else -> Confidence.LOW }
            val ok = validateEdits(project, mapOf(str("key") to str("value")), field?.key)
            ok.entries.firstOrNull()?.let { (k, v) -> Inference(k, v, conf, str("evidence").take(160)) }
        }.take(8)
        return Interpretation(
            inferences = inferences, followUp = (obj["follow_up"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }, gloss = (obj["gloss"] as? JsonPrimitive)?.content?.trim()?.takeIf { it.isNotBlank() }?.take(220),
            intent = finalIntent, selected = selected, value = value, edits = cleanEdits,
            rejectedGenres = strs("reject_genres").filter { it in genreIds }, rejectedTags = tags("reject_tags"), affirmedTags = tags("affirm_tags"),
            references = (strs("references") + flatRefs).distinct(), facts = strs("facts").map { it.take(200) }.take(8), retract = strs("retract").map { it.take(40) }.take(8),
            question = if (finalIntent == AnswerIntent.QUESTION) (value ?: "") else null, by = by,
            preferences = strs("preferences").map { it.take(200) }.take(5), constraints = strs("constraints").map { it.take(200) }.take(5),
            ambiguities = strs("ambiguous").map { it.take(160) }.take(3), delegated = fieldKeys("delegate"),
            scope = (obj["scope"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.take(300), stale = fieldKeys("stale"),
            acceptsRecommendation = bool("accept_recommendation"), misunderstood = bool("misunderstood"),
        )
    }

    suspend fun interpret(provider: LlmProvider, by: InterpreterKind, directorName: String, project: Project, field: Field?, text: String): Interpretation? {
        val base = userPrompt(project, field, text)
        for (attempt in 0..1) {
            val prompt = if (attempt == 0) base else base + "\nYour previous reply was not a valid JSON object. Reply with ONLY the JSON object."
            val r = runCatching {
                kotlinx.coroutines.withTimeoutOrNull(TIMEOUT_MS) {
                    provider.complete(LlmRequest(systemPrompt(directorName), listOf(LlmMessage("user", prompt)), maxTokens = 700, temperature = 0f))
                }
            }.getOrNull()
            if (r !is LlmResult.Ok) return null // a transport failure or timeout: retrying would only double the wait
            parse(r.text, project, field, by)?.let { return it }
        }
        return null
    }
}
