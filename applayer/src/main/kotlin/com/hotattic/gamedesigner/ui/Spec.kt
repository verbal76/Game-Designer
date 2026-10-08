package com.hotattic.gamedesigner.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.core.engine.AuditLevel
import com.hotattic.gamedesigner.core.engine.CompletenessEngine
import com.hotattic.gamedesigner.core.generate.SpecVersioning
import com.hotattic.gamedesigner.core.schema.Fields

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpecScreen(vm: AppViewModel, nav: NavController, id: String) {
    val project by vm.current.collectAsState()
    val busy by vm.busy.collectAsState()
    val ctx = LocalContext.current
    LaunchedEffect(id) { if (vm.current.value?.id != id) vm.open(id) }
    val p = project?.takeIf { it.id == id }
    var selected by remember(p?.versions?.size) { mutableIntStateOf(p?.versions?.lastOrNull()?.number ?: 0) }
    var tab by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf<String?>(null) }
    var pendingSave by remember { mutableStateOf("") }
    val saveMd = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri -> if (uri != null) vm.saveText(uri, pendingSave) }
    var exportVersion by remember { mutableIntStateOf(0) }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.exportPackage(exportVersion, uri) }
    // Naming the file comes before the picker: (is it the zip?, suggested name).
    var naming by remember { mutableStateOf<Pair<Boolean, String>?>(null) }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Spec and prompt") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        if (p == null) return@Scaffold
        val v = p.versions.firstOrNull { it.number == selected }
        val completeness = remember(p) { CompletenessEngine.compute(p) }
        val audit = remember(p) { vm.auditSummary(p) }
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (p.versions.isEmpty()) {
                Text("No spec generated yet.", style = MaterialTheme.typography.titleMedium)
                Text("Design is ${completeness.percent}% complete. ${audit.summary()}", style = MaterialTheme.typography.bodyMedium)
            } else {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(p.versions.reversed()) { ver -> FilterChip(selected == ver.number, { selected = ver.number }, { Text("v${ver.number} ${ver.kind.label}") }) }
                }
            }
            val tabs = listOf("CLAUDE.md", "Master prompt", "Changes", "Audit")
            PrimaryScrollableTabRow(tab, edgePadding = 0.dp) { tabs.forEachIndexed { i, t -> Tab(tab == i, { tab = i }, text = { Text(t) }) } }
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                when {
                    tab == 0 && v != null -> SelectionContainer { Text(v.claudeMd, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    tab == 1 && v != null -> SelectionContainer { Text(v.masterPrompt, fontFamily = FontFamily.Monospace, fontSize = 12.sp) }
                    tab == 2 && v != null -> {
                        val older = p.versions.lastOrNull { it.number < v.number }
                        val diff = SpecVersioning.diff(older, v)
                        Text(if (older == null) "Initial version - ${diff.size} decisions recorded." else "Changes since v${older.number}:", style = MaterialTheme.typography.titleSmall)
                        diff.take(80).forEach { d -> Text("- ${d.title}: ${d.before?.take(60) ?: "(none)"} -> ${d.after?.take(60) ?: "(removed)"}", style = MaterialTheme.typography.bodySmall) }
                    }
                    tab == 3 || v == null -> {
                        Text(audit.summary(), style = MaterialTheme.typography.titleSmall)
                        audit.findings.forEach { f -> Text("[${f.level.name}] ${f.message}", style = MaterialTheme.typography.bodySmall, color = if (f.level == AuditLevel.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(vertical = 2.dp)) }
                        Text("Only you can do:", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                        audit.humanOnlyTasks.forEach { Text("- $it", style = MaterialTheme.typography.bodySmall) }
                        if (completeness.missingRequired.isNotEmpty()) Text("Still open in the design: " + completeness.missingRequired.joinToString { it.title }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                }
            }
            val text = when (tab) { 1 -> v?.masterPrompt; else -> v?.claudeMd }
            if (v != null && tab <= 1 && text != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton({ copy(ctx, text) }) { Text("Copy") }
                    OutlinedButton({ share(ctx, text) }) { Text("Share") }
                    OutlinedButton({ pendingSave = text; naming = false to (if (tab == 1) "MASTER_PROMPT.md" else "CLAUDE.md") }) { Text("Save") }
                }
                if (p.versions.size >= 2) OutlinedButton({ nav.navigate("compare/${p.id}/${(p.versions.lastOrNull { it.number < v.number } ?: p.versions.first()).number}/${v.number}") }, Modifier.fillMaxWidth()) { Text("Compare with the previous version (side by side)") }
                OutlinedButton({ exportVersion = v.number; naming = true to "${(p.value("display_name") ?: "game").replace(' ', '_')}_spec_v${v.number}.zip" }, Modifier.fillMaxWidth()) { Text("Export full package (.zip)") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Button({ vm.generate() }, Modifier.weight(1f), enabled = busy == null) { Text(if (p.versions.isEmpty()) "Generate" else "New version") }
                if (v != null) OutlinedButton({ vm.reviewWithCloud(v.number) { review = it } }, enabled = busy == null) { Text("Review with Claude") }
            }
            if (busy != null) Text(busy ?: "", style = MaterialTheme.typography.bodySmall)
            if (p.versions.isEmpty() && !completeness.readyForGeneration) Text("Tip: generating before the design is complete is allowed, but the audit will list what is missing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    naming?.let { (isZip, suggested) ->
        var name by remember(suggested) { mutableStateOf(suggested) }
        AlertDialog(
            onDismissRequest = { naming = null },
            title = { Text(if (isZip) "Name the package" else "Name the file") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("File name") }) },
            confirmButton = {
                TextButton({
                    val ext = if (isZip) ".zip" else ".md"
                    // Keep it a plain, safe file name: no folders, no characters providers reject; the extension stays correct.
                    var clean = name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").trim('.', ' ').ifEmpty { suggested.substringBeforeLast('.') }
                    if (!clean.endsWith(ext, ignoreCase = true)) clean += ext
                    naming = null
                    if (isZip) saveZip.launch(clean) else saveMd.launch(clean)
                }) { Text("Continue") }
            },
            dismissButton = { TextButton({ naming = null }) { Text("Cancel") } },
        )
    }
    review?.let { r -> AlertDialog({ review = null }, confirmButton = { TextButton({ review = null }) { Text("Close") } }, title = { Text("Claude's review") }, text = { Column(Modifier.verticalScroll(rememberScrollState())) { SelectionContainer { Text(r) } } }) }
}

private fun copy(ctx: Context, text: String) {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Game Designer", text))
    android.widget.Toast.makeText(ctx, "Copied", android.widget.Toast.LENGTH_SHORT).show()
}

private fun share(ctx: Context, text: String) {
    val i = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
    ctx.startActivity(Intent.createChooser(i, "Share"))
}
