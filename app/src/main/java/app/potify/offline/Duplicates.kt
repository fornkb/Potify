package app.potify.offline

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

/** One copy of a song, with what's needed to decide which copy to keep. */
data class SongCopy(
    val track: Library.Track,
    /** Playlists this copy is in. */
    val playlists: List<String>,
    /** Came from a Spotify import, so it has Spotify's clean title and artists. */
    val fromSpotify: Boolean,
)

/** Copies of what looks like the same song; [copies] starts with the one suggested for keeping. */
data class DuplicateGroup(val copies: List<SongCopy>)

/**
 * Finds songs saved more than once, usually the same song from different YouTube channels or
 * playlists ("Money Trees" from a Spotify import and "Kendrick Lamar - Money Trees (Official
 * Audio)" from a YouTube playlist). Titles are compared without the parts uploads add (brackets,
 * "Official Video", "Artist - "), and the artist or the length has to agree as well. Live, remix,
 * slowed and other versions are kept apart, as are different language versions.
 */
object Duplicates {
    /** Words that make a different recording when only one copy has them. */
    private val VERSIONS = setOf(
        "live", "remix", "acoustic", "slowed", "reverb", "sped", "instrumental", "karaoke", "cover",
        "nightcore", "8d", "lofi", "extended", "unplugged", "reprise", "demo", "mashup", "boosted",
        "orchestral", "piano", "guitar", "loop", "hour", "hours", "reprised", "chopped", "screwed",
    )
    /** A copy that names a different language is a different version; one that names none isn't. */
    private val LANGUAGES = setOf(
        "tamil", "telugu", "hindi", "malayalam", "kannada", "bengali", "marathi", "punjabi", "english",
        "korean", "japanese", "jp", "spanish", "french", "chinese", "arabic", "urdu",
    )
    /** Words uploads add that say nothing about which song it is. */
    private val NOISE = setOf(
        "official", "video", "audio", "lyrics", "lyric", "lyrical", "hd", "hq", "4k", "8k", "1080p", "720p",
        "mv", "visualizer", "visualiser", "videoclip", "clip", "oficial", "explicit", "clean", "remastered",
        "remaster", "version", "full", "song", "songs", "music", "new", "latest", "topic", "with", "ver",
    )
    private val BRACKETS = Regex("""[(\[{【「『〔][^)\]}】」』〕]*[)\]}】」』〕]""")
    private val SEPARATORS = Regex("""\s+[-–—~]\s+|\s*[|｜•·]\s*|\s+//\s+|\s+-|-\s+""")
    private val CREDITS = Regex("""\s(feat|ft|featuring|prod|starring)\b.*$""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val MV = Regex("""\bm/v\b""")

    /** What's compared, worked out once per song. */
    private class Key(
        val copy: SongCopy,
        /** The title's parts ("Artist", "Song", "Film"), as word sets without noise. */
        val segments: List<Set<String>>,
        val words: Set<String>,
        /** Artist and channel names, letters and digits only ("kendricklamar"). */
        val names: List<String>,
        /** Title and artist, letters and digits only, to look names up in. */
        val haystack: String,
        val versions: Set<String>,
        val languages: Set<String>,
        val seconds: Long,
    )

    fun find(library: Library): List<DuplicateGroup> {
        val tracks = library.allTracks()
        val playlists = library.playlistTitlesByTrack()
        val spotify = library.spotifyTrackIds()
        val keys = tracks.map { key(SongCopy(it, playlists[it.id()].orEmpty(), it.id() in spotify)) }

        // Only songs that share a word can match, so compare within those buckets.
        val byWord = HashMap<String, MutableList<Int>>()
        keys.forEachIndexed { i, key -> key.words.forEach { byWord.getOrPut(it) { ArrayList() } += i } }
        val parent = IntArray(keys.size) { it }
        fun root(i: Int): Int {
            var r = i
            while (parent[r] != r) r = parent[r]
            var j = i
            while (parent[j] != r) { val next = parent[j]; parent[j] = r; j = next }
            return r
        }
        val compared = HashSet<Long>()
        for (bucket in byWord.values) {
            // A word on hundreds of songs ("love", "the") says nothing; rarer shared words will pair them.
            if (bucket.size < 2 || bucket.size > 200) continue
            for (x in bucket.indices) for (y in x + 1 until bucket.size) {
                val a = bucket[x]
                val b = bucket[y]
                if (!compared.add(a.toLong() * keys.size + b)) continue
                if (root(a) != root(b) && same(keys[a], keys[b])) parent[root(a)] = root(b)
            }
        }
        return keys.indices.groupBy(::root).values
            .filter { it.size > 1 }
            .map { members -> DuplicateGroup(members.map { keys[it].copy }.sortedByDescending(::keepScore)) }
            .sortedBy { it.copies.first().track.title().lowercase(Locale.ROOT) }
    }

    /** Puts the copy most worth keeping first among copies the user picked themselves. */
    fun suggestKeep(copies: List<SongCopy>): List<SongCopy> = copies.sortedByDescending(::keepScore)

    fun describe(library: Library, ids: List<String>): List<SongCopy> {
        val tracks = library.tracksByIds(ids)
        val playlists = library.playlistTitlesByTrack()
        val spotify = library.spotifyTrackIds()
        return ids.mapNotNull { id -> tracks[id]?.let { SongCopy(it, playlists[id].orEmpty(), id in spotify) } }
    }

    /** Downloaded first (no download needed), then Spotify's clean names, then official audio. */
    private fun keepScore(copy: SongCopy): Int {
        val track = copy.track
        var score = 0
        if (track.isSaved) score += 100
        if (copy.fromSpotify) score += 20
        if (track.artist().orEmpty().endsWith(" - Topic")) score += 10
        score += copy.playlists.size.coerceAtMost(5)
        if (track.liked()) score += 1
        return score
    }

    private fun key(copy: SongCopy): Key {
        val track = copy.track
        val title = track.title().orEmpty()
        val artist = track.artist().orEmpty()
        // "Artist - Song", "Song | Film | Singer" and Spotify's "Song - From Film" all become parts;
        // [same] compares them with the artist parts left out.
        val bare = BRACKETS.replace(normalize(title), " ")
        val segments = SEPARATORS.split(bare)
            .map { CREDITS.replace(" $it", "") }
            .map { part -> part.split(NON_WORD).filter { it.isNotEmpty() && it !in NOISE }.toSet() }
            .filter { part -> part.any { w -> w.any(Char::isLetterOrDigit) } }
        val allWords = words(title)
        val names = artist.split(',', '&', ';', '/')
            .map { compact(it.replace(Regex("""(?i)\s*-\s*topic\s*$"""), "")).removeSuffix("vevo").removeSuffix("official") }
            .filter { it.length >= 3 }
        return Key(
            copy = copy,
            segments = segments,
            words = segments.flatten().filterTo(HashSet()) { it.length >= 2 || it.any(Char::isDigit) },
            names = names,
            haystack = compact(title) + compact(artist),
            versions = allWords.filterTo(HashSet()) { it in VERSIONS },
            languages = allWords.filterTo(HashSet()) { it in LANGUAGES },
            seconds = track.seconds(),
        )
    }

    private fun same(a: Key, b: Key): Boolean {
        if (a.versions != b.versions) return false
        if (a.languages.isNotEmpty() && b.languages.isNotEmpty() && a.languages.intersect(b.languages).isEmpty()) return false

        // Leave out artist names (either song's), so "Kendrick Lamar - Money Trees" and
        // "Money Trees" by Kendrick Lamar compare as the same title.
        val names = a.names + b.names
        // A part that is (most of) an artist's name, e.g. "kendrick lamar" or "the weeknd".
        fun isName(segment: Set<String>): Boolean {
            val joined = segment.joinToString("")
            return names.any { name ->
                (name.contains(joined) || joined.contains(name)) &&
                    minOf(name.length, joined.length) * 10 >= maxOf(name.length, joined.length) * 6
            }
        }
        fun strip(segments: List<Set<String>>) = segments.filterNot(::isName)
            .map { part -> part.filterNot { w -> w.length >= 3 && names.any { it.contains(w) } }.toSet() }
            .filter { it.isNotEmpty() }
            .distinct()
        val ra = strip(a.segments)
        val rb = strip(b.segments)
        if (ra.isEmpty() || rb.isEmpty()) return false

        val full = ra.toSet() == rb.toSet()
        val partial = !full && (
            (ra[0] == rb[0] && (ra.size == 1 || rb.size == 1 || ra.drop(1).any { it in rb })) ||
                (ra.size == 1 && ra[0] in rb) || (rb.size == 1 && rb[0] in ra)
            )
        if (!full && !partial) return false

        val overlap = b.names.any { a.haystack.contains(it) } || a.names.any { b.haystack.contains(it) }
        val diff = if (a.seconds > 0 && b.seconds > 0) abs(a.seconds - b.seconds) else null
        return when {
            // Same title and artist: a music video may run longer than the audio.
            full && overlap -> diff == null || diff <= 75
            partial && overlap -> diff == null || diff <= 30
            // Different channels with no artist in common: the length has to agree closely.
            full -> diff != null && diff <= 6
            else -> diff != null && diff <= 4
        }
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("""\p{M}+"""), "")
            .lowercase(Locale.ROOT)
            .replace(MV, "mv")
            .replace("’", "")
            .replace("'", "")
            .replace("&", " and ")

    private fun words(text: String): Set<String> =
        normalize(text).split(NON_WORD).filter { it.isNotEmpty() }.toSet()

    private fun compact(text: String) = normalize(text).split(NON_WORD).joinToString("")
}
