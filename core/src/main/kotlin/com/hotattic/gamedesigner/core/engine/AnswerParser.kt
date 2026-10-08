package com.hotattic.gamedesigner.core.engine

import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.schema.Field
import com.hotattic.gamedesigner.core.schema.FieldKind
import com.hotattic.gamedesigner.core.schema.GenreKnowledge
import com.hotattic.gamedesigner.core.schema.Keys
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

/**
 * Rule-based reading of a reply to the question just asked. This is the offline path and the validator for the LLM
 * interpreter; selection semantics (cardinality, "all", exceptions, positions, numeric intervals) live in [OptionResolver].
 */
object AnswerParser {

    private val postponePhrases = listOf("later", "come back", "not sure yet", "ask me again", "circle back", "not now", "hold off", "i'll think", "ill think")
    private val skipPhrases = listOf("skip", "pass", "none", "nothing", "no thanks", "not needed", "don't need", "dont need", "n/a")
    private val affirmPhrases = listOf("yes", "yep", "yeah", "yup", "correct", "that's right", "thats right", "right", "sure", "ok", "okay", "sounds good", "looks good", "confirm", "confirmed", "do it", "go ahead", "agreed", "exactly", "perfect", "great", "good")
    private val negatePhrases = listOf("no", "nope", "nah", "not quite", "wrong", "incorrect", "change", "that's not", "thats not")
    private val questionStarts = listOf("what", "why", "how", "should", "can ", "could ", "do ", "does ", "is ", "are ", "explain", "which", "whats", "what's", "tell me about", "help")

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9.\\s/+-]"), " ").replace(Regex("\\s+"), " ").trim()

    fun isDelegate(text: String): Boolean = OptionResolver.resolve(listOf(com.hotattic.gamedesigner.core.schema.Option("x", "x")), false, text).mode == OptionResolver.Mode.DELEGATE
    fun isPostpone(text: String): Boolean { val t = norm(text); return postponePhrases.any { t.contains(it) } }
    fun isAffirm(text: String): Boolean {
        val t = norm(text)
        if (t.isEmpty()) return false
        if (Regex("\\b(not|no|never|wrong|incorrect|isn t|isnt|but|except|however)\\b").containsMatchIn(t)) return false
        val words = t.split(' ')
        return words.size <= 6 && affirmPhrases.any { a -> t == a || t.startsWith("$a ") || t.endsWith(" $a") || t.startsWith("$a,") }
    }
    fun isNegate(text: String): Boolean {
        val t = norm(text)
        val words = t.split(' ')
        return words.size <= 6 && negatePhrases.any { n -> t == n || t.startsWith("$n ") }
    }
    private fun startsInterrogative(text: String): Boolean { val n = norm(text) + " "; return questionStarts.any { n.startsWith(it.trimEnd() + " ") } }
    fun looksLikeQuestion(text: String): Boolean {
        val t = text.trim()
        if (t.endsWith("?")) return true
        val n = norm(t)
        return startsInterrogative(t) && n.split(' ').size <= 25
    }

    private fun positionalOnly(raw: String) = Regex("(?i)^\\s*(?:option|number|choice|#)?\\s*\\d+\\s*\\??\\s*$|^\\s*(?:the\\s+)?(?:first|second|third|fourth|fifth|last)\\s+(?:one|option)\\s*\\??\\s*$").matches(raw)

    fun parse(field: Field, traits: Traits, text: String): Answer {
        val raw = text.trim()
        if (raw.isEmpty()) return Answer.Unclear
        val options = field.options(traits)
        val multi = field.kind == FieldKind.MULTI

        // A real question that merely names an option ("what is 2.5D?") is a question, not an answer.
        val words = raw.split(Regex("\\s+")).size
        val interrogative = startsInterrogative(raw)
        if (looksLikeQuestion(raw) && words >= 3 && (interrogative || words >= 5) && !positionalOnly(raw)) return Answer.Question(raw)

        if (field.kind.isSelect && options.isNotEmpty()) {
            val r = OptionResolver.resolve(options, multi, raw)
            when (r.mode) {
                OptionResolver.Mode.DELEGATE -> return Answer.Delegate
                OptionResolver.Mode.ALL, OptionResolver.Mode.SELECT -> if (r.ids.isNotEmpty()) {
                    // A genuine question that merely mentions an option is not an answer.
                    if (!(looksLikeQuestion(raw) && raw.split(Regex("\\s+")).size >= 4 && !r.confident)) return validate(field, traits, Decision.joinList(r.ids))
                }
                OptionResolver.Mode.NONE -> return if (!field.required || multi) Answer.Skip else Answer.Postpone
                OptionResolver.Mode.UNCLEAR -> Unit
            }
        } else if (OptionResolver.resolve(listOf(com.hotattic.gamedesigner.core.schema.Option("x", "x")), false, raw).mode == OptionResolver.Mode.DELEGATE) return Answer.Delegate

        if (isPostpone(raw)) return Answer.Postpone
        val n = norm(raw)
        val onlySkip = skipPhrases.any { n == it || n == "$it it" || n == "$it this" || n == "i $it" }
        if (onlySkip) return when {
            field.key == Keys.REFERENCES || field.key == Keys.MUST_NOT_CHANGE -> Answer.Value("none")
            !field.required -> Answer.Skip
            else -> Answer.Postpone
        }
        if (looksLikeQuestion(raw) && raw.split(Regex("\\s+")).size >= 3) return Answer.Question(raw)

        return when (field.kind) {
            FieldKind.TEXT -> validate(field, traits, raw)
            FieldKind.BOOLEAN -> when {
                isAffirm(raw) -> Answer.Value("true")
                isNegate(raw) || Regex("\\b(no|false|nope|don't|dont|not)\\b").containsMatchIn(n) -> Answer.Value("false")
                else -> Answer.Unclear
            }
            FieldKind.NUMBER -> Regex("-?\\d+(?:\\.\\d+)?").find(n)?.let { validate(field, traits, it.value) } ?: Answer.Unclear
            FieldKind.SINGLE, FieldKind.MULTI -> {
                if (field.key == Keys.GENRE) {
                    val g = GenreKnowledge.detect(raw)
                    if (g.isNotEmpty()) return Answer.Value(Decision.joinList(g.map { it.id }.take(3)))
                }
                if (field.key == Keys.PLATFORMS) DecisionExtractor.extract(raw).values[Keys.PLATFORMS]?.let { return Answer.Value(it) }
                if (field.allowCustom && raw.length >= 3) return if (field.key == Keys.GENRE) Answer.Value("other") else validate(field, traits, raw)
                Answer.Unclear
            }
        }
    }

    private fun validate(field: Field, traits: Traits, value: String): Answer {
        val err = field.validate(traits, value)
        return if (err != null) Answer.Invalid(err) else Answer.Value(value)
    }
}
