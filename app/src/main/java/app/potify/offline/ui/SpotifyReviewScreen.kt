package app.potify.offline.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.potify.offline.AppViewModel
import app.potify.offline.Candidate
import app.potify.offline.Confidence
import app.potify.offline.PlayerConnection
import app.potify.offline.ReviewRow
import app.potify.offline.RowStatus
import app.potify.offline.SongMatcher
import app.potify.offline.SpotifyImport
import app.potify.offline.SpotifyRef
import app.potify.offline.formatDifference
import app.potify.offline.formatTime
import coil3.compose.AsyncImage
import kotlinx.coroutines.launch
import kotlin.math.abs

private enum class ReviewFilter { ALL, CHECK, SKIPPED }

/** Amber for "accepted, but the length differs a bit". */
@Composable
private fun amber(): Color =
    if (MaterialTheme.colorScheme.background.luminance() < 0.5f) Color(0xFFE3B341) else Color(0xFF946200)

@Composable
fun SpotifyReviewScreen(
    vm: AppViewModel,
    player: PlayerConnection,
    onBack: () -> Unit,
    onClosed: () -> Unit,
    onQueued: () -> Unit,
    onSaved: (String) -> Unit,
    onMessage: (String) -> Unit,
    padding: PaddingValues,
) {
    val review = vm.spotify
    // Discarded or finished: leave, unless navigation already moved on (this screen can still be
    // composed while its exit animation runs).
    LaunchedEffect(review) { if (review == null) onClosed() }
    DisposableEffect(Unit) { onDispose { vm.preview.stop() } }
    if (review == null) return

    var discarding by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding())) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
            Text("Review songs", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { discarding = true }) { Text("Discard") }
        }
        when (val phase = review.phase) {
            SpotifyImport.Phase.Loading -> CenterMessage {
                CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 3.dp)
                Spacer(Modifier.height(16.dp))
                Text("Reading the Spotify link…", style = Muted)
            }
            is SpotifyImport.Phase.Failed -> CenterMessage {
                Text(phase.message, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                Button(onClick = review::retryLoad) { Text("Try again") }
            }
            SpotifyImport.Phase.Ready -> ReviewList(review, vm, player, onMessage, Modifier.weight(1f)) {
                vm.finishSpotify(onQueued, onSaved)
            }
        }
    }
    if (discarding) {
        ConfirmDialog(
            "Discard this review?",
            "Nothing from this link has been downloaded yet.",
            "Discard",
            onConfirm = {
                discarding = false
                vm.discardSpotify()
            },
            onDismiss = { discarding = false },
        )
    }
}

@Composable
private fun CenterMessage(content: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) { content() }
}

@Composable
private fun ReviewList(
    review: SpotifyImport,
    vm: AppViewModel,
    player: PlayerConnection,
    onMessage: (String) -> Unit,
    modifier: Modifier,
    onDownload: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val list = review.list ?: return
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var sheetFor by remember { mutableStateOf<String?>(null) }
    val rows = review.rows
    val toCheck = rows.count { it.needsCheck }
    val skipped = rows.count { it.skipped }
    val ready = rows.count { it.willDownload }
    val failed = rows.count { it.status == RowStatus.FAILED }
    val selected = ReviewFilter.entries[filter]
    val shown = rows.withIndex().filter { (_, row) ->
        when (selected) {
            ReviewFilter.ALL -> true
            ReviewFilter.CHECK -> row.needsCheck
            ReviewFilter.SKIPPED -> row.skipped
        }
    }
    val kind = when (list.ref.type) {
        SpotifyRef.ALBUM -> "Spotify album"
        SpotifyRef.TRACK -> "Spotify song"
        else -> "Spotify playlist"
    }

    Column(modifier) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 12.dp)) {
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RemoteImage(list.cover, list.ref.id, Modifier.size(96.dp), corner = 12.dp)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(kind, style = MaterialTheme.typography.labelMedium, color = colors.primary)
                        Text(list.name, style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            buildList {
                                list.owner?.let { add(it) }
                                if (review.refreshOf != null) {
                                    add("${rows.size} new")
                                    if (review.alreadyHad > 0) add("${review.alreadyHad} already saved")
                                } else {
                                    add("${rows.size} ${if (rows.size == 1) "song" else "songs"}")
                                }
                            }.joinToString(" · "),
                            style = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (list.maybeTruncated) {
                item { FullPlaylistCard(review, list.name, onMessage) }
            }
            item {
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                    when {
                        review.isMatching -> {
                            Text("Finding songs on YouTube · ${review.matchedCount} of ${rows.size}", style = Muted)
                            Spacer(Modifier.height(6.dp))
                            ProgressLine(review.matchedCount.toFloat() / rows.size.coerceAtLeast(1), Modifier.fillMaxWidth())
                        }
                        rows.isEmpty() -> Text(
                            if (review.refreshOf != null) "No new songs: everything on this playlist is already saved."
                            else "This link has no songs.",
                            style = Muted,
                        )
                        else -> Text(
                            buildList {
                                add("$ready ready")
                                if (toCheck > 0) add("$toCheck to check")
                                if (skipped > 0) add("$skipped skipped")
                            }.joinToString(" · "),
                            style = Muted,
                        )
                    }
                    if (failed > 0 && !review.isMatching) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (review.limited) "YouTube is limiting searches right now."
                                else "$failed ${if (failed == 1) "song" else "songs"} couldn't be searched.",
                                style = MaterialTheme.typography.bodySmall, color = colors.error,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = review::matchRemaining) { Text("Search again") }
                        }
                    }
                }
            }
            if (rows.size > 1) {
                item {
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ReviewFilter.entries.forEach { option ->
                            val label = when (option) {
                                ReviewFilter.ALL -> "All ${rows.size}"
                                ReviewFilter.CHECK -> "To check $toCheck"
                                ReviewFilter.SKIPPED -> "Skipped $skipped"
                            }
                            FilterChip(
                                selected = selected == option,
                                onClick = { filter = option.ordinal },
                                label = { Text(label) },
                                shape = RoundedCornerShape(50),
                                border = FilterChipDefaults.filterChipBorder(
                                    enabled = true, selected = selected == option,
                                    borderColor = colors.outlineVariant,
                                    selectedBorderColor = colors.secondaryContainer,
                                ),
                            )
                        }
                    }
                }
            }
            items(shown, key = { it.value.song.id }) { (index, row) ->
                ReviewRowItem(row) { if (row.status == RowStatus.DONE || row.status == RowStatus.FAILED) sheetFor = row.song.id }
            }
            if (shown.isEmpty() && rows.isNotEmpty()) {
                item {
                    Text(
                        if (selected == ReviewFilter.CHECK) "Nothing to check." else "No skipped songs.",
                        style = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp),
                    )
                }
            }
        }
        Surface(color = colors.surfaceContainer) {
            Column {
                HorizontalDivider(color = colors.outlineVariant)
                val nothingThere = rows.isEmpty()
                Button(
                    onClick = if (nothingThere) vm::discardSpotify else onDownload,
                    enabled = nothingThere || (ready > 0 && !review.isMatching),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).height(48.dp),
                ) {
                    Text(
                        when {
                            nothingThere -> "Done"
                            review.isMatching -> "Finding songs…"
                            ready == 0 -> "Nothing to download"
                            else -> "Download $ready ${if (ready == 1) "song" else "songs"}"
                        }
                    )
                }
            }
        }
    }

    sheetFor?.let { id ->
        val index = rows.indexOfFirst { it.song.id == id }
        if (index >= 0) {
            CandidateSheet(review, index, vm, player, onMessage, onDismiss = {
                vm.preview.stop()
                sheetFor = null
            })
        }
    }
}

@Composable
private fun ReviewRowItem(row: ReviewRow, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val chosen = row.chosen
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(if (row.skipped) 0.5f else 1f)
            .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            RemoteImage(chosen?.thumb, row.song.id, Modifier.size(52.dp))
            if (row.status == RowStatus.SEARCHING) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                row.song.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOf(row.song.artists, formatTime(row.song.seconds)).filter { it.isNotBlank() }.joinToString(" · "),
                style = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val (line, color) = when {
                row.status == RowStatus.FAILED -> (row.error ?: "Couldn't search YouTube.") to colors.error
                row.status == RowStatus.SEARCHING -> "Searching YouTube…" to colors.onSurfaceVariant
                row.status == RowStatus.WAITING -> "Waiting to search" to colors.onSurfaceVariant
                chosen == null -> "No match found. Tap to search yourself." to colors.error
                else -> "▶ ${chosen.title} · ${chosen.channel.removeSuffix(" - Topic")}" to colors.onSurfaceVariant
            }
            Text(line, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        MatchBadge(row)
    }
}

@Composable
private fun MatchBadge(row: ReviewRow) {
    val colors = MaterialTheme.colorScheme
    val difference = row.difference
    when {
        row.skipped -> Text("Skipped", style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
        row.needsCheck -> Text(
            "Check",
            style = MaterialTheme.typography.labelMedium,
            color = colors.onErrorContainer,
            modifier = Modifier.clip(RoundedCornerShape(50)).background(colors.errorContainer).padding(horizontal = 10.dp, vertical = 3.dp),
        )
        row.status != RowStatus.DONE || row.chosen == null -> {}
        difference != null && abs(difference) > SongMatcher.EXACT_SECONDS -> Text(
            formatDifference(difference), style = MaterialTheme.typography.labelMedium, color = amber(),
        )
        row.confidence == Confidence.EXACT || row.userPicked ->
            Icon(Icons.Rounded.CheckCircle, "Matched", Modifier.size(20.dp), tint = colors.primary)
    }
}

/** Lets the user pick a different YouTube result for one song, hear it first, or skip the song. */
@Composable
private fun CandidateSheet(
    review: SpotifyImport,
    index: Int,
    vm: AppViewModel,
    player: PlayerConnection,
    onMessage: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val row = review.rows[index]
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    var query by rememberSaveable(row.song.id) { mutableStateOf("${row.song.firstArtist} ${SongMatcher.coreTitle(row.song.title)}") }
    var searching by remember { mutableStateOf(false) }

    fun runSearch() {
        if (query.isBlank() || searching) return
        focus.clearFocus()
        searching = true
        scope.launch {
            if (!review.searchManually(index, query.trim())) onMessage("Couldn't search YouTube. Check your connection.")
            searching = false
        }
    }

    Sheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Pick the right version", style = MaterialTheme.typography.titleMedium)
            Text(
                "${row.song.title} · ${row.song.artists} · ${formatTime(row.song.seconds)} on Spotify",
                style = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                label = { Text("Search YouTube") },
                trailingIcon = {
                    if (searching) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = ::runSearch) { Icon(Icons.Rounded.Search, "Search") }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(6.dp))
        LazyColumn(Modifier.heightIn(max = 420.dp)) {
            items(row.candidates, key = { it.videoId }) { candidate ->
                CandidateRow(
                    candidate = candidate,
                    spotifySeconds = row.song.seconds,
                    selected = row.chosen?.videoId == candidate.videoId,
                    previewing = vm.preview.playingId == candidate.videoId,
                    loading = vm.preview.loadingId == candidate.videoId,
                    onPreview = {
                        vm.preview.toggle(scope, candidate, onStart = player::pause, onError = onMessage)
                    },
                    onSelect = {
                        review.choose(index, candidate)
                        onDismiss()
                    },
                )
            }
            if (row.candidates.isEmpty()) {
                item {
                    Text(
                        "No results. Try other words above.",
                        style = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                }
            }
        }
        HorizontalDivider(Modifier.padding(top = 6.dp), color = colors.outlineVariant)
        if (row.skipped) {
            SheetAction(Icons.Rounded.Undo, "Include this song") {
                review.setSkipped(index, false)
                onDismiss()
            }
        } else {
            SheetAction(Icons.Rounded.Block, "Skip this song", "It won't be downloaded") {
                review.setSkipped(index, true)
                onDismiss()
            }
        }
    }
}

@Composable
private fun CandidateRow(
    candidate: Candidate,
    spotifySeconds: Long,
    selected: Boolean,
    previewing: Boolean,
    loading: Boolean,
    onPreview: () -> Unit,
    onSelect: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val difference = if (spotifySeconds > 0 && candidate.seconds > 0) candidate.seconds - spotifySeconds else null
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        RemoteImage(candidate.thumb, candidate.videoId, Modifier.size(width = 80.dp, height = 45.dp), corner = 6.dp, crop = true)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(candidate.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (candidate.music) {
                    Text(
                        "YT Music",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.primary,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(colors.primaryContainer).padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                // The length stays visible; a long channel name is what gets shortened.
                Text(
                    candidate.channel.removeSuffix(" - Topic"),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    " · ${formatTime(candidate.seconds)}",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant, maxLines = 1,
                )
                if (difference != null) {
                    Spacer(Modifier.width(6.dp))
                    val size = abs(difference)
                    Text(
                        if (size <= SongMatcher.EXACT_SECONDS) "✓ length" else formatDifference(difference),
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            size <= SongMatcher.EXACT_SECONDS -> colors.primary
                            size <= SongMatcher.CLOSE_SECONDS -> amber()
                            else -> colors.error
                        },
                    )
                }
            }
        }
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onPreview) {
                    Icon(
                        if (previewing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                        if (previewing) "Stop preview" else "Preview",
                        tint = colors.primary,
                    )
                }
            }
        }
    }
}

/** An image from the internet (Spotify cover or YouTube thumbnail), with a note icon until it loads. */
@Composable
private fun RemoteImage(url: String?, seed: String, modifier: Modifier, corner: Dp = 8.dp, crop: Boolean = true) {
    val palette = LocalArtPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(corner))
            .background(palette.tiles[abs(seed.hashCode()) % palette.tiles.size]),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.MusicNote, null, Modifier.size(20.dp), tint = palette.onTile)
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = null,
                contentScale = if (crop) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Spotify's embed page stops at 100 songs. This walks the user through exporting the whole
 * playlist with Exportify (signing in there, in a browser tab) and picking the CSV it saves.
 */
@Composable
private fun FullPlaylistCard(review: SpotifyImport, name: String, onMessage: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val reading = review.readingCsv
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val resolver = context.applicationContext.contentResolver
        review.importCsv(read = {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    resolver.openInputStream(uri)?.use { input ->
                        // Exports are small; anything huge isn't a playlist export.
                        val bytes = input.readNBytesCompat(8 * 1024 * 1024)
                        String(bytes, Charsets.UTF_8)
                    }
                }.getOrNull()
            }
        }) { result ->
            onMessage(
                when (result) {
                    is SpotifyImport.CsvResult.Added ->
                        if (result.count > 0) "Added ${result.count} more ${if (result.count == 1) "song" else "songs"} from the file"
                        else "The file had no songs that weren't already here"
                    is SpotifyImport.CsvResult.Failed -> result.message
                }
            )
        }
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = colors.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("This playlist may have more than 100 songs", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(
                "Spotify only shares the first 100 here. To get the rest, export the playlist with Exportify " +
                    "(sign in with Spotify, then tap Export next to “$name”), and choose the file it saves.",
                style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.material3.OutlinedButton(modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 12.dp), onClick = {
                    runCatching {
                        androidx.browser.customtabs.CustomTabsIntent.Builder().setShowTitle(true).build()
                            .launchUrl(context, android.net.Uri.parse("https://exportify.net/"))
                    }.onFailure { onMessage("Couldn't open a browser.") }
                }) { Text("Open Exportify", maxLines = 1) }
                if (reading) {
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    }
                } else {
                    Button(modifier = Modifier.weight(1f), onClick = {
                        picker.launch(arrayOf("text/csv", "text/comma-separated-values", "application/csv", "text/plain", "application/vnd.ms-excel", "application/octet-stream"))
                    }) { Text("Choose file", maxLines = 1) }
                }
            }
        }
    }
}

/** Reads at most [limit] bytes (InputStream.readNBytes needs Android 13). */
private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(16 * 1024)
    while (out.size() < limit) {
        val n = read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (n < 0) break
        out.write(buffer, 0, n)
    }
    return out.toByteArray()
}
