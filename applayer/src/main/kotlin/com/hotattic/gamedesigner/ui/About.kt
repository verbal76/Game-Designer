package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.VersionIdentity
import com.hotattic.gamedesigner.shellapi.ShellServices

/** Which APK, which application layer, which OTA, which AI mode and model: answered at a glance. */
@Composable
fun AboutSection(vm: AppViewModel, shell: ShellServices) {
    val st by vm.container.modelController.status.collectAsState()
    val settings by vm.settings.collectAsState()
    val d = shell.ota.diagnostics()
    val local = st.loaded || (st.ready && settings.localModelEnabled)
    val mode = if (local) "LOCAL AI (on-device LLM)" else "RULES-ONLY (no on-device model active)"
    val model = if (local) vm.container.modelController.activeLabel() + (if (!st.loaded) " - not loaded yet" else "") else "none"
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("About", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        VersionIdentity.lines(d, mode, model).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}
