package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hotattic.gamedesigner.core.director.DesignMode
import com.hotattic.gamedesigner.core.director.LessonView

/**
 * Bob's teaching card, laid out for a phone: one column (no sideways scrolling), wrapped text, the essentials always visible
 * (what it is, why it matters, Bob's recommendation, the advantages and disadvantages, and a "hard to change later" warning when the
 * choice is expensive to reverse) and the longer material (how it works, what happens if you choose differently, long-term effects)
 * behind one tap. Nothing is removed to make it shorter; it is only folded.
 */
@Composable
fun LessonCard(lesson: LessonView, messageId: String, onExplainMore: (() -> Unit)?) {
    var open by remember(messageId) { mutableStateOf(false) }
    Surface(Modifier.fillMaxWidth().padding(top = 8.dp), color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp, shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(lesson.term, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            if (lesson.hardToReverse) {
                Text("Hard to change later", Modifier.background(MaterialTheme.colorScheme.errorContainer, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(lesson.what, style = MaterialTheme.typography.bodyMedium)
                    lesson.why?.let { LessonLine("Why it matters", it) }
                    lesson.recommendation?.let {
                        Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)).padding(10.dp)) {
                            Text("Bob recommends", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    lesson.advantages?.let { LessonLine("Good", it) }
                    lesson.disadvantages?.let { LessonLine("Watch out", it) }
                    if (open) {
                        lesson.how?.let { LessonLine("How it works", it) }
                        lesson.ifElse?.let { LessonLine("If you choose something else", it) }
                        lesson.longTerm?.let { LessonLine("Long-term", it) }
                        lesson.more?.let { LessonLine("More detail", it) }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (lesson.hasDetails) TextButton({ open = !open }) { Text(if (open) "Show less" else "Show details") }
            }
            if (onExplainMore != null && lesson.more == null) OutlinedButton(onExplainMore) { Text("Explain more") }
        }
    }
}

@Composable
private fun LessonLine(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

/**
 * Beginner / Advanced selector. Two touch-sized chips, the current one clearly selected, with one line saying what the selected mode does.
 * Switching changes only how much Bob asks and shows; every answer and approved decision stays as it is.
 */
@Composable
fun ModeSelector(current: DesignMode, onSelect: (DesignMode) -> Unit, showCaption: Boolean) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Mode", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            DesignMode.values().forEach { m -> FilterChip(m == current, { if (m != current) onSelect(m) }, { Text(m.label) }) }
        }
        if (showCaption) Text("${current.blurb} ${DesignMode.BOTH_MODES_NOTE}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
    }
}
