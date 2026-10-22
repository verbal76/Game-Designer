package com.hotattic.gamedesigner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.core.model.ClaudePlan
import com.hotattic.gamedesigner.core.model.Experience
import com.hotattic.gamedesigner.core.model.UsageStyle

@Composable
fun OnboardingScreen(vm: AppViewModel) {
    val s by vm.settings.collectAsState()
    var step by rememberSaveable { mutableStateOf(0) }
    var name by rememberSaveable { mutableStateOf(s.directorName) }
    var exp by rememberSaveable { mutableStateOf(s.defaultExperience) }
    var internet by rememberSaveable { mutableStateOf(s.internetResearchAllowed) }
    var github by rememberSaveable { mutableStateOf(s.githubEnabled) }
    var plan by rememberSaveable { mutableStateOf(s.defaultClaudePlan) }
    var usage by rememberSaveable { mutableStateOf(s.defaultUsageStyle) }
    val last = 4

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(20.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Step ${step + 1} of ${last + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
            when (step) {
                0 -> {
                    Text("Welcome to Game Designer", style = MaterialTheme.typography.headlineMedium)
                    Text("I'm your AI game director. Tell me your idea in plain words - typing or your phone's voice dictation - and we'll turn it into a complete, researched, consistent build specification.")
                    Text("You get two things at the end: a detailed CLAUDE.md for your game, and a short master prompt that tells Claude Code how to build it into a real, playable game.", style = MaterialTheme.typography.bodyMedium)
                    Text("Everything is saved on this phone. Nothing is sent anywhere unless you allow it in the next steps.", style = MaterialTheme.typography.bodyMedium)
                }
                1 -> {
                    Text("Who's directing?", style = MaterialTheme.typography.headlineSmall)
                    OutlinedTextField(name, { name = it.take(24) }, label = { Text("Director's name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Text("Default is Bob. Rename me anything you like - it's only a name.", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Your experience with game development", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.selectableGroup()) {
                        listOf(
                            Experience.BEGINNER to "Beginner - just describe the game; I'll handle engines and tech",
                            Experience.INTERMEDIATE to "Intermediate - I know some of it",
                            Experience.EXPERT to "Expert - I have preferences and will override you",
                        ).forEach { (e, label) -> Choice(exp == e, label) { exp = e } }
                    }
                }
                2 -> {
                    Text("Permissions, up front", style = MaterialTheme.typography.headlineSmall)
                    Text("So I don't interrupt you every few minutes, decide now:")
                    ToggleRow("Let me research on the internet when it helps", "Reference games, current engine versions and requirements. Sources are recorded. Turn off to stay fully offline.", internet) { internet = it }
                    ToggleRow("I may connect GitHub (optional)", "Lets me inspect existing game repositories. You add a token later in Settings. Everything works without GitHub.", github) { github = it }
                }
                3 -> {
                    Text("Your Claude setup", style = MaterialTheme.typography.headlineSmall)
                    Text("This helps me size the game so it can actually be built within your allowance. I give qualitative estimates (Low / Moderate / Heavy / Extreme), not made-up token counts.", style = MaterialTheme.typography.bodyMedium)
                    Column(Modifier.selectableGroup()) { ClaudePlan.values().forEach { p -> Choice(plan == p, p.label) { plan = p } } }
                    Text("How aggressively should builds use it?", style = MaterialTheme.typography.titleMedium)
                    Column(Modifier.selectableGroup()) { UsageStyle.values().forEach { u -> Choice(usage == u, u.label) { usage = u } } }
                }
                else -> {
                    Text("On-device AI (optional)", style = MaterialTheme.typography.headlineSmall)
                    Text("Game Designer works right away using its built-in interview engine. For smarter understanding of free-form descriptions, you can add an on-device model in Settings (a multi-gigabyte download, or import a file). It runs on your phone; your ideas never leave it.")
                    Text("You can also add a Claude API key in Settings for deeper reviews. Neither is required.", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            if (step > 0) OutlinedButton({ step-- }) { Text("Back") } else Spacer(Modifier.height(1.dp))
            Button({
                if (step < last) step++
                else vm.updateSettings { it.copy(directorName = name.ifBlank { "Bob" }, defaultExperience = exp, internetResearchAllowed = internet, githubEnabled = github, defaultClaudePlan = plan, defaultUsageStyle = usage, onboardingComplete = true) }
            }) { Text(if (step < last) "Next" else "Start designing") }
        }
    }
}

@Composable
fun Choice(selected: Boolean, label: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onClick, role = Role.RadioButton).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 12.dp))
    }
}

@Composable
fun ToggleRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}
