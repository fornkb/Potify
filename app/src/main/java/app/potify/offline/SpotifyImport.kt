package app.potify.offline

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.io.File
import java.io.IOException
import kotlin.math.abs

enum class RowStatus { WAITING, SEARCHING, DONE, FAILED }

/** One Spotify song on the review screen, with the YouTube videos found for it. */
data class ReviewRow(
    val song: SpotifySong,
    val status: RowStatus = RowStatus.WAITING,
    val candidates: List<Candidate> = emptyList(),
    val chosen: Candidate? = null,
    /** The user picked [chosen] themselves, so it needs no further checking. */
    val userPicked: Boolean = false,
    val skipped: Boolean = false,
    val error: String? = null,
) {
    val confidence: Confidence get() = SongMatcher.confidence(song, chosen)
    val needsCheck: Boolean
        get() = !skipped && (status == RowStatus.FAILED ||
            (status == RowStatus.DONE && !userPicked && confidence == Confidence.CHECK))
    /** How far the chosen video's length is from Spotify's, in seconds (positive = longer). */
    val difference: Long? get() = chosen?.takeIf { song.seconds > 0 && it.seconds > 0 }?.let { it.seconds - song.seconds }
    val willDownload: Boolean get() = !skipped && status == RowStatus.DONE && chosen != null
}

/**
 * A Spotify link being reviewed: reads the songs, matches each to a YouTube video (four at a
 * time), lets the user swap or skip songs, then saves the playlist and queues the downloads.
 * The draft is kept on disk so leaving the app doesn't lose the review.
 */
class SpotifyImport private constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    /** The library playlist being refreshed, or null for a new import. */
    val refreshOf: String?,
) {
    sealed interface Phase {
        data object Loading : Phase
        data class Failed(val message: String) : Phase
        data object Ready : Phase
    }

    var phase by mutableStateOf<Phase>(Phase.Loading)
        private set
    var list by mutableStateOf<SpotifyList?>(null)
        private set
    /** Songs left out because the playlist being refreshed already has them. */
    var alreadyHad by mutableStateOf(0)
        private set
    /** YouTube is refusing searches for now; matching stopped. */
    var limited by mutableStateOf(false)
        private set
    val rows = mutableStateListOf<ReviewRow>()

    private var link: String = ""
    private var matching: Job? = null
    private var saving: Job? = null

    val matchedCount get() = rows.count { it.status == RowStatus.DONE || it.status == RowStatus.FAILED }
    val isMatching get() = rows.any { it.status == RowStatus.WAITING || it.status == RowStatus.SEARCHING } && !limited

    fun load(text: String) {
        link = text
        phase = Phase.Loading
        limited = false
        scope.launch {
            try {
                val ref = withContext(Dispatchers.IO) { Spotify.resolve(text) }
                    ?: throw Spotify.NotFound()
                val loaded = withContext(Dispatchers.IO) { Spotify.load(ref) }
                val existing = refreshOf?.let { id -> withContext(Dispatchers.IO) { Library.get(context).importedSourceIds(id) } }
                    .orEmpty()
                val songs = loaded.songs.distinctBy { it.id }.filter { it.id !in existing }
                list = loaded
                alreadyHad = loaded.songs.size - songs.size
                rows.clear()
                rows.addAll(songs.map { ReviewRow(it) })
                phase = Phase.Ready
                save()
                matchRemaining()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Spotify.NotFound) {
                phase = Phase.Failed(e.message.orEmpty())
            } catch (e: Exception) {
                Log.w("PotifySpotify", "Couldn't read $text", e)
                phase = Phase.Failed("Couldn't read this from Spotify. Check your connection and try again.")
            }
        }
    }

    fun retryLoad() = load(link)

    /** Drafts saved by older versions don't know how many songs Spotify listed; ask once. */
    private fun recountListed() = scope.launch {
        val current = list ?: return@launch
        val fresh = runCatching { withContext(Dispatchers.IO) { Spotify.load(current.ref) } }.getOrNull() ?: return@launch
        if (list === current) {
            list = current.copy(listed = fresh.listed)
            save()
        }
    }

    sealed interface CsvResult {
        data class Added(val count: Int) : CsvResult
        data class Failed(val message: String) : CsvResult
    }

    /** A CSV export is being read and merged. */
    var readingCsv by mutableStateOf(false)
        private set

    /**
     * Reads and merges a CSV export on this review's own scope. The card that starts it disappears
     * once the list is complete, so the work mustn't belong to that card (it used to be cancelled
     * there before matching restarted, leaving the new songs waiting forever).
     */
    fun importCsv(read: suspend () -> String?, onResult: (CsvResult) -> Unit) {
        if (readingCsv) return
        readingCsv = true
        scope.launch {
            val result = try {
                val text = read()
                if (text == null) CsvResult.Failed("Couldn't open that file.") else mergeCsv(text)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PotifySpotify", "CSV import failed", e)
                CsvResult.Failed("Couldn't read that file.")
            } finally {
                readingCsv = false
            }
            onResult(result)
        }
    }

    /**
     * Adds the songs from an Exportify CSV of this playlist, for playlists longer than the 100
     * songs Spotify's embed page shares. Songs already on the review keep their matches; the list
     * follows the playlist's order from the file.
     */
    private suspend fun mergeCsv(text: String): CsvResult {
        val current = list ?: return CsvResult.Failed("Nothing to add to.")
        val songs = try {
            withContext(Dispatchers.Default) { Spotify.parseCsv(text) }
        } catch (e: Exception) {
            Log.w("PotifySpotify", "Unreadable CSV", e)
            return CsvResult.Failed("That file doesn't look like an Exportify playlist export.")
        }
        if (songs.isEmpty()) return CsvResult.Failed("That file has no songs in it.")
        val existing = refreshOf?.let { id -> withContext(Dispatchers.IO) { Library.get(context).importedSourceIds(id) } }
            .orEmpty()
        val known = current.songs.map { it.id }.toSet() + existing + rows.map { it.song.id }
        // An export of this playlist contains nearly all of the songs already known for it; a
        // different playlist may share a song or two, so a single match isn't enough.
        val shared = songs.count { it.id in known }
        if (known.isNotEmpty() && shared < 0.6 * minOf(known.size, songs.size)) {
            return CsvResult.Failed("That file is a different playlist. Export “${current.name}” instead.")
        }
        val byId = rows.associateBy { it.song.id }
        val wanted = songs.filter { it.id !in existing }
        val merged = wanted.map { byId[it.id] ?: ReviewRow(it) } +
            rows.filter { row -> wanted.none { it.id == row.song.id } }
        val added = merged.size - rows.size
        matching?.cancel()
        rows.clear()
        rows.addAll(merged.map { if (it.status == RowStatus.SEARCHING) it.copy(status = RowStatus.WAITING) else it })
        alreadyHad = songs.size - wanted.size
        list = current.copy(songs = songs, listed = songs.size, complete = true)
        // Start matching before anything else suspends, then save the draft.
        matchRemaining()
        save()
        return CsvResult.Added(added)
    }

    private fun savedCandidate(saved: Library.Track, song: SpotifySong) = Candidate(
        saved.id(), saved.title(), ALREADY_SAVED,
        if (saved.seconds() > 0) saved.seconds() else song.seconds,
        "https://i.ytimg.com/vi/${saved.id()}/mqdefault.jpg", music = false,
        score = 100.0, titleMatch = 1.0,
    )

    /**
     * Songs matched before they were saved from another playlist (or in a draft from an older
     * version) switch to the library's copy, unless the user picked a video for them here.
     */
    private suspend fun reuseSaved() {
        val ids = rows.filter { !it.userPicked }.map { it.song.id }
        if (ids.isEmpty()) return
        val saved = withContext(Dispatchers.IO) {
            val library = Library.get(context)
            ids.mapNotNull { id -> library.trackForSource(id)?.let { id to it } }.toMap()
        }
        for (index in rows.indices) {
            val row = rows[index]
            val track = saved[row.song.id] ?: continue
            if (row.userPicked || row.chosen?.videoId == track.id()) continue
            val candidate = savedCandidate(track, row.song)
            rows[index] = row.copy(
                status = RowStatus.DONE, chosen = candidate, userPicked = true, error = null,
                candidates = listOf(candidate) + row.candidates.filter { it.videoId != candidate.videoId },
            )
        }
    }

    /** Matches every song that hasn't been matched yet (or failed), four at a time. */
    fun matchRemaining() {
        if (matching?.isActive == true) return
        limited = false
        matching = scope.launch {
            reuseSaved()
            val permits = Semaphore(4)
            rows.indices
                .filter { rows[it].status != RowStatus.DONE }
                .map { index -> launch { permits.withPermit { if (!limited) matchRow(index) } } }
                .joinAll()
            save()
        }
    }

    private suspend fun matchRow(index: Int) {
        val song = rows[index].song
        update(index) { copy(status = RowStatus.SEARCHING, error = null) }
        try {
            // Imported before (e.g. it's on another Spotify playlist): reuse that video, so the
            // song isn't downloaded twice or listed twice in All songs.
            val saved = withContext(Dispatchers.IO) { Library.get(context).trackForSource(song.id) }
            if (saved != null) {
                val candidate = savedCandidate(saved, song)
                update(index) {
                    copy(status = RowStatus.DONE, candidates = listOf(candidate), chosen = candidate, userPicked = true)
                }
                return
            }
            val found = withContext(Dispatchers.IO) { SongMatcher.match(song) }
            update(index) {
                copy(status = RowStatus.DONE, candidates = found, chosen = found.firstOrNull(), userPicked = false)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ReCaptchaException) {
            limited = true
            update(index) { copy(status = RowStatus.FAILED, error = "YouTube is limiting searches. Try again later.") }
        } catch (e: Exception) {
            Log.w("PotifySpotify", "Search failed for ${song.title}", e)
            update(index) { copy(status = RowStatus.FAILED, error = "Couldn't search YouTube.") }
        }
    }

    /** Runs the user's own search for one song and shows those results instead. */
    suspend fun searchManually(index: Int, query: String): Boolean {
        val song = rows.getOrNull(index)?.song ?: return false
        val found = try {
            withContext(Dispatchers.IO) { SongMatcher.searchManually(song, query) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("PotifySpotify", "Manual search failed", e)
            return false
        }
        update(index) { copy(status = RowStatus.DONE, candidates = found, error = null) }
        return true
    }

    fun choose(index: Int, candidate: Candidate) =
        update(index) { copy(chosen = candidate, userPicked = true, skipped = false, status = RowStatus.DONE) }

    fun setSkipped(index: Int, skipped: Boolean) = update(index) { copy(skipped = skipped) }

    private fun update(index: Int, change: ReviewRow.() -> ReviewRow) {
        if (index !in rows.indices) return
        rows[index] = rows[index].change()
        scheduleSave()
    }

    /** Saves the chosen songs to the library and queues them. Returns the playlist id and count. */
    suspend fun finish(): Pair<String, Int> {
        val current = list ?: return "" to 0
        matching?.cancel()
        saving?.cancel()
        val songs = rows.filter { it.willDownload }.map { row ->
            val video = row.chosen!!
            Library.ImportedSong(
                video.videoId, row.song.title, row.song.artists,
                if (video.seconds > 0) video.seconds else row.song.seconds, row.song.id,
            )
        }
        val ref = current.ref
        val added = withContext(Dispatchers.IO) {
            val library = Library.get(context)
            val count = if (ref.type == SpotifyRef.TRACK) {
                library.addImportedSongs(Library.ADDED, "Added songs", Library.LOCAL, songs)
            } else {
                library.addImportedSongs(ref.playlistId, current.name, Library.SPOTIFY, songs)
            }
            Artwork.fetchMissing(library)
            draftFile(context).delete()
            count
        }
        return ref.playlistId to added
    }

    fun cancel() {
        matching?.cancel()
        saving?.cancel()
        draftFile(context).delete()
    }

    // ---- draft on disk ----

    private fun scheduleSave() {
        saving?.cancel()
        saving = scope.launch {
            delay(800)
            save()
        }
    }

    private suspend fun save() {
        val current = list ?: return
        val json = JSONObject()
            .put("type", current.ref.type).put("id", current.ref.id)
            .put("name", current.name).put("owner", current.owner).put("cover", current.cover)
            .put("refreshOf", refreshOf).put("alreadyHad", alreadyHad).put("link", link)
            .put("listed", current.listed).put("complete", current.complete)
            .put("rows", JSONArray(rows.map { rowJson(it) }))
        withContext(Dispatchers.IO) {
            val file = draftFile(context)
            val temp = File(file.path + ".tmp")
            temp.writeText(json.toString())
            temp.renameTo(file)
        }
    }

    private fun rowJson(row: ReviewRow) = JSONObject()
        .put("id", row.song.id).put("title", row.song.title).put("artists", row.song.artists)
        .put("seconds", row.song.seconds)
        .put("status", if (row.status == RowStatus.DONE) "done" else "waiting")
        .put("chosen", row.chosen?.videoId).put("userPicked", row.userPicked).put("skipped", row.skipped)
        .put("candidates", JSONArray(row.candidates.map { c ->
            JSONObject().put("videoId", c.videoId).put("title", c.title).put("channel", c.channel)
                .put("seconds", c.seconds).put("thumb", c.thumb).put("music", c.music)
                .put("score", c.score).put("titleMatch", c.titleMatch).put("versionMatch", c.versionMatch).put("artistMatch", c.artistMatch)
        }))

    companion object {
        /** Shown as the "channel" of a song matched to a video that's already in the library. */
        const val ALREADY_SAVED = "Already in your library"

        private fun draftFile(context: Context) = File(context.filesDir, "spotify_review.json")

        fun start(context: Context, scope: CoroutineScope, text: String, refreshOf: String? = null) =
            SpotifyImport(context.applicationContext, scope, refreshOf).also { it.load(text) }

        /** The review left unfinished last time, if any. */
        fun restore(context: Context, scope: CoroutineScope): SpotifyImport? {
            val file = draftFile(context)
            if (!file.isFile) return null
            return try {
                val json = JSONObject(file.readText())
                val ref = SpotifyRef(json.getString("type"), json.getString("id"))
                val import = SpotifyImport(context.applicationContext, scope, json.optString("refreshOf").ifBlank { null })
                import.link = json.optString("link", ref.url)
                import.alreadyHad = json.optInt("alreadyHad")
                val rowsJson = json.getJSONArray("rows")
                val rows = (0 until rowsJson.length()).map { i -> rowFromJson(rowsJson.getJSONObject(i)) }
                import.list = SpotifyList(
                    ref, json.optString("name"), json.optString("owner").ifBlank { null },
                    json.optString("cover").ifBlank { null }, rows.map { it.song },
                    listed = json.optInt("listed", rows.size), complete = json.optBoolean("complete"),
                )
                import.rows.addAll(rows)
                import.phase = Phase.Ready
                if (!json.has("listed") && ref.type != SpotifyRef.TRACK) import.recountListed()
                // Matches songs that were still waiting (or had failed) when the app closed, and
                // switches songs saved meanwhile from another playlist to the library's copy.
                import.matchRemaining()
                import
            } catch (e: Exception) {
                Log.w("PotifySpotify", "Dropping an unreadable review draft", e)
                file.delete()
                null
            }
        }

        private fun rowFromJson(json: JSONObject): ReviewRow {
            val song = SpotifySong(json.getString("id"), json.getString("title"), json.optString("artists"), json.optLong("seconds"))
            val list = json.optJSONArray("candidates") ?: JSONArray()
            val candidates = (0 until list.length()).map { i ->
                val c = list.getJSONObject(i)
                Candidate(
                    c.getString("videoId"), c.optString("title"), c.optString("channel"), c.optLong("seconds"),
                    c.optString("thumb").ifBlank { null }, c.optBoolean("music"), c.optDouble("score"), c.optDouble("titleMatch"), c.optDouble("versionMatch", 1.0), c.optDouble("artistMatch", 1.0),
                )
            }
            val chosenId = json.optString("chosen").ifBlank { null }
            val done = json.optString("status") == "done"
            return ReviewRow(
                song = song,
                status = if (done) RowStatus.DONE else RowStatus.WAITING,
                candidates = candidates,
                chosen = candidates.firstOrNull { it.videoId == chosenId },
                userPicked = json.optBoolean("userPicked"),
                skipped = json.optBoolean("skipped"),
            )
        }
    }
}

/**
 * Plays a few seconds of a YouTube result straight from the internet so the user can hear which
 * version it is. Starts about a third of the way in, past most intros.
 */
class PreviewPlayer(private val context: Context) {
    var playingId by mutableStateOf<String?>(null)
        private set
    var loadingId by mutableStateOf<String?>(null)
        private set

    private var player: ExoPlayer? = null
    private var job: Job? = null

    fun isActive(videoId: String) = playingId == videoId || loadingId == videoId

    fun toggle(scope: CoroutineScope, candidate: Candidate, onStart: () -> Unit, onError: (String) -> Unit) {
        if (isActive(candidate.videoId)) {
            stop()
            return
        }
        stop()
        onStart()
        loadingId = candidate.videoId
        job = scope.launch {
            try {
                val audio = withContext(Dispatchers.IO) { DownloadBatch.SOURCE.audioFor(candidate.videoId, true) }
                val headers = YouTubeSource.streamHeaders(audio.url())
                val http = DefaultHttpDataSource.Factory()
                    .setUserAgent(headers["User-Agent"])
                    .setDefaultRequestProperties(headers.filterKeys { it != "User-Agent" })
                    .setConnectTimeoutMs(15_000)
                    .setReadTimeoutMs(20_000)
                val source = ProgressiveMediaSource.Factory(http).createMediaSource(MediaItem.fromUri(audio.url()))
                val seconds = if (audio.seconds() > 0) audio.seconds() else candidate.seconds
                val exo = player ?: create(onError).also { player = it }
                exo.setMediaSource(source, (seconds * 1000 * 0.3).toLong())
                exo.prepare()
                exo.playWhenReady = true
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("PotifySpotify", "Preview failed for ${candidate.videoId}", e)
                loadingId = null
                onError((e as? YouTubeSource.Unavailable)?.message ?: "Couldn't play this preview.")
            }
        }
    }

    private fun create(onError: (String) -> Unit) = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        )
        addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> if (loadingId != null) {
                        playingId = loadingId
                        loadingId = null
                    }
                    Player.STATE_ENDED -> stop()
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                // Paused from outside, e.g. another app took audio focus.
                if (!isPlaying && playbackState == Player.STATE_READY && !playWhenReady) stop()
            }

            override fun onPlayerError(error: PlaybackException) {
                Log.w("PotifySpotify", "Preview playback failed", error)
                stop()
                onError("Couldn't play this preview.")
            }
        })
    }

    fun stop() {
        job?.cancel()
        job = null
        player?.stop()
        player?.clearMediaItems()
        playingId = null
        loadingId = null
    }

    fun release() {
        stop()
        player?.release()
        player = null
    }
}

/** "+6 s", "−12 s" or "1:04 longer" style text for a length difference. */
fun formatDifference(seconds: Long): String {
    val sign = if (seconds >= 0) "+" else "−"
    val size = abs(seconds)
    return if (size < 60) "$sign$size s" else "$sign${formatTime(size)}"
}
