package app.potify.offline.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PauseCircleOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.potify.offline.AppViewModel
import app.potify.offline.DownloadState
import app.potify.offline.Library
import kotlinx.coroutines.launch

private enum class Filter(val label: String) { ALL("All"), YOUTUBE("From links"), YOURS("Yours") }

@Composable
fun LibraryScreen(
    vm: AppViewModel,
    link: String,
    linkError: String?,
    onLinkChange: (String) -> Unit,
    onSubmitLink: () -> Unit,
    onOpen: (String) -> Unit,
    onSettings: () -> Unit,
    onOpenReview: () -> Unit,
    padding: PaddingValues,
) {
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val liked by vm.liked.collectAsStateWithLifecycle()
    val songCount by vm.songCount.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<Library.Playlist?>(null) }
    val selected = Filter.entries[filter]
    val shown = playlists.orEmpty().filter {
        when (selected) {
            Filter.ALL -> true
            Filter.YOUTUBE -> !it.isLocal
            Filter.YOURS -> it.isLocal
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = padding.calculateBottomPadding() + 16.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Your library", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "New playlist") }
                IconButton(onClick = onSettings) { Icon(Icons.Rounded.Settings, "Settings") }
            }
        }
        item(span = { GridItemSpan(maxLineSpan) }) {
            LinkField(link, linkError, onLinkChange, onSubmitLink)
        }
        vm.spotify?.let { review ->
            item(span = { GridItemSpan(maxLineSpan) }) { ReviewBanner(review, onOpenReview) }
        }
        if (downloads.visible) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                DownloadBanner(downloads, onPause = vm::pauseDownloads, onResume = vm::startDownloads)
            }
        }
        if (!playlists.isNullOrEmpty() || liked.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Filter.entries.forEach { option ->
                        FilterChip(
                            selected = selected == option,
                            onClick = { filter = option.ordinal },
                            label = { Text(option.label) },
                            shape = RoundedCornerShape(50),
                            border = FilterChipDefaults.filterChipBorder(
                                enabled = true, selected = selected == option,
                                borderColor = MaterialTheme.colorScheme.outlineVariant,
                                selectedBorderColor = MaterialTheme.colorScheme.secondaryContainer,
                            ),
                        )
                    }
                }
            }
        }
        if (selected != Filter.YOUTUBE && songCount > 0) {
            item { AllSongsTile(songCount) { onOpen(Library.ALL) } }
        }
        if (selected != Filter.YOUTUBE && (liked.isNotEmpty() || selected == Filter.YOURS)) {
            item { LikedTile(liked.size) { onOpen(Library.LIKED) } }
        }
        items(shown, key = { it.id() }) { playlist ->
            PlaylistTile(playlist, onClick = { onOpen(playlist.id()) }, onLongClick = { actionsFor = playlist })
        }
        if (playlists != null && shown.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                when (selected) {
                    Filter.YOURS -> EmptyMessage(
                        "Make a playlist of your own",
                        "Tap + above, then add songs from any playlist with the ⋮ menu.",
                    )
                    else -> EmptyMessage(
                        "Start your library",
                        "Paste a YouTube or Spotify link above: a playlist, an album or a single song. Its audio is saved to this phone so you can listen offline.",
                    )
                }
            }
        }
    }

    if (creating) {
        TextInputDialog("New playlist", "", "Create", onConfirm = {
            vm.createPlaylist(it)
            creating = false
        }, onDismiss = { creating = false })
    }
    actionsFor?.let { playlist ->
        PlaylistActionsSheet(playlist, vm, onDismiss = { actionsFor = null })
    }
}

@Composable
private fun LinkField(value: String, error: String?, onChange: (String) -> Unit, onSubmit: () -> Unit) {
    val clipboard = LocalClipboard.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    Column {
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = colors.surfaceContainer,
            border = BorderStroke(1.dp, if (error != null) colors.error else colors.outlineVariant),
        ) {
            Row(
                Modifier.height(54.dp).padding(start = 14.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Link, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) {
                        Text("YouTube or Spotify link", maxLines = 1, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onChange,
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            focus.clearFocus()
                            onSubmit()
                        }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (value.isEmpty()) {
                    TextButton(onClick = {
                        scope.launch {
                            val clip = clipboard.getClipEntry()?.clipData
                            if (clip != null && clip.itemCount > 0) {
                                clip.getItemAt(0).coerceToText(context)?.toString()?.let(onChange)
                            }
                        }
                    }) {
                        Icon(Icons.Rounded.ContentPaste, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Paste")
                    }
                } else {
                    FilledIconButton(onClick = {
                        focus.clearFocus()
                        onSubmit()
                    }) { Icon(Icons.Rounded.Download, "Download playlist") }
                }
            }
        }
        if (error != null) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                modifier = Modifier.padding(start = 4.dp, top = 6.dp),
            )
        }
    }
}

@Composable
private fun DownloadBanner(state: DownloadState, onPause: () -> Unit, onResume: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val summary = state.summary
    val detail = when {
        summary.waiting() > 0 -> "${summary.waiting()} ${if (summary.waiting() == 1) "song" else "songs"} left"
        summary.readingPlaylists() > 0 -> "Reading playlist…"
        else -> "Finishing up…"
    }
    Surface(shape = MaterialTheme.shapes.medium, color = colors.primaryContainer.copy(alpha = 0.6f)) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (state.running) Icons.Rounded.Download else Icons.Rounded.PauseCircleOutline,
                    null, tint = colors.primary, modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (state.running) "Downloading" else "Downloads paused",
                        style = MaterialTheme.typography.titleSmall,
                        color = colors.onPrimaryContainer,
                    )
                    Text(detail, style = Muted)
                }
                TextButton(onClick = if (state.running) onPause else onResume) {
                    Text(if (state.running) "Pause" else "Resume")
                }
            }
            if (state.running) {
                Spacer(Modifier.height(8.dp))
                ProgressLine(null, Modifier.fillMaxWidth().padding(end = 8.dp))
            }
        }
    }
}

@Composable
private fun LikedTile(count: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).background(colors.primaryContainer, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Favorite, null, Modifier.size(44.dp), tint = colors.primary)
        }
        Spacer(Modifier.height(8.dp))
        Text("Liked songs", style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text("$count ${if (count == 1) "song" else "songs"}", style = Muted)
    }
}

/** Every song from every playlist, in one list. */
@Composable
private fun AllSongsTile(count: Int, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.clickable(onClick = onClick)) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).background(colors.tertiaryContainer, MaterialTheme.shapes.medium),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.LibraryMusic, null, Modifier.size(44.dp), tint = colors.onTertiaryContainer)
        }
        Spacer(Modifier.height(8.dp))
        Text("All songs", style = MaterialTheme.typography.titleSmall, maxLines = 1)
        Text("$count ${if (count == 1) "song" else "songs"}", style = Muted)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PlaylistTile(playlist: Library.Playlist, onClick: () -> Unit, onLongClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Cover(playlist.thumbs(), playlist.id(), Modifier.fillMaxWidth().aspectRatio(1f), corner = 14.dp)
        Spacer(Modifier.height(8.dp))
        Text(playlist.title(), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        when {
            Library.FAILED == playlist.status() -> Text(
                playlist.error() ?: "Couldn't read this playlist",
                style = MaterialTheme.typography.bodySmall,
                color = colors.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            playlist.total() == 0 && !playlist.isLocal -> Text(
                if (Library.ACTIVE == playlist.status()) "Reading playlist…" else "Waiting…",
                style = Muted,
            )
            playlist.pending() > 0 -> {
                Spacer(Modifier.height(5.dp))
                ProgressLine(playlist.saved().toFloat() / playlist.total().coerceAtLeast(1), Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text("${playlist.saved()} of ${playlist.total()} saved", style = Muted)
            }
            else -> Text(
                (if (playlist.isLocal) "Your playlist · " else "") +
                    "${playlist.total()} ${if (playlist.total() == 1) "song" else "songs"}",
                style = Muted,
                maxLines = 1,
            )
        }
    }
}

/** Shown on the library while a Spotify link's review is unfinished. */
@Composable
private fun ReviewBanner(review: app.potify.offline.SpotifyImport, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val list = review.list
    val title = when {
        list == null -> "Reading Spotify link…"
        review.refreshOf != null -> "New songs in ${list.name}"
        else -> list.name
    }
    val detail = when {
        review.phase is app.potify.offline.SpotifyImport.Phase.Failed -> "Couldn't read this link"
        review.isMatching -> "Finding songs on YouTube · ${review.matchedCount} of ${review.rows.size}"
        else -> "${review.rows.size} ${if (review.rows.size == 1) "song" else "songs"} ready to review"
    }
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = colors.primaryContainer,
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Link, null, Modifier.size(20.dp), tint = colors.onPrimaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onPrimaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.bodySmall, color = colors.onPrimaryContainer.copy(alpha = 0.75f))
            }
            Text("Review", style = MaterialTheme.typography.labelLarge, color = colors.primary)
        }
    }
}
