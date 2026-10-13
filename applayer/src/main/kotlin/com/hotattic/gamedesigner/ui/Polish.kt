package com.hotattic.gamedesigner.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.hotattic.gamedesigner.HandoffStatus
import com.hotattic.gamedesigner.core.engine.StageProgress
import com.hotattic.gamedesigner.core.engine.StageState
import com.hotattic.gamedesigner.core.generate.HandoffStage

/** True when the system turned animations off (Settings > Accessibility > Remove animations, or the developer animation scale set to 0). Motion then becomes instant. */
@Composable
fun rememberReducedMotion(): Boolean {
    val ctx = LocalContext.current
    return remember(ctx) {
        try { android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f } catch (e: Exception) { false }
    }
}

/** A light tick for choices that matter (picking an answer, switching mode, sending). Goes through the system, so the user's touch-feedback setting is respected. */
@Composable
fun rememberTick(): () -> Unit {
    val haptic = LocalHapticFeedback.current
    return remember(haptic) { { haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) } }
}

/** The press is acknowledged the instant the finger lands: a small, quick shrink that returns on release. */
@Composable
fun Modifier.pressScale(source: MutableInteractionSource, reduced: Boolean): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) 0.97f else 1f, tween(if (reduced) 0 else 90), label = "press")
    return this.graphicsLayer { scaleX = scale; scaleY = scale }
}

/**
 * One answer choice. A single-choice tap (selected = null) answers at once; a multiple-choice chip toggles. Bob's recommendation is marked in words
 * (never by colour alone), and the press, the tick and the selected state all happen on the tap itself, before any processing starts.
 */
@Composable
fun OptionChip(label: String, recommended: Boolean, selected: Boolean?, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val reduced = rememberReducedMotion()
    val tick = rememberTick()
    val text = if (recommended) "$label (recommended)" else label
    val mod = Modifier.pressScale(source, reduced).semantics { if (recommended) stateDescription = "Bob recommends this" }
    if (selected == null) SuggestionChip({ tick(); onClick() }, { Text(text) }, modifier = mod, interactionSource = source)
    else FilterChip(selected, { tick(); onClick() }, { Text(text) }, modifier = mod, interactionSource = source)
}

/** Where the owner is in the design: one line of text and one row of segments, tappable for the full list. Nothing here is a percentage. */
@Composable
fun StageStrip(progress: StageProgress, onClick: () -> Unit) {
    if (progress.stages.isEmpty()) return
    val reduced = rememberReducedMotion()
    Column(
        Modifier.fillMaxWidth().clickable(onClickLabel = "Show design stages", onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { contentDescription = "Design progress. ${progress.summary}. ${progress.completed} of ${progress.stages.size} stages complete. Tap for details." },
    ) {
        Text(progress.summary, style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            progress.stages.forEach { s ->
                val alpha by animateFloatAsState(if (s.state == StageState.REMAINING) 0.45f else 1f, tween(if (reduced) 0 else 240), label = "stage")
                val color = when (s.state) { StageState.COMPLETE -> MaterialTheme.colorScheme.primary; StageState.CURRENT -> MaterialTheme.colorScheme.tertiary; StageState.REMAINING -> MaterialTheme.colorScheme.outline }
                Box(Modifier.weight(1f).fillMaxHeight().graphicsLayer { this.alpha = alpha }.background(color, RoundedCornerShape(3.dp)))
            }
        }
    }
}

@Composable
fun StageDialog(progress: StageProgress, onDismiss: () -> Unit) {
    AlertDialog(onDismiss, confirmButton = { TextButton(onDismiss) { Text("Close") } }, title = { Text(progress.summary) }, text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            progress.stages.forEach { s ->
                val state = when (s.state) { StageState.COMPLETE -> "complete"; StageState.CURRENT -> "in progress, ${s.open} to decide"; StageState.REMAINING -> "not started" }
                Text("${s.title}: $state", style = MaterialTheme.typography.bodyMedium, color = if (s.state == StageState.CURRENT) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
            }
            Text("Stages reopen if a new requirement needs another decision. To change an earlier answer, use Back, or just tell Bob what to change.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    })
}

/** Handoff generation as it really is: the steps that are done, the one running, the ones to come, and an honest end state. */
@Composable
fun HandoffPanel(status: HandoffStatus, onDismiss: () -> Unit, onOpenDocuments: () -> Unit, onRetry: () -> Unit) {
    val running = listOf(HandoffStage.PREPARING, HandoffStage.GENERATING, HandoffStage.VALIDATING)
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), tonalElevation = 2.dp,
        color = when (status.stage) { HandoffStage.COMPLETED -> MaterialTheme.colorScheme.primaryContainer; HandoffStage.FAILED -> MaterialTheme.colorScheme.errorContainer; else -> MaterialTheme.colorScheme.surfaceVariant }) {
        Column(Modifier.padding(12.dp).semantics(mergeDescendants = true) { contentDescription = "Handoff package. ${status.stage.label}. ${status.detail}" }, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (status.stage) {
                HandoffStage.COMPLETED -> {
                    Text("Package ready", style = MaterialTheme.typography.titleSmall)
                    Text(status.detail, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { Button(onOpenDocuments) { Text("See the documents") }; TextButton(onDismiss) { Text("Dismiss") } }
                }
                HandoffStage.FAILED -> {
                    Text("Could not finish", style = MaterialTheme.typography.titleSmall)
                    Text(status.detail, style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { OutlinedButton(onRetry) { Text("Try again") }; TextButton(onDismiss) { Text("Dismiss") } }
                }
                else -> {
                    Text("Making your development package", style = MaterialTheme.typography.titleSmall)
                    running.forEach { st ->
                        val done = running.indexOf(st) < running.indexOf(status.stage)
                        val now = st == status.stage
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (now) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text(if (done) "Done" else "Next", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                            Text(st.label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
