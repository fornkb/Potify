package app.potify.offline

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** A Spotify playlist, album or song that a pasted link points at. */
data class SpotifyRef(val type: String, val id: String) {
    /** The library id of the playlist this link imports into. */
    val playlistId get() = if (type == TRACK) Library.ADDED else "spotify:$type:$id"
    val url get() = "https://open.spotify.com/$type/$id"

    companion object {
        const val PLAYLIST = "playlist"
        const val ALBUM = "album"
        const val TRACK = "track"

        /** The Spotify link a library playlist was imported from, or null for other playlists. */
        fun fromPlaylistId(playlistId: String): SpotifyRef? {
            val parts = playlistId.split(':')
            return if (parts.size == 3 && parts[0] == "spotify" && parts[1] in listOf(PLAYLIST, ALBUM)) {
                SpotifyRef(parts[1], parts[2])
            } else null
        }
    }
}

data class SpotifySong(val id: String, val title: String, val artists: String, val seconds: Long) {
    val firstArtist get() = artists.split(',').first().trim()
}

data class SpotifyList(
    val ref: SpotifyRef,
    val name: String,
    val owner: String?,
    val cover: String?,
    val songs: List<SpotifySong>,
    /** Entries the embed page listed, before podcast episodes and duplicates were dropped. */
    val listed: Int = songs.size,
    /** The full song list came from a CSV export, so nothing is missing. */
    val complete: Boolean = false,
) {
    /** Spotify's embed page lists at most 100 songs, so a list that reaches 100 may be cut short. */
    val maybeTruncated get() = ref.type != SpotifyRef.TRACK && !complete && listed >= Spotify.EMBED_LIMIT
}

/**
 * Reads Spotify links without an account, using the public embed page (the one sites use to show
 * a Spotify player). It includes each song's title, artists and length, for up to 100 songs.
 */
object Spotify {
    const val EMBED_LIMIT = 100

    private val LINK = Regex(
        """https?://(?:open|play)\.spotify\.com/(?:intl-[A-Za-z-]+/)?(?:embed/)?(playlist|album|track)/([A-Za-z0-9]{22})"""
    )
    private val URI = Regex("""spotify:(playlist|album|track):([A-Za-z0-9]{22})""")
    private val SHORT_LINK = Regex("""https?://(?:spotify\.link|spotify\.app\.link)/[A-Za-z0-9_-]+""")
    private val NEXT_DATA = Regex(
        """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL
    )

    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
            .build()
    }

    /** True when [text] contains a Spotify link of any kind, including a short link. */
    fun looksLikeSpotify(text: String) =
        LINK.containsMatchIn(text) || URI.containsMatchIn(text) || SHORT_LINK.containsMatchIn(text)

    /** The playlist, album or song in [text], without any network access. */
    fun parse(text: String): SpotifyRef? {
        val match = LINK.find(text) ?: URI.find(text) ?: return null
        return SpotifyRef(match.groupValues[1], match.groupValues[2])
    }

    /** Like [parse], but also follows spotify.link short links (needs the network). */
    fun resolve(text: String): SpotifyRef? {
        parse(text)?.let { return it }
        val short = SHORT_LINK.find(text)?.value ?: return null
        val request = Request.Builder().url(short).header("User-Agent", YouTubeSource.DESKTOP_USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            parse(response.request.url.toString())?.let { return it }
            return parse(response.body?.string().orEmpty())
        }
    }

    fun load(ref: SpotifyRef): SpotifyList {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/${ref.type}/${ref.id}")
            .header("User-Agent", YouTubeSource.DESKTOP_USER_AGENT)
            .header("Accept-Language", "en")
            .build()
        val html = http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Spotify returned ${response.code}")
            response.body?.string().orEmpty()
        }
        val json = NEXT_DATA.find(html)?.groupValues?.get(1)
            ?: throw IOException("Spotify's page changed and couldn't be read.")
        val props = JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
        val entity = props.optJSONObject("state")?.optJSONObject("data")?.optJSONObject("entity")
            ?: throw NotFound()
        // Playlists carry "coverArt"; albums and songs only the largest of "visualIdentity.image".
        val cover = entity.optJSONObject("coverArt")?.optJSONArray("sources")?.optJSONObject(0)?.optString("url")
            ?: entity.optJSONObject("visualIdentity")?.optJSONArray("image")?.let { images ->
                (0 until images.length()).mapNotNull { images.optJSONObject(it) }
                    .maxByOrNull { it.optInt("maxWidth") }?.optString("url")
            }
        val name = entity.optString("name").ifBlank { entity.optString("title") }
        if (ref.type == SpotifyRef.TRACK) {
            val artists = names(entity.optJSONArray("artists"))
            val song = SpotifySong(ref.id, name, artists, entity.optLong("duration") / 1000)
            return SpotifyList(ref, name, artists, cover, listOf(song))
        }
        val list = entity.optJSONArray("trackList") ?: JSONArray()
        val songs = ArrayList<SpotifySong>(list.length())
        for (i in 0 until list.length()) {
            val track = list.optJSONObject(i) ?: continue
            if (track.optString("entityType", "track") != "track") continue // podcast episodes
            val id = track.optString("uri").substringAfterLast(':')
            val title = track.optString("title")
            if (id.isBlank() || title.isBlank()) continue
            songs += SpotifySong(id, title, clean(track.optString("subtitle")), track.optLong("duration") / 1000)
        }
        return SpotifyList(ref, name, clean(entity.optString("subtitle")).ifBlank { null }, cover, songs, list.length())
    }

    private fun names(artists: JSONArray?): String {
        if (artists == null) return ""
        return (0 until artists.length()).mapNotNull { artists.optJSONObject(it)?.optString("name") }
            .filter { it.isNotBlank() }.joinToString(", ")
    }

    /** Spotify separates artists with non-breaking spaces. */
    private fun clean(text: String) = text.replace(' ', ' ').trim()

    /**
     * Reads a playlist exported as CSV by Exportify (exportify.net). Columns are found by their
     * header names, so small changes to the export format don't break it.
     */
    fun parseCsv(text: String): List<SpotifySong> {
        val table = csvRows(text.removePrefix("\uFEFF"))
        if (table.size < 2) throw IOException("Empty file")
        val header = table[0].map { it.trim().lowercase() }
        fun column(vararg names: String) = header.indexOfFirst { h -> names.any { h == it } }
            .takeIf { it >= 0 } ?: header.indexOfFirst { h -> names.any { h.contains(it) } }
        val uri = column("track uri", "spotify uri", "uri")
        val title = column("track name", "name", "title")
        val artists = column("artist name(s)", "artist names", "artist name", "artist")
        val duration = column("track duration (ms)", "duration (ms)", "duration_ms", "duration")
        if (title < 0 || artists < 0) throw IOException("Not a playlist export")
        return table.drop(1).mapNotNull { row ->
            fun cell(i: Int) = if (i in row.indices) row[i].trim() else ""
            val name = cell(title)
            if (name.isEmpty()) return@mapNotNull null
            val id = cell(uri).let { u ->
                Regex("spotify:track:([A-Za-z0-9]{22})").find(u)?.groupValues?.get(1)
                    ?: Regex("track/([A-Za-z0-9]{22})").find(u)?.groupValues?.get(1)
            } ?: return@mapNotNull null // Local files and episodes have no Spotify track id.
            val who = cell(artists).split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
            val ms = cell(duration).toDoubleOrNull() ?: 0.0
            SpotifySong(id, name, who, (ms / 1000).toLong())
        }.distinctBy { it.id }
    }

    /** Splits CSV text into rows of cells, handling quoted cells with commas, quotes and line breaks. */
    private fun csvRows(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { cell.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> { row += cell.toString(); cell.clear() }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row += cell.toString(); cell.clear()
                    if (row.any { it.isNotBlank() }) rows += row
                    row = ArrayList()
                }
                else -> cell.append(c)
            }
            i++
        }
        row += cell.toString()
        if (row.any { it.isNotBlank() }) rows += row
        return rows
    }

    class NotFound : IOException("Couldn't open this on Spotify. Private playlists can't be read: make it public, or check the link.")
}
