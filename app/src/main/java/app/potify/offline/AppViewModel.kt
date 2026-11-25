package app.potify.offline

import android.app.Application
import android.media.MediaMetadataRetriever
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class DownloadState(val summary: Library.DownloadSummary, val running: Boolean) {
    /** Show the downloads banner when something is downloading or waiting. */
    val visible get() = running || summary.waiting() > 0 || summary.readingPlaylists() > 0
}

sealed interface LinkResult {
    data object Invalid : LinkResult
    data object Added : LinkResult
    /** A video opened from a playlist: ask whether to save just the song or the whole playlist. */
    data class Ambiguous(val videoId: String, val playlistId: String) : LinkResult
}

data class SearchResults(val text: String, val playlists: List<Library.Playlist>, val tracks: List<Library.Track>)

class AppViewModel(private val app: Application) : AndroidViewModel(app) {
    val library: Library = Library.get(app)
    private val changes = MutableStateFlow(0)
    private val listener = Runnable { changes.update { it + 1 } }
    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = mutableMessages

    val theme = MutableStateFlow(Prefs.theme(app))
    val dataSaver = MutableStateFlow(Prefs.dataSaver(app))

    init {
        library.addListener(listener)
        viewModelScope.launch(Dispatchers.IO) { maintenance() }
    }

    override fun onCleared() {
        library.removeListener(listener)
    }

    /** Re-runs [query] off the main thread whenever the library changes. */
    private fun <T> live(query: () -> T): Flow<T> =
        changes.map { withContext(Dispatchers.IO) { query() } }

    val playlists: StateFlow<List<Library.Playlist>?> =
        live { library.playlists() }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val liked: StateFlow<List<Library.Track>> =
        live { library.likedTracks() }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val ticker = flow {
        while (true) {
            emit(Unit)
            delay(2_000)
        }
    }

    val downloads: StateFlow<DownloadState> = merge(changes, ticker).conflate()
        .map { withContext(Dispatchers.IO) { DownloadState(library.downloadSummary(), Downloads.scheduled(app)) } }
        .stateIn(
            viewModelScope, SharingStarted.WhileSubscribed(5_000),
            DownloadState(Library.DownloadSummary(0, 0, 0, 0), false),
        )

    /** Live per-track download progress (0..1) for tracks downloading right now. */
    val progress: StateFlow<Map<String, Float>> = flow {
        while (true) {
            emit(DownloadProgress.snapshot())
            delay(400)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    fun tracks(playlistId: String): Flow<List<Library.Track>> = live { library.tracks(playlistId) }

    fun track(id: String): Flow<Library.Track?> = live { library.track(id) }

    // ---- search ----

    val searchText = MutableStateFlow("")

    @OptIn(FlowPreview::class)
    val searchResults: StateFlow<SearchResults?> =
        combine(searchText.debounce(150), changes) { text, _ -> text.trim() }.conflate()
            .map { text ->
                if (text.isEmpty()) null else withContext(Dispatchers.IO) {
                    SearchResults(
                        text,
                        library.playlists().filter { it.title().contains(text, ignoreCase = true) },
                        library.search(text),
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // ---- downloads ----

    /**
     * Queues a pasted YouTube link: a playlist, or a single video (saved to Added songs). A video
     * opened from a playlist carries both, and the caller asks which one was meant.
     */
    fun addLink(text: String, onQueued: () -> Unit): LinkResult {
        val parsed = try {
            YouTubeLink.parse(text)
        } catch (e: IllegalArgumentException) {
            return LinkResult.Invalid
        }
        val video = parsed.videoId()
        val playlist = parsed.playlistId()
        return when {
            video != null && playlist != null -> LinkResult.Ambiguous(video, playlist)
            playlist != null -> {
                addPlaylist(playlist, onQueued)
                LinkResult.Added
            }
            else -> {
                addVideo(video!!, onQueued)
                LinkResult.Added
            }
        }
    }

    fun addPlaylist(playlistId: String, onQueued: () -> Unit) = viewModelScope.launch {
        withContext(Dispatchers.IO) { library.enqueue(playlistId) }
        say("Playlist added · downloading starts shortly")
        onQueued()
    }

    fun addVideo(videoId: String, onQueued: () -> Unit) = viewModelScope.launch {
        val alreadySaved = withContext(Dispatchers.IO) {
            library.addVideo(videoId).also { Artwork.fetchMissing(library) }
        }
        if (alreadySaved) {
            say("Already downloaded · it's in Added songs")
        } else {
            say("Song added · downloading starts shortly")
            onQueued()
        }
    }

    fun startDownloads() {
        viewModelScope.launch {
            val started = withContext(Dispatchers.IO) { !library.hasWork() || Downloads.start(app) }
            if (!started) say("Queue saved. Tap Resume to try again.")
            changes.update { it + 1 }
        }
    }

    fun pauseDownloads() {
        Downloads.pause(app)
        changes.update { it + 1 }
    }

    fun refresh(playlistId: String) = io({ library.enqueue(playlistId) }) { startDownloads() }

    fun retry(playlistId: String) = io({ library.retry(playlistId) }) { startDownloads() }

    fun downloadAgain(trackId: String) = io({ library.downloadAgain(trackId) }) { startDownloads() }

    fun removeDownload(trackId: String) = io({ library.removeDownload(trackId) })

    // ---- library edits ----

    fun deletePlaylist(id: String) = io({ library.deletePlaylist(id) }) { say("Playlist deleted") }

    fun renamePlaylist(id: String, title: String) = io({ library.renamePlaylist(id, title.trim()) })

    fun createPlaylist(title: String, thenAdd: String? = null) = io({
        val id = library.createPlaylist(title.trim())
        if (thenAdd != null) library.addToPlaylist(id, thenAdd)
    }) { say(if (thenAdd != null) "Added to ${title.trim()}" else "Playlist created") }

    fun addToPlaylist(playlist: Library.Playlist, trackId: String) = viewModelScope.launch {
        val added = withContext(Dispatchers.IO) { library.addToPlaylist(playlist.id(), trackId) }
        say(if (added) "Added to ${playlist.title()}" else "Already in ${playlist.title()}")
    }

    fun removeFromPlaylist(playlistId: String, trackId: String) =
        io({ library.removeFromPlaylist(playlistId, trackId) })

    fun setLiked(trackId: String, liked: Boolean) = io({ library.setLiked(trackId, liked) })

    // ---- settings ----

    fun setTheme(value: String) {
        Prefs.setTheme(app, value)
        theme.value = value
    }

    fun setDataSaver(enabled: Boolean) {
        Prefs.setDataSaver(app, enabled)
        dataSaver.value = enabled
    }

    suspend fun storageUsed(): LongArray = withContext(Dispatchers.IO) { library.storageUsed() }

    fun cleanStorage(onDone: () -> Unit) = viewModelScope.launch {
        val freed = withContext(Dispatchers.IO) { library.cleanStorage(!Downloads.scheduled(app)) }
        say(if (freed > 0) "Freed ${formatBytes(freed)}" else "Nothing to clean up")
        onDone()
    }

    // ---- playback ----

    /** Playable items for [tracks] (saved, file present) and where [startId] lands among them. */
    suspend fun playable(tracks: List<Library.Track>, startId: String?): Pair<List<MediaItem>, Int> =
        withContext(Dispatchers.IO) {
            val saved = tracks.filter { it.isSaved && library.audioFile(it.file()).isFile }
            saved.map { MediaItems.from(library, it) } to saved.indexOfFirst { it.id() == startId }.coerceAtLeast(0)
        }

    // ---- helpers ----

    private fun say(message: String) {
        mutableMessages.tryEmit(message)
    }

    private fun io(work: () -> Unit, then: () -> Unit = {}) = viewModelScope.launch {
        withContext(Dispatchers.IO) { work() }
        then()
    }

    /** Fills in sizes, durations and artwork that older versions didn't record. */
    private fun maintenance() {
        for (track in library.savedMissingMetadata()) {
            val file = library.audioFile(track.file())
            if (!file.isFile) continue
            val seconds = if (track.seconds() > 0) track.seconds() else durationOf(file)
            library.setMetadata(track.id(), file.length(), seconds)
        }
        Artwork.fetchMissing(library)
    }

    private fun durationOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000
        } catch (e: RuntimeException) {
            0L
        } finally {
            retriever.release()
        }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.ROOT, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${(bytes + (1L shl 19)) shr 20} MB"
    bytes >= 1L shl 10 -> "${(bytes + 512) shr 10} KB"
    else -> "$bytes B"
}

fun formatTime(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return if (s >= 3600) String.format(java.util.Locale.ROOT, "%d:%02d:%02d", s / 3600, s % 3600 / 60, s % 60)
    else String.format(java.util.Locale.ROOT, "%d:%02d", s / 60, s % 60)
}

fun formatTotal(seconds: Long): String {
    val minutes = (seconds + 30) / 60
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}
