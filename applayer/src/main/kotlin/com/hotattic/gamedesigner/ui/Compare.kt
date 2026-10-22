package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.core.engine.CompareRow
import com.hotattic.gamedesigner.core.generate.SpecCompare

private val addedTint = Color(0xFF2E7D32).copy(alpha = 0.38f)
private val changedTint = Color(0xFFB26A00).copy(alpha = 0.38f)
private val removedTint = Color(0xFFB3261E).copy(alpha = 0.38f)

private fun statusTint(s: String, side: String): Color = when {
    s == "NEW" && side == "R" -> addedTint
    s == "REMOVED" && side == "L" -> removedTint
    s == "CHANGED" -> changedTint
    else -> Color.Transparent
}

@Composable
private fun Cell(text: String?, by: String, tint: Color, modifier: Modifier) {
    Column(modifier.background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp)).background(tint, RoundedCornerShape(8.dp)).padding(8.dp)) {
        Text(text ?: "—", style = MaterialTheme.typography.bodySmall, color = if (text == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        if (by.isNotBlank() && text != null) Text(by, style = MaterialTheme.typography.labelSmall, color = if (by.startsWith("You")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The saved design on the left, the reevaluated one on the right; colour shows what was added, changed or removed. */
@Composable
fun CompareTable(rows: List<CompareRow>, leftLabel: String, rightLabel: String, empty: String) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(leftLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
            Text(rightLabel, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        }
        if (rows.isEmpty()) { Text(empty, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp)); return }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            var lastGroup = ""
            rows.forEachIndexed { i, r ->
                if (r.group != lastGroup) { lastGroup = r.group; item(key = "g$i-${r.group}") { Text(r.group, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) } }
                item(key = "r$i-${r.key}") {
                    Column {
                        Text(r.title + when (r.status) { "NEW" -> "   NEW"; "CHANGED" -> "   CHANGED"; "REMOVED" -> "   REMOVED"; else -> "" }, style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                            Cell(r.before, r.beforeBy, statusTint(r.status, "L"), Modifier.weight(1f))
                            Cell(r.after, r.afterBy, statusTint(r.status, "R"), Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

/** Two generated specs, section by section, with only the real edits lit up. Old versions are read-only history. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecCompareScreen(vm: AppViewModel, nav: NavController, id: String, a0: Int, b0: Int) {
    val project by vm.current.collectAsState()
    LaunchedEffect(id) { if (vm.current.value?.id != id) vm.open(id) }
    val p = project?.takeIf { it.id == id }
    var left by remember { mutableIntStateOf(a0) }
    var right by remember { mutableIntStateOf(b0) }
    var doc by remember { mutableIntStateOf(0) }
    var changedOnly by remember { mutableStateOf(true) }
    val open = remember { mutableStateOf(setOf<String>()) }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Compare specs") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        if (p == null) return@Scaffold
        val va = p.versions.firstOrNull { it.number == left }; val vb = p.versions.firstOrNull { it.number == right }
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Left", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(p.versions.map { it.number }) { n -> FilterChip(left == n, { left = n }, { Text("v$n") }) } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Right", style = MaterialTheme.typography.labelMedium)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) { items(p.versions.map { it.number }) { n -> FilterChip(right == n, { right = n }, { Text("v$n") }) } }
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(listOf(0 to "CLAUDE.md", 1 to "Master prompt")) { (k, l) -> FilterChip(doc == k, { doc = k }, { Text(l) }) }
                item { FilterChip(changedOnly, { changedOnly = !changedOnly }, { Text("Changed sections only") }) }
            }
            if (va == null || vb == null) { Text("Pick two versions."); return@Column }
            val sections = remember(left, right, doc) { SpecCompare.compare(if (doc == 0) va.claudeMd else va.masterPrompt, if (doc == 0) vb.claudeMd else vb.masterPrompt) }
            val shown = sections.filter { !changedOnly || it.status != "SAME" }
            Text("${sections.count { it.status == "CHANGED" }} changed, ${sections.count { it.status == "NEW" }} new, ${sections.count { it.status == "REMOVED" }} removed, ${sections.count { it.status == "SAME" }} unchanged sections  (v$left on the left, v$right on the right)", style = MaterialTheme.typography.bodySmall)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (shown.isEmpty()) item { Text("These two versions are identical.", modifier = Modifier.padding(8.dp)) }
                items(shown, key = { it.title }) { s ->
                    val expanded = s.title in open.value
                    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))) {
                        Row(Modifier.fillMaxWidth().clickable { open.value = if (expanded) open.value - s.title else open.value + s.title }.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.background(when (s.status) { "NEW" -> addedTint; "REMOVED" -> removedTint; "CHANGED" -> changedTint; else -> Color.Transparent }, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text(s.status, style = MaterialTheme.typography.labelSmall) }
                            Text(s.title, Modifier.weight(1f).padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                            if (s.status != "SAME") Text("${s.changedLines}", style = MaterialTheme.typography.labelSmall)
                        }
                        if (expanded) {
                            val rows = SpecCompare.collapse(s.rows, 1)
                            Column(Modifier.padding(horizontal = 6.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                rows.forEach { r ->
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        val lt = when (r.kind) { "REMOVED" -> removedTint; "CHANGED" -> changedTint; else -> Color.Transparent }
                                        val rt = when (r.kind) { "ADDED" -> addedTint; "CHANGED" -> changedTint; else -> Color.Transparent }
                                        Text(r.left ?: "", Modifier.weight(1f).background(lt).padding(3.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (r.kind == "GAP") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                        Text(r.right ?: "", Modifier.weight(1f).background(rt).padding(3.dp), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = if (r.kind == "GAP") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
