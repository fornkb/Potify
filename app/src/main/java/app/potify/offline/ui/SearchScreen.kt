package app.potify.offline.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.potify.offline.AppViewModel
import app.potify.offline.Library
import app.potify.offline.PlayerConnection
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(
    vm: AppViewModel,
    player: PlayerConnection,
    onOpen: (String) -> Unit,
    padding: PaddingValues,
) {
    val text by vm.searchText.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    var trackSheet by remember { mutableStateOf<Library.Track?>(null) }
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme

    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
        Text(
            "Search",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 14.dp),
        )
        Surface(
            shape = MaterialTheme.shapes.medium,
            color = colors.surfaceContainer,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.padding(horizontal = 16.dp),
        ) {
            Row(Modifier.height(50.dp).padding(start = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Search, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (text.isEmpty()) {
                        Text("Songs, artists or playlists", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                    }
                    BasicTextField(
                        value = text,
                        onValueChange = { vm.searchText.value = it },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = colors.onSurface),
                        cursorBrush = SolidColor(colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (text.isNotEmpty()) {
                    IconButton(onClick = { vm.searchText.value = "" }) { Icon(Icons.Rounded.Close, "Clear search") }
                }
            }
        }
        val found = results
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 10.dp, bottom = padding.calculateBottomPadding() + 12.dp),
        ) {
            when {
                text.isBlank() -> item {
                    EmptyMessage("Find something to play", "Search your saved songs and playlists by title or artist.")
                }
                found == null -> Unit
                found.playlists.isEmpty() && found.tracks.isEmpty() -> item {
                    EmptyMessage("No matches", "Nothing in your library matches “${found.text}”.")
                }
                else -> {
                    if (found.playlists.isNotEmpty()) {
                        item { SectionLabel("Playlists") }
                        items(found.playlists, key = { "p:" + it.id() }) { playlist ->
                            Row(
                                Modifier.fillMaxWidth().clickable { onOpen(playlist.id()) }.padding(horizontal = 16.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Cover(playlist.thumbs(), playlist.id(), Modifier.size(48.dp), corner = 8.dp)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(playlist.title(), style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(playlistMeta(playlist), style = Muted, maxLines = 1)
                                }
                            }
                        }
                    }
                    if (found.tracks.isNotEmpty()) {
                        item { SectionLabel("Songs") }
                        items(found.tracks, key = { "t:" + it.id() }) { track ->
                            TrackRow(
                                track = track,
                                isCurrent = track.id() == player.currentId,
                                isPlaying = player.isPlaying,
                                progress = progress[track.id()],
                                onClick = {
                                    if (!track.isSaved) {
                                        trackSheet = track
                                    } else scope.launch {
                                        val (items, index) = vm.playable(found.tracks, track.id())
                                        player.play(items, index, "Search results", false)
                                    }
                                },
                                onMore = { trackSheet = track },
                            )
                        }
                    }
                }
            }
        }
    }
    trackSheet?.let { track -> TrackActionsSheet(track, null, vm, onDismiss = { trackSheet = null }) }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
    )
}
