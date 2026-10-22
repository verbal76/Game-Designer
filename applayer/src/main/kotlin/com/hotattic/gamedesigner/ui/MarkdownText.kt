package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hotattic.gamedesigner.core.director.SimpleMarkdown

/** Bob's text with its bold, italic and code markers drawn as styling instead of shown as raw characters. The parsing is core's tested [SimpleMarkdown]. */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium, color: Color = Color.Unspecified) {
    val lines = remember(text) { SimpleMarkdown.parse(text) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.forEach { l ->
            if (l.text.isBlank()) Spacer(Modifier.height(4.dp))
            else {
                val lead = if (l.bullet) "• " else ""
                val styled = buildAnnotatedString {
                    append(lead); append(l.text)
                    l.spans.forEach { sp ->
                        val s = when (sp.style) {
                            SimpleMarkdown.Style.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                            SimpleMarkdown.Style.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                            SimpleMarkdown.Style.CODE -> SpanStyle(fontFamily = FontFamily.Monospace)
                        }
                        addStyle(s, lead.length + sp.start, lead.length + sp.end)
                    }
                }
                Text(styled, style = style, color = color)
            }
        }
    }
}
