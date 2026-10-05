package com.hotattic.gamedesigner.ui

import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.core.models.ModelChoice
import com.hotattic.gamedesigner.core.models.ModelEntry
import com.hotattic.gamedesigner.core.models.ModelRecommender
import com.hotattic.gamedesigner.models.ModelController
import com.hotattic.gamedesigner.models.ModelUi
import kotlinx.coroutines.launch

private fun stars(n: Int) = "*".repeat(n.coerceIn(1, 5)) + "-".repeat(5 - n.coerceIn(1, 5))

/** Device-aware acquisition of Bob's on-device model: profile this phone, offer what it can run, download, verify, load, prove. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(vm: AppViewModel, nav: NavController) {
    val mc = vm.container.modelController
    val states by mc.states.collectAsState()
    val profile by mc.profile.collectAsState()
    val status by mc.status.collectAsState()
    val imported by mc.imported.collectAsState()
    val message by mc.message.collectAsState()
    val settings by vm.settings.collectAsState()
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }

    // Downloads need the screen awake: Android may pause a backgrounded app mid-download (progress is kept and resumable).
    val view = LocalView.current
    val working = states.values.any { it is ModelUi.Working }
    DisposableEffect(working) { view.keepScreenOn = working; onDispose { view.keepScreenOn = false } }
    DisposableEffect(Unit) { mc.refresh(); onDispose { vm.refreshInterpreter() } }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val name = try { ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } } catch (e: Exception) { null } ?: "imported.litertlm"
            mc.say("Importing $name...")
            try { val saved = vm.container.models.importFrom(uri, name); mc.refresh(); mc.say("Imported $saved. It is unverified: use it only if you trust where it came from.") } catch (e: Exception) { mc.say("Import failed: ${e.message}") }
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Bob's AI") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val local = status.loaded
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(if (local) "LOCAL AI MODE" else if (status.ready) "Model selected, not loaded yet" else "RULES-ONLY MODE", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(if (local) "Bob understands you with ${mc.activeLabel()} running on this phone. Nothing you type leaves the device." else "Without an on-device model Bob matches your words with simple rules. The answer buttons still work exactly.", style = MaterialTheme.typography.bodySmall)
                    Text("Runtime: ${status.runtime}", style = MaterialTheme.typography.bodySmall)
                    if (local) Text("Loaded in ${status.loadMillis / 1000.0}s  |  ${status.inferences} generations this session", style = MaterialTheme.typography.bodySmall)
                    status.lastError?.let { Text("Last error: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (status.ready && !local) OutlinedButton({ scope.launch { mc.say("Loading..."); val st = vm.container.shell.localLlm.load(); mc.refresh(); mc.say(if (st.loaded) "Loaded." else st.lastError ?: "Could not load.") } }) { Text("Load now") }
                        OutlinedButton({ scope.launch { testing = true; testResult = "Asking the model..."; testResult = mc.testBob(); testing = false } }, enabled = status.ready && !testing) { Text("Test Bob") }
                    }
                    testResult?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
            }
            message?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodyMedium) }

            Text("This phone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text(profile.summary(), style = MaterialTheme.typography.bodySmall)

            val choices = remember(profile) { mc.choices() }
            Text("Recommended for this phone", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (choices.isEmpty()) Text("No catalog model fits this phone's memory and storage. Free some space, or import a smaller model under Advanced.", style = MaterialTheme.typography.bodySmall)
            choices.forEach { c -> ModelCard(mc, c.entry, states[c.entry.id] ?: ModelUi.Absent, settings.localModelId == c.entry.id, c.label.title, c, 0) }

            val viable = remember(profile) { ModelRecommender.viable(profile, mc.catalog).filter { e -> choices.none { it.entry.id == e.id } } }
            val hidden = mc.catalog.size - ModelRecommender.viable(profile, mc.catalog).size
            if (viable.isNotEmpty()) {
                TextButton({ showAll = !showAll }) { Text(if (showAll) "Hide other compatible models" else "Other compatible models (${viable.size})") }
                if (showAll) viable.forEach { e -> ModelCard(mc, e, states[e.id] ?: ModelUi.Absent, settings.localModelId == e.id, "", null, 0) }
            }
            if (hidden > 0) Text("$hidden larger model(s) are hidden because this phone cannot run them comfortably.", style = MaterialTheme.typography.bodySmall)

            Text("Advanced", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            Text("You can import a .litertlm model file you already have. Imported files are not verified against a known hash or license.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton({ importLauncher.launch(arrayOf("*/*")) }) { Text("Import a .litertlm file") }
            imported.forEach { f ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(f + if (settings.localModelId == f) "  (in use)" else "", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton({ mc.useImported(f) }, enabled = settings.localModelId != f) { Text("Use") }
                    TextButton({ mc.removeImported(f) }) { Text("Delete") }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(mc: ModelController, e: ModelEntry, st: ModelUi, active: Boolean, label: String, choice: ModelChoice?, @Suppress("UNUSED_PARAMETER") depth: Int) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (label.isNotBlank()) Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            Text(e.name, style = MaterialTheme.typography.titleSmall)
            Text("${e.family} - ${e.variant} - ${e.quantization} - ${e.format}", style = MaterialTheme.typography.bodySmall)
            Text("Download ${e.sizeMb / 1000.0} GB  |  memory about ${e.runtimeRamMb / 1000.0} GB  |  context ${e.contextTokens / 1000}k tokens", style = MaterialTheme.typography.bodySmall)
            Text("Speed ${stars(e.speed)}   Quality ${stars(e.quality)}   License ${e.license}", style = MaterialTheme.typography.bodySmall)
            if (e.note.isNotBlank()) Text(e.note, style = MaterialTheme.typography.bodySmall)
            choice?.let { Text(it.fits, style = MaterialTheme.typography.bodySmall); if (it.caution.isNotBlank()) Text(it.caution, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary) }
            Text("SHA-256 ${e.sha256.take(12)}... verified before use", style = MaterialTheme.typography.labelSmall)
            when (st) {
                is ModelUi.Working -> {
                    LinearProgressIndicator(progress = { if (st.total > 0) (st.bytes.toFloat() / st.total).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                    Text("${st.phase}: ${st.bytes / 1_000_000} / ${st.total / 1_000_000} MB", style = MaterialTheme.typography.bodySmall)
                    TextButton({ mc.cancel(e.id) }) { Text("Pause") }
                }
                is ModelUi.Failed -> {
                    Text(st.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    if (st.needsToken) Text("Add a Hugging Face token in Settings, then tap Resume.", style = MaterialTheme.typography.bodySmall)
                    Button({ mc.install(e) }) { Text(if (st.resumable) "Resume" else "Try again") }
                }
                ModelUi.Installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button({ mc.use(e) }, enabled = !active) { Text(if (active) "In use" else "Use this model") }
                    TextButton({ mc.remove(e) }) { Text("Remove") }
                }
                ModelUi.Absent -> Button({ mc.install(e) }) { Text("Download") }
            }
        }
    }
}
