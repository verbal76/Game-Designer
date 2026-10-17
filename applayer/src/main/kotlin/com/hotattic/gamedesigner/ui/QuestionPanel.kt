package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.dp
import com.hotattic.gamedesigner.core.director.Accepted
import com.hotattic.gamedesigner.core.director.AnswerDraft
import com.hotattic.gamedesigner.core.director.GuidedPanel
import com.hotattic.gamedesigner.core.director.NextState
import com.hotattic.gamedesigner.core.director.PanelAction
import com.hotattic.gamedesigner.core.director.PanelKind
import com.hotattic.gamedesigner.core.director.PanelModel
import com.hotattic.gamedesigner.core.director.PanelOption

/**
 * The active question as ONE card that lives inside the conversation's single scroll area, right after the history, with no scroll container of its own
 * and no height cap: the question, why Bob asks, the answer choices, Bob's recommendation with its reasoning, and an obvious way to type your own answer.
 * It is a view over engine state. The draft (what is picked or typed) is held by the screen per question; nothing is recorded until CONFIRM in [ActionBar].
 */
@Composable
fun ActiveQuestionCard(
    model: PanelModel,
    draft: AnswerDraft,
    onDraft: (AnswerDraft) -> Unit,
    onAction: (PanelAction, List<String>) -> Unit,
    onExplainMore: () -> Unit,
    onTypeOwn: () -> Unit,
) {
    val tick = rememberTick()
    var details by rememberSaveable(model.messageId) { mutableStateOf(model.lesson?.more != null) }
    Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Bob's question", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            MarkdownText(model.title, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold))
            if (!model.detail.isNullOrBlank()) MarkdownText(model.detail!!, style = MaterialTheme.typography.bodyMedium)
            if (!model.why.isNullOrBlank()) MarkdownText("Why Bob asks: ${model.why}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            model.currentLabel?.let { Text("Currently recorded: $it", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary) }
            // The choices come straight after the question so they can never be missed below an explanation.
            if (model.options.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                model.options.forEach { o ->
                    val picked = o.id in draft.selected
                    OptionRow(o, picked, model.multi) { tick(); onDraft(draft.pick(model, o.id)) }
                }
                if (model.multi) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton({ onDraft(draft.pickAll(model.options.map { it.id })) }) { Text("Select all") }
                    TextButton({ onDraft(draft.clear()) }, enabled = draft.selected.isNotEmpty()) { Text("Clear") }
                }
            }
            if (model.allowsText || model.options.isEmpty()) TypeOwnRow(draft.text.isNotBlank(), model.options.isEmpty(), onTypeOwn)
            if (model.recommendationLabel != null) {
                Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)).padding(10.dp)) {
                    Text("Bob recommends: ${model.recommendationLabel}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    if (!model.recommendationWhy.isNullOrBlank()) MarkdownText(model.recommendationWhy!!, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    val rec = model.options.filter { it.recommended }
                    if (rec.isNotEmpty() && rec.none { it.id in draft.selected }) TextButton({ tick(); onDraft(draft.pickAll(rec.map { it.id })) }) { Text("Select Bob's recommendation (you still confirm)") }
                }
            }
            if (model.lesson != null) {
                TextButton({ tick(); details = !details }) { Text(if (details) "Hide the explanation" else "Explain this to me") }
                if (details) LessonCard(model.lesson!!, model.messageId, onExplainMore)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.canDelegate) OutlinedButton({ tick(); onAction(GuidedPanel.delegate(model), listOf("Bob's choice")) }) { Text("Choose for me") }
                if (model.exampleAvailable) TextButton({ tick(); onAction(GuidedPanel.example(model), emptyList()) }) { Text("Show an example") }
                if (model.kind == PanelKind.SINGLE || model.kind == PanelKind.MULTI || model.kind == PanelKind.TEXT || model.kind == PanelKind.UPLOAD || model.kind == PanelKind.OTHER)
                    TextButton({ tick(); onAction(GuidedPanel.skip(model), listOf("Skipped")) }) { Text(if (model.canSkip) "Skip" else "Ask me later") }
            }
        }
    }
}

/** After CONFIRM: the answer was recorded, what Bob understood, and that NEXT reveals the question he already has ready. Drawn in the same scroll area. */
@Composable
fun AcceptedCard(accepted: Accepted, next: NextState) {
    Surface(Modifier.fillMaxWidth(), tonalElevation = 2.dp, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Recorded", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(accepted.summary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            accepted.notes.forEach { MarkdownText(it, style = MaterialTheme.typography.bodyMedium) }
            Text(next.reason ?: "Press Next to see the next question.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The obvious "my own answer" choice. The typing happens in the box at the bottom of the screen, which is the same box for every question. */
@Composable
private fun TypeOwnRow(active: Boolean, only: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .border(if (active) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .selectable(active, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(if (only) "Type your answer in the box below" else "Something else: type or dictate my own answer", style = MaterialTheme.typography.bodyLarge, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
            Text("The box at the bottom of the screen. Then press Confirm.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun OptionRow(o: PanelOption, picked: Boolean, multi: Boolean, onClick: () -> Unit) {
    val mod = if (multi) Modifier.toggleable(picked, role = Role.Checkbox, onValueChange = { onClick() })
    else Modifier.selectable(picked, role = Role.RadioButton, onClick = onClick)
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp)
            .background(if (picked) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface, shape)
            .border(if (picked) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), shape)
            .then(mod).padding(horizontal = 10.dp, vertical = 6.dp)
            .semantics { stateDescription = (if (picked) "Selected" else "Not selected") + (if (o.recommended) ". Bob recommends this" else "") },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (multi) Checkbox(picked, null) else RadioButton(picked, null)
        Column(Modifier.padding(start = 8.dp)) {
            Text(if (o.recommended) "${o.label} (Bob recommends)" else o.label, style = MaterialTheme.typography.bodyLarge, fontWeight = if (picked) FontWeight.SemiBold else FontWeight.Normal)
            if (o.description.isNotBlank()) Text(o.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * The separate action area: ONE short line saying what to do (never a stack of warnings), then BACK | CONFIRM | NEXT in the same order in every state.
 * It sits outside the scrolling content, so it can never cover the question or the choices.
 */
@Composable
fun ActionBar(
    hint: String?,
    canBack: Boolean, onBack: () -> Unit,
    confirmLabel: String, confirmEnabled: Boolean, onConfirm: () -> Unit,
    nextEnabled: Boolean, onNext: () -> Unit,
    compact: Boolean,
) {
    val tick = rememberTick()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (hint != null && !compact) Text(hint, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.semantics { stateDescription = hint })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton({ tick(); onBack() }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = canBack) { Text("Back") }
            Button({ onConfirm() }, Modifier.weight(2f).heightIn(min = 48.dp).semantics { contentDescription = if (confirmEnabled) "$confirmLabel. Records this answer." else "$confirmLabel. Not available yet." }, enabled = confirmEnabled) { Text(confirmLabel, maxLines = 1) }
            OutlinedButton({ tick(); onNext() }, Modifier.weight(1f).heightIn(min = 48.dp), enabled = nextEnabled) { Text("Next") }
        }
    }
}
