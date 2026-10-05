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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavController
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.shellapi.ShellServices
import com.hotattic.gamedesigner.applayer.AppLayerEntry
import com.hotattic.gamedesigner.DEFAULT_CLOUD_MODEL
import com.hotattic.gamedesigner.core.llm.LlmMessage
import com.hotattic.gamedesigner.core.llm.LlmRequest
import com.hotattic.gamedesigner.core.llm.LlmResult
import com.hotattic.gamedesigner.core.model.CloudProviderId
import com.hotattic.gamedesigner.core.model.ClaudePlan
import com.hotattic.gamedesigner.core.model.Experience
import com.hotattic.gamedesigner.core.model.UsageStyle
import com.hotattic.gamedesigner.shellapi.ModelState
import com.hotattic.gamedesigner.shellapi.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(vm: AppViewModel, nav: NavController, shell: ShellServices) {
    val s by vm.settings.collectAsState()
    val c = vm.container
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val modelStates by c.models.state.collectAsState()
    val imported by c.models.imported.collectAsState()
    var name by remember(s.directorName) { mutableStateOf(s.directorName) }
    var anthropicKey by remember { mutableStateOf("") }
    var cloudModel by remember(s.cloudModel) { mutableStateOf(s.cloudModel) }
    var ghToken by remember { mutableStateOf("") }
    var hfToken by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var keysVersion by remember { mutableStateOf(0) }

    val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.viewModelScope.launch {
            val dn = ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } ?: "imported.litertlm"
            status = "Importing model..."
            val saved = try { c.models.importFrom(uri, dn) } catch (e: Exception) { status = "Import failed: ${e.message}"; return@launch }
            c.updateSettings { it.copy(localModelId = saved) }
            status = "Imported $saved and selected it."
        }
    }
    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.backupAll(uri) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.restoreBackup(uri) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Section("Director")
            OutlinedTextField(name, { name = it.take(24) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton({ vm.updateSettings { it.copy(directorName = name.ifBlank { "Bob" }) } }) { Text("Save name") }
            Text("Default experience level", style = MaterialTheme.typography.titleSmall)
            Experience.values().forEach { e -> Choice(s.defaultExperience == e, e.name.lowercase().replaceFirstChar { it.uppercase() }) { vm.updateSettings { it.copy(defaultExperience = e) } } }

            HorizontalDivider()
            Section("Claude plan (defaults for new projects)")
            ClaudePlan.values().forEach { p -> Choice(s.defaultClaudePlan == p, p.label) { vm.updateSettings { it.copy(defaultClaudePlan = p) } } }
            UsageStyle.values().forEach { u -> Choice(s.defaultUsageStyle == u, u.label) { vm.updateSettings { it.copy(defaultUsageStyle = u) } } }

            HorizontalDivider()
            Section("Internet and permissions")
            ToggleRow("Allow automatic internet research", "Reference games, current engine versions. Sources are recorded in the project.", s.internetResearchAllowed) { v -> vm.updateSettings { it.copy(internetResearchAllowed = v) } }
            ToggleRow("GitHub integration", "Optional. Needed only to inspect existing repositories.", s.githubEnabled) { v -> vm.updateSettings { it.copy(githubEnabled = v) } }

            HorizontalDivider()
            Section("On-device model")
            val any = c.localModelAvailable()
            Text(if (any) "A local model is installed. ${s.directorName} uses it to understand free-form descriptions and answer questions; the interview itself never depends on it." else "No local model installed. ${s.directorName} is using the built-in interview engine - fully functional, but less flexible with free-form text.", style = MaterialTheme.typography.bodySmall)
            ToggleRow("Use local model when available", "Runs entirely on this phone.", s.localModelEnabled) { v -> vm.updateSettings { it.copy(localModelEnabled = v) } }
            c.models.catalog.forEach { m ->
                val st = modelStates[m.id] ?: ModelState.None
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${m.name} (~${m.approxSizeMb / 1000.0} GB, ${m.license})", style = MaterialTheme.typography.titleSmall)
                    Text(m.note + if (!m.verified) " Catalog address not yet confirmed from the build environment; if the download fails, import the file instead." else "", style = MaterialTheme.typography.bodySmall)
                    when (st) {
                        is ModelState.Downloading -> { LinearProgressIndicator(progress = { if (st.total > 0) st.bytes.toFloat() / st.total else 0f }, modifier = Modifier.fillMaxWidth()); Text("${st.bytes / 1_000_000} / ${st.total / 1_000_000} MB", style = MaterialTheme.typography.bodySmall) }
                        is ModelState.Failed -> Text(st.reason, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        else -> Unit
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (st == ModelState.Ready) {
                            OutlinedButton({ vm.updateSettings { it.copy(localModelId = m.id) } }, enabled = s.localModelId != m.id) { Text(if (s.localModelId == m.id) "Selected" else "Use this") }
                            TextButton({ c.models.delete(m.id) }) { Text("Delete") }
                        } else if (st !is ModelState.Downloading) {
                            Button({ vm.viewModelScope.launch { c.models.download(m) } }) { Text(if (st is ModelState.Failed) "Retry / resume" else "Download") }
                        }
                    }
                }
            }
            imported.forEach { f ->
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Text(f, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton({ vm.updateSettings { it.copy(localModelId = f) } }, enabled = s.localModelId != f) { Text(if (s.localModelId == f) "Selected" else "Use") }
                    TextButton({ c.models.delete(f) }) { Text("Delete") }
                }
            }
            OutlinedButton({ importModel.launch(arrayOf("*/*")) }) { Text("Import a .litertlm model file") }
            OutlinedTextField(hfToken, { hfToken = it }, label = { Text("Hugging Face token (only if a model requires it)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton({ c.secrets.put(SecretStore.HF_TOKEN, hfToken.trim()); hfToken = ""; keysVersion++; status = "Token saved securely." }) { Text("Save token") }

            HorizontalDivider()
            Section("Cloud AI (optional)")
            val cloudSet = keysVersion >= 0 && c.secrets.has(SecretStore.ANTHROPIC_KEY)
            Text(if (cloudSet && s.cloudProvider == CloudProviderId.ANTHROPIC) "Claude is configured for deeper reviews." else "Not configured. The app works fully without it.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(anthropicKey, { anthropicKey = it }, label = { Text(if (cloudSet) "API key saved - enter a new one to replace" else "Anthropic API key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cloudModel, { cloudModel = it }, label = { Text("Model (default $DEFAULT_CLOUD_MODEL)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    if (anthropicKey.isNotBlank()) c.secrets.put(SecretStore.ANTHROPIC_KEY, anthropicKey.trim())
                    anthropicKey = ""; keysVersion++
                    vm.updateSettings { it.copy(cloudProvider = CloudProviderId.ANTHROPIC, cloudModel = cloudModel.trim()) }
                    status = "Saved securely on this device."
                }) { Text("Save") }
                OutlinedButton({
                    scope.launch {
                        status = "Testing..."
                        val r = c.cloud.complete(LlmRequest("Reply with the single word OK.", listOf(LlmMessage("user", "ping")), maxTokens = 10))
                        status = when (r) { is LlmResult.Ok -> "Cloud AI works."; is LlmResult.Failure -> "Test failed: ${r.reason}" }
                    }
                }, enabled = cloudSet) { Text("Test") }
                TextButton({ c.secrets.put(SecretStore.ANTHROPIC_KEY, null); vm.updateSettings { it.copy(cloudProvider = CloudProviderId.NONE) }; keysVersion++ }, enabled = cloudSet) { Text("Remove key") }
            }

            HorizontalDivider()
            Section("GitHub (optional)")
            val ghSet = keysVersion >= 0 && c.secrets.has(SecretStore.GITHUB_TOKEN)
            Text("Use a fine-grained personal access token limited to the repositories you want, with read access to Contents and Metadata. The token is stored encrypted in the Android Keystore and never in a project or export.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(ghToken, { ghToken = it }, label = { Text(if (ghSet) "Token saved - enter a new one to replace" else "GitHub token") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ if (ghToken.isNotBlank()) c.secrets.put(SecretStore.GITHUB_TOKEN, ghToken.trim()); ghToken = ""; keysVersion++; vm.updateSettings { it.copy(githubEnabled = true) }; status = "Saved securely." }) { Text("Save") }
                OutlinedButton({ scope.launch { status = "Testing..."; status = try { "Signed in as ${withContext(Dispatchers.IO) { c.github().whoAmI() }}" } catch (e: Exception) { e.message } } }, enabled = ghSet) { Text("Test") }
                TextButton({ c.secrets.put(SecretStore.GITHUB_TOKEN, null); keysVersion++ }, enabled = ghSet) { Text("Remove") }
            }

            HorizontalDivider()
            Section("Your data")
            Text("Projects are stored only on this phone. A backup contains projects and uploaded images, never API keys or tokens.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ backup.launch("game-designer-backup.zip") }) { Text("Export backup") }
                OutlinedButton({ restore.launch(arrayOf("application/zip", "application/octet-stream")) }) { Text("Restore backup") }
            }
            status?.let { Text(it, color = MaterialTheme.colorScheme.secondary) }
            HorizontalDivider()
            DiagnosticsSection(shell)
            Text("Game Designer ${shell.nativeVersionName} (build ${shell.nativeVersionCode}) - a Hot Attic Games app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Section(t: String) = Text(t, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

@Composable
private fun DiagnosticsSection(shell: ShellServices) {
    var d by remember { mutableStateOf(shell.ota.diagnostics()) }
    var msg by remember { mutableStateOf<String?>(null) }
    Section("Diagnostics and updates")
    Text("Native app: ${d.nativeVersionName} (build ${d.nativeVersionCode}), shell API ${d.shellApiLevel}\nRuntime: ${d.runtimeFingerprint}", style = MaterialTheme.typography.bodySmall)
    Text("Application layer: ${d.layerSource.uppercase()} v${d.layerVersion} (${d.layerLabel}); running code identity ${AppLayerEntry.LAYER_LABEL} v${AppLayerEntry.LAYER_VERSION}", style = MaterialTheme.typography.bodySmall)
    Text("Update channel: ${d.channel}${if (!d.trustedKeyPresent) " (disabled: this build has no trusted update key)" else ""}", style = MaterialTheme.typography.bodySmall)
    Text("Last check: ${if (d.lastCheckAt == 0L) "never" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(d.lastCheckAt))} - ${d.lastCheckResult}", style = MaterialTheme.typography.bodySmall)
    d.pendingVersion?.let { Text("Update $it is downloaded and verified. It activates the next time the app starts (close it from recents and reopen).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary) }
    if (d.startupNote.isNotBlank()) Text("Startup: ${d.startupNote}", style = MaterialTheme.typography.bodySmall)
    if (d.lastEvent.isNotBlank()) Text("Last event: ${d.lastEvent}", style = MaterialTheme.typography.bodySmall)
    if (d.badVersions.isNotEmpty()) Text("Rejected/rolled back: ${d.badVersions.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("off", "dev", "stable").forEach { ch -> androidx.compose.material3.FilterChip(d.channel == ch, { shell.ota.setChannel(ch); d = shell.ota.diagnostics() }, { Text(ch) }) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ msg = "Checking..."; shell.ota.checkNow { msg = it; d = shell.ota.diagnostics() } }, enabled = d.channel != "off") { Text("Check for update") }
        TextButton({ shell.ota.resetToBundled(); d = shell.ota.diagnostics(); msg = "Reset. The bundled layer runs from the next start." }) { Text("Use bundled layer") }
    }
    msg?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
}
