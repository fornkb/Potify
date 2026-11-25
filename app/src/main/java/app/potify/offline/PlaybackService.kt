package app.potify.offline

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

class PlaybackService : MediaSessionService() {
    companion object {
        /** Custom command: move a song within the shuffled play order. */
        const val MOVE_IN_PLAY_ORDER = "app.potify.MOVE_IN_PLAY_ORDER"
        const val ARG_FROM = "from"
        const val ARG_TO = "to"
    }

    private var session: MediaSession? = null
    private lateinit var player: ExoPlayer
    private lateinit var library: Library
    private lateinit var store: QueueStore
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val periodicSave = object : Runnable {
        override fun run() {
            save()
            if (player.isPlaying) handler.postDelayed(this, 15_000)
        }
    }

    private val sleepNow = Runnable {
        player.pause()
        SleepTimer.cancel()
    }

    override fun onCreate() {
        super.onCreate()
        library = Library.get(this)
        store = QueueStore(this)
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
                true,
            )
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = save()
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                if (shuffleModeEnabled) reshuffle()
                save()
            }
            override fun onRepeatModeChanged(repeatMode: Int) = save()

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                handler.removeCallbacks(periodicSave)
                if (isPlaying) handler.postDelayed(periodicSave, 15_000) else save()
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) SleepTimer.cancel()
            }

            override fun onPlayerError(error: PlaybackException) {
                // A file went missing or is unreadable: skip it rather than stopping the queue.
                if (player.hasNextMediaItem()) {
                    player.seekToNextMediaItem()
                    player.prepare()
                    player.play()
                }
            }
        })
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, player)
            .setSessionActivity(open)
            .setCallback(object : MediaSession.Callback {
                override fun onAddMediaItems(
                    mediaSession: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    mediaItems: MutableList<MediaItem>,
                ): ListenableFuture<MutableList<MediaItem>> =
                    Futures.immediateFuture(MediaItems.resolve(library, mediaItems).toMutableList())

                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val commands = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(MOVE_IN_PLAY_ORDER, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(commands)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    if (customCommand.customAction == MOVE_IN_PLAY_ORDER) {
                        moveInShuffleOrder(args.getInt(ARG_FROM), args.getInt(ARG_TO))
                        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                    }
                    return super.onCustomCommand(session, controller, customCommand, args)
                }
            })
            .build()
        restore()
        scope.launch { SleepTimer.state.collect(::applySleepTimer) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /**
     * ExoPlayer only flips a flag when shuffle turns on: the random order was fixed when the queue
     * was loaded, so the current song sits somewhere in the middle of it and every song before it
     * would be skipped. Instead, keep the current song first and shuffle all the others after it.
     */
    private fun reshuffle() {
        val count = player.mediaItemCount
        if (count < 2) return
        val current = player.currentMediaItemIndex
        val order = listOf(current) + (0 until count).filter { it != current }.shuffled()
        player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
    }

    /** The playlist indices in the order they will play (follows shuffle). */
    private fun playOrder(): MutableList<Int> {
        val timeline = player.currentTimeline
        val order = ArrayList<Int>(timeline.windowCount)
        var index = timeline.getFirstWindowIndex(true)
        while (index != C.INDEX_UNSET && order.size < timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, true)
        }
        return order
    }

    /** Moves a song within the shuffled play order (positions are in play order). */
    private fun moveInShuffleOrder(from: Int, to: Int) {
        val order = playOrder()
        if (from !in order.indices || to !in order.indices || from == to) return
        order.add(to, order.removeAt(from))
        player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), Random.nextLong()))
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        save()
        if (!player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        save()
        handler.removeCallbacksAndMessages(null)
        scope.cancel()
        SleepTimer.cancel()
        session?.run {
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    private fun applySleepTimer(state: SleepTimer.State) {
        handler.removeCallbacks(sleepNow)
        player.pauseAtEndOfMediaItems = state is SleepTimer.State.EndOfTrack
        if (state is SleepTimer.State.At) handler.postDelayed(sleepNow, state.remainingMillis())
    }

    private fun save() {
        if (player.mediaItemCount == 0) {
            store.clear()
            return
        }
        store.save(
            QueueStore.Saved(
                ids = List(player.mediaItemCount) { player.getMediaItemAt(it).mediaId },
                index = player.currentMediaItemIndex,
                position = player.currentPosition.coerceAtLeast(0),
                shuffle = player.shuffleModeEnabled,
                repeat = player.repeatMode,
                title = player.playlistMetadata.title?.toString(),
            )
        )
    }

    /** Puts the last queue back, paused, so the app reopens where you left off. */
    private fun restore() {
        val saved = store.load() ?: return
        val tracks = library.tracksByIds(saved.ids)
        val currentId = saved.ids.getOrNull(saved.index)
        val playable = saved.ids.mapNotNull { id ->
            tracks[id]?.takeIf { it.isSaved && library.audioFile(it.file()).isFile }
        }
        if (playable.isEmpty()) return
        val index = playable.indexOfFirst { it.id() == currentId }
        player.setMediaItems(
            playable.map { MediaItems.from(library, it) },
            index.coerceAtLeast(0),
            if (index >= 0) saved.position else 0,
        )
        player.playlistMetadata = MediaMetadata.Builder().setTitle(saved.title).build()
        player.shuffleModeEnabled = saved.shuffle
        player.repeatMode = saved.repeat
        player.prepare()
    }
}
