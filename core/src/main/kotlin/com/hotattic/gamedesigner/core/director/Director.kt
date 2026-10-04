package com.hotattic.gamedesigner.core.director

import com.hotattic.gamedesigner.core.engine.Alternative
import com.hotattic.gamedesigner.core.engine.Answer
import com.hotattic.gamedesigner.core.engine.AnswerParser
import com.hotattic.gamedesigner.core.engine.AssetPlan
import com.hotattic.gamedesigner.core.engine.AuditEngine
import com.hotattic.gamedesigner.core.engine.CompletenessEngine
import com.hotattic.gamedesigner.core.engine.Conflict
import com.hotattic.gamedesigner.core.engine.ConflictEngine
import com.hotattic.gamedesigner.core.engine.DecisionExtractor
import com.hotattic.gamedesigner.core.engine.Extraction
import com.hotattic.gamedesigner.core.engine.InterviewPlanner
import com.hotattic.gamedesigner.core.engine.NextStep
import com.hotattic.gamedesigner.core.engine.ProjectOps
import com.hotattic.gamedesigner.core.engine.ScopeEngine
import com.hotattic.gamedesigner.core.engine.Severity
import com.hotattic.gamedesigner.core.llm.DirectorPrompts
import com.hotattic.gamedesigner.core.llm.LlmMessage
import com.hotattic.gamedesigner.core.llm.LlmProvider
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.model.AckChoice
import com.hotattic.gamedesigner.core.model.AppSettings
import com.hotattic.gamedesigner.core.model.Decision
import com.hotattic.gamedesigner.core.model.DecisionSource
import com.hotattic.gamedesigner.core.model.DecisionStatus
import com.hotattic.gamedesigner.core.model.FeedbackSeverity
import com.hotattic.gamedesigner.core.model.PlaytestFeedback
import com.hotattic.gamedesigner.core.model.Project
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.QuickReply
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.research.ResearchOutcome
import com.hotattic.gamedesigner.core.research.ResearchProvider
import com.hotattic.gamedesigner.core.schema.EngineRecommender
import com.hotattic.gamedesigner.core.schema.Field
import com.hotattic.gamedesigner.core.schema.FieldKind
import com.hotattic.gamedesigner.core.schema.Fields
import com.hotattic.gamedesigner.core.schema.Keys
import com.hotattic.gamedesigner.core.schema.Traits

/** Things the Director asks the host app to do (UI-only side effects). */
sealed class DirectorAction {
    object GenerateSpec : DirectorAction()
    data class RequestUpload(val slot: String) : DirectorAction()
}

data class DirectorTurn(
    val project: Project,
    val action: DirectorAction? = null,
    /** Which engine produced model-assisted parts of this turn, if any ("local model", "cloud model"). */
    val modelNote: String? = null,
)

class DirectorDeps(
    val local: LlmProvider? = null,
    val cloud: LlmProvider? = null,
    val research: ResearchProvider? = null,
    val clock: () -> Long = System::currentTimeMillis,
)

private const val PENDING_PROPOSALS = "__proposals__"
private const val PENDING_ASSET_PLAN = "__asset_plan__"
private const val PENDING_CONFLICT_PREFIX = "__conflict:"
private const val PENDING_READY = "__ready__"
private const val MODEL_TIMEOUT_MS = 60_000L

/**
 * The conversational AI Director ("Bob" unless renamed). Decision order, validation, conflict handling and state
 * changes are deterministic and fully testable; language models only enhance understanding of free text and answer
 * free-form questions, and the app is fully functional with none installed.
 */
class Director(private val deps: DirectorDeps) {

    private fun name(s: AppSettings) = s.directorName.ifBlank { "Bob" }

    // ---- Entry points ------------------------------------------------------------------------------------------

    fun start(project: Project, settings: AppSettings): Project {
        val now = deps.clock()
        if (project.messages.isNotEmpty()) return project
        return when (project.mode) {
            ProjectMode.NEW_GAME -> {
                val f = Fields.get(Keys.CONCEPT)!!
                val msg = "Hi, I'm ${name(settings)}. " + f.prompt
                ProjectOps.setPending(ProjectOps.addMessage(project, Role.DIRECTOR, msg, now, f.key), f.key)
            }
            ProjectMode.EXISTING_GAME -> {
                val intro = "Hi, I'm ${name(settings)}. I'll look at what's really in your repository first, then we'll plan changes that don't break what already works."
                askNext(ProjectOps.addMessage(project, Role.DIRECTOR, intro, now), settings)
            }
            ProjectMode.PLAYTEST_CONTINUE -> {
                val last = project.versions.lastOrNull()
                val msg = if (last == null) "There's no generated spec in this project yet, so there is nothing to playtest. Open the project and generate a spec first."
                else "Tell me how the build went - what felt wrong, what broke, what you'd change. Text or voice is fine; send as many messages as you like. " +
                    "When you're done, say \"generate\" and I'll turn it into a continuation spec against v${last.number}."
                ProjectOps.addMessage(project, Role.DIRECTOR, msg, now, quick = listOf(QuickReply("Generate continuation spec", "generate")))
            }
        }
    }

    /** Switches an existing project between designing and playtest feedback, with an orienting message. */
    fun enterMode(project: Project, settings: AppSettings, mode: ProjectMode): Project {
        val now = deps.clock()
        val p = ProjectOps.setMode(project, mode, now)
        return when (mode) {
            ProjectMode.PLAYTEST_CONTINUE -> {
                val last = p.versions.lastOrNull()
                if (last == null) ProjectOps.addMessage(ProjectOps.setMode(p, ProjectMode.NEW_GAME, now), Role.DIRECTOR, "There's no generated spec yet, so there's nothing to playtest. Let's finish the design first.", now)
                else ProjectOps.addMessage(p, Role.DIRECTOR, "Playtest mode. Tell me how v${last.number} went - what felt wrong, what broke, what you'd change. Text or voice is fine; send as many messages as you like, then say \"generate\" for a continuation spec.", now,
                    quick = listOf(QuickReply("Generate continuation spec", "generate"), QuickReply("Back to designing", "back to designing")))
            }
            else -> askNext(ProjectOps.addMessage(p, Role.DIRECTOR, "Back to designing. Tell me what you'd like to change or add, or say \"status\".", now), settings)
        }
    }

    private suspend fun <T> timed(block: suspend () -> T): T? = kotlinx.coroutines.withTimeoutOrNull(MODEL_TIMEOUT_MS) { block() }

    suspend fun handleUserMessage(project: Project, settings: AppSettings, text: String): DirectorTurn {
        val now = deps.clock()
        var p = ProjectOps.addMessage(project, Role.USER, text.trim(), now)
        val lower = text.trim().lowercase()

        if (p.mode == ProjectMode.PLAYTEST_CONTINUE) {
            if (lower in setOf("back to designing", "back to design", "design mode")) return DirectorTurn(enterMode(p, settings, ProjectMode.NEW_GAME))
            return playtestTurn(p, settings, text.trim())
        }

        // Global commands
        if (isStatusCommand(lower)) return DirectorTurn(reply(p, Messages.status(p, name(settings))))
        if (isGenerateCommand(lower)) return generateRequest(p, settings)
        if (isBulkDelegate(lower)) return bulkDelegate(p, settings)
        if (lower in setOf("refine", "refine details", "more questions", "polish details")) {
            val opt = InterviewPlanner.nextOptional(p)
            return DirectorTurn(if (opt == null) reply(p, "There are no optional questions left.") else askField(p, opt, deps.clock()))
        }

        val pending = p.pendingFieldKey
        var action: DirectorAction? = null
        var note: String? = null

        when {
            pending == null -> {
                val (np, n) = absorbFreeText(p, settings, text.trim())
                p = np; note = n
                p = researchNewReferences(p, settings)
            }
            pending == PENDING_PROPOSALS -> {
                when {
                    AnswerParser.isAffirm(text) -> p = ProjectOps.confirmAllProposed(p, now)
                    AnswerParser.isNegate(text) -> {
                        val cleared = ProjectOps.proposedKeys(p).fold(p) { acc, k -> if (acc.decision(k)?.source == DecisionSource.INFERRED) ProjectOps.clearDecision(acc, k, now) else acc }
                        p = ProjectOps.setPending(ProjectOps.addMessage(cleared, Role.DIRECTOR, "Okay - tell me what I got wrong, or just describe it again and I'll re-read it.", now), null)
                        return DirectorTurn(p)
                    }
                    else -> {
                        val (np, n) = absorbFreeText(p, settings, text.trim()); p = np; note = n
                        p = researchNewReferences(p, settings)
                    }
                }
            }
            pending == PENDING_ASSET_PLAN -> {
                val policyField = Fields.get(Keys.ASSET_POLICY)!!
                if (AnswerParser.isAffirm(text) || AnswerParser.isDelegate(text)) {
                    p = p.copy(assets = p.assets + AssetPlan.resolveMissing(p, now))
                } else when (val a = AnswerParser.parse(policyField, Traits(p), text)) {
                    is Answer.Value -> p = ProjectOps.setDecision(p, Keys.ASSET_POLICY, a.value, DecisionSource.USER, now).copy(assets = emptyList())
                    else -> return DirectorTurn(reply(p, "Say \"looks good\" to accept the asset plan, or tell me to switch the policy (CC0 only / allow CC-BY / only original assets).", PENDING_ASSET_PLAN, assetPlanQuick()))
                }
            }
            pending.startsWith(PENDING_CONFLICT_PREFIX) -> {
                val id = pending.removePrefix(PENDING_CONFLICT_PREFIX).removeSuffix("__")
                val conflict = ConflictEngine.all(p).firstOrNull { it.id == id }
                if (conflict == null) p = ProjectOps.setPending(p, null)
                else {
                    val res = resolveConflictReply(p, conflict, text, now)
                    if (res.second != null) return DirectorTurn(reply(res.first, res.second!!, pending, conflictQuick(conflict)))
                    p = res.first
                }
            }
            pending == PENDING_READY -> {
                if (AnswerParser.isAffirm(text) || "generate" in lower) return generateRequest(p, settings)
                val (np, n) = absorbFreeText(p, settings, text.trim()); p = np; note = n
            }
            else -> {
                val field = Fields.get(pending)
                if (field == null) p = ProjectOps.setPending(p, null)
                else {
                    val r = answerField(p, settings, field, text.trim(), now)
                    p = r.project
                    note = r.modelNote
                    action = r.action
                    if (r.directReply != null) return DirectorTurn(reply(p, r.directReply, field.key, quickFor(field, Traits(p))), action, note)
                    if (action != null) {
                        // Upload requested: keep the question open until the file arrives or the owner changes their mind.
                        val ask = "Pick an image from your phone and I'll keep it as the untouched master. Or say \"create one for me\" if you'd rather I generate it."
                        return DirectorTurn(reply(p, ask, field.key, quickFor(field, Traits(p))), action, note)
                    }
                }
            }
        }
        return DirectorTurn(askNext(p, settings), action, note)
    }

    // ---- Answering a specific field ----------------------------------------------------------------------------

    private class FieldResult(val project: Project, val directReply: String? = null, val action: DirectorAction? = null, val modelNote: String? = null)

    private suspend fun answerField(p0: Project, settings: AppSettings, field: Field, text: String, now: Long): FieldResult {
        var p = p0
        val traits = Traits(p)
        // The concept (and other free text answers) also carry implicit decisions.
        return when (val a = AnswerParser.parse(field, traits, text)) {
            is Answer.Value -> {
                val value = a.value
                p = ProjectOps.setDecision(p, field.key, value, DecisionSource.USER, now)
                var note: String? = null
                if (field.key == Keys.CONCEPT || field.key == Keys.CORE_FANTASY) {
                    val (np, n) = absorbFreeText(p, settings, text); p = np; note = n
                    p = researchNewReferences(p, settings)
                }
                if (field.key == Keys.REFERENCES && value != "none") {
                    p = ProjectOps.addReferences(p, DecisionExtractor.referenceGames(value).ifEmpty { value.split(',', '+', '&').map { it.trim() }.filter { it.isNotEmpty() } }, now)
                    p = ProjectOps.setDecision(p, Keys.REFERENCES, p.references.joinToString(", ") { it.name }, DecisionSource.USER, now)
                }
                if (field.key == Keys.REFERENCES) p = researchNewReferences(p, settings)
                if (field.key in Keys.brandingKeyForSlot.values && value == "upload") {
                    val slot = Keys.brandingKeyForSlot.entries.first { it.value == field.key }.key
                    return FieldResult(p, action = DirectorAction.RequestUpload(slot), modelNote = note)
                }
                if (field.key == Keys.SCOPE_CHOICE) { /* recorded; scope recalculated on demand */ }
                FieldResult(ProjectOps.setPending(p, null), modelNote = note)
            }
            Answer.Delegate -> {
                val d = ProjectOps.delegate(p, field.key, now)
                if (d == null && !field.required) FieldResult(ProjectOps.setPending(ProjectOps.addMessage(ProjectOps.defer(p, field.key, now), Role.DIRECTOR, "Okay, I'll leave ${field.title.lowercase()} out.", now), null))
                else if (d == null) FieldResult(p, directReply = "I can't pick that one for you - it's your idea. ${field.prompt}")
                else {
                    var np = d.first
                    if (field.key == Keys.REFERENCES) np = np.copy(decisions = np.decisions + (Keys.REFERENCES to np.decisions.getValue(Keys.REFERENCES).copy(source = DecisionSource.DIRECTOR_CHOICE)))
                    FieldResult(ProjectOps.setPending(ProjectOps.addMessage(np, Role.DIRECTOR, "Going with: ${Messages.display(np, field.key, d.second.value)}. ${d.second.rationale}", now, field.key), null))
                }
            }
            Answer.Postpone -> FieldResult(ProjectOps.postpone(ProjectOps.addMessage(p, Role.DIRECTOR, "No problem, we'll come back to that.", now), field.key, now))
            Answer.Skip -> FieldResult(ProjectOps.setPending(ProjectOps.defer(p, field.key, now), null))
            is Answer.Question -> {
                val (answer, note) = answerQuestion(p, settings, field, a.text)
                FieldResult(p, directReply = answer, modelNote = note)
            }
            is Answer.Invalid -> FieldResult(p, directReply = a.reason)
            Answer.Affirm, Answer.Negate, Answer.Unclear -> {
                // Maybe the message answers other fields implicitly (voice users often volunteer extra detail).
                val before = p.decisions
                val (np, note) = absorbFreeText(p, settings, text)
                if (np.decisions != before) FieldResult(ProjectOps.setPending(np, null), modelNote = note)
                else FieldResult(p, directReply = Messages.clarify(field, traits))
            }
        }
    }

    private suspend fun answerQuestion(p: Project, settings: AppSettings, field: Field, question: String): Pair<String, String?> {
        val provider = readyProvider()
        val base = Messages.explain(field, Traits(p))
        if (provider != null) {
            val ctx = "Current question: ${field.prompt}\nWhy it matters: ${field.why}\nOptions: ${field.options(Traits(p)).joinToString { it.label }}\nProject concept: ${p.value(Keys.CONCEPT) ?: "(none yet)"}"
            val r = timed { provider.complete(LlmRequest(DirectorPrompts.answerSystem(name(settings), p.prefs.experience == com.hotattic.gamedesigner.core.model.Experience.BEGINNER),
                listOf(LlmMessage("user", "$ctx\n\nOwner asks: $question")), maxTokens = 220)) }
            if (r is LlmResult.Ok && r.text.isNotBlank()) return (r.text.trim() + "\n\n" + Messages.reask(field)) to provider.displayName
        }
        return base to null
    }

    // ---- Free text understanding ------------------------------------------------------------------------------

    private suspend fun readyProvider(): LlmProvider? =
        deps.local?.takeIf { runCatching { it.isReady() }.getOrDefault(false) } ?: deps.cloud?.takeIf { runCatching { it.isReady() }.getOrDefault(false) }

    /** Extracts proposals from free text: deterministic first, model-assisted fill-in second. */
    private suspend fun absorbFreeText(p0: Project, settings: AppSettings, text: String): Pair<Project, String?> {
        val now = deps.clock()
        var ex = DecisionExtractor.extract(text)
        var note: String? = null
        val provider = if (text.length >= 20) readyProvider() else null
        if (provider != null) {
            val r = runCatching {
                timed { provider.complete(LlmRequest(DirectorPrompts.extractionSystem(), listOf(LlmMessage("user", text)), maxTokens = 400, temperature = 0f)) }
            }.getOrNull()
            if (r is LlmResult.Ok) {
                val (raw, refs) = DirectorPrompts.parseExtraction(r.text)
                val clean = DecisionExtractor.sanitize(raw)
                // Deterministic results win on conflict; the model fills gaps.
                ex = Extraction(clean + ex.values, (ex.referenceGames + refs).distinctBy { it.lowercase() })
                note = provider.displayName
            }
        }
        var p = p0
        // Never overwrite the field being asked by implicit extraction of the same message when it already holds a confirmed value.
        p = ProjectOps.applyExtraction(p, ex, now)
        return p to note
    }

    private suspend fun researchNewReferences(p0: Project, settings: AppSettings): Project {
        if (!settings.internetResearchAllowed) return p0
        val research = deps.research ?: return p0
        var p = p0
        for (ref in p0.references.filter { it.summary.isBlank() && it.sources.isEmpty() }) {
            when (val r = runCatching { research.researchReferenceGame(ref.name) }.getOrElse { ResearchOutcome.Unavailable(it.message ?: "error") }) {
                is ResearchOutcome.Found -> {
                    val found = r.value
                    p = ProjectOps.updateReference(p, ref.copy(summary = found.summary, traits = found.traits, sources = found.sources))
                    p = ProjectOps.addMessage(p, Role.SYSTEM, "Researched ${ref.name}: ${found.summary.take(220)}", deps.clock())
                }
                is ResearchOutcome.NotFound -> p = ProjectOps.addMessage(p, Role.SYSTEM, "I couldn't find reliable information on \"${ref.name}\". I'll rely on how you describe it.", deps.clock())
                is ResearchOutcome.Unavailable -> p = ProjectOps.addMessage(p, Role.SYSTEM, "I can't reach the internet right now (${r.reason}), so I'll continue without researching ${ref.name}.", deps.clock())
            }
        }
        return p
    }

    // ---- Commands ----------------------------------------------------------------------------------------------

    private fun isStatusCommand(l: String) = l in setOf("status", "progress", "how complete", "how far along are we", "what's left", "whats left", "what is left", "show status")
    private fun isGenerateCommand(l: String) = l == "generate" || l.startsWith("generate ") && l.length < 60 || l in setOf("create the spec", "make the spec", "build the spec", "generate spec", "generate the spec", "generate claude.md", "generate it")
    private fun isBulkDelegate(l: String) = l in setOf("choose everything for me", "choose all for me", "choose the rest for me", "choose everything remaining for me", "you decide everything", "pick everything for me", "choose all remaining", "decide the rest")

    private fun generateRequest(p: Project, settings: AppSettings): DirectorTurn {
        val c = CompletenessEngine.compute(p)
        val audit = AuditEngine.audit(p)
        val now = deps.clock()
        if (p.mode == ProjectMode.PLAYTEST_CONTINUE) {
            return if (p.feedback.none { it.status == com.hotattic.gamedesigner.core.model.FeedbackStatus.OPEN })
                DirectorTurn(reply(p, "There's no open feedback yet. Tell me what you noticed first."))
            else DirectorTurn(ProjectOps.addMessage(p, Role.DIRECTOR, "Generating the continuation spec and prompt.", now), DirectorAction.GenerateSpec)
        }
        if (audit.passes) return DirectorTurn(ProjectOps.addMessage(p, Role.DIRECTOR, "Everything required is resolved and the audit passes. Generating the spec and prompt now.", now), DirectorAction.GenerateSpec)
        val msg = buildString {
            append("I can't generate a reliable spec yet - ${audit.errors.size} required item(s) still open (${c.percent}% complete):\n")
            audit.errors.take(5).forEach { append("- ${it.message}\n") }
            append("\nSay \"choose everything remaining for me\" and I'll resolve what I can, or answer the next question.")
        }
        return DirectorTurn(reply(p, msg, quick = listOf(QuickReply("Choose everything remaining for me", "choose everything remaining for me"), QuickReply("Show status", "status"))))
    }

    private fun bulkDelegate(p0: Project, settings: AppSettings): DirectorTurn {
        val now = deps.clock()
        var p = p0
        var count = 0
        repeat(120) {
            when (val step = InterviewPlanner.next(p)) {
                is NextStep.Ask -> {
                    val d = ProjectOps.delegate(p, step.field.key, now)
                    if (d == null) return DirectorTurn(askNext(finishBulk(p, count, now), settings))
                    p = d.first; count++
                }
                is NextStep.Confirm -> { p = ProjectOps.confirm(p, step.field.key, now); count++ }
                is NextStep.AssetPlanStep -> { p = p.copy(assets = p.assets + AssetPlan.resolveMissing(p, now)); count++ }
                is NextStep.ResolveConflicts, is NextStep.Optional, NextStep.Ready -> return DirectorTurn(askNext(finishBulk(p, count, now), settings))
            }
        }
        return DirectorTurn(askNext(finishBulk(p, count, now), settings))
    }

    private fun finishBulk(p: Project, count: Int, now: Long) =
        ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, if (count == 0) "There was nothing I could choose for you right now." else "Done - I chose $count thing(s) using what I'd recommend. You can change any of them later.", now), null)

    // ---- Playtest mode -----------------------------------------------------------------------------------------

    private fun playtestTurn(p0: Project, settings: AppSettings, text: String): DirectorTurn {
        val now = deps.clock()
        val l = text.lowercase()
        if (isGenerateCommand(l)) return generateRequest(p0, settings)
        if (isStatusCommand(l)) return DirectorTurn(reply(p0, "${p0.feedback.count { it.status == com.hotattic.gamedesigner.core.model.FeedbackStatus.OPEN }} open feedback item(s) against v${p0.versions.lastOrNull()?.number ?: 0}."))
        val fb = PlaytestFeedback(id = "f${p0.feedback.size + 1}_$now", createdAt = now, againstVersion = p0.versions.lastOrNull()?.number ?: 0, text = text, severity = guessSeverity(l))
        val p = p0.copy(feedback = p0.feedback + fb, updatedAt = now)
        val msg = "Logged as ${fb.severity.name.lowercase()}. Anything else? Say \"generate\" when you're done and I'll build the continuation spec."
        return DirectorTurn(reply(p, msg, quick = listOf(QuickReply("Generate continuation spec", "generate"))))
    }

    fun guessSeverity(l: String): FeedbackSeverity = when {
        listOf("crash", "freez", "won't start", "wont start", "won't open", "stuck", "softlock", "soft lock", "can't progress", "cant progress", "lost my save", "black screen").any { it in l } -> FeedbackSeverity.BLOCKER
        listOf("broken", "doesn't work", "doesnt work", "bug", "wrong", "too hard", "too easy", "unplayable", "laggy", "lag", "slow").any { it in l } -> FeedbackSeverity.MAJOR
        listOf("would be nice", "could use", "wish", "maybe add", "idea", "suggest", "it'd be cool", "nice to have").any { it in l } -> FeedbackSeverity.SUGGESTION
        else -> FeedbackSeverity.MAJOR
    }

    // ---- Conflicts ---------------------------------------------------------------------------------------------

    /** Returns the updated project, plus a message if the reply could not be understood (project unchanged then). */
    private fun resolveConflictReply(p: Project, c: Conflict, text: String, now: Long): Pair<Project, String?> {
        val l = text.lowercase()
        val keep = listOf("keep my", "keep it", "override", "anyway", "my choice", "proceed", "stay with", "i want it", "i'm sure", "im sure", "leave it", "stick with").any { it in l }
        val alt = pickAlternative(c, l)
        return when {
            alt != null -> ProjectOps.setPending(ProjectOps.applyAlternative(p, c.id, alt, now), null) to null
            keep && !c.overridable -> p to "I can't proceed with that - ${c.message} ${c.recommendation}"
            keep -> ProjectOps.setPending(ProjectOps.overrideConflict(p, c, now), null) to null
            (AnswerParser.isAffirm(l) || "recommend" in l || "accept" in l) && c.alternatives.isNotEmpty() ->
                ProjectOps.setPending(ProjectOps.applyAlternative(p, c.id, c.alternatives.first(), now), null) to null
            (AnswerParser.isAffirm(l) || "recommend" in l || "accept" in l) -> ProjectOps.setPending(ProjectOps.acknowledge(p, c.id, AckChoice.ACCEPTED_RECOMMENDATION, now), null) to null
            AnswerParser.looksLikeQuestion(text) -> p to (c.message + " " + c.recommendation)
            else -> p to "Do you want to ${if (c.alternatives.isNotEmpty()) "use my recommendation (${c.alternatives.first().label})" else "follow my recommendation"}, or keep your choice?"
        }
    }

    private fun pickAlternative(c: Conflict, l: String): Alternative? {
        Regex("(?:use )?(?:alternative |option )?([0-9])\\b").find(l)?.let { m ->
            if ("alternative" in l || "option" in l || l.trim().length <= 3) c.alternatives.getOrNull(m.groupValues[1].toInt() - 1)?.let { return it }
        }
        return c.alternatives.firstOrNull { a -> l.contains(a.label.lowercase()) }
    }

    private fun conflictQuick(c: Conflict): List<QuickReply> = buildList {
        c.alternatives.take(2).forEachIndexed { i, a -> add(QuickReply(a.label, "use alternative ${i + 1}")) }
        if (c.overridable) add(QuickReply("Keep my choice", "keep my choice"))
    }

    // ---- Composing the next message ---------------------------------------------------------------------------

    private fun assetPlanQuick() = listOf(QuickReply("Looks good", "looks good"), QuickReply("CC0 only", "cc0 only"), QuickReply("Original assets only", "only original assets"))

    private fun quickFor(field: Field, t: Traits): List<QuickReply> = buildList {
        val opts = field.options(t)
        if (field.kind != FieldKind.TEXT) opts.take(8).forEach { add(QuickReply(it.label, it.label)) }
        if (field.suggest(t) != null) add(QuickReply("Choose for me", "choose for me"))
        if (!field.required) add(QuickReply("Skip", "skip"))
        else if (field.key != Keys.CONCEPT) add(QuickReply("Ask me later", "ask me later"))
    }

    private fun reply(p: Project, text: String, pending: String? = p.pendingFieldKey, quick: List<QuickReply> = emptyList()): Project =
        ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, text, deps.clock(), pending?.takeUnless { it.startsWith("__") }, quick), pending)

    /** Chooses the next thing to say. Announces at most one new conflict per turn, before the next question. */
    internal fun askNext(p0: Project, settings: AppSettings): Project {
        var p = p0
        val now = deps.clock()

        // 1. Batch-confirm freshly inferred proposals so the owner isn't asked about each one.
        val proposals = ProjectOps.proposedKeys(p).filter { Fields.get(it)?.let { f -> f.isRelevant(Traits(p)) } == true || it in setOf(Keys.GENRE, Keys.DIMENSION, Keys.PLATFORMS, Keys.REFERENCES) }
        if (proposals.isNotEmpty() && p.pendingFieldKey != PENDING_PROPOSALS && proposals.any { it !in p.announcedConflicts }) {
            val lines = proposals.mapNotNull { k -> Fields.get(k)?.let { f -> "- ${f.title}: ${Messages.display(p, k, p.value(k).orEmpty())}" } }
            if (lines.isNotEmpty()) {
                val msg = "Here's what I understood so far:\n${lines.joinToString("\n")}\n\nIs that right? Say \"yes\" or tell me what to change."
                p = p.copy(announcedConflicts = p.announcedConflicts + proposals)
                return ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, msg, now, quick = listOf(QuickReply("Yes", "yes"), QuickReply("Not quite", "no"))), PENDING_PROPOSALS)
            }
        }

        // 2. Announce one new conflict, if any.
        val fresh = ConflictEngine.all(p).firstOrNull { c ->
            c.severity != Severity.NOTE && ("conflict:${c.id}" !in p.announcedConflicts) && (c.id !in p.conflictAcks.map { it.conflictId } || !c.overridable)
        }
        if (fresh != null) {
            p = p.copy(announcedConflicts = p.announcedConflicts + "conflict:${fresh.id}")
            val msg = Messages.conflict(fresh)
            return ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, msg, now, quick = conflictQuick(fresh)), PENDING_CONFLICT_PREFIX + fresh.id + "__")
        }
        // Notes: mention once, no blocking.
        val note = ConflictEngine.notes(p).firstOrNull { "conflict:${it.id}" !in p.announcedConflicts }
        if (note != null) {
            p = p.copy(announcedConflicts = p.announcedConflicts + "conflict:${note.id}")
            p = ProjectOps.addMessage(p, Role.DIRECTOR, "Note: ${note.title}. ${note.message}", now)
        }

        return when (val step = InterviewPlanner.next(p)) {
            is NextStep.Ask -> askField(p, step.field, now)
            is NextStep.Confirm -> {
                val f = step.field
                val msg = "I'm assuming ${f.title.lowercase()} is ${Messages.display(p, f.key, step.proposedValue)}. Right?"
                ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, msg, now, quick = listOf(QuickReply("Yes", "yes"), QuickReply("Change it", "no"))), PENDING_PROPOSALS)
            }
            is NextStep.AssetPlanStep -> {
                val t = Traits(p)
                val lines = step.needs.map { n ->
                    val r = AssetPlan.defaultRecord(n, t, now)
                    "- ${n.label}: " + if (r.resolution == com.hotattic.gamedesigner.core.model.AssetResolution.EXTERNAL_CC0) "CC0 sets from ${r.source.substringBefore(" (")} and similar, with original procedural fallback" else "original, generated by code"
                }
                val msg = "Asset plan (default policy: ${Messages.display(p, Keys.ASSET_POLICY, p.value(Keys.ASSET_POLICY) ?: "cc0_default")}):\n${lines.joinToString("\n")}\n\nThe build verifies each file's license, logs provenance, and builds an original procedural replacement for anything it can't find under a clean license. Okay?"
                ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, msg, now, quick = assetPlanQuick()), PENDING_ASSET_PLAN)
            }
            is NextStep.ResolveConflicts -> {
                val c = step.conflicts.first()
                ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, Messages.conflict(c), now, quick = conflictQuick(c)), PENDING_CONFLICT_PREFIX + c.id + "__")
            }
            is NextStep.Optional -> askField(p, step.field, now)
            NextStep.Ready -> readyMessage(p, now)
        }
    }

    private fun readyMessage(p: Project, now: Long): Project {
        val audit = AuditEngine.audit(p)
        val scope = ScopeEngine.recommend(p)
        val optional = InterviewPlanner.nextOptional(p)
        if (!audit.passes) {
            val msg = "Almost there, but the audit found blocking items:\n" + audit.errors.take(5).joinToString("\n") { "- ${it.message}" }
            return ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, msg, now), null)
        }
        val msg = "The design is complete and the audit passes (${audit.warnings.size} warning(s)). " +
            "Scope: ${scope.effectiveTier.label}; Claude usage estimate: ${scope.resources.level.label}. Ready to generate the CLAUDE.md and master prompt?" +
            if (optional != null) "\nI also have optional polish questions (like color and mood) - say \"refine\" to answer them." else ""
        val quick = buildList {
            add(QuickReply("Generate spec + prompt", "generate"))
            if (optional != null) add(QuickReply("Refine details", "refine"))
            add(QuickReply("Show status", "status"))
        }
        return ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, msg, now, quick = quick), PENDING_READY)
    }

    private fun askField(p: Project, field: Field, now: Long): Project {
        val t = Traits(p)
        val sb = StringBuilder()
        Messages.preface(p, field)?.let { sb.append(it).append("\n\n") }
        sb.append(field.prompt)
        val opts = field.options(t)
        if (field.kind != FieldKind.TEXT && opts.isNotEmpty() && p.prefs.experience == com.hotattic.gamedesigner.core.model.Experience.BEGINNER) {
            sb.append("\n")
            opts.take(8).forEachIndexed { i, o -> sb.append("\n${i + 1}. ${o.label}").append(if (o.description.isNotBlank()) " - ${o.description}" else "") }
        }
        val s = field.suggest(t)
        if (s != null && field.kind != FieldKind.TEXT) sb.append("\n\nI'd go with: ${Messages.display(p, field.key, s.value)}. ${s.rationale}")
        else if (s != null) sb.append("\n\nOr say \"choose for me\" and I'll draft it.")
        return ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, sb.toString(), now, field.key, quickFor(field, t)), field.key)
    }
}
