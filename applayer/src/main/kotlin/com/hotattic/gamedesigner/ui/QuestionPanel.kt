package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hotattic.gamedesigner.core.director.Accepted
import com.hotattic.gamedesigner.core.director.GuidedPanel
import com.hotattic.gamedesigner.core.director.NextState
import com.hotattic.gamedesigner.core.director.PanelAction
import com.hotattic.gamedesigner.core.director.PanelKind
import com.hotattic.gamedesigner.core.director.PanelModel
import com.hotattic.gamedesigner.core.director.PanelOption

private const val SEP = "\u0001"

/**
 * Bob's guided question: one question at a time, with what it is, why it matters, the choices, Bob's recommendation (shown, never pre-selected),
 * what the owner has picked and whether it is confirmed, and always-visible BACK | CONFIRM | NEXT.
 *
 * It is a view over engine state. The only thing kept here is the draft selection, which belongs to the message that asked the question and is
 * saved across rotation. Everything the buttons do goes to the engine through [onAction] (the same selection / text pipelines as before,
 * with the panel's stable turn id) or [onBack] (the engine's undo). Typing in the conversation box below keeps working at all times.
 */
@Composable
fun QuestionPanel(
    model: PanelModel,
    canBack: Boolean,
    next: NextState,
    onAction: (PanelAction, List<String>) -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onExplainMore: () -> Unit,
    minimized: Boolean,
    onMinimizedChange: (Boolean) -> Unit,
    compact: Boolean,
) {
    val tick = rememberTick()
    var draftRaw by rememberSaveable(model.messageId) { mutableStateOf("") }
    var text by rememberSaveable(model.messageId) { mutableStateOf("") }
    var typing by rememberSaveable(model.messageId) { mutableStateOf(false) }
    var details by rememberSaveable(model.messageId) { mutableStateOf(false) }
    val draft: Set<String> = if (draftRaw.isEmpty()) emptySet() else draftRaw.split(SEP).toSet()
    val blocked = GuidedPanel.confirmBlocked(model, draft, text)
    val chosen = model.options.filter { it.id in draft }

    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), tonalElevation = 3.dp, shape = RoundedCornerShape(18.dp)) {
        if (minimized) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bob is waiting on: ${model.title}", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                TextButton({ tick(); onMinimizedChange(false) }) { Text("Open") }
            }
            return@Surface
        }
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Header: always visible, so the panel can be folded away without losing the question.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Bob's question", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                TextButton({ tick(); onMinimizedChange(true) }, Modifier.semantics { contentDescription = "Fold the question away. It stays here until you answer." }) { Text("Hide") }
            }
            // Everything that can grow scrolls inside this share; the three buttons below never scroll away.
            Column(Modifier.weight(1f, fill = false).heightIn(max = if (compact) 150.dp else 330.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(model.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (!model.why.isNullOrBlank() && !compact) Text("Why Bob asks: ${model.why}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (!model.detail.isNullOrBlank() && !compact) Text(model.detail!!.take(900), style = MaterialTheme.typography.bodyMedium)
                model.currentLabel?.let { Text("Currently recorded: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
                if (model.recommendationLabel != null) {
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)).padding(10.dp)) {
                        Text("Bob recommends: ${model.recommendationLabel}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        if (!model.recommendationWhy.isNullOrBlank() && !compact) Text(model.recommendationWhy!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        val rec = model.options.filter { it.recommended }
                        if (rec.isNotEmpty() && rec.none { it.id in draft }) TextButton({ tick(); draftRaw = rec.joinToString(SEP) { it.id } }) { Text("Pick Bob's recommendation") }
                    }
                }
                model.options.forEach { o ->
                    val picked = o.id in draft
                    OptionRow(o, picked, model.multi) {
                        tick()
                        draftRaw = if (model.multi) (if (picked) draft - o.id else draft + o.id).joinToString(SEP) else o.id
                    }
                }
                if (model.multi) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ draftRaw = model.options.joinToString(SEP) { it.id } }) { Text("Select all") }
                    TextButton({ draftRaw = "" }, enabled = draft.isNotEmpty()) { Text("Clear") }
                }
                if (model.allowsText) {
                    if (typing || model.kind == PanelKind.TEXT || model.options.isEmpty()) {
                        OutlinedTextField(
                            text, { text = it }, Modifier.fillMaxWidth().heightIn(max = 140.dp),
                            label = { Text(if (model.options.isEmpty()) "Your answer" else "Or say it in your own words") },
                            minLines = 1, maxLines = 5, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences), shape = RoundedCornerShape(14.dp),
                        )
                    } else TextButton({ typing = true }) { Text("Something else - type my own answer") }
                }
                if (model.lesson != null) {
                    TextButton({ tick(); details = !details }) { Text(if (details) "Hide the explanation" else "Explain this to me") }
                    if (details) LessonCard(model.lesson!!, model.messageId, onExplainMore)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (model.canDelegate) OutlinedButton({ tick(); onAction(GuidedPanel.delegate(model), listOf("Bob's choice")) }) { Text("Choose for me") }
                    if (model.kind == PanelKind.SINGLE || model.kind == PanelKind.MULTI || model.kind == PanelKind.TEXT || model.kind == PanelKind.UPLOAD || model.kind == PanelKind.OTHER)
                        TextButton({ tick(); onAction(GuidedPanel.skip(model), listOf("Skipped")) }) { Text(if (model.canSkip) "Skip" else "Ask me later") }
                }
            }
            // The owner's selection and its state, in words.
            val selectionLine = when {
                text.isNotBlank() -> "Your answer: \"${text.trim().take(80)}\" - not confirmed yet"
                chosen.isNotEmpty() -> "Your selection: ${chosen.joinToString(", ") { it.label }} - not confirmed yet"
                else -> "Nothing selected yet. Bob never answers for you."
            }
            Text(selectionLine, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary, modifier = Modifier.semantics { stateDescription = selectionLine })
            NavRow(
                canBack = canBack, onBack = onBack,
                confirmLabel = if (model.multi && draft.isNotEmpty()) "Confirm (${draft.size})" else "Confirm", confirmEnabled = blocked == null,
                onConfirm = { GuidedPanel.confirm(model, draft, text)?.let { a -> tick(); onAction(a, if (text.isNotBlank()) listOf(text.trim().take(60)) else chosen.map { it.label }) } },
                nextEnabled = next.enabled, onNext = onNext,
            )
            val reason = next.reason ?: blocked
            if (!compact && reason != null) Text(reason, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Shown after CONFIRM: the answer was accepted, what Bob understood, and NEXT to reveal the question he already has ready. */
@Composable
fun AcceptedPanel(accepted: Accepted, canBack: Boolean, next: NextState, onBack: () -> Unit, onNext: () -> Unit, compact: Boolean) {
    Surface(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), tonalElevation = 3.dp, shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f, fill = false).heightIn(max = if (compact) 120.dp else 260.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Confirmed", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(accepted.summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                accepted.notes.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
                Text(next.reason ?: "Bob has the next question ready.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            NavRow(canBack = canBack, onBack = onBack, confirmLabel = "Confirmed", confirmEnabled = false, onConfirm = {}, nextEnabled = next.enabled, onNext = onNext)
        }
    }
}

@Composable
private fun OptionRow(o: PanelOption, picked: Boolean, multi: Boolean, onClick: () -> Unit) {
    val mod = if (multi) Modifier.toggleable(picked, role = Role.Checkbox, onValueChange = { onClick() })
    else Modifier.selectable(picked, role = Role.RadioButton, onClick = onClick)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).then(mod).padding(vertical = 2.dp)
            .semantics { if (o.recommended) stateDescription = if (picked) "Selected. Bob recommends this" else "Bob recommends this" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multi) Checkbox(picked, null) else RadioButton(picked, null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(if (o.recommended) "${o.label} (Bob recommends)" else o.label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal)
            if (o.description.isNotBlank()) Text(o.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** BACK | CONFIRM | NEXT: the same three controls, in the same order, in every state. */
@Composable
private fun NavRow(canBack: Boolean, onBack: () -> Unit, confirmLabel: String, confirmEnabled: Boolean, onConfirm: () -> Unit, nextEnabled: Boolean, onNext: () -> Unit) {
    val tick = rememberTick()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton({ tick(); onBack() }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = canBack) { Text("Back") }
        Button({ onConfirm() }, Modifier.weight(1.4f).heightIn(min = 48.dp), enabled = confirmEnabled) { Text(confirmLabel, maxLines = 1) }
        OutlinedButton({ tick(); onNext() }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = nextEnabled) { Text("Next") }
    }
}
