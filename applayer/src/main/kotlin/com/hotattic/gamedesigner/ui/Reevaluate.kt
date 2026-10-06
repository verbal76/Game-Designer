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
import com.hotattic.gamedesigner.core.engine.ReevalCompare
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

private fun kindLabel(k: String) = when (k) {
    "NEEDS_DECISION" -> "NEEDS YOUR DECISION"; "CONTRADICTION" -> "CONTRADICTION"; "KEPT_REC" -> "NEWER RECOMMENDATION (yours kept)"; else -> k
}

/** Reevaluate, step 2: the saved design and the reevaluated one side by side. Nothing is replaced until the design is approved; the old spec stays recoverable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReevalScreen(vm: AppViewModel, nav: NavController, id: String) {
    val project by vm.current.collectAsState()
    LaunchedEffect(id) { if (vm.current.value?.id != id) vm.open(id) }
    val p = project?.takeIf { it.id == id }
    val r = p?.reeval
    var tab by remember { mutableStateOf(0) }
    var show by remember { mutableStateOf("CHANGES") }
    Scaffold(topBar = {
        TopAppBar(title = { Text("Reevaluation") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        if (p == null || r == null) {
            Column(Modifier.padding(pad).padding(16.dp)) { Text("No reevaluation yet for this design.") }
            return@Scaffold
        }
        val rows = remember(p) { ReevalCompare.rows(p) }
        val counts = remember(rows) { ReevalCompare.counts(rows) }
        val attention = r.items.filter { it.kind in setOf("NEEDS_DECISION", "CONTRADICTION", "KEPT_REC") }
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name.ifBlank { "Untitled game" }, style = MaterialTheme.typography.titleMedium)
            Text("${counts["NEW"] ?: 0} new  -  ${counts["CHANGED"] ?: 0} changed  -  ${counts["REMOVED"] ?: 0} removed  -  ${r.preservedOwner} of your decisions kept exactly  -  completeness ${r.completenessBefore}% -> ${r.completenessAfter}%", style = MaterialTheme.typography.bodySmall)
            Text(
                when (r.status) {
                    "APPROVED" -> "Approved: Spec v${r.approvedSpec} was created. Spec v${r.fromSpec ?: "-"} is still available."
                    "DISCARDED" -> "Discarded: your design is back to exactly what it was."
                    else -> "Not applied yet. Answer anything new and approve in the chat; that creates the next spec version and keeps Spec v${r.fromSpec ?: "-"}."
                }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            androidx.compose.material3.TabRow(tab) {
                androidx.compose.material3.Tab(tab == 0, { tab = 0 }, text = { Text("Side by side") })
                androidx.compose.material3.Tab(tab == 1, { tab = 1 }, text = { Text(if (attention.isEmpty()) "Needs attention" else "Needs attention (${attention.size})") })
                androidx.compose.material3.Tab(tab == 2, { tab = 2 }, text = { Text("What was stored") })
            }
            Column(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("CHANGES" to "Changes only", "ALL" to "Everything", "YOURS" to "Your decisions")) { (k, l) -> FilterChip(show == k, { show = k }, { Text(l) }) }
                        }
                        CompareTable(rows.filter { row -> when (show) { "CHANGES" -> row.status != "SAME"; "YOURS" -> row.beforeBy.startsWith("You"); else -> true } }, leftLabel = "SAVED DESIGN", rightLabel = "REEVALUATED",
                            empty = if (show == "CHANGES") "Nothing differs. The newer design logic agrees with the saved design." else "Nothing to show.")
                    }
                    1 -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (attention.isEmpty()) item { Text("Nothing needs your attention.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp)) }
                        items(attention) { it ->
                            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                Column(Modifier.padding(10.dp)) {
                                    Text("${kindLabel(it.kind)}: ${it.title}", style = MaterialTheme.typography.labelLarge)
                                    if (it.before != null && it.after != null) Text("${it.before}  ->  ${it.after}", style = MaterialTheme.typography.bodySmall)
                                    if (it.note.isNotBlank()) Text(it.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    else -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val s = r.source
                        Text(s.note, style = MaterialTheme.typography.bodyMedium)
                        Text("Your messages: ${s.ownerMessages}   Bob's messages: ${s.directorMessages}\nYour original idea stored word for word: ${if (s.verbatimConcept) "yes" else if (s.conceptRecovered) "recovered from your first message" else "no"}\nDecisions: ${s.decisionsTotal} (${s.ownerExplicit} yours, ${s.ownerCorrection} corrections, ${s.acceptedRecommendations} accepted from Bob, ${s.inferred} inferred by Bob, ${s.defaults} defaults)\nDecisions that carry your own words: ${s.withRawAnswer}\nOlder decisions without authority records: ${s.legacyProvenance}\nThings you ruled out: ${s.rejections}\nSpec versions: ${s.specVersions}   Uploaded images: ${s.uploadedAssets}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (r.status == "APPROVED") {
                    Button({ nav.navigate("spec/$id") }, Modifier.weight(1f)) { Text("Open the spec") }
                    if (r.fromSpec != null && r.approvedSpec != null) OutlinedButton({ nav.navigate("compare/$id/${r.fromSpec}/${r.approvedSpec}") }) { Text("Compare specs") }
                } else if (r.status == "OPEN") {
                    Button({ nav.navigate("chat/$id") { popUpTo("reeval/$id") { inclusive = true } } }, Modifier.weight(1f)) { Text(if (r.newQuestions > 0) "Continue with Bob" else "Review and approve") }
                    OutlinedButton({ vm.discardReevaluation() }) { Text("Discard") }
                } else Button({ nav.navigate("chat/$id") }, Modifier.weight(1f)) { Text("Back to the design") }
            }
        }
    }
}
