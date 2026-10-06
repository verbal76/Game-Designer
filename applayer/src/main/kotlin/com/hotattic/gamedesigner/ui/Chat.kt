package com.hotattic.gamedesigner.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.UiEvent
import com.hotattic.gamedesigner.core.engine.CompletenessEngine
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
    var showPrefs by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()

    LaunchedEffect(id) { if (vm.current.value?.id != id) vm.open(id) }
    // The ViewModel remembers which slot the picker is for (it survives activity recreation while Files is open), ingests the bytes
    // durably, and only then advances the conversation. A cancelled picker leaves the question open.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> vm.onImagePicked(uri) }
    LaunchedEffect(Unit) { vm.events.collect { e -> if (e is UiEvent.PickImage) picker.launch("image/*") } }
    val p = project?.takeIf { it.id == id }
    LaunchedEffect(p?.messages?.size, busy) { p?.messages?.size?.let { if (it > 0) listState.animateScrollToItem(it - 1 + if (busy != null) 1 else 0) } }

    val completeness = remember(p) { p?.let { CompletenessEngine.compute(it) } }

    Scaffold(topBar = {
        TopAppBar(
            title = {
                Column {
                    Text(p?.value("display_name") ?: p?.name?.ifBlank { null } ?: "New game", maxLines = 1)
                    if (completeness != null && p?.mode == ProjectMode.NEW_GAME) Text("${completeness.percent}% designed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
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
                        DropdownMenuItem({ Text("Rename project") }, { menu = false; showRename = true })
                        DropdownMenuItem({ Text("Claude plan and usage") }, { menu = false; showPrefs = true })
                        if (p?.mode == ProjectMode.PLAYTEST_CONTINUE) DropdownMenuItem({ Text("Back to designing") }, { menu = false; vm.setMode(ProjectMode.NEW_GAME) })
                        else if (p != null && p.versions.isNotEmpty()) DropdownMenuItem({ Text("Playtest feedback mode") }, { menu = false; vm.setMode(ProjectMode.PLAYTEST_CONTINUE) })
                    }
                }
            },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding().navigationBarsPadding()) {
            if (completeness != null && p?.mode == ProjectMode.NEW_GAME) LinearProgressIndicator(progress = { completeness.percent / 100f }, modifier = Modifier.fillMaxWidth())
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
            val msgs = p?.messages.orEmpty()
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(msgs, key = { it.id }) { m -> Bubble(m, settings.directorName) }
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
                if (busy != null) item { Row(verticalAlignment = Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(4.dp).widthIn(max = 22.dp), strokeWidth = 2.dp); Text(busy ?: "", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodySmall) } }
            }
            val last = msgs.lastOrNull()
            val spec = last?.question?.takeIf { last.role == Role.DIRECTOR && busy == null && it.fieldKey == p?.pendingFieldKey && (it.kind == "SINGLE" || it.kind == "MULTI" || it.kind == "BOOLEAN" || it.kind == "ASSET_UPLOAD") }
            if (spec != null && last != null) {
                QuestionCard(spec, last.id, onSelect = { ids -> vm.submitSelection(spec.fieldKey, ids) }, onSend = { vm.send(it) })
            } else if (last != null && last.role == Role.DIRECTOR && last.quickReplies.isNotEmpty() && busy == null) {
                LazyRow(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(last.quickReplies) { q -> SuggestionChip({ vm.send(q.send) }, { Text(q.label) }) }
                }
            }
            if (busy == null && p?.answerTrail?.isNotEmpty() == true && p.pendingTurn == null) {
                TextButton({ vm.goBack() }, Modifier.padding(start = 8.dp)) { Text("\u2190 Back to previous question") }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    input, { input = it }, Modifier.weight(1f),
                    placeholder = { Text(if (p?.mode == ProjectMode.PLAYTEST_CONTINUE) "Describe what you noticed..." else "Type or dictate your answer...") },
                    minLines = 1, maxLines = 8,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    shape = RoundedCornerShape(20.dp),
                )
                FilledIconButton({ val t = input; input = ""; vm.send(t) }, Modifier.padding(start = 8.dp), enabled = input.isNotBlank() && busy == null) { Icon(Icons.AutoMirrored.Filled.Send, "Send") }
            }
        }
    }

    if (showStatus && completeness != null && p != null) {
        val dims = remember(p) { com.hotattic.gamedesigner.core.engine.DesignDimensions.status(p).filter { it.state != com.hotattic.gamedesigner.core.engine.DimState.NOT_APPLICABLE } }
        AlertDialog({ showStatus = false }, confirmButton = { TextButton({ showStatus = false }) { Text("Close") } }, title = { Text("${completeness.percent}% designed") }, text = {
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
private fun Bubble(m: ChatMessage, directorName: String) {
    val mine = m.role == Role.USER
    val system = m.role == Role.SYSTEM
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.widthIn(max = 520.dp)
                .background(when { mine -> MaterialTheme.colorScheme.primaryContainer; system -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f); else -> MaterialTheme.colorScheme.surfaceVariant }, RoundedCornerShape(16.dp))
                .padding(12.dp),
        ) {
            if (!mine) Text(if (system) "Research / notes" else directorName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            SelectionContainer { Text(m.text, style = if (system) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium) }
        }
    }
}

/**
 * Structured answer card. Single-select submits on tap; multi-select toggles, offers Select all / Clear, and only
 * submits when the owner taps Continue, so a first tap never ends the question.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuestionCard(q: QuestionSpec, messageId: String, onSelect: (List<String>) -> Unit, onSend: (String) -> Unit) {
    val multi = q.kind == "MULTI"
    var selected by remember(messageId) { mutableStateOf(emptySet<String>()) }
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp), tonalElevation = 2.dp, shape = RoundedCornerShape(16.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (multi) Text("Pick every one that applies", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    when (q.kind) {
                        "BOOLEAN" -> {
                            SuggestionChip({ onSend("yes") }, { Text("Yes") })
                            SuggestionChip({ onSend("no") }, { Text("No") })
                        }
                        "MULTI" -> q.options.forEach { o -> FilterChip(o.id in selected, { selected = if (o.id in selected) selected - o.id else selected + o.id }, { Text(o.label) }) }
                        else -> q.options.forEach { o -> SuggestionChip({ onSelect(listOf(o.id)) }, { Text(o.label) }) }
                    }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (multi) {
                    TextButton({ selected = q.options.map { it.id }.toSet() }) { Text("Select all") }
                    TextButton({ selected = emptySet() }, enabled = selected.isNotEmpty()) { Text("Clear") }
                    Button({ onSelect(q.options.map { it.id }.filter { it in selected }) }, enabled = selected.isNotEmpty()) { Text("Continue (${selected.size})") }
                }
                if (q.canDelegate) OutlinedButton({ onSend("choose for me") }) { Text("Choose for me") }
                if (q.canSkip) TextButton({ onSend("skip") }) { Text("Skip") } else TextButton({ onSend("ask me later") }) { Text("Ask me later") }
            }
        }
    }
}
