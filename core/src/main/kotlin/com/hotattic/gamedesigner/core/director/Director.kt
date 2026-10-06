package com.hotattic.gamedesigner.core.director

import com.hotattic.gamedesigner.core.engine.Alternative
import com.hotattic.gamedesigner.core.engine.Answer
import com.hotattic.gamedesigner.core.engine.AnswerIntent
import com.hotattic.gamedesigner.core.engine.Interpretation
import com.hotattic.gamedesigner.core.engine.DerivedDefaults
import com.hotattic.gamedesigner.core.engine.DesignSeeder
import com.hotattic.gamedesigner.core.engine.ReviewGate
import com.hotattic.gamedesigner.core.generate.DesignReview
import com.hotattic.gamedesigner.core.engine.MetaConversation
import com.hotattic.gamedesigner.core.model.BrandingAsset
import com.hotattic.gamedesigner.core.model.BrandingMode
import com.hotattic.gamedesigner.core.model.BrandingSlot
import com.hotattic.gamedesigner.core.engine.InterpreterKind
import com.hotattic.gamedesigner.core.engine.LlmInterpreter
import com.hotattic.gamedesigner.core.engine.LocalInterpreter
import com.hotattic.gamedesigner.core.engine.Reconciler
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
import com.hotattic.gamedesigner.core.model.ChoiceOption
import com.hotattic.gamedesigner.core.model.PlaytestFeedback
import com.hotattic.gamedesigner.core.model.Provenance
import com.hotattic.gamedesigner.core.model.QuestionSpec
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
    /** What interpreted the owner's words this turn (rules only, on-device model, cloud model). */
    val interpreter: InterpreterKind? = null,
    /** True when this action had already been committed (same turn id); nothing was changed. */
    val duplicate: Boolean = false,
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
private const val PENDING_REVIEW = "__review__"
private const val PENDING_MORE_REFS = "__more_refs__"
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
                ProjectOps.setPending(ProjectOps.addMessage(project, Role.DIRECTOR, msg, now, f.key, question = QuestionSpec(f.key, "TEXT")), f.key)
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

    /** Opens one specific question (used by "edit" and "refine"). */
    /**
     * Back: takes back the owner's most recent answer and asks that question again, with its choices. Anything Bob derived from it
     * is released by the Reconciler; other owner decisions are untouched. Repeated Back walks further back.
     */
    fun goBack(project: Project): Project? {
        val now = deps.clock()
        val key = project.answerTrail.lastOrNull() ?: return null
        val field = Fields.get(key) ?: return project.copy(answerTrail = project.answerTrail - key)
        val cleared = ProjectOps.clearDecision(project, key, now).copy(answerTrail = project.answerTrail - key, lastAnsweredKey = null, pendingTurn = null)
        val settled = reconciled(project, cleared, now)
        return askField(ProjectOps.setPending(ProjectOps.addMessage(settled, Role.DIRECTOR, "Okay, going back. I've cleared your answer to \"${field.title}\".", now), null), field, now)
    }

    fun askAbout(project: Project, fieldKey: String): Project = Fields.get(fieldKey)?.let { askField(ProjectOps.setPending(project, null), it, deps.clock()) } ?: project

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

    private fun Project.withTrail(key: String): Project = copy(answerTrail = (answerTrail.filter { it != key } + key).takeLast(30))

    private fun Project.withTurn(id: String?): Project = if (id == null) this else copy(processedTurns = (processedTurns + id).takeLast(64))

    /**
     * One owner message is one logical transaction: interpret, mutate, respond, ask the next question. A repeated
     * [turnId] (a duplicated callback, a retry, a replay after restoration) returns the project untouched.
     */
    suspend fun handleUserMessage(project: Project, settings: AppSettings, text: String, turnId: String? = null): DirectorTurn {
        if (turnId != null && turnId in project.processedTurns) {
            return DirectorTurn(if (project.pendingTurn?.id == turnId) project.copy(pendingTurn = null) else project, duplicate = true)
        }
        val t = handleUserMessageOnce(project, settings, text)
        val done = if (turnId == null) t.project else t.project.withTurn(turnId)
        return t.copy(project = if (done.pendingTurn?.id == turnId) done.copy(pendingTurn = null) else done)
    }

    /**
     * Durably records an owner message BEFORE slow (local-model) inference runs. If inference is cancelled, times out, or the
     * process dies, the message is still on disk and [pendingTurnOf] lets the app resume it exactly once (the turn id guards
     * against a second application). Returns null when another message is already waiting.
     */
    fun queueTurn(project: Project, text: String, turnId: String): Project? {
        if (turnId in project.processedTurns) return project
        val waiting = project.pendingTurn
        if (waiting != null && waiting.id != turnId) return null
        return project.copy(pendingTurn = com.hotattic.gamedesigner.core.model.PendingTurn(turnId, text.trim(), deps.clock()))
    }

    private suspend fun handleUserMessageOnce(project: Project, settings: AppSettings, text: String): DirectorTurn {
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
        var kind: InterpreterKind? = null

        // Conversation repair: talk about the app ("I already uploaded it", "I already answered that") never becomes a requirement.
        if (pending != null && !pending.startsWith("__") && MetaConversation.isMeta(text.trim()) && !LocalInterpreter.interpret(p, null, text).misunderstood) {
            val field = Fields.get(pending)
            if (field != null) {
                val slot = Keys.brandingKeyForSlot.entries.firstOrNull { it.value == pending }?.key
                val uploaded = slot?.let { p.branding[it] }?.takeIf { it.mode == BrandingMode.UPLOADED }
                val have = p.decision(pending)?.takeIf { it.status == DecisionStatus.CONFIRMED && it.value.isNotBlank() }
                when {
                    uploaded != null -> {
                        p = ProjectOps.setDecision(p, pending, "upload", Provenance.OWNER_EXPLICIT, now, raw = "uploaded ${uploaded.originalName}")
                        p = ProjectOps.addMessage(p, Role.DIRECTOR, "You're right - I have your ${slotTitle(slot!!)} (${uploaded.originalName}, ${uploaded.width}x${uploaded.height}). Moving on.", now)
                        return DirectorTurn(askNext(ProjectOps.setPending(p, null), settings))
                    }
                    have != null -> {
                        p = ProjectOps.addMessage(p, Role.DIRECTOR, "You're right - I already have that: ${Messages.display(p, pending, have.value)}. Moving on.", now)
                        return DirectorTurn(askNext(ProjectOps.setPending(p, null), settings))
                    }
                    MetaConversation.designSentences(text).isEmpty() -> return DirectorTurn(replyField(p, "Sorry about that - I may have missed it. ${field.prompt}", field))
                }
            }
        }

        suspend fun absorbInto(field: Field?) {
            val r = absorb(p, settings, text.trim(), field)
            p = r.project; note = r.note; kind = r.kind
        }

        when {
            pending == null -> absorbInto(null)
            pending == PENDING_PROPOSALS -> {
                val plain = DecisionExtractor.extract(text).isEmpty
                when {
                    AnswerParser.isAffirm(text) && plain -> p = ProjectOps.confirmAllProposed(p, now)
                    AnswerParser.isNegate(text) && plain -> {
                        val cleared = ProjectOps.proposedKeys(p).fold(p) { acc, k -> if (acc.decision(k)?.source == DecisionSource.INFERRED) ProjectOps.clearDecision(acc, k, now) else acc }
                        p = ProjectOps.setPending(ProjectOps.addMessage(cleared, Role.DIRECTOR, "Okay - tell me what I got wrong, or just describe it again and I'll re-read it.", now), null)
                        return DirectorTurn(p)
                    }
                    else -> absorbInto(null)
                }
            }
            pending == PENDING_ASSET_PLAN -> {
                val policyField = Fields.get(Keys.ASSET_POLICY)!!
                if (AnswerParser.isAffirm(text) || AnswerParser.isDelegate(text)) {
                    p = p.copy(assets = p.assets + AssetPlan.resolveMissing(p, now))
                } else when (val a = AnswerParser.parse(policyField, Traits(p), text)) {
                    is Answer.Value -> { p = ProjectOps.setDecision(p, Keys.ASSET_POLICY, a.value, Provenance.OWNER_EXPLICIT, now, raw = text.trim()).copy(assets = emptyList()); p = p.copy(assets = AssetPlan.resolveMissing(p, now)) }
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
            pending == PENDING_REVIEW -> {
                val sectionKey = if (text.trim().split(Regex("\\s+")).size <= 8 && Regex("(?i)^(change|edit|redo|fix|revise|update)\\b").containsMatchIn(text.trim())) DesignReview.sectionKeyFor(text) else null
                when {
                    isApproval(text) -> return approveReview(p, "owner")
                    AnswerParser.isDelegate(text) -> return approveReview(p, "delegated")
                    sectionKey != null && Fields.get(sectionKey) != null -> return DirectorTurn(askField(ProjectOps.setPending(p, null), Fields.get(sectionKey)!!, now))
                    Regex("(?i)^(i want to change something|change something|something'?s wrong|not quite|no|nope|something is off)\\W*$").containsMatchIn(text.trim()) ->
                        return DirectorTurn(reply(p, reviewChangePrompt, PENDING_REVIEW, reviewQuick(), reviewSpec()))
                    else -> {
                        val before = ReviewGate.fingerprint(p)
                        absorbInto(null)
                        if (ReviewGate.fingerprint(p) == before) return DirectorTurn(reply(p, "I didn't catch a change in that. $reviewChangePrompt", PENDING_REVIEW, reviewQuick(), reviewSpec()))
                    }
                }
            }
            pending == PENDING_MORE_REFS -> {
                val t = lower.trim('.', '!', ' ')
                val refField = Fields.get(Keys.REFERENCES)!!
                when {
                    t in setOf("done", "that's all", "thats all", "that's it", "thats it", "no more", "no", "nope", "none", "i'm done", "im done", "finished", "next") -> p = ProjectOps.setPending(p, null)
                    t in setOf("add another", "add another game", "another", "one more", "yes", "yeah", "yep", "sure") ->
                        return DirectorTurn(reply(p, "Which game is next? Name it, and say what you like about it if you want.", PENDING_MORE_REFS, moreRefsQuick(false)))
                    else -> {
                        val r = answerField(p, settings, refField, text.trim(), now)
                        p = r.project; note = r.modelNote; kind = r.kind
                        return DirectorTurn(moreRefsPrompt(p), null, note, kind)
                    }
                }
            }
            pending == PENDING_READY -> {
                if (AnswerParser.isAffirm(text) || "generate" in lower) return generateRequest(p, settings)
                absorbInto(null)
            }
            else -> {
                val field = Fields.get(pending)
                if (field == null) p = ProjectOps.setPending(p, null)
                else {
                    val r = answerField(p, settings, field, text.trim(), now)
                    p = r.project
                    note = r.modelNote
                    kind = r.kind
                    action = r.action
                    if (r.directReply != null) return DirectorTurn(replyField(p, r.directReply, field), action, note, kind)
                    // Several inspirations are normal: offer to add more instead of silently moving on after one.
                    if (field.key == Keys.REFERENCES && p.references.isNotEmpty() && p.value(Keys.REFERENCES) != "none" && action == null)
                        return DirectorTurn(moreRefsPrompt(p), null, note, kind)
                    if (action != null) {
                        // Upload requested: keep the question open until the file arrives or the owner changes their mind.
                        val ask = "Pick an image from your phone and I'll keep it as the untouched master. Or say \"create one for me\" if you'd rather I generate it."
                        return DirectorTurn(uploadPrompt(p, ask, field), action, note, kind)
                    }
                }
            }
        }
        return DirectorTurn(askNext(p, settings), action, note, kind)
    }

    /** Structured answer from the question card (chips / toggles). No language interpretation is involved. */
    suspend fun submitSelection(project: Project, settings: AppSettings, fieldKey: String, ids: List<String>, turnId: String? = null): DirectorTurn {
        val qMsg = project.messages.lastOrNull { it.question?.fieldKey == fieldKey }
        val tid = turnId ?: qMsg?.let { "sel:${it.id}" }
        // A second tap on a question that was already answered (or is no longer open) must not act on the next question.
        if ((tid != null && tid in project.processedTurns) || project.pendingFieldKey != fieldKey) return DirectorTurn(project, duplicate = true)
        val t = submitSelectionOnce(project, settings, fieldKey, ids)
        return t.copy(project = t.project.withTurn(tid))
    }

    private suspend fun submitSelectionOnce(project: Project, settings: AppSettings, fieldKey: String, ids: List<String>): DirectorTurn {
        val now = deps.clock()
        if (fieldKey == PENDING_ASSET_PLAN) return assetPlanSelection(project, settings, ids.firstOrNull(), now)
        if (fieldKey == PENDING_REVIEW) {
            val said = ProjectOps.addMessage(project, Role.USER, if (ids.firstOrNull() == "looks_right") "Looks right" else "I want to change something", now)
            return if (ids.firstOrNull() == "looks_right") approveReview(said, "owner") else DirectorTurn(reply(said, reviewChangePrompt, PENDING_REVIEW, reviewQuick(), reviewSpec()))
        }
        val field = Fields.get(fieldKey) ?: return DirectorTurn(project)
        val t = Traits(project)
        val options = field.options(t)
        val valid = ids.filter { id -> options.any { it.id == id } }
        val labels = valid.map { id -> options.first { it.id == id }.label }
        var p = ProjectOps.addMessage(project, Role.USER, if (labels.isEmpty()) "None of these" else labels.joinToString(", "), now)
        if (valid.isEmpty()) {
            val r = if (!field.required) ProjectOps.setPending(ProjectOps.defer(p, field.key, now), null) else ProjectOps.postpone(ProjectOps.addMessage(p, Role.DIRECTOR, "No problem, we'll come back to that.", now), field.key, now)
            return DirectorTurn(askNext(r, settings))
        }
        val value = Decision.joinList(if (field.kind == FieldKind.SINGLE) valid.take(1) else valid)
        val res = commitValue(p, settings, field, value, labels.joinToString(", "), now)
        if (res.directReply != null) return DirectorTurn(replyField(res.project, res.directReply, field), res.action, res.modelNote)
        if (res.action != null) return DirectorTurn(uploadPrompt(res.project, "Pick an image from your phone and I'll keep it as the untouched master. Or say \"create one for me\" if you'd rather I generate it.", field), res.action, res.modelNote)
        return DirectorTurn(askNext(res.project, settings), res.action, res.modelNote)
    }

    private val reviewChangePrompt = "Tell me what to change in your own words, or name a part to edit (world, loop, failure, progression, first build, look, sound, completion, must-not-change)."

    private fun isApproval(text: String): Boolean {
        val l = text.lowercase()
        if (Regex("\\b(not|no|never|wrong|incorrect|but|except)\\b|n't").containsMatchIn(l)) return false
        return AnswerParser.isAffirm(text) || Regex("\\b(looks? (right|good|correct|great)|that'?s (right|it|the game|correct)|approved?|go ahead|build it|ship it)\\b").containsMatchIn(l)
    }

    private fun reviewQuick() = listOf(QuickReply("Looks right", "looks right"), QuickReply("I want to change something", "I want to change something"))
    private fun reviewSpec() = QuestionSpec(PENDING_REVIEW, "SINGLE", listOf(ChoiceOption("looks_right", "Looks right"), ChoiceOption("change", "I want to change something")), canDelegate = true)

    private fun approveReview(p: Project, by: String): DirectorTurn {
        val now = deps.clock()
        var q = ReviewGate.approve(p, now, by)
        q = ProjectOps.addMessage(q, Role.DIRECTOR, if (by == "delegated") "Okay - I'll treat that review as approved and lock the design in." else "Locked in. Generating the build package from the design you approved.", now)
        return generateRequest(ProjectOps.setPending(q, null), AppSettings())
    }

    private fun reviewMessage(p: Project, now: Long): Project {
        val audit = AuditEngine.audit(p)
        if (!audit.passes) {
            val msg = "Almost there, but the audit found blocking items:\n" + audit.errors.take(5).joinToString("\n") { "- ${it.message}" }
            return ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, msg, now), null)
        }
        return ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, DesignReview.compose(p), now, quick = reviewQuick(), question = reviewSpec()), PENDING_REVIEW)
    }

    /** Structured answer to the asset-plan card: accept the plan, or fix the licensing policy and accept the resulting plan. */
    private fun assetPlanSelection(project: Project, settings: AppSettings, id: String?, now: Long): DirectorTurn {
        val labels = mapOf("looks_good" to "Looks good", "cc0_default" to "CC0 only", "original_only" to "Original assets only")
        var p = ProjectOps.addMessage(project, Role.USER, labels[id] ?: "Looks good", now)
        if (id == "cc0_default" || id == "original_only") {
            p = ProjectOps.setDecision(p, Keys.ASSET_POLICY, id, Provenance.OWNER_EXPLICIT, now, raw = labels.getValue(id)).copy(assets = emptyList())
        }
        p = p.copy(assets = p.assets + AssetPlan.resolveMissing(p, now))
        return DirectorTurn(askNext(ProjectOps.setPending(p, null), settings))
    }

    private fun slotTitle(slot: String) = when (slot) { BrandingSlot.ICON -> "game icon"; BrandingSlot.STUDIO_SPLASH -> "studio logo"; else -> "game splash image" }

    /**
     * A file was durably ingested for [slot]. Records the owner-supplied asset, satisfies that question, and advances the
     * conversation exactly once (a repeated callback for the same bytes is a no-op).
     */
    fun attachmentReceived(project: Project, settings: AppSettings, slot: String, asset: BrandingAsset, turnId: String? = null): DirectorTurn {
        val now = deps.clock()
        val key = Keys.brandingKeyForSlot[slot] ?: return DirectorTurn(project)
        val tid = turnId ?: "att:$slot:${asset.sha256}"
        val existing = project.branding[slot]
        val same = existing?.sha256 == asset.sha256 && existing.mode == BrandingMode.UPLOADED && project.value(key) == "upload"
        if (tid in project.processedTurns || (same && project.pendingFieldKey != key)) return DirectorTurn(project, duplicate = true)
        var p = project.copy(branding = project.branding + (slot to asset))
        p = ProjectOps.setDecision(p, key, "upload", Provenance.OWNER_EXPLICIT, now, raw = "uploaded ${asset.originalName}")
        val advancing = project.pendingFieldKey == key
        val what = slotTitle(slot)
        if (advancing) p = ProjectOps.addMessage(p, Role.USER, "Attached ${asset.originalName} (${asset.width}x${asset.height}) as the $what.", now)
        p = ProjectOps.addMessage(p, if (advancing) Role.DIRECTOR else Role.SYSTEM, "Saved your $what (${asset.originalName}, ${asset.width}x${asset.height}). I keep it untouched as the master and derive every size from it.", now)
        if (advancing) p = askNext(ProjectOps.setPending(p, null), settings)
        return DirectorTurn(p.withTurn(tid))
    }

    // ---- Answering a specific field ----------------------------------------------------------------------------

    private data class FieldResult(val project: Project, val directReply: String? = null, val action: DirectorAction? = null, val modelNote: String? = null, val kind: InterpreterKind? = null)

    private fun reconciled(before: Project, after: Project, now: Long): Project {
        val r = Reconciler.reconcile(before, after, now)
        return if (r.notices.isEmpty()) r.project else ProjectOps.addMessage(r.project, Role.DIRECTOR, r.notices.joinToString("\n"), now)
    }

    /** Records the answer for [field] as an owner decision (with their words kept as raw), then handles field-specific effects. */
    private suspend fun commitValue(p0: Project, settings: AppSettings, field: Field, value: String, raw: String, now: Long, interp: Interpretation? = null): FieldResult {
        var p = p0
        // "Top-down" and "Isometric" answer the dimension question and the camera in one tap.
        if (field.key == Keys.DIMENSION && value in setOf("top_down", "isometric")) {
            val (dim, cam) = if (value == "top_down") "2D" to "top_down" else "2.5D" to "isometric_2d"
            p = ProjectOps.setDecision(p, Keys.PERSPECTIVE, cam, Provenance.OWNER_EXPLICIT, now, raw = raw)
            return commitValue(p, settings, field, dim, raw, now, interp)
        }
        val recommended = p.decision(field.key)
        val acceptedRec = interp?.acceptsRecommendation == true && recommended != null && recommended.status == DecisionStatus.PROPOSED && recommended.value == value
        p = ProjectOps.setDecision(p, field.key, value, if (acceptedRec) Provenance.OWNER_ACCEPTED_RECOMMENDATION else Provenance.OWNER_EXPLICIT, now, raw = raw).copy(lastAnsweredKey = field.key).withTrail(field.key)
        var note: String? = null
        var kind: InterpreterKind? = null
        if (field.key == Keys.CONCEPT) p = ProjectOps.setOriginalConcept(p, raw)
        if (field.key == Keys.CONCEPT || field.key == Keys.CORE_FANTASY || field.key == Keys.FIVE_MINUTES) {
            val r = absorb(p, settings, raw, null, field, interp)
            p = r.project; note = r.note; kind = r.kind
            p = researchNewReferences(p, settings)
        }
        if (field.key == Keys.REFERENCES && value != "none") {
            p = ProjectOps.addReferences(p, DecisionExtractor.referenceGames(value).ifEmpty { value.split(',', '+', '&').map { it.trim() }.filter { it.isNotEmpty() } }, now)
            p = ProjectOps.setDecision(p, Keys.REFERENCES, p.references.joinToString(", ") { it.name }, Provenance.OWNER_EXPLICIT, now, raw = raw)
        }
        if (field.key == Keys.REFERENCES) p = researchNewReferences(p, settings)
        if (field.key in Keys.brandingKeyForSlot.values && value == "upload") {
            val slot = Keys.brandingKeyForSlot.entries.first { it.value == field.key }.key
            return FieldResult(p, action = DirectorAction.RequestUpload(slot), modelNote = note, kind = kind)
        }
        return FieldResult(ProjectOps.setPending(reconciled(p0, p, now), null), modelNote = note, kind = kind)
    }

    private suspend fun answerField(p0: Project, settings: AppSettings, field: Field, text: String, now: Long): FieldResult {
        val traits = Traits(p0)
        val interp = interpret(p0, settings, field, text)
        val kind = interp.by
        val model = if (kind == InterpreterKind.RULES) null else kind.label
        // "That's not what I meant": undo what Bob last recorded from the owner's answer and ask again, rather than building on a misreading.
        if (interp.misunderstood) {
            val last = p0.lastAnsweredKey?.takeIf { it != field.key }?.let { Fields.get(it) }
            if (last != null && p0.decision(last.key) != null) {
                val cleared = ProjectOps.clearDecision(p0, last.key, now).copy(lastAnsweredKey = null)
                return FieldResult(ProjectOps.setPending(ProjectOps.addMessage(cleared, Role.DIRECTOR, "Sorry, I misread that. I've taken back what I recorded for ${last.title.lowercase()}. ${last.prompt}", now, last.key), last.key), kind = kind)
            }
            return FieldResult(p0, directReply = "Sorry, I misread that. Tell me again in your own words. ${field.prompt}", kind = kind)
        }
        // Statements that could describe two different games: ask, don't guess. Everything else in the message still lands.
        if (interp.ambiguities.isNotEmpty() && interp.intent != AnswerIntent.QUESTION) {
            val applied = applyStatement(p0, text, interp.copy(ambiguities = emptyList(), edits = emptyMap()), field, now)
            val q = interp.ambiguities.first().trim().trimEnd('.', '?')
            return FieldResult(applied.project, directReply = (applied.announcement.takeIf { it.isNotBlank() }?.plus("\n\n") ?: "") + "Before I record that, one thing could go two ways: $q? Tell me which you mean.", kind = kind)
        }
        // Corrections volunteered alongside the answer ("no, it's not turn based") apply first, so the answer is judged in their light.
        var p = if (interp.hasCorrections || interp.affirmedTags.isNotEmpty() || (field.key != Keys.CONCEPT && interp.edits.isNotEmpty() && interp.intent == AnswerIntent.UNCLEAR)) applyStatement(p0, text, interp, field, now).project else p0
        return when (interp.intent) {
            AnswerIntent.SELECT, AnswerIntent.ALL, AnswerIntent.FREEFORM -> {
                val value = when {
                    field.kind.isSelect -> Decision.joinList(interp.selected.ifEmpty { interp.value?.split(Decision.LIST_SEPARATOR).orEmpty() }.let { if (field.kind == FieldKind.SINGLE) it.take(1) else it })
                    else -> interp.value ?: text
                }
                val err = if (value.isBlank()) "I need an answer for that one." else field.validate(Traits(p), value)
                if (err != null) FieldResult(p, directReply = err, kind = kind)
                else commitValue(p, settings, field, value, text, now, interp).let { r -> r.copy(modelNote = r.modelNote ?: model, kind = kind) }
            }
            AnswerIntent.DELEGATE -> {
                val d = ProjectOps.delegate(p, field.key, now)
                if (d == null && !field.required) FieldResult(ProjectOps.setPending(ProjectOps.addMessage(ProjectOps.defer(p, field.key, now), Role.DIRECTOR, "Okay, I'll leave ${field.title.lowercase()} out.", now), null), kind = kind)
                else if (d == null) FieldResult(p, directReply = "I can't pick that one for you - it's your idea. ${field.prompt}", kind = kind)
                else {
                    val np = d.first
                    FieldResult(ProjectOps.setPending(ProjectOps.addMessage(reconciled(p, np, now).withTrail(field.key), Role.DIRECTOR, "Going with my recommendation: ${Messages.display(np, field.key, d.second.value).trimEnd('.', ' ')}. ${d.second.rationale}", now, field.key), null), kind = kind)
                }
            }
            AnswerIntent.POSTPONE -> FieldResult(ProjectOps.postpone(ProjectOps.addMessage(p, Role.DIRECTOR, "No problem, we'll come back to that.", now), field.key, now), kind = kind)
            AnswerIntent.SKIP, AnswerIntent.NONE -> {
                if (!field.required || field.kind == FieldKind.MULTI) FieldResult(ProjectOps.setPending(ProjectOps.defer(p, field.key, now), null), kind = kind)
                else FieldResult(ProjectOps.postpone(ProjectOps.addMessage(p, Role.DIRECTOR, "No problem, we'll come back to that.", now), field.key, now), kind = kind)
            }
            AnswerIntent.QUESTION -> {
                val (answer, qnote) = answerQuestion(p, settings, field, interp.question?.takeIf { it.isNotBlank() } ?: text)
                FieldResult(p, directReply = answer, modelNote = qnote, kind = kind)
            }
            AnswerIntent.AFFIRM, AnswerIntent.NEGATE, AnswerIntent.UNCLEAR -> {
                // Maybe the message answers other fields implicitly (voice users often volunteer extra detail).
                val r = absorb(p, settings, text, null)
                val changed = r.project.decisions != p0.decisions || r.project.rejected != p0.rejected || r.project.facts != p0.facts
                if (changed) FieldResult(ProjectOps.setPending(r.project, null), modelNote = r.note, kind = r.kind)
                else FieldResult(p, directReply = interp.reason.ifBlank { Messages.clarify(field, traits) }, kind = kind)
            }
        }
    }

    private suspend fun answerQuestion(p: Project, settings: AppSettings, field: Field, question: String): Pair<String, String?> {
        val provider = readyProvider()?.first
        val base = Messages.explain(field, Traits(p))
        if (provider != null) {
            val ctx = "Current question: ${field.prompt}\nWhy it matters: ${field.why}\nOptions: ${field.options(Traits(p)).joinToString { it.label }}\nProject concept: ${p.originalConcept.ifBlank { p.value(Keys.CONCEPT) ?: "(none yet)" }}"
            val r = timed { provider.complete(LlmRequest(DirectorPrompts.answerSystem(name(settings), p.prefs.experience == com.hotattic.gamedesigner.core.model.Experience.BEGINNER),
                listOf(LlmMessage("user", "$ctx\n\nOwner asks: $question")), maxTokens = 220)) }
            if (r is LlmResult.Ok && r.text.isNotBlank()) return (r.text.trim() + "\n\n" + Messages.reask(field)) to provider.displayName
        }
        return base to null
    }

    // ---- Language understanding --------------------------------------------------------------------------------

    /** The provider that interprets the owner's words: the on-device model first (private, offline), cloud only as a fallback. Null means rules only. */
    private suspend fun readyProvider(): Pair<LlmProvider, InterpreterKind>? =
        deps.local?.takeIf { runCatching { it.isReady() }.getOrDefault(false) }?.let { it to InterpreterKind.LOCAL_LLM }
            ?: deps.cloud?.takeIf { runCatching { it.isReady() }.getOrDefault(false) }?.let { it to InterpreterKind.CLOUD_LLM }

    /** What is currently interpreting the owner's words, for the UI banner. */
    suspend fun interpreterKind(): InterpreterKind = readyProvider()?.second ?: InterpreterKind.RULES

    /** Rules always run (they are the fallback and the cross-check); a ready model refines them. */
    internal suspend fun interpret(p: Project, settings: AppSettings, field: Field?, text: String): Interpretation {
        val rules = LocalInterpreter.interpret(p, field, text)
        // Trivial replies need no model: a bare yes/no or an unambiguous option reply.
        val trivial = rules.intent in setOf(AnswerIntent.AFFIRM, AnswerIntent.NEGATE, AnswerIntent.DELEGATE, AnswerIntent.POSTPONE) && text.length < 30 && !rules.hasCorrections
        if (trivial) return rules
        val (provider, kind) = readyProvider() ?: return rules
        val llm = LlmInterpreter.interpret(provider, kind, name(settings), p, field, text) ?: return rules
        return mergeInterpretations(rules, llm)
    }

    internal fun mergeInterpretations(rules: Interpretation, llm: Interpretation): Interpretation {
        val useLlmAnswer = llm.intent != AnswerIntent.UNCLEAR
        val affirmed = (rules.affirmedTags + llm.affirmedTags).distinct()
        val rejectedTags = (rules.rejectedTags + llm.rejectedTags).distinct().filter { it !in llm.affirmedTags }
        return (if (useLlmAnswer) llm else rules.copy(by = llm.by)).copy(
            edits = llm.edits + rules.edits,
            rejectedGenres = (rules.rejectedGenres + llm.rejectedGenres).distinct(),
            rejectedTags = rejectedTags, affirmedTags = affirmed,
            references = (rules.references + llm.references).distinctBy { it.lowercase() },
            retract = (rules.retract + llm.retract).distinct(), facts = llm.facts, by = llm.by,
            preferences = llm.preferences, constraints = llm.constraints, ambiguities = llm.ambiguities,
            delegated = llm.delegated, scope = llm.scope, stale = llm.stale,
            acceptsRecommendation = llm.acceptsRecommendation, misunderstood = rules.misunderstood || llm.misunderstood,
        )
    }

    class Absorbed(val project: Project, val note: String?, val kind: InterpreterKind?)

    /** Understands a free message that is not (only) the answer to the open question, and applies it with owner authority. */
    private suspend fun absorb(p0: Project, settings: AppSettings, text: String, field: Field?, concept: Field? = null, precomputed: Interpretation? = null): Absorbed {
        val now = deps.clock()
        val interp0 = precomputed ?: interpret(p0, settings, null, text)
        // Something that could mean two different games is asked about, not guessed: its volunteered decisions are held back.
        val ask = interp0.ambiguities.firstOrNull()?.trim()?.trimEnd('.', '?')
        val interp = if (ask != null) interp0.copy(ambiguities = emptyList(), edits = emptyMap()) else interp0
        val applied = applyStatement(p0, text, interp, concept ?: field, now)
        var p = researchNewReferences(applied.project, settings)
        if (applied.announcement.isNotBlank()) p = ProjectOps.addMessage(p, Role.DIRECTOR, applied.announcement, now)
        if (ask != null) p = ProjectOps.addMessage(p, Role.DIRECTOR, "Before I record that, one thing could go two ways: $ask? Tell me which you mean.", now)
        return Absorbed(p, interp.by.takeIf { it != InterpreterKind.RULES }?.label, interp.by)
    }

    class Applied(val project: Project, val announcement: String)

    private val negationWord = Regex("(?i)(\\bnot\\b|\\bno\\b|n't\\b|\\bnever\\b|\\bwithout\\b|\\bforget\\b)")

    private fun sentences(text: String): List<String> = MetaConversation.designSentences(text)

    /** A fact an LLM proposes must be traceable to the owner's own words, or it is an invention. */
    private fun grounded(fact: String, text: String): Boolean {
        fun toks(x: String) = x.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()
        val f = toks(fact); if (f.isEmpty() || MetaConversation.isMeta(fact)) return false
        val t = toks(text)
        return f.count { it in t }.toDouble() / f.size >= 0.7
    }

    /**
     * Applies one owner message to the design with the right authority: rejections first (which also drop dependent
     * genres), then retractions of withdrawn facts, then new facts, then volunteered decisions, then dependency invalidation.
     */
    internal fun applyStatement(p0: Project, text: String, interp: Interpretation, field: Field?, now: Long): Applied {
        var p = p0
        val said = mutableListOf<String>()
        val rejectedTagNames = p.rejected[com.hotattic.gamedesigner.core.schema.Dependencies.TAG].orEmpty()
        val reversal = interp.affirmedTags.any { it.name in rejectedTagNames }
        val corrected = interp.hasCorrections || reversal

        for (tag in interp.affirmedTags) if (tag.name in p.rejected[com.hotattic.gamedesigner.core.schema.Dependencies.TAG].orEmpty()) p = ProjectOps.restoreTag(p, tag, now)
        for (tag in interp.rejectedTags) {
            val (np, dropped) = ProjectOps.rejectTag(p, tag, now)
            p = np
            val what = tag.name.lowercase().replace('_', '-')
            said += "Understood - this is not $what." + if (dropped.isNotEmpty()) " I dropped ${dropped.joinToString { com.hotattic.gamedesigner.core.schema.GenreKnowledge.resolve(it).label }} from the design." else ""
        }
        for (g in interp.rejectedGenres) if (g in p.list(Keys.GENRE) || p.rejected[Keys.GENRE].orEmpty().contains(g).not()) {
            val had = g in p.list(Keys.GENRE)
            p = ProjectOps.reject(p, Keys.GENRE, listOf(g), now)
            if (had) said += "Understood - removed ${com.hotattic.gamedesigner.core.schema.GenreKnowledge.resolve(g).label}."
        }
        if (interp.retract.isNotEmpty()) {
            val keys = interp.retract.map { it.lowercase() }
            val before = p.activeFacts().size
            p = ProjectOps.retractFacts(p, "Withdrawn by the owner: \"${text.take(80)}\"", now) { f -> f.category != "correction" && keys.any { it in f.text.lowercase() } }
            val gone = before - p.activeFacts().size
            if (gone > 0) said += "I withdrew $gone earlier requirement(s) that no longer apply."
        }

        // New facts: the concept's sentences, affirmative statements inside a correction, and anything volunteered.
        val newFacts = mutableListOf<String>()
        val category: String
        when {
            field?.key == Keys.CONCEPT -> { category = "concept"; newFacts += sentences(text) }
            field?.key == Keys.FIVE_MINUTES -> { category = "five_minutes"; newFacts += sentences(text) }
            corrected -> {
                category = "correction"
                interp.rejectedTags.forEach { newFacts += "The game is NOT ${it.name.lowercase().replace('_', ' ')}; it plays in real time." }
                newFacts += sentences(text).filter { !negationWord.containsMatchIn(it) }
            }
            field == null && text.length >= 25 -> { category = "owner"; newFacts += sentences(text) }
            else -> category = "owner"
        }
        newFacts += interp.facts.filter { grounded(it, text) }
        p = ProjectOps.addFacts(p, newFacts, category, Provenance.OWNER_EXPLICIT, now)

        // Volunteered decisions: an explicit correction is owner-authored; otherwise they are proposals awaiting confirmation.
        for ((k, v) in interp.edits) {
            val existing = p.decision(k)
            if (corrected) p = ProjectOps.setDecision(p, k, v, Provenance.OWNER_EXPLICIT, now)
            else if (!(existing != null && existing.status == DecisionStatus.CONFIRMED && existing.value.isNotBlank()))
                p = ProjectOps.setDecision(p, k, v, Provenance.SYSTEM_INFERENCE, now, DecisionStatus.PROPOSED, ProjectOps.FROM_OWNER_WORDS)
        }
        if (interp.references.isNotEmpty()) p = ProjectOps.addReferences(p, interp.references, now)
        p = applyTypedProposals(p, text, interp, now)

        val r = Reconciler.reconcile(p0, p, now)
        said += r.notices
        return Applied(r.project, said.joinToString("\n"))
    }

    /**
     * The deterministic half of LLM interpretation. Every proposal is grounded in the owner's own words and committed
     * with the right provenance; nothing here can overwrite an owner decision with a model guess.
     */
    private fun applyTypedProposals(p0: Project, text: String, interp: Interpretation, now: Long): Project {
        var p = p0
        val constraints = interp.constraints.filter { grounded(it, text) }
        if (constraints.isNotEmpty()) {
            val have = p.decision(Keys.MUST_NOT_CHANGE)?.value?.takeIf { it.isNotBlank() && it.trim().lowercase() != "none" }
            val merged = (have?.split(" | ").orEmpty() + constraints.map { it.trim().trimEnd('.') }).distinctBy { it.lowercase() }.joinToString(" | ")
            p = ProjectOps.setDecision(p, Keys.MUST_NOT_CHANGE, merged, Provenance.OWNER_EXPLICIT, now, raw = text.take(200))
        }
        interp.preferences.filter { grounded(it, text) }.let { p = ProjectOps.addFacts(p, it.map { s -> "Preference (not a hard requirement): $s" }, "preference", Provenance.OWNER_EXPLICIT, now) }
        interp.scope?.takeIf { it.isNotBlank() && grounded(it, text) }?.let { s ->
            if (Fields.get(Keys.FIRST_SLICE) != null) p = ProjectOps.setDecision(p, Keys.FIRST_SLICE, s.trim().trimEnd('.'), Provenance.OWNER_EXPLICIT, now, raw = text.take(200))
        }
        for (k in interp.delegated.distinct()) {
            val f = Fields.get(k) ?: continue
            val existing = p.decision(k)
            if (existing != null && existing.ownerAuthored && existing.status == DecisionStatus.CONFIRMED) continue
            ProjectOps.delegate(p, k, now)?.let { p = it.first }
            if (f.key == k) p = p.copy(postponed = p.postponed - k)
        }
        // A stale hint never deletes an owner decision; it only releases things Bob derived himself so they are re-derived.
        for (k in interp.stale.distinct()) {
            val d = p.decision(k) ?: continue
            if (!d.ownerAuthored && d.value.isNotBlank()) p = ProjectOps.clearDecision(p, k, now)
        }
        return p
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
        if (audit.passes && !ReviewGate.approved(p)) return DirectorTurn(reviewMessage(p, now))
        if (audit.passes) {
            val review = com.hotattic.gamedesigner.core.generate.SpecVersioning.preflight(p, now)
            if (!review.clean) {
                val msg = "I found contradictions I must settle before exporting:\n" + review.errors.take(5).joinToString("\n") { "- ${it.message}" + if (it.line.isNotBlank()) " (\"${it.line.take(120)}\")" else "" } +
                    "\n\nTell me which way each should go (your latest word wins), or say \"status\"."
                return DirectorTurn(reply(p, msg, null))
            }
            return DirectorTurn(ProjectOps.addMessage(p, Role.DIRECTOR, "Everything required is resolved, the audit passes and the consistency review is clean. Generating the spec and prompt now.", now), DirectorAction.GenerateSpec)
        }
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
                    // An optional question with nothing to recommend (e.g. "a great five minutes") is simply skipped.
                    if (d == null && !step.field.required) { p = ProjectOps.defer(p, step.field.key, now); return@repeat }
                    if (d == null) return DirectorTurn(askNext(finishBulk(p, count, now), settings))
                    p = d.first; count++
                }
                is NextStep.Confirm -> { p = ProjectOps.confirm(p, step.field.key, now); count++ }
                is NextStep.AssetPlanStep -> { p = p.copy(assets = p.assets + AssetPlan.resolveMissing(p, now)); count++ }
                is NextStep.ResolveConflicts, is NextStep.Optional, NextStep.Review, NextStep.Ready -> return DirectorTurn(askNext(finishBulk(p, count, now), settings))
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

    private fun assetPlanSpec() = QuestionSpec(PENDING_ASSET_PLAN, "SINGLE", listOf(ChoiceOption("looks_good", "Looks good"), ChoiceOption("cc0_default", "CC0 only"), ChoiceOption("original_only", "Original assets only")))

    private fun assetPlanQuick() = listOf(QuickReply("Looks good", "looks good"), QuickReply("CC0 only", "cc0 only"), QuickReply("Original assets only", "only original assets"))

    private fun quickFor(field: Field, t: Traits): List<QuickReply> = buildList {
        val opts = field.options(t)
        if (field.kind != FieldKind.TEXT) opts.take(8).forEach { add(QuickReply(it.label, it.label)) }
        if (field.suggest(t) != null) add(QuickReply("Choose for me", "choose for me"))
        if (!field.required) add(QuickReply("Skip", "skip"))
        else if (field.key != Keys.CONCEPT) add(QuickReply("Ask me later", "ask me later"))
    }

    private fun reply(p: Project, text: String, pending: String? = p.pendingFieldKey, quick: List<QuickReply> = emptyList(), question: QuestionSpec? = null): Project =
        ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, text, deps.clock(), pending?.takeUnless { it.startsWith("__") }, quick, question), pending)

    /** Re-asks a field with its structured question so the card stays usable after a clarification. */
    private fun moreRefsQuick(withAdd: Boolean) = if (withAdd) listOf(QuickReply("Add another game", "add another"), QuickReply("Done", "done")) else listOf(QuickReply("Done", "done"))

    private fun moreRefsPrompt(p: Project): Project =
        reply(p, "Got it. So far: ${p.references.joinToString(", ") { it.name }}. Add another game, or are you done?", PENDING_MORE_REFS, moreRefsQuick(true))

    private fun replyField(p: Project, text: String, field: Field): Project =
        ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, text, deps.clock(), field.key, quickFor(field, Traits(p)), specFor(field, Traits(p))), field.key)

    /** The upload prompt carries no new question card: the card that was just answered stays answered, so a second tap cannot re-open the picker. */
    private fun uploadPrompt(p: Project, text: String, field: Field): Project =
        ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, text, deps.clock(), field.key, listOf(QuickReply("Choose the image", "upload my own"), QuickReply("Create one for me", "create an original one for me"))), field.key)

    private fun specFor(field: Field, t: Traits): QuestionSpec = QuestionSpec(
        field.key, if (field.key in Keys.brandingKeyForSlot.values) "ASSET_UPLOAD" else field.kind.name, if (field.kind.isSelect) field.options(t).map { ChoiceOption(it.id, it.label, it.description) } else emptyList(),
        canDelegate = field.suggest(t) != null, canSkip = !field.required,
    )

    /** Chooses the next thing to say. Announces at most one new conflict per turn, before the next question. */
    internal fun askNext(p0: Project, settings: AppSettings): Project {
        val now = deps.clock()
        // "Do I already know this?" - use what the owner said, then take routine engineering decisions myself.
        var p = DerivedDefaults.apply(DesignSeeder.seed(p0, now).first, now)

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
                ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, msg, now, quick = assetPlanQuick(), question = assetPlanSpec()), PENDING_ASSET_PLAN)
            }
            is NextStep.ResolveConflicts -> {
                val c = step.conflicts.first()
                ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, Messages.conflict(c), now, quick = conflictQuick(c)), PENDING_CONFLICT_PREFIX + c.id + "__")
            }
            is NextStep.Optional -> askField(p, step.field, now)
            NextStep.Review -> reviewMessage(p, now)
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
        if (field.kind == FieldKind.MULTI) sb.append("\n(Pick every one that applies, then tap Continue. Or say \"all\" / \"all except ...\".)")
        if (field.kind != FieldKind.TEXT && opts.isNotEmpty() && p.prefs.experience == com.hotattic.gamedesigner.core.model.Experience.BEGINNER) {
            sb.append("\n")
            opts.take(8).forEachIndexed { i, o -> sb.append("\n${i + 1}. ${o.label}").append(if (o.description.isNotBlank()) " - ${o.description}" else "") }
        }
        val s = field.suggest(t)
        if (s != null && field.kind != FieldKind.TEXT) sb.append("\n\nI'd go with: ${Messages.display(p, field.key, s.value)}. ${s.rationale}")
        else if (s != null) sb.append("\n\nOr say \"choose for me\" and I'll draft it.")
        return ProjectOps.setPending(ProjectOps.addMessage(p, Role.DIRECTOR, sb.toString(), now, field.key, quickFor(field, t), specFor(field, t)), field.key)
    }
}
