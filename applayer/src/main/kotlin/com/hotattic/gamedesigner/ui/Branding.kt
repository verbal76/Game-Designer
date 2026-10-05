package com.hotattic.gamedesigner.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.hotattic.gamedesigner.AppViewModel
import com.hotattic.gamedesigner.core.model.BrandingMode
import com.hotattic.gamedesigner.core.model.BrandingSlot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private data class SlotInfo(val slot: String, val title: String, val blurb: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrandingScreen(vm: AppViewModel, nav: NavController, id: String) {
    val project by vm.current.collectAsState()
    LaunchedEffect(id) { if (vm.current.value?.id != id) vm.open(id) }
    val p = project?.takeIf { it.id == id }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    // The slot is saved across activity recreation (the Files picker is another activity); a lost slot is recovered by the ViewModel.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> val s = pick; pick = null; vm.onImagePicked(uri, s) }
    val slots = listOf(
        SlotInfo(BrandingSlot.ICON, "Game icon", "Your game's app icon. Square, at least 512x512 recommended."),
        SlotInfo(BrandingSlot.STUDIO_SPLASH, "Studio / developer splash", "Shown briefly at launch before the game's own title screen."),
        SlotInfo(BrandingSlot.GAME_SPLASH, "Game splash / title image", "The full-screen title image for your game."),
    )
    Scaffold(topBar = { TopAppBar(title = { Text("Game branding") }, navigationIcon = { IconButton({ nav.popBackStack() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } }) }) { pad ->
        if (p == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(pad).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text("Optional. Your uploads are kept untouched as masters; the build derives every platform size from them. Anything you skip can be created for you.", style = MaterialTheme.typography.bodyMedium) }
            items(slots, key = { it.slot }) { s ->
                val b = p.branding[s.slot]
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(s.title, style = MaterialTheme.typography.titleMedium)
                        Text(s.blurb, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        val file = vm.brandingFile(s.slot)
                        if (file != null && b?.mode == BrandingMode.UPLOADED) {
                            Preview(file)
                            Text("${b.originalName} - ${b.width}x${b.height}", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Text(when (b?.mode) {
                                BrandingMode.GENERATE_ORIGINAL -> "An original asset will be created."
                                BrandingMode.GENERIC_TEMPORARY -> "A clean generic placeholder will be used."
                                BrandingMode.SKIP -> "Skipped."
                                else -> "Not set yet."
                            }, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button({ pick = s.slot; picker.launch("image/*") }) { Text(if (file != null) "Replace" else "Upload") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(b?.mode == BrandingMode.GENERATE_ORIGINAL, { vm.setBrandingChoice(s.slot, "generate_original") }, { Text("Create original") })
                            FilterChip(b?.mode == BrandingMode.GENERIC_TEMPORARY, { vm.setBrandingChoice(s.slot, "generic_temporary") }, { Text("Generic") })
                            FilterChip(b?.mode == BrandingMode.SKIP, { vm.setBrandingChoice(s.slot, "skip") }, { Text("Skip") })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Preview(file: File) {
    val bmp by produceState<Bitmap?>(null, file.absolutePath) {
        value = withContext(Dispatchers.IO) {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, o)
            var sample = 1
            while (o.outWidth / sample > 1200 || o.outHeight / sample > 1200) sample *= 2
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    }
    Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), "Preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize()) }
    }
}
