package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.schema.Field
import com.hotattic.gamedesigner.core.schema.FieldKind
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Option
import com.hotattic.gamedesigner.core.schema.Platforms
import com.hotattic.gamedesigner.core.schema.Traits

sealed class Answer {
    data class Value(val value: String) : Answer()
    object Delegate : Answer()
    object Postpone : Answer()
    object Skip : Answer()
    object Affirm : Answer()
    object Negate : Answer()
    data class Question(val text: String) : Answer()
    data class Invalid(val reason: String) : Answer()
    object Unclear : Answer()
}

/** Interprets the owner's reply in the context of the field that was just asked. Works for typed or dictated text. */
object AnswerParser {

    private val delegatePhrases = listOf(
        "choose for me", "you choose", "you pick", "you decide", "pick for me", "decide for me", "surprise me", "up to you", "your call",
        "whatever you recommend", "your recommendation", "go with your", "what you recommend", "i don't care", "i dont care", "don't care",
        "dont care", "you tell me", "whatever you think", "whatever is best", "best option", "recommended", "default", "sounds good, you", "bob choose",
    )
    private val postponePhrases = listOf("later", "come back", "not sure yet", "ask me again", "circle back", "not now", "hold off", "i'll think", "ill think")
    private val skipPhrases = listOf("skip", "pass", "none", "nothing", "no thanks", "not needed", "don't need", "dont need", "n/a")
    private val affirmPhrases = listOf("yes", "yep", "yeah", "yup", "correct", "that's right", "thats right", "right", "sure", "ok", "okay", "sounds good", "looks good", "confirm", "confirmed", "do it", "go ahead", "agreed", "exactly", "perfect", "great", "good")
    private val negatePhrases = listOf("no", "nope", "nah", "not quite", "wrong", "incorrect", "change", "that's not", "thats not")
    private val questionStarts = listOf("what", "why", "how", "should", "can ", "could ", "do ", "does ", "is ", "are ", "explain", "which", "whats", "what's", "tell me about", "help")

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9.\\s/+-]"), " ").replace(Regex("\\s+"), " ").trim()

    fun isDelegate(text: String): Boolean { val t = norm(text); return delegatePhrases.any { t.contains(it) } }
    fun isPostpone(text: String): Boolean { val t = norm(text); return postponePhrases.any { t.contains(it) } }
    fun isAffirm(text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty()) return false
        val words = t.split(' ')
        return words.size <= 6 && affirmPhrases.any { a -> t == a || t.startsWith("$a ") || t.endsWith(" $a") || t.startsWith("$a,") }
    }
    fun isNegate(text: String): Boolean {
        val t = norm(text)
        val words = t.split(' ')
        return words.size <= 6 && negatePhrases.any { n -> t == n || t.startsWith("$n ") }
    }
    fun looksLikeQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.endsWith("?")) return true
        val n = norm(t)
        return questionStarts.any { n.startsWith(it) } && n.split(' ').size <= 25
    }

    fun parse(field: Field, traits: Traits, text: String): Answer {
        val raw = text.trim()
        if (raw.isEmpty()) return Answer.Unclear
        if (isDelegate(raw)) return Answer.Delegate
        // A genuine question is never an answer, even if it mentions an option ("what is 2.5D?").
        if (looksLikeQuestion(raw) && raw.split(Regex("\\s+")).size >= 3) return Answer.Question(raw)
        val options = field.options(traits)
        val optionHit = if (options.isNotEmpty()) matchOptions(field, options, raw) else emptyList()
        if (optionHit.isNotEmpty()) return validate(field, traits, Decision.joinList(optionHit))

        if (isPostpone(raw)) return Answer.Postpone
        val n = norm(raw)
        val onlySkip = skipPhrases.any { n == it || n == "$it it" || n == "$it this" || n == "i $it" }
        if (onlySkip) {
            return when {
                field.key == Keys.REFERENCES -> Answer.Value("none")
                !field.required -> Answer.Skip
                else -> Answer.Postpone
            }
        }
        if (looksLikeQuestion(raw)) return Answer.Question(raw)

        return when (field.kind) {
            FieldKind.TEXT -> validate(field, traits, raw)
            FieldKind.SINGLE, FieldKind.MULTI -> {
                if (field.key == Keys.GENRE) {
                    val g = GenreKnowledge.detect(raw)
                    if (g.isNotEmpty()) return Answer.Value(Decision.joinList(g.map { it.id }.take(3)))
                }
                if (field.key == Keys.PLATFORMS) {
                    val ex = DecisionExtractor.extract(raw).values[Keys.PLATFORMS]
                    if (ex != null) return Answer.Value(ex)
                }
                if (field.allowCustom && raw.length >= 3) return if (field.key == Keys.GENRE) Answer.Value("other") else validate(field, traits, raw)
                Answer.Unclear
            }
        }
    }

    private fun validate(field: Field, traits: Traits, value: String): Answer {
        val err = field.validate(traits, value)
        return if (err != null) Answer.Invalid(err) else Answer.Value(value)
    }

    /** Matches the reply against option ids, labels, ordinals ("2", "option 3", "the second one") and key words. */
    private fun matchOptions(field: Field, options: List<Option>, raw: String): List<String> {
        val n = norm(raw)
        // Ordinals / numbered picks: "1", "2 and 3", "option 2".
        if (Regex("^(option\\s*)?[0-9]+(\\s*(,|and|&)\\s*(option\\s*)?[0-9]+)*$").matches(n)) {
            val picks = Regex("[0-9]+").findAll(n).map { it.value.toInt() }.toList()
            val ids = picks.mapNotNull { options.getOrNull(it - 1)?.id }
            return if (field.kind == FieldKind.SINGLE) ids.take(1) else ids
        }
        val hits = linkedSetOf<String>()
        for (o in options) {
            val label = norm(o.label)
            val id = o.id.lowercase().replace('_', ' ')
            if (n == label || n == id || n == o.id.lowercase()) { hits += o.id; continue }
            val keyed = Regex("(^|[^a-z0-9])${Regex.escape(label)}($|[^a-z0-9])").containsMatchIn(n) ||
                Regex("(^|[^a-z0-9])${Regex.escape(id)}($|[^a-z0-9])").containsMatchIn(n)
            if (keyed && label.length >= 2) hits += o.id
        }
        if (field.key == Keys.PLATFORMS && hits.isEmpty()) {
            DecisionExtractor.extract(raw).values[Keys.PLATFORMS]?.let { return it.split(Decision.LIST_SEPARATOR) }
            if (Regex("\\bphone\\b").containsMatchIn(n) || "mobile" in n) return listOf(Platforms.ANDROID)
        }
        if (hits.isEmpty() && field.kind != FieldKind.TEXT) {
            // Loose keyword overlap on distinctive words; require a unique best option.
            val words = n.split(' ').filter { it.length > 3 }.toSet()
            val scored = options.map { o -> o to (norm(o.label) + " " + o.id.replace('_', ' ')).split(' ').count { it.length > 3 && it in words } }
            val best = scored.maxOfOrNull { it.second } ?: 0
            if (best > 0 && scored.count { it.second == best } == 1) hits += scored.first { it.second == best }.first.id
        }
        return if (field.kind == FieldKind.SINGLE) hits.take(1) else hits.toList()
    }
}
