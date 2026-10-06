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
import com.hotattic.gamedesigner.shellapi.SecretStore
import com.hotattic.gamedesigner.core.llm.ProviderFactory
import com.hotattic.gamedesigner.core.llm.ProviderIds
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
    var name by remember(s.directorName) { mutableStateOf(s.directorName) }
    var anthropicKey by remember { mutableStateOf("") }
    var cloudModel by remember(s.cloudModel) { mutableStateOf(s.cloudModel) }
    var baseUrl by remember(s.llmBaseUrl) { mutableStateOf(s.llmBaseUrl) }
    var ghToken by remember { mutableStateOf("") }
    var hfToken by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var keysVersion by remember { mutableStateOf(0) }

    val backup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> if (uri != null) vm.backupAll(uri) }
    val restore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.restoreBackup(uri) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AboutSection(vm, shell)
            HorizontalDivider()
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
            Section("Bob's AI (on this phone)")
            val st by c.modelController.status.collectAsState()
            val modeLine = if (st.loaded || (st.ready && s.localModelEnabled)) "LOCAL AI: ${c.modelController.activeLabel()} (${st.runtime})" else "RULES-ONLY: no on-device model is active"
            Text(modeLine, style = MaterialTheme.typography.bodyMedium)
            Text(if (st.ready) "${s.directorName} understands you with the on-device model; everything stays on this phone." else "Without a model ${s.directorName} uses built-in rules for free text. Everything still works through the answer buttons.", style = MaterialTheme.typography.bodySmall)
            ToggleRow("Use the on-device model", "Runs entirely on this phone, offline.", s.localModelEnabled) { v -> vm.updateSettings { it.copy(localModelEnabled = v) } }
            Button({ nav.navigate("models") }) { Text(if (st.ready) "Manage models" else "Set up on-device AI") }
            OutlinedTextField(hfToken, { hfToken = it }, label = { Text("Hugging Face token (only for models that require sign-in)") }, visualTransformation = PasswordVisualTransformation(), singleLine = true, modifier = Modifier.fillMaxWidth())
            TextButton({ c.secrets.put(SecretStore.HF_TOKEN, hfToken.trim()); hfToken = ""; keysVersion++; status = "Token saved securely." }) { Text("Save token") }

            HorizontalDivider()
            Section("Cloud AI (optional)")
            val providerId = c.providerId()
            val desc = ProviderFactory.descriptor(providerId.ifBlank { ProviderIds.ANTHROPIC })!!
            val cloudSet = keysVersion >= 0 && c.secrets.has(desc.secretName)
            Text(if (c.cloudConfigured()) "${desc.label} is configured and will interpret your messages and review specs." else "Not configured. The app works fully without it, using built-in rules for free text.", style = MaterialTheme.typography.bodySmall)
            ProviderFactory.available.forEach { d -> Choice(d.id == desc.id, d.label) { vm.updateSettings { it.copy(llmProviderId = d.id, cloudProvider = if (d.id == ProviderIds.ANTHROPIC) CloudProviderId.ANTHROPIC else it.cloudProvider) } } }
            if (desc.needsBaseUrl) OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("Base URL (https://...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(anthropicKey, { anthropicKey = it }, label = { Text(if (cloudSet) "API key saved - enter a new one to replace" else "${desc.label} API key") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(cloudModel, { cloudModel = it }, label = { Text(if (desc.defaultModel.isNotBlank()) "Model (default ${desc.defaultModel})" else "Model name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("What is sent to this service: " + ProviderFactory.PRIVACY_SENT.joinToString(" ") + " What is never sent: " + ProviderFactory.PRIVACY_NOT_SENT.joinToString(" "), style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({
                    if (anthropicKey.isNotBlank()) c.secrets.put(desc.secretName, anthropicKey.trim())
                    anthropicKey = ""; keysVersion++
                    vm.updateSettings { it.copy(llmProviderId = desc.id, llmBaseUrl = baseUrl.trim(), cloudProvider = if (desc.id == ProviderIds.ANTHROPIC) CloudProviderId.ANTHROPIC else it.cloudProvider, cloudModel = cloudModel.trim()) }
                    status = "Saved securely on this device."
                }) { Text("Save") }
                OutlinedButton({
                    scope.launch {
                        status = "Testing..."
                        val r = c.cloud.complete(LlmRequest("Reply with the single word OK.", listOf(LlmMessage("user", "ping")), maxTokens = 10))
                        status = when (r) { is LlmResult.Ok -> "Cloud AI works."; is LlmResult.Failure -> "Test failed: ${r.reason}" }
                    }
                }, enabled = c.cloudConfigured()) { Text("Test") }
                TextButton({ c.secrets.put(desc.secretName, null); vm.updateSettings { it.copy(llmProviderId = "", cloudProvider = CloudProviderId.NONE) }; keysVersion++ }, enabled = cloudSet) { Text("Remove key") }
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
    Text("Application layer: ${com.hotattic.gamedesigner.VersionIdentity.layerName(d.nativeVersionName, d.layerVersion)} (${if (d.layerSource == "ota") "OTA #${d.layerVersion} - ${d.layerLabel}" else "built into the APK"}); running code identity ${AppLayerEntry.LAYER_LABEL} (sequence ${AppLayerEntry.LAYER_VERSION})", style = MaterialTheme.typography.bodySmall)
    Text("Update channel: ${d.channel}${if (!d.trustedKeyPresent) " (disabled: this build has no trusted update key)" else ""}", style = MaterialTheme.typography.bodySmall)
    Text("Last check: ${if (d.lastCheckAt == 0L) "never" else java.text.DateFormat.getDateTimeInstance().format(java.util.Date(d.lastCheckAt))} - ${d.lastCheckResult}", style = MaterialTheme.typography.bodySmall)
    d.pendingVersion?.let { v ->
        Text("${d.pendingName ?: "Build $v"} is downloaded and verified. It starts the next time the app restarts.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Button({ shell.ota.restartNow() }) { Text("Restart now") }
    }
    Text(when (d.channel) {
        "dev" -> "Dev line: new builds download and are scheduled automatically. A banner offers Restart when one is ready."
        "stable" -> "Stable line: automatic updates are OFF. Newer builds still download in the background, so they are ready when you choose them below."
        else -> "Updates are off."
    }, style = MaterialTheme.typography.bodySmall)
    if (d.startupNote.isNotBlank()) Text("Startup: ${d.startupNote}", style = MaterialTheme.typography.bodySmall)
    if (d.lastEvent.isNotBlank()) Text("Last event: ${d.lastEvent}", style = MaterialTheme.typography.bodySmall)
    if (d.badVersions.isNotEmpty()) Text("Rejected/rolled back: ${d.badVersions.joinToString()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("off" to "Off", "dev" to "Dev (automatic)", "stable" to "Stable (choose build)").forEach { (ch, label) ->
            androidx.compose.material3.FilterChip(d.channel == ch, { shell.ota.setChannel(ch); d = shell.ota.diagnostics() }, { Text(label) })
        }
    }
    if (d.channel == "stable") BuildPicker(shell, onChanged = { d = shell.ota.diagnostics() }, say = { msg = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton({ msg = "Checking..."; shell.ota.checkNow { msg = it; d = shell.ota.diagnostics() } }, enabled = d.channel != "off") { Text("Check for update") }
        TextButton({ shell.ota.resetToBundled(); d = shell.ota.diagnostics(); msg = "Reset. The bundled layer runs from the next start." }) { Text("Use bundled layer") }
    }
    msg?.let { Text(it, color = MaterialTheme.colorScheme.secondary, style = MaterialTheme.typography.bodySmall) }
}


/** The stable line: pick one of the last 10 published builds. Choosing downloads it if needed and schedules it for the next restart. */
@Composable
private fun BuildPicker(shell: ShellServices, onChanged: () -> Unit, say: (String) -> Unit) {
    var builds by remember { mutableStateOf(shell.ota.builds()) }
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }
    androidx.compose.runtime.LaunchedEffect(Unit) { shell.ota.refreshBuilds { say(it); builds = shell.ota.builds(); loading = false } }
    fun label(b: com.hotattic.gamedesigner.shellapi.OtaBuild) = buildString {
        append("#${b.version} ${b.name}")
        if (b.date.isNotBlank()) append(" - ${b.date.take(10)}")
        if (b.sourceSha.isNotBlank()) append(" - ${b.sourceSha.take(7)}")
        if (b.running) append("  (running)") else if (b.chosen) append("  (chosen)") else if (b.downloaded) append("  (downloaded)")
    }
    val current = builds.firstOrNull { it.chosen } ?: builds.firstOrNull { it.running }
    if (builds.isEmpty()) {
        Text(if (loading) "Loading the list of stable builds..." else "No stable builds have been published for this app version yet. Nothing will change until one is.", style = MaterialTheme.typography.bodySmall)
        return
    }
    androidx.compose.foundation.layout.Box {
        OutlinedButton({ open = true }, Modifier.fillMaxWidth()) { Text(current?.let { label(it) } ?: "Choose a stable build  \u25BE") }
        androidx.compose.material3.DropdownMenu(open, { open = false }) {
            builds.forEach { b ->
                androidx.compose.material3.DropdownMenuItem(text = { Text(label(b)) }, onClick = {
                    open = false
                    say(if (b.downloaded) "Switching..." else "Downloading and verifying build ${b.version}...")
                    shell.ota.chooseBuild(b.version) { r -> say(r); builds = shell.ota.builds(); onChanged() }
                })
            }
        }
    }
    Text("Choosing a build never changes the running app by itself. It starts after you tap Restart.", style = MaterialTheme.typography.bodySmall)
}
