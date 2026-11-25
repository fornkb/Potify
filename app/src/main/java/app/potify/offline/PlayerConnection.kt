package app.potify.offline

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Executor

/** Connects the screen to [PlaybackService] and mirrors the player's state for Compose. */
class PlayerConnection(private val context: Context) {
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private var future: ListenableFuture<MediaController>? = null

    var controller by mutableStateOf<MediaController?>(null)
        private set
    var currentId by mutableStateOf<String?>(null)
        private set
    var title by mutableStateOf("")
        private set
    var artist by mutableStateOf("")
        private set
    var artworkUri by mutableStateOf<Uri?>(null)
        private set
    var isPlaying by mutableStateOf(false)
        private set
    var shuffle by mutableStateOf(false)
        private set
    var repeatMode by mutableIntStateOf(Player.REPEAT_MODE_OFF)
        private set
    var duration by mutableLongStateOf(0L)
        private set
    var sourceTitle by mutableStateOf<String?>(null)
        private set
    /** Bumped whenever the queue changes, so queue views know to re-read it. */
    var queueVersion by mutableIntStateOf(0)
        private set

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) queueVersion++
            sync()
        }

        override fun onPlayerError(error: PlaybackException) {
            Toast.makeText(context, "This saved track couldn’t be played.", Toast.LENGTH_SHORT).show()
        }
    }

    fun connect() {
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val pending = MediaController.Builder(context, token).buildAsync()
        future = pending
        pending.addListener({
            if (future !== pending) return@addListener
            controller = runCatching { pending.get() }.getOrNull()?.also {
                it.addListener(listener)
                queueVersion++
            }
            sync()
        }, mainExecutor)
    }

    fun release() {
        future?.let { MediaController.releaseFuture(it) }
        future = null
        controller = null
        sync()
    }

    private fun sync() {
        val c = controller
        val item = c?.currentMediaItem
        currentId = item?.mediaId
        title = c?.mediaMetadata?.title?.toString().orEmpty()
        artist = c?.mediaMetadata?.artist?.toString().orEmpty()
        artworkUri = c?.mediaMetadata?.artworkUri
        isPlaying = c?.isPlaying == true
        shuffle = c?.shuffleModeEnabled == true
        repeatMode = c?.repeatMode ?: Player.REPEAT_MODE_OFF
        duration = c?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        sourceTitle = c?.playlistMetadata?.title?.toString()
    }

    fun position(): Long = controller?.currentPosition?.coerceAtLeast(0) ?: 0L

    fun play(items: List<MediaItem>, startIndex: Int, source: String, shuffled: Boolean) {
        val c = controller ?: return
        if (items.isEmpty()) return
        // Shuffle goes off before loading and back on after, so the service builds a fresh shuffle
        // order that starts with the chosen song and includes every other song after it.
        c.shuffleModeEnabled = false
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex), 0)
        c.playlistMetadata = MediaMetadata.Builder().setTitle(source).build()
        if (shuffled) c.shuffleModeEnabled = true
        c.prepare()
        c.play()
    }

    fun playPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
        } else {
            if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition(0)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun pause() {
        controller?.pause()
    }

    fun next() {
        controller?.takeIf { it.hasNextMediaItem() }?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPrevious()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun cycleRepeat() {
        val c = controller ?: return
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    /** The queue in play order (follows shuffle), as (index in playlist, item) pairs. */
    fun queue(): List<Pair<Int, MediaItem>> {
        val c = controller ?: return emptyList()
        val timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val order = ArrayList<Int>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(c.shuffleModeEnabled)
        while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
        }
        return order.map { it to c.getMediaItemAt(it) }
    }

    fun currentIndex(): Int = controller?.currentMediaItemIndex ?: -1

    /** Moves a queued song; [from] and [to] are positions in play order, as returned by [queue]. */
    fun moveInQueue(from: Int, to: Int) {
        val c = controller ?: return
        if (from == to) return
        if (c.shuffleModeEnabled) {
            val args = Bundle().apply {
                putInt(PlaybackService.ARG_FROM, from)
                putInt(PlaybackService.ARG_TO, to)
            }
            c.sendCustomCommand(SessionCommand(PlaybackService.MOVE_IN_PLAY_ORDER, Bundle.EMPTY), args)
        } else {
            c.moveMediaItem(from, to)
        }
    }

    /**
     * After duplicates were merged: removed songs leave the queue, except the one playing, which
     * switches to the kept copy (when that one is downloaded) since its file is gone.
     */
    fun replaceMerged(replacements: Map<String, MediaItem?>) {
        val c = controller ?: return
        if (replacements.isEmpty()) return
        val current = c.currentMediaItemIndex
        for (i in c.mediaItemCount - 1 downTo 0) {
            val id = c.getMediaItemAt(i).mediaId
            if (!replacements.containsKey(id)) continue
            val kept = replacements[id]
            if (i != current) c.removeMediaItem(i)
            else if (kept != null) c.replaceMediaItem(i, kept)
        }
    }

    /** Removes a song from the queue; [index] is its playlist index. */
    fun removeFromQueue(index: Int) {
        controller?.removeMediaItem(index)
    }

    fun jumpTo(index: Int) {
        val c = controller ?: return
        c.seekToDefaultPosition(index)
        c.play()
    }
}
