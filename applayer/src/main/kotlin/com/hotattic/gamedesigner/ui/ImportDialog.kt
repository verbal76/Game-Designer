package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.hotattic.gamedesigner.AppViewModel

/**
 * Step 2 of importing: says what each chosen file is (complete project data, a backup, only a generated spec, or not recognised) before anything is
 * saved. Importing never overwrites an existing project.
 */
@Composable
fun ImportDialog(vm: AppViewModel) {
    val preview by vm.importPreview.collectAsState()
    val p = preview ?: return
    AlertDialog(
        onDismissRequest = { vm.cancelImports() },
        title = { Text(if (p.importable > 0) "Import ${p.importable} project(s)?" else "Nothing to import") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                p.files.forEach { f ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(f.fileName, style = MaterialTheme.typography.titleSmall)
                        Text(f.headline, style = MaterialTheme.typography.bodyMedium)
                        if (f.projects.isNotEmpty()) Text("Projects: " + f.projects.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                        f.details.take(4).forEach { d -> Text(d, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    }
                }
                Text("Projects are added as new ones. If a project with the same id or name already exists, the imported one is saved as a separate copy; nothing you already have is changed or replaced.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { if (p.importable > 0) TextButton({ vm.confirmImports() }) { Text("Import") } else TextButton({ vm.cancelImports() }) { Text("Close") } },
        dismissButton = { if (p.importable > 0) TextButton({ vm.cancelImports() }) { Text("Cancel") } },
    )
}

/** What a backup contains and what it does not. A backup with anything left out is never presented as a clean success. */
@Composable
fun BackupReportDialog(vm: AppViewModel) {
    val report by vm.backupReport.collectAsState()
    val r = report ?: return
    AlertDialog(
        onDismissRequest = { vm.dismissBackupReport() },
        title = { Text(if (r.success) "Backup saved" else "Backup needs attention") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(r.headline, style = MaterialTheme.typography.bodyMedium)
                r.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                if (!r.success) Text("Nothing on this phone was changed or deleted. Do not rely on this file as a complete backup. Fix the items above, or save another backup.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton({ vm.dismissBackupReport() }) { Text("OK") } },
    )
}
