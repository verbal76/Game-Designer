package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import com.hotattic.gamedesigner.core.engine.ReevalExplainer
import com.hotattic.gamedesigner.core.engine.ReevalRunState
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

/**
 * Reevaluate, step 1: choose which saved design to run through the newest design intelligence. The chosen card itself shows what is happening
 * (highlighted border, its own progress and name, a Cancel button, or the error), the other cards wait, and a second tap does nothing.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReevalPickerScreen(vm: AppViewModel, nav: NavController) {
    val projects by vm.projects.collectAsState()
    val run by vm.reevalRun.collectAsState()
    val s by vm.settings.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    val anyRunning = run is ReevalRunState.Running
    Scaffold(topBar = {
        TopAppBar(title = { Text("Reevaluate") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Which saved design should ${s.directorName} reconsider? Everything you already decided stays exactly as it is. ${s.directorName} reviews the design and shows you what he found; nothing in your saved design changes until you approve it.", style = MaterialTheme.typography.bodyMedium)
            val eligible = projects.filter { !it.corrupt }
            if (eligible.isEmpty()) Text("No saved designs yet.", style = MaterialTheme.typography.bodyMedium)
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(eligible, key = { it.id }) { p ->
                    val name = p.name.ifBlank { "Untitled game" }
                    val running = run is ReevalRunState.Running && run.id == p.id
                    val failed = run is ReevalRunState.Failed && run.id == p.id
                    val shape = RoundedCornerShape(12.dp)
                    Card(
                        Modifier.fillMaxWidth()
                            .then(if (running) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else if (failed) Modifier.border(2.dp, MaterialTheme.colorScheme.error, shape) else Modifier)
                            .clickable(enabled = !anyRunning && !running) {
                                vm.dismissReevalError()
                                vm.reevaluate(p.id) { id -> nav.navigate("reeval/$id") { popUpTo("reeval-pick") { inclusive = true } } }
                            },
                        colors = CardDefaults.cardColors(containerColor = if (running) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant),
                        shape = shape,
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            Text("${modeLabel(p.mode)} - ${p.versionCount} spec version(s) - ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.updatedAt))}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (running) {
                                Text("Reevaluating $name...", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                LinearProgressIndicator(Modifier.fillMaxWidth())
                                Text("Your saved design is not changed. This can take a while on the phone.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                                TextButton({ vm.cancelReevaluation() }) { Text("Cancel") }
                            } else if (failed) {
                                Text("Could not reevaluate $name: ${(run as ReevalRunState.Failed).message}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton({ vm.dismissReevalError(); vm.reevaluate(p.id) { id -> nav.navigate("reeval/$id") { popUpTo("reeval-pick") { inclusive = true } } } }) { Text("Try again") }
                                    TextButton({ vm.dismissReevalError() }) { Text("Dismiss") }
                                }
                            } else if (anyRunning) Text("Waiting: another design is being reevaluated.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            else Text("Tap to review this design", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

private fun kindChip(k: ReevalExplainer.Kind) = k.label.uppercase()

@Composable
private fun SummaryTab(sum: ReevalExplainer.Summary) {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sum.headline, style = MaterialTheme.typography.titleMedium)
        Text(sum.reviewed, style = MaterialTheme.typography.bodyMedium)
        Text(sum.unchanged, style = MaterialTheme.typography.bodyMedium)
        if (sum.adds.isNotEmpty() || sum.changes.isNotEmpty() || sum.foundInWords.isNotEmpty()) {
            Text("What Bob suggests", style = MaterialTheme.typography.titleSmall)
            sum.adds.forEach { Text("\u2022 Add: $it", style = MaterialTheme.typography.bodyMedium) }
            sum.changes.forEach { Text("\u2022 Change: $it", style = MaterialTheme.typography.bodyMedium) }
            sum.foundInWords.forEach { Text("\u2022 Found in your own words: $it", style = MaterialTheme.typography.bodyMedium) }
            Text("Suggestions only take effect if you approve them.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else Text("Bob has nothing to add or change.", style = MaterialTheme.typography.bodyMedium)
        if (sum.attention.isNotEmpty()) {
            Text("What still needs attention", style = MaterialTheme.typography.titleSmall)
            ReevalExplainer.Kind.values().forEach { k -> val n = sum.attention.count { it.kind == k }; if (n > 0) Text("\u2022 $n ${k.label.lowercase()}${if (n == 1) "" else "s"}", style = MaterialTheme.typography.bodyMedium) }
            val fresh = sum.attention.count { it.isNew }
            Text("$fresh found this time, ${sum.attention.size - fresh} were already open before. Open the Needs attention tab for each one.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            sum.groupingNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text("What the words mean", style = MaterialTheme.typography.titleSmall)
        sum.glossary.forEach { (t, m) -> Text("$t: $m", style = MaterialTheme.typography.bodySmall) }
        Text("Completeness", style = MaterialTheme.typography.titleSmall)
        Text(sum.completeness, style = MaterialTheme.typography.bodySmall)
        Text("Is my saved design changed?", style = MaterialTheme.typography.titleSmall)
        Text(sum.saved, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun AttentionTab(sum: ReevalExplainer.Summary) {
    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sum.attention.isEmpty()) item { Text("Nothing needs your attention.", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(8.dp)) }
        items(sum.attention, key = { it.key }) { a ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(kindChip(a.kind) + if (a.isNew) "  -  found this time" else "  -  already open before", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    Text(a.title, style = MaterialTheme.typography.titleSmall)
                    Text(a.explanation, style = MaterialTheme.typography.bodyMedium)
                    Text("Why it matters: ${a.why}", style = MaterialTheme.typography.bodySmall)
                    Text(a.relation, style = MaterialTheme.typography.bodySmall)
                    a.previously?.let { Text("What you had decided: $it", style = MaterialTheme.typography.bodySmall) }
                    if (a.choices.isNotEmpty()) { Text("Your choices:", style = MaterialTheme.typography.labelLarge); a.choices.forEach { Text("\u2022 $it", style = MaterialTheme.typography.bodySmall) } }
                    a.ifChanged?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    Text("What to do: ${a.action}", style = MaterialTheme.typography.bodyMedium)
                    Text(if (a.deferrable) "Can wait: you can approve the design without settling this." else "Cannot wait: the spec needs this settled.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

/** Reevaluate, step 2: what Bob found, in plain words, the saved design and the reevaluated one side by side. Nothing is replaced until the design is approved; the old spec stays recoverable. */
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
        TopAppBar(title = { Text("What Bob found") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } })
    }) { pad ->
        if (p == null || r == null) {
            Column(Modifier.padding(pad).padding(16.dp)) { Text("No reevaluation yet for this design.") }
            return@Scaffold
        }
        val rows = remember(p) { ReevalCompare.rows(p) }
        val sum = remember(p) { ReevalExplainer.summarize(p) }
        if (sum == null) { Column(Modifier.padding(pad).padding(16.dp)) { Text("No reevaluation yet for this design.") }; return@Scaffold }
        Column(Modifier.fillMaxSize().padding(pad).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name.ifBlank { "Untitled game" }, style = MaterialTheme.typography.titleMedium)
            androidx.compose.material3.PrimaryScrollableTabRow(tab, edgePadding = 0.dp) {
                androidx.compose.material3.Tab(tab == 0, { tab = 0 }, text = { Text("Summary") })
                androidx.compose.material3.Tab(tab == 1, { tab = 1 }, text = { Text(if (sum.attention.isEmpty()) "Needs attention" else "Needs attention (${sum.attention.size})") })
                androidx.compose.material3.Tab(tab == 2, { tab = 2 }, text = { Text("Side by side") })
                androidx.compose.material3.Tab(tab == 3, { tab = 3 }, text = { Text("Second opinion") })
                androidx.compose.material3.Tab(tab == 4, { tab = 4 }, text = { Text("What was stored") })
            }
            Column(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> SummaryTab(sum)
                    1 -> AttentionTab(sum)
                    2 -> {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(listOf("CHANGES" to "Changes only", "ALL" to "Everything", "YOURS" to "Your decisions")) { (k, l) -> FilterChip(show == k, { show = k }, { Text(l) }) }
                        }
                        CompareTable(rows.filter { row -> when (show) { "CHANGES" -> row.status != "SAME"; "YOURS" -> row.beforeBy.startsWith("You"); else -> true } }, leftLabel = "SAVED DESIGN", rightLabel = "REEVALUATED",
                            empty = if (show == "CHANGES") "Nothing differs. The newer design logic agrees with the saved design." else "Nothing to show.")
                    }
                    3 -> Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Have a different AI look at this design cold, without ${vm.settings.collectAsState().value.directorName}'s history with it. It lists contradictions, missing systems and anything that looks like a guess. It only advises: nothing changes unless you raise a point.", style = MaterialTheme.typography.bodySmall)
                        val busy by vm.busy.collectAsState()
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton({ vm.secondOpinion(true) }, enabled = busy == null) { Text("Ask Claude (cloud)") }
                            OutlinedButton({ vm.secondOpinion(false) }, enabled = busy == null) { Text("Ask the on-device model") }
                        }
                        Text("Cloud sends a summary of this design (your words and decisions, no uploaded files or keys) to your configured provider. The on-device model stays on this phone.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (busy != null) Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) { CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp); Text(busy ?: "", style = MaterialTheme.typography.bodySmall) }
                        r.secondOpinion?.let { so ->
                            Text("Second opinion from ${so.provider}  -  ${DateFormat.getDateTimeInstance().format(Date(so.at))}", style = MaterialTheme.typography.labelLarge)
                            val pts = com.hotattic.gamedesigner.core.engine.SecondOpinion.points(so.text)
                            if (pts.isEmpty()) Text(so.text, style = MaterialTheme.typography.bodySmall)
                            pts.forEach { pt ->
                                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text(pt, style = MaterialTheme.typography.bodySmall)
                                        TextButton({ vm.raiseWithBob(pt) { nav.navigate("chat/$id") { popUpTo("reeval/$id") { inclusive = true } } } }) { Text("Raise with Bob") }
                                    }
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
            // What each button does is said in words, right above it. Approving is never a side effect of navigating.
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(sum.primary.explanation, style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (r.status == "APPROVED") {
                        Button({ nav.navigate("spec/$id") }, Modifier.weight(1f)) { Text(sum.primary.label) }
                        if (r.fromSpec != null && r.approvedSpec != null) OutlinedButton({ nav.navigate("compare/$id/${r.fromSpec}/${r.approvedSpec}") }) { Text("Compare specs") }
                    } else if (r.status == "OPEN") {
                        Button({ nav.navigate("chat/$id") { popUpTo("reeval/$id") { inclusive = true } } }, Modifier.weight(1f)) { Text(sum.primary.label) }
                        OutlinedButton({ vm.discardReevaluation() }) { Text("Discard") }
                    } else Button({ nav.navigate("chat/$id") }, Modifier.weight(1f)) { Text(sum.primary.label) }
                }
                sum.secondary?.let { Text("Discard: ${it.explanation}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}
