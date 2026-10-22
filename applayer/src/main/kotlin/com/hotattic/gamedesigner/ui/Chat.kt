package com.hotattic.gamedesigner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import com.hotattic.gamedesigner.core.director.ComposerPolicy
import com.hotattic.gamedesigner.core.director.ConversationScroll
import com.hotattic.gamedesigner.core.director.SubmitGuard
import com.hotattic.gamedesigner.core.director.AnswerDraft
import com.hotattic.gamedesigner.core.director.ConversationView
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.UiEvent
import com.hotattic.gamedesigner.core.director.DesignMode
import com.hotattic.gamedesigner.core.director.GuidedPanel
import com.hotattic.gamedesigner.core.director.NextState
import com.hotattic.gamedesigner.core.director.LessonParser
import com.hotattic.gamedesigner.core.director.Messages
import com.hotattic.gamedesigner.core.engine.CompletenessEngine
import com.hotattic.gamedesigner.core.engine.DesignStages
import com.hotattic.gamedesigner.core.engine.StageProgress
import com.hotattic.gamedesigner.core.model.ChatMessage
import com.hotattic.gamedesigner.core.model.ClaudePlan
import com.hotattic.gamedesigner.core.engine.InterpreterKind
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.model.QuestionSpec
import com.hotattic.gamedesigner.core.model.ProjectPrefs
import com.hotattic.gamedesigner.core.model.Role
import com.hotattic.gamedesigner.core.model.UsageStyle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: AppViewModel, nav: NavController, id: String) {
    val project by vm.current.collectAsState()
    val busy by vm.busy.collectAsState()
    val settings by vm.settings.collectAsState()
    val interpreter by vm.interpreter.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var showStatus by remember { mutableStateOf(false) }
    var showStages by remember { mutableStateOf(false) }
    var showPrefs by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    // Guided question panel: screen-only state. The open question, its options and every decision stay in the engine.
    var draftsRaw by rememberSaveable { mutableStateOf("") }
    var acceptedTurn by rememberSaveable { mutableStateOf("") }
    var acceptedBefore by rememberSaveable { mutableStateOf(0) }
    var acceptedKey by rememberSaveable { mutableStateOf("") }
    var acceptedLabels by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(id) { if (vm.current.value?.id != id) vm.open(id) }
    // The ViewModel remembers which slot the picker is for (it survives activity recreation while Files is open), ingests the bytes
    // durably, and only then advances the conversation. A cancelled picker leaves the question open.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> vm.onImagePicked(uri) }
    LaunchedEffect(Unit) { vm.events.collect { e -> if (e is UiEvent.PickImage) picker.launch("image/*") else if (e is UiEvent.RestoreInput && input.isBlank()) input = e.text } }
    val p = project?.takeIf { it.id == id }
    // Scrolling is decided below, once it is known whether a question is open.

    val completeness = remember(p) { p?.let { CompletenessEngine.compute(it) } }
    // Stage progress is worked out off the main thread, so a long project never delays a tap or the keyboard.
    val stages by produceState<StageProgress?>(null, p) { value = p?.let { withContext(Dispatchers.Default) { DesignStages.of(it) } } }
    val tick = rememberTick()
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val composerFocus = remember { androidx.compose.ui.focus.FocusRequester() }

    // What the screen shows is a view over engine state: the open question, the accepted answer, and a history that never repeats the question.
    val model = p?.let { GuidedPanel.model(it, busy != null) }
    val history = remember(p, model?.messageId, model?.notes) { if (p != null) ConversationView.history(p, model) else emptyList<ChatMessage>() }
    val accepted = if (p != null && acceptedTurn.isNotEmpty()) GuidedPanel.accepted(p, acceptedBefore, acceptedKey, acceptedTurn, acceptedLabels.split("\u0001").filter { it.isNotBlank() })?.takeIf { GuidedPanel.stillCurrent(it, p) } else null
    val canBack = p != null && GuidedPanel.canBack(p, busy != null)
    val nextState = if (p != null) GuidedPanel.next(p, model, accepted, busy != null) else NextState(false, null)
    val questionActive = model != null && accepted == null && p?.messages?.isNotEmpty() == true
    val drafts = remember(draftsRaw) { AnswerDraft.decode(draftsRaw) }
    val draft = if (questionActive) drafts[model!!.key] ?: AnswerDraft() else AnswerDraft()
    fun setDraft(d: AnswerDraft) { val k = model?.key ?: return; draftsRaw = AnswerDraft.encode(if (d.isEmpty) drafts - k else drafts + (k to d)) }
    val extraItems = (if (p?.pendingTurn != null) 1 else 0) + (if (busy != null) 1 else 0)
    val showCard = questionActive || accepted != null
    val itemCount = history.size + extraItems + (if (showCard) 1 else 0)
    val compact = imeBottom > 0 || fontScale > 1.3f
    val dismissKeyboard = rememberKeyboardDismiss()
    val submitGuard = remember { SubmitGuard() }

    // ---- following the conversation ----------------------------------------------------------------------------------------------------------
    // ConversationScroll (core) decides; the follower reports what happened to it and performs the scroll once the layout has settled.
    val contentKey = listOf<Any?>(history.size, model?.messageId, accepted != null, p?.pendingTurn?.id, busy != null)
    val follower = rememberConversationFollower(listState, itemCount, imeBottom, contentKey, p?.id)
    fun report(e: ConversationScroll.Event) = follower.report(e)
    // Once an answer is recorded its draft is finished with.
    LaunchedEffect(accepted != null) { if (accepted != null && acceptedKey.isNotEmpty()) draftsRaw = AnswerDraft.encode(drafts - acceptedKey) }

    // Every explicit answering action: one submission, the keyboard closes at once, and the newest content comes into view.
    fun submitted(a: com.hotattic.gamedesigner.core.director.PanelAction, labels: List<String>, key: String) {
        if (!submitGuard.begin(a.turnId, System.currentTimeMillis())) return
        tick(); acceptedTurn = a.turnId; acceptedBefore = p?.messages?.size ?: 0; acceptedKey = key
        acceptedLabels = labels.joinToString("\u0001")
        vm.perform(a)
        dismissKeyboard()
        report(ConversationScroll.Event.CONFIRMED)
    }
    fun confirmDraft() {
        val m = model ?: return
        GuidedPanel.confirm(m, draft.selected, draft.text)?.let { a ->
            submitted(a, if (draft.text.isNotBlank()) listOf(draft.text.trim().take(60)) else m.options.filter { it.id in draft.selected }.map { it.label }, m.key)
        }
    }

    // The Scaffold adds no bottom inset of its own: the keyboard and the navigation bar are applied exactly once, on the content below.
    Scaffold(contentWindowInsets = WindowInsets(0), topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(p?.value("display_name") ?: p?.name?.ifBlank { null } ?: "New game", maxLines = 1)
                    if (p?.mode == ProjectMode.NEW_GAME) stages?.takeIf { it.stages.isNotEmpty() }?.let { Text(it.summary, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary, maxLines = 1) }
                    else if (p != null) Text(modeLabel(p.mode), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            },
            navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                IconButton({ nav.navigate("spec/$id") }) { Icon(Icons.Default.Description, "Spec") }
                IconButton({ nav.navigate("branding/$id") }) { Icon(Icons.Default.Image, "Branding") }
                Box {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text("Status") }, { menu = false; showStatus = true })
                        if (p != null && (p.decisions.isNotEmpty() || p.messages.any { it.role == Role.USER })) DropdownMenuItem({ Text("Reevaluate this design") }, { menu = false; vm.reevaluate(p.id) { nav.navigate("reeval/$it") } })
                        DropdownMenuItem({ Text("Rename project") }, { menu = false; showRename = true })
                        DropdownMenuItem({ Text("Claude plan and usage") }, { menu = false; showPrefs = true })
                        if (p?.mode == ProjectMode.PLAYTEST_CONTINUE) DropdownMenuItem({ Text("Back to designing") }, { menu = false; vm.setMode(ProjectMode.NEW_GAME) })
                        else if (p != null && p.versions.isNotEmpty()) DropdownMenuItem({ Text("Playtest feedback mode") }, { menu = false; vm.setMode(ProjectMode.PLAYTEST_CONTINUE) })
                    }
                }
            },
        )
    }) { pad ->
        // One scrolling area (the history, then the open question as its last item) and, below it, a separate fixed bottom area with the answer box and
        // BACK | CONFIRM | NEXT. The fixed area is outside the scroll, so it can never cover content, and the keyboard is applied once.
        Column(Modifier.fillMaxSize().padding(pad).imePadding().navigationBarsPadding()) {
            if (!compact) {
                if (p?.mode == ProjectMode.NEW_GAME) stages?.let { StageStrip(it) { showStages = true } }
                if (p != null && p.mode == ProjectMode.NEW_GAME) ModeSelector(DesignMode.of(p.prefs.experience), { vm.setDesignMode(it) }, showCaption = false)
                if (interpreter == InterpreterKind.RULES && p?.mode != ProjectMode.PLAYTEST_CONTINUE) {
                    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "RULES-ONLY MODE: no on-device AI is active, so free text is matched by simple rules. Answer buttons are exact.",
                            Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        TextButton({ nav.navigate("models") }) { Text("Set up AI") }
                    }
                } else if (interpreter == InterpreterKind.LOCAL_LLM) {
                    Text("LOCAL AI: Bob is using the on-device model", Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
            ConversationViewport(listState, ConversationScroll.returnLabel(follower.state), { report(ConversationScroll.Event.RETURN_TO_LATEST) }, Modifier.weight(1f).fillMaxWidth()) {
                items(history, key = { it.id }) { m -> Bubble(m, settings.directorName) }
                p?.pendingTurn?.let { pt -> item(key = "pending-${pt.id}") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        Column(Modifier.widthIn(max = 520.dp).background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(12.dp)) {
                            SelectionContainer { Text(pt.text, style = MaterialTheme.typography.bodyMedium) }
                            if (busy == null) {
                                Text("Saved, not answered yet.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                                TextButton({ vm.retryPending() }) { Text("Retry") }
                            }
                        }
                    }
                } }
                if (busy != null) item(key = "busy") { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(4.dp).widthIn(max = 22.dp), strokeWidth = 2.dp); Text(busy ?: "", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall) } }
                if (accepted != null) item(key = "accepted-card") { AcceptedCard(accepted, nextState) }
                else if (questionActive && model != null) item(key = "active-question") {
                    ActiveQuestionCard(
                        model, draft, onDraft = { setDraft(it) },
                        onAction = { a, labels -> submitted(a, labels, model.key) },
                        onExplainMore = { vm.send("explain more") },
                        onTypeOwn = { runCatching { composerFocus.requestFocus() } },
                    )
                }
            }
            // The fixed bottom area: quick replies when no question is open, the one answer box, and the three navigation buttons.
            Surface(Modifier.fillMaxWidth(), tonalElevation = 6.dp) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    val last = p?.messages?.lastOrNull()
                    if (!questionActive && accepted == null && last != null && last.role == Role.DIRECTOR && last.quickReplies.isNotEmpty() && busy == null) {
                        // No open question but Bob offered replies (status, generate...): they stay one tap away.
                        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) { items(last.quickReplies) { q -> SuggestionChip({ vm.send(q.send) }, { Text(q.label) }) } }
                    }
                    if (accepted == null && !questionActive) {
                        // Free messages (no open question): the only place the send arrow exists. Sending is an explicit action, so the newest content comes into view.
                        AnswerComposer(
                            input, { input = it },
                            if (p?.mode == ProjectMode.PLAYTEST_CONTINUE) "Describe what you noticed..." else "Type or dictate a message to Bob...",
                            compact, composerFocus, showSend = ComposerPolicy.showSendArrow(false), sendEnabled = input.isNotBlank() && busy == null,
                            onSend = { val t = input; input = ""; tick(); vm.send(t); report(ConversationScroll.Event.CONFIRMED) },
                        )
                    }
                    if (questionActive && model != null) {
                        val blocked = GuidedPanel.confirmBlocked(model, draft.selected, draft.text)
                        QuestionAnswerBar(
                            draft.text, { setDraft(draft.type(it)) }, "Or type or dictate your own answer...", compact, composerFocus,
                            hint = draft.hint(model), canBack = canBack, onBack = { acceptedTurn = ""; vm.goBack(); report(ConversationScroll.Event.ADVANCED) },
                            confirmLabel = draft.confirmLabel(model), confirmEnabled = blocked == null && busy == null, onConfirm = { confirmDraft() },
                            nextEnabled = nextState.enabled, onNext = {},
                        )
                    } else if (accepted != null) {
                        ActionBar(
                            hint = null, canBack = canBack, onBack = { acceptedTurn = ""; vm.goBack(); report(ConversationScroll.Event.ADVANCED) },
                            confirmLabel = "Recorded", confirmEnabled = false, onConfirm = {},
                            nextEnabled = nextState.enabled, onNext = { acceptedTurn = ""; report(ConversationScroll.Event.ADVANCED) }, compact = compact,
                        )
                    } else if (busy == null && p?.answerTrail?.isNotEmpty() == true && p.pendingTurn == null) {
                        TextButton({ vm.goBack() }) { Text("\u2190 Back to previous question") }
                    }
                }
            }
        }
    }

    if (showStatus && completeness != null && p != null) {
        val dims = remember(p) { com.hotattic.gamedesigner.core.engine.DesignDimensions.status(p).filter { it.state != com.hotattic.gamedesigner.core.engine.DimState.NOT_APPLICABLE } }
        AlertDialog({ showStatus = false }, confirmButton = { TextButton({ showStatus = false }) { Text("Close") } }, title = { Text(stages?.summary ?: "Design status") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("Completeness counts the decisions that could change your game, not the number of questions asked.", style = MaterialTheme.typography.bodySmall)
                dims.forEach { d ->
                    val mark = when (d.state) { com.hotattic.gamedesigner.core.engine.DimState.DECIDED -> "decided"; com.hotattic.gamedesigner.core.engine.DimState.DELEGATED -> "delegated to Bob"; com.hotattic.gamedesigner.core.engine.DimState.DISCRETION -> "Bob's call"; else -> "open" }
                    Text("${d.dim.title}: $mark", style = MaterialTheme.typography.bodySmall, color = if (d.state == com.hotattic.gamedesigner.core.engine.DimState.UNRESOLVED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
                if (completeness.proposed.isNotEmpty()) Text("Awaiting your confirmation: " + completeness.proposed.joinToString { it.title }, style = MaterialTheme.typography.bodySmall)
                if (p.designApproval == null || !com.hotattic.gamedesigner.core.engine.ReviewGate.approved(p)) Text("Your approval of the design review is still needed before the spec is generated.", style = MaterialTheme.typography.bodySmall)
            }
        })
    }
    if (showStages) stages?.let { StageDialog(it) { showStages = false } }
    if (showRename && p != null) {
        var name by remember { mutableStateOf(p.name) }
        AlertDialog({ showRename = false }, title = { Text("Rename project") }, text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton({ vm.renameProject(name); showRename = false }) { Text("Save") } }, dismissButton = { TextButton({ showRename = false }) { Text("Cancel") } })
    }
    if (showPrefs && p != null) {
        var plan by remember { mutableStateOf(p.prefs.claudePlan) }
        var usage by remember { mutableStateOf(p.prefs.usageStyle) }
        AlertDialog({ showPrefs = false }, title = { Text("Claude plan and usage") }, text = {
            Column { ClaudePlan.values().forEach { Choice(plan == it, it.label) { plan = it } }; Text("Usage", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)); UsageStyle.values().forEach { Choice(usage == it, it.label) { usage = it } } }
        }, confirmButton = { TextButton({ vm.setProjectPrefs(ProjectPrefs(p.prefs.experience, plan, usage)); showPrefs = false }) { Text("Save") } }, dismissButton = { TextButton({ showPrefs = false }) { Text("Cancel") } })
    }
}

@Composable
internal fun Bubble(m: ChatMessage, directorName: String) {
    val mine = m.role == Role.USER
    val system = m.role == Role.SYSTEM
    // Non-question messages can still carry a teaching card; it is drawn as a card so a long lesson stays readable on a phone.
    val parts = remember(m.id, m.text) { if (m.role == Role.DIRECTOR) LessonParser.split(m.text) else null }
    // A design disagreement is a conversation, not an error: it gets a calm tone. Only a real technical stop uses the error colours.
    val tone = remember(m.id, m.text) { if (m.role == Role.DIRECTOR) Messages.toneOf(m.text) else Messages.Tone.NORMAL }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 520.dp)
                .background(when { mine -> MaterialTheme.colorScheme.primaryContainer; system -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f); tone == Messages.Tone.CONFLICT_HARD -> MaterialTheme.colorScheme.errorContainer; tone == Messages.Tone.CONFLICT_SOFT -> MaterialTheme.colorScheme.secondaryContainer; else -> MaterialTheme.colorScheme.surfaceVariant }, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            if (!mine) Text(if (system) "Research / notes" else when (tone) { Messages.Tone.CONFLICT_SOFT -> "$directorName - design check"; Messages.Tone.CONFLICT_HARD -> "$directorName - needs a decision"; else -> directorName }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            val lesson = parts?.lesson
            if (mine) SelectionContainer { Text(m.text, style = MaterialTheme.typography.bodyMedium) }
            else if (parts != null && lesson != null) {
                if (parts.before.isNotBlank()) SelectionContainer { MarkdownText(parts.before) }
                LessonCard(lesson, m.id, null)
                if (parts.after.isNotBlank()) SelectionContainer { MarkdownText(parts.after, Modifier.padding(top = 8.dp)) }
            } else SelectionContainer { MarkdownText(m.text, style = if (system) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium) }
        }
    }
}
