package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.shellapi.SecretStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReposScreen(vm: AppViewModel, nav: NavController) {
    val repos by vm.repos.collectAsState()
    val busy by vm.busy.collectAsState()
    val hasToken = vm.container.secrets.has(SecretStore.GITHUB_TOKEN)
    LaunchedEffect(hasToken) { if (hasToken) vm.loadRepos() }
    Scaffold(topBar = { TopAppBar(title = { Text("Choose a repository") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (!hasToken) {
                Text("GitHub is optional. To inspect an existing game repository, add a fine-grained personal access token with read access to Contents and Metadata in Settings. Nothing is ever written to your repository without your confirmation.")
                Button({ nav.navigate("settings") }) { Text("Open Settings") }
            } else {
                busy?.let { Text(it) }
                val list = repos
                if (list != null && list.isEmpty() && busy == null) Text("No repositories found, or the token was rejected. Check the token in Settings.")
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list.orEmpty(), key = { it.owner + "/" + it.name }) { r ->
                        Card(Modifier.fillMaxWidth().clickable(enabled = busy == null) { vm.startFromRepo(r) { id -> nav.navigate("chat/$id") { popUpTo("home") } } }) {
                            Column(Modifier.padding(14.dp)) {
                                Text("${r.owner}/${r.name}" + if (r.isPrivate) "  (private)" else "", style = MaterialTheme.typography.titleSmall)
                                if (r.description.isNotBlank()) Text(r.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}
