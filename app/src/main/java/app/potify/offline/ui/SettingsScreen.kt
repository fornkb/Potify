package app.potify.offline.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.potify.offline.AppViewModel
import app.potify.offline.Prefs
import app.potify.offline.formatBytes

@Composable
fun SettingsScreen(vm: AppViewModel, onBack: () -> Unit, padding: PaddingValues) {
    val theme by vm.theme.collectAsStateWithLifecycle()
    val dataSaver by vm.dataSaver.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var storage by remember { mutableStateOf<LongArray?>(null) }
    var refreshStorage by remember { mutableIntStateOf(0) }
    LaunchedEffect(refreshStorage) { storage = vm.storageUsed() }
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.width(4.dp))
                Text("Settings", style = MaterialTheme.typography.titleLarge)
            }
        }

        item { Section("Appearance") }
        item {
            val options = listOf(Prefs.THEME_SYSTEM to "System", Prefs.THEME_LIGHT to "Light", Prefs.THEME_DARK to "Dark")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                options.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = theme == value,
                        onClick = { vm.setTheme(value) },
                        shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        icon = {},
                    ) { Text(label) }
                }
            }
        }

        item { Section("Audio quality") }
        item {
            Choice("Best", "Highest quality YouTube offers, about 1.2 MB per minute", !dataSaver) { vm.setDataSaver(false) }
            Choice("Data saver", "Around 70 kbps, about 0.5 MB per minute", dataSaver) { vm.setDataSaver(true) }
            Note("Applies to songs downloaded from now on.")
        }

        item { Section("Downloads") }
        item {
            val summary = downloads.summary
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        when {
                            downloads.running -> "Downloading"
                            summary.waiting() > 0 -> "Paused"
                            else -> "Everything is downloaded"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    val detail = buildList {
                        if (summary.waiting() > 0) add("${summary.waiting()} waiting")
                        if (summary.failed() > 0) add("${summary.failed()} unavailable")
                    }.joinToString(" · ")
                    if (detail.isNotEmpty()) Text(detail, style = Muted)
                }
                if (downloads.running) {
                    OutlinedButton(onClick = vm::pauseDownloads) { Text("Pause") }
                } else if (summary.waiting() > 0 || summary.readingPlaylists() > 0) {
                    OutlinedButton(onClick = vm::startDownloads) { Text("Resume") }
                }
            }
        }

        item { Section("Storage") }
        item {
            val used = storage
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text(
                    if (used == null) "Checking…" else formatBytes(used[0] + used[1]) + " used on this phone",
                    style = MaterialTheme.typography.bodyLarge,
                )
                if (used != null) Text("Audio ${formatBytes(used[0])} · Artwork ${formatBytes(used[1])}", style = Muted)
                Spacer(Modifier.height(12.dp))
                OutlinedButton(onClick = { vm.cleanStorage { refreshStorage++ } }) { Text("Free up space") }
                Spacer(Modifier.height(6.dp))
                Text("Removes leftover files that no playlist or liked song uses.", style = Muted)
            }
        }

        item { Section("About") }
        item {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("Potify ${version.orEmpty()}", style = MaterialTheme.typography.bodyLarge)
                Text("Saves YouTube playlist audio for offline listening.", style = Muted)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    Column(Modifier.padding(top = 18.dp)) {
        HorizontalDivider(Modifier.padding(horizontal = 20.dp), color = MaterialTheme.colorScheme.outlineVariant)
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 10.dp),
        )
    }
}

@Composable
private fun Choice(title: String, detail: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 8.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(detail, style = Muted)
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, style = Muted, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 6.dp))
}
