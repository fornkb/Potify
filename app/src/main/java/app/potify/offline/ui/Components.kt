package app.potify.offline.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.potify.offline.AppViewModel
import app.potify.offline.Library
import app.potify.offline.formatBytes
import app.potify.offline.formatTime
import app.potify.offline.formatTotal
import coil3.compose.AsyncImage
import java.io.File
import kotlin.math.absoluteValue

/** Folder holding saved artwork; provided once at the root. */
val LocalThumbDir = staticCompositionLocalOf<File?> { null }

@Composable
fun Artwork(thumb: String?, seed: String, modifier: Modifier = Modifier, corner: Dp = 8.dp) {
    val palette = LocalArtPalette.current
    val dir = LocalThumbDir.current
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(palette.tiles[seed.hashCode().absoluteValue % palette.tiles.size]),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.fillMaxSize(0.4f), tint = palette.onTile)
        if (!thumb.isNullOrEmpty() && dir != null) {
            AsyncImage(
                model = File(dir, thumb),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Playlist cover: a 2×2 mosaic of the first four tracks' artwork, or a single image. */
@Composable
fun Cover(thumbs: List<String>, seed: String, modifier: Modifier = Modifier, corner: Dp = 12.dp) {
    if (thumbs.size < 4) {
        Artwork(thumbs.firstOrNull(), seed, modifier, corner)
        return
    }
    Column(modifier.clip(RoundedCornerShape(corner))) {
        for (row in 0..1) {
            Row(Modifier.weight(1f)) {
                for (column in 0..1) {
                    Artwork(thumbs[row * 2 + column], seed, Modifier.weight(1f).fillMaxSize(), 0.dp)
                }
            }
        }
    }
}

@Composable
fun ProgressLine(fraction: Float?, modifier: Modifier = Modifier) {
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    if (fraction == null) {
        LinearProgressIndicator(
            modifier.height(3.dp), trackColor = trackColor, strokeCap = StrokeCap.Round,
        )
    } else {
        LinearProgressIndicator(
            progress = { fraction }, modifier = modifier.height(3.dp), trackColor = trackColor,
            strokeCap = StrokeCap.Round, gapSize = 0.dp, drawStopIndicator = {},
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TrackRow(
    track: Library.Track,
    isCurrent: Boolean,
    isPlaying: Boolean,
    progress: Float?,
    onClick: () -> Unit,
    onMore: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val saved = track.isSaved
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(track.thumb(), track.id(), Modifier.size(48.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                track.title(),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = when {
                    isCurrent -> colors.primary
                    saved -> colors.onSurface
                    else -> colors.onSurface.copy(alpha = 0.6f)
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            when (track.status()) {
                Library.ACTIVE -> {
                    Spacer(Modifier.height(5.dp))
                    ProgressLine(progress, Modifier.fillMaxWidth(0.7f))
                }
                Library.FAILED -> Text(
                    track.error() ?: "Unavailable",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.error,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                else -> Text(
                    trackSubtitle(track),
                    style = Muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isCurrent && isPlaying) {
            Icon(Icons.Rounded.GraphicEq, "Playing", Modifier.size(20.dp), tint = colors.primary)
        }
        IconButton(onClick = onMore) {
            Icon(Icons.Rounded.MoreVert, "More for ${track.title()}", tint = colors.onSurfaceVariant)
        }
    }
}

private fun trackSubtitle(track: Library.Track): String {
    val parts = mutableListOf<String>()
    track.artist()?.takeIf { it.isNotBlank() }?.let { parts += it.removeSuffix(" - Topic") }
    when (track.status()) {
        Library.QUEUED -> parts += "Waiting to download"
        Library.REMOVED -> parts += "Not downloaded"
        else -> if (track.seconds() > 0) parts += formatTime(track.seconds())
    }
    return parts.joinToString(" · ")
}

fun playlistMeta(playlist: Library.Playlist): String {
    val parts = mutableListOf("${playlist.total()} ${if (playlist.total() == 1) "song" else "songs"}")
    if (playlist.seconds() > 0) parts += formatTotal(playlist.seconds())
    if (playlist.bytes() > 0) parts += formatBytes(playlist.bytes())
    return parts.joinToString(" · ")
}

// ---- sheets and dialogs ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Sheet(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(bottom = 12.dp).navigationBarsPadding(), content = content)
    }
}

@Composable
fun SheetHeader(title: String, subtitle: String?, art: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        art()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(Modifier.padding(top = 14.dp, bottom = 6.dp), color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
fun SheetAction(icon: ImageVector, label: String, detail: String? = null, danger: Boolean = false, onClick: () -> Unit) {
    val color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (danger) color else MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(18.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = color)
            if (detail != null) Text(detail, style = Muted)
        }
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val valid = text.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(80) },
                singleLine = true,
                placeholder = { Text("Playlist name") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(text) }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = valid) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

@Composable
fun ConfirmDialog(title: String, body: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirmLabel, color = MaterialTheme.colorScheme.error) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    )
}

/** Actions for one track: like, add to playlist, remove, delete or re-download. */
@Composable
fun TrackActionsSheet(track: Library.Track, context: Library.Playlist?, vm: AppViewModel, onDismiss: () -> Unit) {
    var addTo by remember { mutableStateOf(false) }
    if (addTo) {
        AddToPlaylistSheet(track.id(), vm, onDismiss)
        return
    }
    val latest by remember(track.id()) { vm.track(track.id()) }.collectAsState(initial = track)
    val current = latest ?: track
    Sheet(onDismiss) {
        SheetHeader(current.title(), current.artist()?.removeSuffix(" - Topic")) {
            Artwork(current.thumb(), current.id(), Modifier.size(48.dp))
        }
        SheetAction(
            if (current.liked()) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
            if (current.liked()) "Remove from Liked songs" else "Add to Liked songs",
        ) {
            vm.setLiked(current.id(), !current.liked())
            onDismiss()
        }
        SheetAction(Icons.AutoMirrored.Rounded.PlaylistAdd, "Add to playlist") { addTo = true }
        if (context?.isLocal == true) {
            SheetAction(Icons.Rounded.RemoveCircleOutline, "Remove from this playlist") {
                vm.removeFromPlaylist(context.id(), current.id())
                onDismiss()
            }
        }
        when (current.status()) {
            Library.SAVED -> SheetAction(
                Icons.Rounded.DeleteOutline, "Delete download",
                detail = "Frees ${formatBytes(current.bytes())} · stays in your playlists",
            ) {
                vm.removeDownload(current.id())
                onDismiss()
            }
            Library.FAILED -> SheetAction(Icons.Rounded.Refresh, "Try again") {
                vm.downloadAgain(current.id())
                onDismiss()
            }
            Library.REMOVED -> SheetAction(Icons.Rounded.Download, "Download again") {
                vm.downloadAgain(current.id())
                onDismiss()
            }
        }
    }
}

@Composable
fun AddToPlaylistSheet(trackId: String, vm: AppViewModel, onDismiss: () -> Unit) {
    val playlists by vm.playlists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    if (creating) {
        TextInputDialog("New playlist", "", "Create", onConfirm = {
            vm.createPlaylist(it, thenAdd = trackId)
            onDismiss()
        }, onDismiss = onDismiss)
        return
    }
    Sheet(onDismiss) {
        Text(
            "Add to playlist",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        SheetAction(Icons.Rounded.Add, "New playlist") { creating = true }
        val yours = playlists.orEmpty().filter { it.isLocal }
        for (playlist in yours) {
            Row(
                Modifier.fillMaxWidth().clickable {
                    vm.addToPlaylist(playlist, trackId)
                    onDismiss()
                }.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Cover(playlist.thumbs(), playlist.id(), Modifier.size(44.dp), corner = 8.dp)
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(playlist.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${playlist.total()} songs", style = Muted)
                }
            }
        }
        if (yours.isEmpty()) {
            Text(
                "You haven't made any playlists yet.",
                style = Muted,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
    }
}

/** Actions for a playlist: refresh, retry, rename, delete. */
@Composable
fun PlaylistActionsSheet(
    playlist: Library.Playlist,
    vm: AppViewModel,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit = {},
) {
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    when {
        renaming -> TextInputDialog("Rename playlist", playlist.title(), "Save", onConfirm = {
            vm.renamePlaylist(playlist.id(), it)
            onDismiss()
        }, onDismiss = onDismiss)
        deleting -> ConfirmDialog(
            "Delete “${playlist.title()}”?",
            "Downloaded audio that isn't in another playlist or in Liked songs will be removed from this phone.",
            "Delete",
            onConfirm = {
                vm.deletePlaylist(playlist.id())
                onDismiss()
                onDeleted()
            },
            onDismiss = onDismiss,
        )
        else -> Sheet(onDismiss) {
            SheetHeader(playlist.title(), playlistMeta(playlist)) {
                Cover(playlist.thumbs(), playlist.id(), Modifier.size(48.dp), corner = 8.dp)
            }
            if (!playlist.isLocal) {
                SheetAction(
                    Icons.Rounded.Refresh,
                    if (playlist.isSpotify) "Refresh from Spotify" else "Refresh from YouTube",
                    if (playlist.isSpotify) "Review and download songs added since last time" else "Downloads songs added since last time",
                ) {
                    vm.refresh(playlist.id())
                    onDismiss()
                }
            }
            if (playlist.failed() > 0) {
                SheetAction(Icons.Rounded.Download, "Retry unavailable songs", "${playlist.failed()} couldn't be downloaded") {
                    vm.retry(playlist.id())
                    onDismiss()
                }
            }
            if (playlist.isLocal) SheetAction(Icons.Rounded.Edit, "Rename") { renaming = true }
            SheetAction(Icons.Rounded.DeleteOutline, "Delete playlist", danger = true) { deleting = true }
        }
    }
}

@Composable
fun EmptyMessage(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 40.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(body, style = Muted.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center))
    }
}
