package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.core.model.ProjectMode
import com.hotattic.gamedesigner.core.persist.ProjectSummary
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(vm: AppViewModel, nav: NavController) {
    val projects by vm.projects.collectAsState()
    val s by vm.settings.collectAsState()
    var playtestPicker by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<ProjectSummary?>(null) }
    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Game Designer") }, actions = {
            IconButton({ nav.navigate("settings") }) { Icon(Icons.Default.Settings, "Settings") }
        })
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Text("What would you like to do, with ${s.directorName}?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            item { ActionCard(Icons.Default.Add, "New game", "Start from an idea, a reference game, or a rough feeling.") { vm.createProject(ProjectMode.NEW_GAME) { nav.navigate("chat/$it") } } }
            item { ActionCard(Icons.Default.Code, "Existing game (GitHub)", "Inspect a repository first, then plan changes that keep what works.") { nav.navigate("repos") } }
            item { ActionCard(Icons.Default.SportsEsports, "Playtest feedback", "Tell ${s.directorName} how a build felt and get a continuation spec.") { playtestPicker = true } }
            item { ActionCard(Icons.Default.Refresh, "Reevaluate", "Run a saved design through ${s.directorName}'s newest design intelligence.") { nav.navigate("reeval-pick") } }
            if (projects.isNotEmpty()) item { Text("Your projects", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
            items(projects, key = { it.id }) { p ->
                Card(Modifier.fillMaxWidth().clickable { nav.navigate("chat/${p.id}") }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name.ifBlank { "Untitled game" }, style = MaterialTheme.typography.titleMedium)
                            Text("${modeLabel(p.mode)} - ${p.versionCount} spec version(s) - ${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(p.updatedAt))}" + if (p.corrupt) " - needs recovery" else "",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton({ deleteTarget = p }) { Text("Delete") }
                    }
                }
            }
            item { Text("Saved on this phone only.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 16.dp)) }
        }
    }

    if (playtestPicker) {
        val eligible = projects.filter { it.versionCount > 0 }
        AlertDialog(onDismissRequest = { playtestPicker = false }, confirmButton = { TextButton({ playtestPicker = false }) { Text("Close") } },
            title = { Text("Which game did you play?") },
            text = {
                if (eligible.isEmpty()) Text("No project has a generated spec yet. Generate one first, build it, then come back with feedback.")
                else Column { eligible.forEach { p -> TextButton({
                    playtestPicker = false
                    vm.open(p.id); vm.setMode(ProjectMode.PLAYTEST_CONTINUE); nav.navigate("chat/${p.id}")
                }) { Text(p.name.ifBlank { "Untitled game" }) } } }
            })
    }
    deleteTarget?.let { t ->
        AlertDialog(onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${t.name.ifBlank { "Untitled game" }}\"?") }, text = { Text("This permanently removes the project, its spec versions and uploaded images from this phone. Export a backup first if unsure.") },
            confirmButton = { TextButton({ vm.deleteProject(t.id); deleteTarget = null }) { Text("Delete") } },
            dismissButton = { TextButton({ deleteTarget = null }) { Text("Cancel") } })
    }
}

fun modeLabel(m: ProjectMode) = when (m) { ProjectMode.NEW_GAME -> "New game"; ProjectMode.EXISTING_GAME -> "Existing game"; ProjectMode.PLAYTEST_CONTINUE -> "Playtest" }

@Composable
private fun ActionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.padding(start = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
