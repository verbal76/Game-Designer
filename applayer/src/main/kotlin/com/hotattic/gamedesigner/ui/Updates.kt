package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hotattic.gamedesigner.shellapi.OtaDiagnostics
import com.hotattic.gamedesigner.shellapi.ShellServices
import kotlinx.coroutines.delay

/**
 * "Update ready - Restart" banner. A build that is downloaded, verified and scheduled for the next start (automatically on the dev
 * line, or because the owner chose it on the stable line) is offered here so it can be applied at once. It never restarts by itself.
 */
@Composable
fun UpdateBanner(shell: ShellServices, modifier: Modifier = Modifier) {
    var d by remember { mutableStateOf<OtaDiagnostics?>(runCatching { shell.ota.diagnostics() }.getOrNull()) }
    var dismissed by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5_000)
            d = runCatching { shell.ota.diagnostics() }.getOrNull()
        }
    }
    val pending = d?.pendingVersion ?: return
    if (pending == dismissed) return
    Surface(modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 6.dp) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Update ready", style = MaterialTheme.typography.titleSmall)
                Text(d?.pendingName ?: "Build $pending", style = MaterialTheme.typography.bodySmall)
            }
            TextButton({ dismissed = pending }) { Text("Later") }
            Button({ shell.ota.restartNow() }) { Text("Restart now") }
        }
    }
}
