package app.potify.offline.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import app.potify.offline.AppViewModel
import app.potify.offline.Library
import app.potify.offline.PlayerConnection
import app.potify.offline.SleepTimer
import app.potify.offline.formatTime
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
private fun rememberPosition(player: PlayerConnection, intervalMs: Long): Long {
    val position by produceState(player.position(), player.controller, player.isPlaying, player.currentId) {
        value = player.position()
        while (player.isPlaying) {
            delay(intervalMs)
            value = player.position()
        }
    }
    return position
}

@Composable
private fun rememberCurrentTrack(player: PlayerConnection, vm: AppViewModel): Library.Track? {
    val id = player.currentId
    val track by remember(id) { if (id == null) flowOf(null) else vm.track(id) }.collectAsStateWithLifecycle(null)
    return track
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlaying(player: PlayerConnection, vm: AppViewModel, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val track = rememberCurrentTrack(player, vm)
    val id = player.currentId ?: return
    val position = rememberPosition(player, 250)
    val duration = player.duration
    var dragging by remember { mutableStateOf<Float?>(null) }
    var sleepSheet by remember { mutableStateOf(false) }
    var queueSheet by remember { mutableStateOf(false) }
    var actions by remember { mutableStateOf(false) }
    val sleep by SleepTimer.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }

    Surface(
        color = colors.background,
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, offset.value.roundToInt()) }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch { offset.snapTo((offset.value + delta).coerceAtLeast(0f)) }
                },
                onDragStopped = { velocity ->
                    if (offset.value > 280f || velocity > 2400f) onClose() else offset.animateTo(0f)
                },
            ),
    ) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Close player", Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Playing from", style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    Text(
                        player.sourceTitle ?: "Your library",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
                IconButton(onClick = { actions = true }, enabled = track != null) { Icon(Icons.Rounded.MoreVert, "Song options") }
            }

            Spacer(Modifier.weight(1f))
            Artwork(
                track?.thumb(), id,
                Modifier.widthIn(max = 420.dp).fillMaxWidth().aspectRatio(1f),
                corner = 22.dp,
            )
            Spacer(Modifier.height(32.dp))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        player.title.ifEmpty { track?.title().orEmpty() },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        (player.artist.ifEmpty { track?.artist().orEmpty() }).removeSuffix(" - Topic"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = colors.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val liked = track?.liked() == true
                IconButton(onClick = { track?.let { vm.setLiked(it.id(), !liked) } }, enabled = track != null) {
                    Icon(
                        if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (liked) "Remove from Liked songs" else "Add to Liked songs",
                        tint = if (liked) colors.primary else colors.onSurfaceVariant,
                        modifier = Modifier.size(26.dp),
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            val fraction = dragging ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
            Slider(
                value = fraction,
                onValueChange = { dragging = it },
                onValueChangeFinished = {
                    dragging?.let { player.seekTo((it * duration).toLong()) }
                    dragging = null
                },
                enabled = duration > 0,
                thumb = {
                    Box(Modifier.size(14.dp).background(colors.primary, CircleShape))
                },
                track = { state ->
                    SliderDefaults.Track(
                        sliderState = state,
                        modifier = Modifier.height(4.dp),
                        colors = SliderDefaults.colors(
                            activeTrackColor = colors.primary,
                            inactiveTrackColor = colors.outlineVariant,
                        ),
                        drawStopIndicator = null,
                        thumbTrackGapSize = 0.dp,
                    )
                },
                modifier = Modifier.fillMaxWidth().height(28.dp),
            )
            Row(Modifier.fillMaxWidth()) {
                val shown = dragging?.let { (it * duration).toLong() } ?: position
                Text(formatTime(shown / 1000), style = Muted)
                Spacer(Modifier.weight(1f))
                Text(if (duration > 0) formatTime(duration / 1000) else "–:––", style = Muted)
            }

            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = player::toggleShuffle) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        if (player.shuffle) "Shuffle on" else "Shuffle off",
                        tint = if (player.shuffle) colors.primary else colors.onSurfaceVariant,
                    )
                }
                IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous", Modifier.size(36.dp))
                }
                FilledIconButton(onClick = player::playPause, modifier = Modifier.size(72.dp)) {
                    Icon(
                        if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (player.isPlaying) "Pause" else "Play",
                        Modifier.size(38.dp),
                    )
                }
                IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Next", Modifier.size(36.dp))
                }
                IconButton(onClick = player::cycleRepeat) {
                    Icon(
                        if (player.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        when (player.repeatMode) {
                            Player.REPEAT_MODE_ONE -> "Repeat this song"
                            Player.REPEAT_MODE_ALL -> "Repeat all"
                            else -> "Repeat off"
                        },
                        tint = if (player.repeatMode == Player.REPEAT_MODE_OFF) colors.onSurfaceVariant else colors.primary,
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { sleepSheet = true }) {
                    val active = sleep != SleepTimer.State.Off
                    Icon(
                        Icons.Rounded.Bedtime, null, Modifier.size(18.dp),
                        tint = if (active) colors.primary else colors.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(sleepLabel(sleep), color = if (active) colors.primary else colors.onSurfaceVariant)
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { queueSheet = true }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Queue", color = colors.onSurfaceVariant)
                }
            }
        }
    }

    if (sleepSheet) SleepSheet(sleep) { sleepSheet = false }
    if (queueSheet) QueueSheet(player) { queueSheet = false }
    if (actions && track != null) TrackActionsSheet(track, null, vm) { actions = false }
}

@Composable
private fun sleepLabel(state: SleepTimer.State): String {
    val remaining by produceState(0L, state) {
        while (state is SleepTimer.State.At) {
            value = state.remainingMillis()
            delay(1_000)
        }
    }
    return when (state) {
        SleepTimer.State.Off -> "Sleep timer"
        SleepTimer.State.EndOfTrack -> "Stops after this song"
        is SleepTimer.State.At -> "Stops in ${formatTime((remaining + 999) / 1000)}"
    }
}

@Composable
private fun SleepSheet(state: SleepTimer.State, onDismiss: () -> Unit) {
    Sheet(onDismiss) {
        Text(
            "Sleep timer",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
        )
        val choices = listOf(15, 30, 45, 60)
        for (minutes in choices) {
            SleepChoice(if (minutes == 60) "1 hour" else "$minutes minutes", false) {
                SleepTimer.startMinutes(minutes)
                onDismiss()
            }
        }
        SleepChoice("End of this song", state == SleepTimer.State.EndOfTrack) {
            SleepTimer.endOfTrack()
            onDismiss()
        }
        if (state != SleepTimer.State.Off) {
            SleepChoice("Turn off timer", false) {
                SleepTimer.cancel()
                onDismiss()
            }
        }
    }
}

@Composable
private fun SleepChoice(label: String, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (checked) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun QueueSheet(player: PlayerConnection, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val queue = remember(player.queueVersion, player.shuffle) { player.queue() }
    val current = player.currentIndex()
    val start = queue.indexOfFirst { it.first == current }.coerceAtLeast(0)
    val playing = queue.getOrNull(start)
    // Songs after the current one, in play order. Rearranged locally while dragging, then the
    // single move is sent to the player when the finger lifts.
    val upcoming = remember(queue, start) { queue.drop(start + 1).toMutableStateList() }
    var draggedKey by remember { mutableStateOf<Int?>(null) }
    var dragFrom by remember { mutableIntStateOf(-1) }
    var offset by remember { mutableFloatStateOf(0f) }
    val rowHeight = 64.dp
    val rowPx = with(LocalDensity.current) { rowHeight.toPx() }

    fun finishDrag() {
        val key = draggedKey ?: return
        val to = upcoming.indexOfFirst { it.first == key }
        if (dragFrom >= 0 && to >= 0 && to != dragFrom) player.moveInQueue(start + 1 + dragFrom, start + 1 + to)
        draggedKey = null
        offset = 0f
    }

    Sheet(onDismiss) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Queue", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (player.shuffle) Text("Shuffled", style = MaterialTheme.typography.labelMedium, color = colors.primary)
        }
        if (playing != null) {
            Text(
                "Now playing", style = MaterialTheme.typography.labelSmall, color = colors.primary,
                modifier = Modifier.padding(start = 20.dp, top = 6.dp),
            )
            QueueRow(playing.second, highlighted = true, isPlaying = player.isPlaying, modifier = Modifier.height(rowHeight))
        }
        if (upcoming.isNotEmpty()) {
            Text(
                "Next up \u00b7 drag \u2261 to reorder", style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 10.dp, bottom = 2.dp),
            )
        } else {
            Text("Nothing else is queued.", style = Muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
        }
        LazyColumn(Modifier.heightIn(max = 460.dp)) {
            itemsIndexed(upcoming, key = { _, entry -> entry.first }) { _, entry ->
                val (index, item) = entry
                val dragged = draggedKey == index
                QueueRow(
                    item,
                    highlighted = false,
                    isPlaying = false,
                    modifier = Modifier
                        .height(rowHeight)
                        .zIndex(if (dragged) 1f else 0f)
                        .graphicsLayer { translationY = if (dragged) offset else 0f }
                        .background(if (dragged) colors.surfaceContainerHighest else Color.Transparent)
                        .then(if (dragged) Modifier else Modifier.animateItem())
                        .clickable {
                            player.jumpTo(index)
                            onDismiss()
                        },
                    trailing = {
                        IconButton(onClick = { player.removeFromQueue(index) }) {
                            Icon(Icons.Rounded.Close, "Remove from queue", tint = colors.onSurfaceVariant)
                        }
                        Icon(
                            Icons.Rounded.DragHandle,
                            "Drag to reorder",
                            tint = colors.onSurfaceVariant,
                            modifier = Modifier
                                .size(48.dp)
                                .padding(12.dp)
                                .pointerInput(index) {
                                    detectDragGestures(
                                        onDragStart = {
                                            draggedKey = index
                                            dragFrom = upcoming.indexOfFirst { it.first == index }
                                            offset = 0f
                                        },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            offset += amount.y
                                            val position = upcoming.indexOfFirst { it.first == index }
                                            if (offset > rowPx / 2 && position < upcoming.lastIndex) {
                                                upcoming.add(position + 1, upcoming.removeAt(position))
                                                offset -= rowPx
                                            } else if (offset < -rowPx / 2 && position > 0) {
                                                upcoming.add(position - 1, upcoming.removeAt(position))
                                                offset += rowPx
                                            }
                                        },
                                        onDragEnd = { finishDrag() },
                                        onDragCancel = { finishDrag() },
                                    )
                                },
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun QueueRow(
    item: androidx.media3.common.MediaItem,
    highlighted: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                item.mediaMetadata.title?.toString().orEmpty(),
                style = MaterialTheme.typography.bodyLarge,
                color = if (highlighted) colors.primary else colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                item.mediaMetadata.artist?.toString().orEmpty().removeSuffix(" - Topic"),
                style = Muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (highlighted && isPlaying) {
            Icon(Icons.Rounded.GraphicEq, null, tint = colors.primary, modifier = Modifier.padding(end = 16.dp))
        }
        trailing()
    }
}

@Composable
fun MiniPlayer(player: PlayerConnection, vm: AppViewModel, onOpen: () -> Unit) {
    val id = player.currentId ?: return
    val track = rememberCurrentTrack(player, vm)
    val position = rememberPosition(player, 500)
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onOpen,
        shape = MaterialTheme.shapes.medium,
        color = colors.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, colors.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column {
            Row(Modifier.padding(start = 8.dp, top = 8.dp, bottom = 6.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(track?.thumb(), id, Modifier.size(42.dp), corner = 8.dp)
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        player.title.ifEmpty { track?.title().orEmpty() },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        (player.artist.ifEmpty { track?.artist().orEmpty() }).removeSuffix(" - Topic"),
                        style = Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = player::playPause) {
                    Icon(if (player.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (player.isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = player::next) { Icon(Icons.Rounded.SkipNext, "Next") }
            }
            val duration = player.duration
            LinearProgressIndicator(
                progress = { if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 6.dp).height(2.dp),
                trackColor = colors.outlineVariant,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
