package app.potify.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.potify.offline.AppViewModel
import app.potify.offline.Library
import app.potify.offline.PlayerConnection
import app.potify.offline.formatBytes
import app.potify.offline.formatTotal
import kotlinx.coroutines.launch
import kotlin.random.Random

@Composable
fun PlaylistScreen(
    id: String,
    vm: AppViewModel,
    player: PlayerConnection,
    showBack: Boolean,
    onBack: () -> Unit,
    onMessage: (String) -> Unit,
    padding: PaddingValues,
) {
    val isLiked = id == Library.LIKED
    val playlists by vm.playlists.collectAsStateWithLifecycle()
    val playlist = if (isLiked) null else playlists?.find { it.id() == id }
    val tracks by remember(id) { vm.tracks(id) }.collectAsStateWithLifecycle(null)
    val progress by vm.progress.collectAsStateWithLifecycle()
    val downloads by vm.downloads.collectAsStateWithLifecycle()
    var trackSheet by remember { mutableStateOf<Library.Track?>(null) }
    var playlistSheet by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val title = if (isLiked) "Liked songs" else playlist?.title().orEmpty()
    val list = tracks.orEmpty()
    val isThisPlaying = player.isPlaying && player.sourceTitle == title

    // The playlist was deleted (here or elsewhere): leave the screen.
    LaunchedEffect(playlists, isLiked) {
        if (!isLiked && playlists != null && playlist == null) onBack()
    }

    fun play(startId: String?, shuffle: Boolean) = scope.launch {
        val (items, index) = vm.playable(list, startId)
        if (items.isEmpty()) {
            onMessage("Nothing here is downloaded yet")
            return@launch
        }
        val start = if (startId == null && shuffle) Random.nextInt(items.size) else index
        player.play(items, start, title, shuffle)
    }

    val saved = list.count { it.isSaved }
    val failed = list.count { it.status() == Library.FAILED }
    val pending = list.count { it.status() == Library.QUEUED || it.status() == Library.ACTIVE }

    LazyColumn(
        Modifier.fillMaxSize().padding(top = padding.calculateTopPadding()),
        contentPadding = PaddingValues(bottom = padding.calculateBottomPadding() + 12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (showBack) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                if (playlist != null) {
                    IconButton(onClick = { playlistSheet = true }) { Icon(Icons.Rounded.MoreVert, "Playlist options") }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 18.dp), contentAlignment = Alignment.Center) {
                    if (isLiked) {
                        Box(
                            Modifier.size(196.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.large),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Rounded.Favorite, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    } else {
                        Cover(playlist?.thumbs().orEmpty(), id, Modifier.size(196.dp), corner = 20.dp)
                    }
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(
                    when {
                        playlist != null -> playlistMeta(playlist)
                        else -> buildList {
                            add("${list.size} ${if (list.size == 1) "song" else "songs"}")
                            list.sumOf { it.seconds() }.takeIf { it > 0 }?.let { add(formatTotal(it)) }
                            list.sumOf { it.bytes() }.takeIf { it > 0 }?.let { add(formatBytes(it)) }
                        }.joinToString(" · ")
                    },
                    style = Muted,
                )
            }
        }
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (playlist != null && !playlist.isLocal) {
                    IconButton(onClick = { vm.refresh(id) }) {
                        Icon(Icons.Rounded.Refresh, "Refresh from YouTube", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { play(null, true) }, enabled = saved > 0) {
                    Icon(Icons.Rounded.Shuffle, "Shuffle play", tint = MaterialTheme.colorScheme.primary)
                }
                FilledIconButton(
                    onClick = { if (isThisPlaying) player.playPause() else play(null, false) },
                    enabled = saved > 0,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        if (isThisPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (isThisPlaying) "Pause" else "Play",
                        Modifier.size(30.dp),
                    )
                }
            }
        }
        if (failed > 0 || pending > 0) {
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp)) {
                    if (pending > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (downloads.running) "Saving $saved of ${list.size}"
                                else "$saved of ${list.size} saved · downloads paused",
                                style = Muted,
                                modifier = Modifier.weight(1f),
                            )
                            if (!downloads.running) TextButton(onClick = vm::startDownloads) { Text("Resume") }
                        }
                        Spacer(Modifier.height(6.dp))
                        ProgressLine(saved.toFloat() / list.size.coerceAtLeast(1), Modifier.fillMaxWidth())
                    }
                    if (failed > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "$failed ${if (failed == 1) "song" else "songs"} couldn't be downloaded",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { vm.retry(id) }) { Text("Retry") }
                        }
                    }
                }
            }
        }
        items(list, key = { it.id() }) { track ->
            TrackRow(
                track = track,
                isCurrent = track.id() == player.currentId,
                isPlaying = player.isPlaying,
                progress = progress[track.id()],
                onClick = { if (track.isSaved) play(track.id(), player.shuffle) else trackSheet = track },
                onMore = { trackSheet = track },
            )
        }
        if (tracks != null && list.isEmpty()) {
            item {
                when {
                    isLiked -> EmptyMessage("Songs you like show up here", "Tap the heart on any song to save it to Liked songs.")
                    playlist?.isLocal == true -> EmptyMessage("This playlist is empty", "Add songs from any playlist with ⋮ → Add to playlist.")
                    else -> EmptyMessage("No songs yet", "They'll appear here once the playlist has been read.")
                }
            }
        }
        item { Spacer(Modifier.width(1.dp)) }
    }

    trackSheet?.let { track ->
        TrackActionsSheet(track, playlist, vm, onDismiss = { trackSheet = null })
    }
    if (playlistSheet && playlist != null) {
        PlaylistActionsSheet(playlist, vm, onDismiss = { playlistSheet = false })
    }
}
