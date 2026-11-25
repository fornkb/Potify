package app.potify.offline.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.potify.offline.AppViewModel
import app.potify.offline.DuplicateGroup
import app.potify.offline.Library
import app.potify.offline.PlayerConnection
import app.potify.offline.SongCopy
import app.potify.offline.formatBytes
import app.potify.offline.formatTime
import kotlinx.coroutines.launch

/** The ⋮ menu of All songs. */
@Composable
fun AllSongsSheet(count: Int, onFindDuplicates: () -> Unit, onSelect: () -> Unit, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        SheetHeader("All songs", "$count ${if (count == 1) "song" else "songs"}") {
            Box(
                Modifier.size(48.dp).background(MaterialTheme.colorScheme.tertiaryContainer, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        SheetAction(Icons.Rounded.ContentCopy, "Remove duplicates", "Finds songs saved more than once, e.g. from different channels", onClick = onFindDuplicates)
        SheetAction(Icons.Rounded.Checklist, "Select duplicates yourself", "Pick copies of a song and keep one", onClick = onSelect)
    }
}

/**
 * Groups of songs that look like the same song. The user picks the copy to keep in each group
 * (or marks a group as not duplicates); the other copies are merged into it.
 */
@Composable
fun DuplicatesScreen(
    vm: AppViewModel,
    player: PlayerConnection,
    onBack: () -> Unit,
    /** Called once the merge is saved; leaves this screen if it's still showing. */
    onDone: () -> Unit,
    onSelectYourself: () -> Unit,
    padding: PaddingValues,
) {
    val colors = MaterialTheme.colorScheme
    val groups by produceState<List<DuplicateGroup>?>(null) { value = vm.findDuplicates() }
    val keep = remember(groups) { mutableStateMapOf<Int, String>() }
    val skipped = remember(groups) { mutableStateListOf<Int>() }
    var confirming by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }

    val found = groups
    val included = found.orEmpty().withIndex().filter { it.index !in skipped }
    val removing = included.sumOf { it.value.copies.size - 1 }
    val freeing = included.sumOf { (index, group) ->
        val kept = keep[index] ?: group.copies.first().track.id()
        group.copies.filter { it.track.id() != kept && it.track.isSaved }.sumOf { it.track.bytes() }
    }

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
        TopBar("Remove duplicates", onBack)
        when {
            found == null -> Column(
                Modifier.fillMaxSize().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text("Looking for songs saved more than once…", style = Muted)
            }
            found.isEmpty() -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                EmptyMessage(
                    "No duplicates found",
                    "No two songs look alike by title, artist and length. If two copies of a song weren't caught, select them yourself.",
                )
                OutlinedButton(onClick = onSelectYourself) { Text("Select duplicates yourself") }
            }
            else -> {
                LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
                    item {
                        Text(
                            "${found.size} ${if (found.size == 1) "song is" else "songs are"} saved more than once. " +
                                "Pick the copy to keep: playlists with the other copies will use it instead, and the other copies are deleted.",
                            style = Muted,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                    itemsIndexed(found, key = { _, group -> group.copies.first().track.id() }) { index, group ->
                        val isSkipped = index in skipped
                        val kept = keep[index] ?: group.copies.first().track.id()
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = colors.surfaceContainer,
                            border = BorderStroke(1.dp, colors.outlineVariant),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                        ) {
                            Column(Modifier.padding(top = 6.dp)) {
                                Column(Modifier.alpha(if (isSkipped) 0.45f else 1f)) {
                                    group.copies.forEach { copy ->
                                        CopyRow(
                                            copy = copy,
                                            selected = !isSkipped && copy.track.id() == kept,
                                            enabled = !isSkipped,
                                            vm = vm,
                                            player = player,
                                            onSelect = { keep[index] = copy.track.id() },
                                        )
                                    }
                                }
                                Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (isSkipped) "Left as they are"
                                        else "Keeping 1 · removing ${group.copies.size - 1}",
                                        style = Muted,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = { if (isSkipped) skipped.remove(index) else skipped.add(index) }) {
                                        Text(if (isSkipped) "Include" else "Not the same song")
                                    }
                                }
                            }
                        }
                    }
                }
                Surface(color = colors.surfaceContainer) {
                    Column {
                        HorizontalDivider(color = colors.outlineVariant)
                        Button(
                            onClick = { confirming = true },
                            enabled = removing > 0 && !merging,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(48.dp),
                        ) {
                            Text(
                                when {
                                    merging -> "Removing…"
                                    removing == 0 -> "Nothing to remove"
                                    else -> "Remove $removing ${if (removing == 1) "duplicate" else "duplicates"}"
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirming) {
        ConfirmDialog(
            "Remove $removing ${if (removing == 1) "duplicate" else "duplicates"}?",
            buildString {
                append("Playlists that had them will play the copy you kept instead.")
                if (freeing > 0) append(" Frees ${formatBytes(freeing)} on this phone.")
            },
            "Remove",
            onConfirm = {
                confirming = false
                merging = true
                val merges = included.map { (index, group) ->
                    val kept = keep[index] ?: group.copies.first().track.id()
                    kept to group.copies.map { it.track.id() }.filter { it != kept }
                }
                vm.mergeDuplicates(merges) { replacements ->
                    player.replaceMerged(replacements)
                    onDone()
                }
            },
            onDismiss = { confirming = false },
        )
    }
}

/**
 * Every song with a tick box, to pick copies of one song the finder didn't catch; then the user
 * chooses which copy to keep. Stays open afterwards for the next song.
 */
@Composable
fun MergeScreen(vm: AppViewModel, player: PlayerConnection, onBack: () -> Unit, padding: PaddingValues) {
    val colors = MaterialTheme.colorScheme
    val tracks by remember { vm.tracks(Library.ALL) }.collectAsStateWithLifecycle(null)
    var query by rememberSaveable { mutableStateOf("") }
    val picked = remember { mutableStateListOf<String>() }
    var choosing by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val all = tracks.orEmpty()
    val text = query.trim()
    val shown = if (text.isEmpty()) all else all.filter {
        it.title().contains(text, ignoreCase = true) || it.artist().orEmpty().contains(text, ignoreCase = true)
    }
    // Songs merged away (or deleted elsewhere) can't stay ticked.
    LaunchedEffect(tracks) {
        val present = tracks?.mapTo(HashSet()) { it.id() } ?: return@LaunchedEffect
        picked.retainAll { it in present }
    }

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
        TopBar("Select duplicates", onBack) {
            if (picked.isNotEmpty()) TextButton(onClick = { picked.clear() }) { Text("Clear") }
        }
        Text(
            "Tick two or more copies of the same song, then choose the one to keep.",
            style = Muted,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = colors.surfaceContainer,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Row(Modifier.height(48.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (query.isEmpty()) {
                        Text("Filter by title or artist", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear filter") }
                }
            }
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(top = 6.dp, bottom = 12.dp)) {
            items(shown, key = { it.id() }) { track ->
                val checked = track.id() in picked
                val toggle = { if (checked) picked.remove(track.id()) else picked.add(track.id()) }
                Row(
                    Modifier.fillMaxWidth().clickable { toggle() }.padding(start = 6.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = checked, onCheckedChange = { toggle() })
                    Artwork(track.thumb(), track.id(), Modifier.size(44.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            track.title(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(copyDetail(track), style = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (tracks != null && shown.isEmpty()) {
                item { EmptyMessage("No matches", "No song's title or artist contains “$text”.") }
            }
        }
        Surface(color = colors.surfaceContainer) {
            Column {
                HorizontalDivider(color = colors.outlineVariant)
                Button(
                    onClick = { choosing = true },
                    enabled = picked.size >= 2,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(48.dp),
                ) {
                    Text(if (picked.size < 2) "Tick at least 2 songs" else "Keep one of ${picked.size} songs")
                }
            }
        }
    }

    if (choosing) {
        KeepSheet(picked.toList(), vm, player, onDismiss = { choosing = false }) { keep, others ->
            choosing = false
            picked.clear()
            vm.mergeDuplicates(listOf(keep to others), player::replaceMerged)
        }
    }
}

/** Picks which of the ticked songs to keep. */
@Composable
private fun KeepSheet(
    ids: List<String>,
    vm: AppViewModel,
    player: PlayerConnection,
    onDismiss: () -> Unit,
    onMerge: (String, List<String>) -> Unit,
) {
    val copies by produceState<List<SongCopy>?>(null, ids) { value = vm.describeCopies(ids) }
    var keep by remember { mutableStateOf<String?>(null) }
    Sheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Keep which copy?", style = MaterialTheme.typography.titleMedium)
            Text(
                "Playlists with the other copies will use the one you keep. The others are deleted from this phone.",
                style = Muted,
            )
        }
        Spacer(Modifier.height(8.dp))
        val list = copies
        if (list == null) {
            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
            }
        } else {
            val kept = keep ?: list.firstOrNull()?.track?.id()
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(list, key = { it.track.id() }) { copy ->
                    CopyRow(copy, selected = copy.track.id() == kept, enabled = true, vm = vm, player = player) {
                        keep = copy.track.id()
                    }
                }
            }
            Button(
                onClick = { if (kept != null) onMerge(kept, list.map { it.track.id() }.filter { it != kept }) },
                enabled = kept != null && list.size >= 2,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(48.dp),
            ) {
                val others = list.size - 1
                Text("Keep this one · remove $others ${if (others == 1) "copy" else "copies"}")
            }
        }
    }
}

/** One copy of a song: tap to keep it; the play button lets the user hear it first. */
@Composable
private fun CopyRow(
    copy: SongCopy,
    selected: Boolean,
    enabled: Boolean,
    vm: AppViewModel,
    player: PlayerConnection,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val track = copy.track
    val scope = rememberCoroutineScope()
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSelect).padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Artwork(track.thumb(), track.id(), Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(copyDetail(track), style = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val where = buildList {
                addAll(copy.playlists)
                if (track.liked()) add("Liked songs")
            }
            if (where.isNotEmpty()) {
                Text(
                    "In " + where.joinToString(", "),
                    style = MaterialTheme.typography.bodySmall, color = colors.primary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Text("Keep", style = MaterialTheme.typography.labelMedium, color = colors.primary, textAlign = TextAlign.End)
        }
        if (track.isSaved) {
            val playing = player.isPlaying && player.currentId == track.id()
            IconButton(onClick = {
                if (playing) player.pause() else scope.launch {
                    val (items, index) = vm.playable(listOf(track), track.id())
                    if (items.isNotEmpty()) player.play(items, index, "Duplicates", false)
                }
            }) {
                Icon(
                    if (playing) Icons.Rounded.GraphicEq else Icons.Rounded.PlayArrow,
                    if (playing) "Pause" else "Play this copy",
                    tint = colors.primary,
                )
            }
        } else {
            Spacer(Modifier.width(12.dp))
        }
    }
}

/** "Channel · 3:45 · 4.1 MB", or why the copy isn't on the phone. */
private fun copyDetail(track: Library.Track): String = buildList {
    track.artist()?.takeIf { it.isNotBlank() }?.let { add(it.removeSuffix(" - Topic")) }
    if (track.seconds() > 0) add(formatTime(track.seconds()))
    when (track.status()) {
        Library.SAVED -> if (track.bytes() > 0) add(formatBytes(track.bytes()))
        Library.QUEUED, Library.ACTIVE -> add("Downloading")
        Library.FAILED -> add("Unavailable")
        Library.REMOVED -> add("Not downloaded")
    }
}.joinToString(" · ")

@Composable
private fun TopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        actions()
    }
}
