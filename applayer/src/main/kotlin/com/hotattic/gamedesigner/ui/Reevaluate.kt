package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.core.engine.Reevaluation
import java.text.DateFormat
import java.util.Date

/** Reevaluate, step 1: choose which saved design to run through the newest design intelligence. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReevalPickerScreen(vm: AppViewModel, nav: NavController) {
    val projects by vm.projects.collectAsState()
    val busy by vm.busy.collectAsState()
    val s by vm.settings.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Reevaluate") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Which saved design should ${s.directorName} reconsider? Everything you already decided stays exactly as it is; only what an older Bob guessed is rethought. Nothing is replaced until you approve.", style = MaterialTheme.typography.bodyMedium)
            if (busy != null) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(4.dp).padding(end = 8.dp), strokeWidth = 2.dp); Text(busy ?: "", style = MaterialTheme.typography.bodySmall) }
            val eligible = projects.filter { !it.corrupt }
            if (eligible.isEmpty()) Text("No saved designs yet.", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(eligible, key = { it.id }) { p ->
                    Card(Modifier.fillMaxWidth().clickable(enabled = busy == null) {
                        vm.reevaluate(p.id) { id -> nav.navigate("reeval/$id") { popUpTo("reeval-pick") { inclusive = true } } }
                    }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(p.name.ifBlank { "Untitled game" }, style = MaterialTheme.typography.titleMedium)
                            Text("${modeLabel(p.mode)} - ${p.versionCount} spec version(s) - ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.updatedAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

private val kinds = listOf("ALL" to "All", "NEW" to "New", "CHANGED" to "Changed", "KEPT_REC" to "Kept recommendation", "REMOVED" to "Removed", "PRESERVED" to "Your decisions", "NEEDS_DECISION" to "Needs your decision", "CONTRADICTION" to "Contradictions")

private fun kindLabel(k: String) = kinds.firstOrNull { it.first == k }?.second?.uppercase() ?: k

/** Reevaluate, step 2: what changed. Nothing is replaced until the design is approved in the normal review; Spec v1 stays recoverable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReevalScreen(vm: AppViewModel, nav: NavController, id: String) {
    val project by vm.current.collectAsState()
    LaunchedEffect(id) { if (vm.current.value?.id != id) vm.open(id) }
    val p = project?.takeIf { it.id == id }
    val r = p?.reeval
    var filter by remember { mutableStateOf("ALL") }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Reevaluation") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        if (p == null || r == null) {
            Column(Modifier.padding(pad).padding(16.dp)) { Text("No reevaluation yet for this design.") }
            return@Scaffold
        }
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(p.name.ifBlank { "Untitled game" }, style = MaterialTheme.typography.titleMedium)
            Text(Reevaluation.summary(r), fontFamily = FontFamily.Monospace, fontSize = 13.sp)
            Text(
                when (r.status) {
                    "APPROVED" -> "Approved: Spec v${r.approvedSpec} was created. Spec v${r.fromSpec ?: "-"} is still available on the Spec screen."
                    "DISCARDED" -> "Discarded: your design is back to exactly what it was."
                    else -> "Not applied yet. Continue with Bob to answer anything new and approve the design; that creates the next spec version and keeps Spec v${r.fromSpec ?: "-"}."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Text("What was stored: ${r.source.note}", style = MaterialTheme.typography.bodySmall)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(kinds.filter { (k, _) -> k == "ALL" || r.items.any { it.kind == k } }) { (k, label) ->
                    FilterChip(filter == k, { filter = k }, { Text(if (k == "ALL") label else "$label ${r.items.count { it.kind == k }}") })
                }
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(r.items.filter { filter == "ALL" || it.kind == filter }.filter { filter != "ALL" || it.kind != "PRESERVED" }) { it ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(10.dp)) {
                            Text("${kindLabel(it.kind)}  ${it.title}", style = MaterialTheme.typography.labelLarge)
                            if (it.before != null && it.after != null) Text("${it.before}  ->  ${it.after}", style = MaterialTheme.typography.bodySmall)
                            else (it.after ?: it.before)?.let { v -> Text(v, style = MaterialTheme.typography.bodySmall) }
                            if (it.note.isNotBlank()) Text(it.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (filter == "ALL" && r.preservedOwner > 0) item { Text("${r.preservedOwner} of your decisions were kept exactly (tap \"Your decisions\" to list them).", style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (r.status == "APPROVED") Button({ nav.navigate("spec/$id") }, Modifier.weight(1f)) { Text("Open the spec") }
                else if (r.status == "OPEN") {
                    Button({ nav.navigate("chat/$id") { popUpTo("reeval/$id") { inclusive = true } } }, Modifier.weight(1f)) { Text(if (r.newQuestions > 0) "Continue with Bob" else "Review and approve") }
                    OutlinedButton({ vm.discardReevaluation() }) { Text("Discard") }
                } else Button({ nav.navigate("chat/$id") }, Modifier.weight(1f)) { Text("Back to the design") }
            }
        }
    }
}
