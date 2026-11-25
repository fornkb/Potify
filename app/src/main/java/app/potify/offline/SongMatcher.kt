package app.potify.offline

import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchExtractor
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.min

/** A YouTube video that might be the right recording of a Spotify song. */
data class Candidate(
    val videoId: String,
    val title: String,
    val channel: String,
    val seconds: Long,
    val thumb: String?,
    /** Found through YouTube Music's song search (official album audio). */
    val music: Boolean,
    val score: Double = 0.0,
    val titleMatch: Double = 0.0,
    /** Share of the version words in Spotify's title (e.g. "Tokyo Dome", "JP Ver") this upload mentions. */
    val versionMatch: Double = 1.0,
    /** Share of the first Spotify artist's name found in the channel or title. */
    val artistMatch: Double = 1.0,
)

enum class Confidence {
    /** Right title, within 3 s of Spotify's length. */
    EXACT,
    /** Right title, 4–10 s off: accepted, the difference is shown. */
    CLOSE,
    /** Over 10 s off, a different title, or nothing found: needs a look. */
    CHECK,
}

/**
 * Finds the YouTube video for a Spotify song. YouTube Music's song search returns clean album
 * audio; regular YouTube search fills in what isn't on YouTube Music (live versions, regional
 * releases). Each result is scored on title, artist and length, with penalties for covers,
 * remixes, lyric edits and similar uploads the Spotify title doesn't ask for.
 */
object SongMatcher {
    const val EXACT_SECONDS = 3
    const val CLOSE_SECONDS = 10
    private const val PER_SEARCH = 5
    private const val KEEP = 8

    private val UNWANTED = setOf(
        "live", "cover", "remix", "slowed", "reverb", "sped", "instrumental", "karaoke", "8d", "nightcore",
        "lofi", "bpm", "acoustic", "reaction", "tutorial", "practice", "fancam", "teaser", "trailer",
        "shorts", "lesson", "chords", "mashup", "extended", "loop", "hour", "hours", "stage", "performance",
        "concert", "dance", "cam", "inkigayo", "musiccore", "comeback", "rehearsal", "behind", "scenes",
    )
    /** Words uploads add that say nothing about which recording it is. */
    private val NOISE = setOf(
        "official", "audio", "video", "music", "mv", "m", "v", "lyrics", "lyric", "lyrical", "hd", "hq", "4k",
        "visualizer", "topic", "full", "song", "songs", "new", "feat", "ft", "prod", "by", "the", "a", "and", "of",
    )
    /** Words in brackets or after " - " that don't change which recording is meant. */
    private val NEUTRAL = setOf(
        "feat", "ft", "featuring", "with", "from", "remaster", "remastered", "original", "motion", "picture",
        "soundtrack", "ost", "the", "album", "version", "explicit", "clean", "and", "of",
    )

    init {
        DownloadBatch.SOURCE // Sets up NewPipe.
    }

    fun match(song: SpotifySong): List<Candidate> {
        val core = coreTitle(song.title)
        val queries = buildList {
            add("${song.firstArtist} $core" to true)
            add("${song.firstArtist} $core" to false)
            // Live, regional or other versions: the words that name the version get cut from the
            // short query, so search the full title as well.
            if (hasVersionText(song)) {
                val full = plainWords("${song.firstArtist} ${song.title}")
                add(full to false)
                add(full to true)
            }
        }
        val found = LinkedHashMap<String, Candidate>()
        var failure: Exception? = null
        for ((query, music) in queries) {
            try {
                for (candidate in search(query, music)) found.putIfAbsent(candidate.videoId, candidate)
            } catch (e: org.schabi.newpipe.extractor.exceptions.ReCaptchaException) {
                throw e
            } catch (e: SearchExtractor.NothingFoundException) {
                // Also thrown for age-restricted queries; the other searches still count.
            } catch (e: Exception) {
                failure = e
            }
        }
        if (found.isEmpty() && failure != null) throw failure
        return rank(song, found.values)
    }

    /** Searches with the user's own words, for when none of the suggestions is right. */
    fun searchManually(song: SpotifySong, query: String): List<Candidate> {
        val found = LinkedHashMap<String, Candidate>()
        for (music in listOf(true, false)) {
            try {
                for (candidate in search(query, music)) found.putIfAbsent(candidate.videoId, candidate)
            } catch (e: SearchExtractor.NothingFoundException) {
            }
        }
        return rank(song, found.values)
    }

    fun confidence(song: SpotifySong, candidate: Candidate?): Confidence {
        if (candidate == null || candidate.titleMatch < 0.99 || song.seconds <= 0) return Confidence.CHECK
        // Spotify names a particular version (live, a venue, a language) that this upload doesn't mention.
        if (hasVersionText(song) && candidate.versionMatch < 0.5) return Confidence.CHECK
        // Same song name by someone else (e.g. "Pasoori Nu" for "Pasoori").
        if (candidate.artistMatch < 0.5) return Confidence.CHECK
        val diff = abs(candidate.seconds - song.seconds)
        return when {
            diff <= EXACT_SECONDS -> Confidence.EXACT
            diff <= CLOSE_SECONDS -> Confidence.CLOSE
            else -> Confidence.CHECK
        }
    }

    private fun search(query: String, music: Boolean): List<Candidate> {
        val extractor = ServiceList.YouTube.getSearchExtractor(
            query, listOf(if (music) "music_songs" else "videos"), "",
        )
        extractor.fetchPage()
        return extractor.initialPage.items.asSequence()
            .filterIsInstance<StreamInfoItem>()
            .mapNotNull { item ->
                val id = YouTubeLink.videoId(item.url) ?: return@mapNotNull null
                val thumb = item.thumbnails.maxByOrNull { it.height }?.url
                Candidate(id, item.name.orEmpty(), item.uploaderName.orEmpty(), item.duration, thumb, music)
            }
            .take(PER_SEARCH)
            .toList()
    }

    private fun rank(song: SpotifySong, candidates: Collection<Candidate>): List<Candidate> =
        candidates.map { score(song, it) }.sortedByDescending { it.score }.take(KEEP)

    private fun score(song: SpotifySong, c: Candidate): Candidate {
        val titleWords = words(coreTitle(song.title))
        val nameWords = words(c.title)
        val titleMatch = overlap(titleWords, nameWords)
        // Any credited artist counts: film songs often list the composer first and the singer later.
        val uploadWords = words(c.channel) + nameWords
        val artistMatch = song.artists.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .maxOfOrNull { overlap(words(it), uploadWords) } ?: 1.0
        val spotifyWords = words(song.title) + words(song.artists)
        // Words naming a version (e.g. "Tokyo Dome", "JP Ver") that the upload also mentions.
        val versionWords = versionWords(song) - titleWords
        val versionMatch = if (versionWords.isEmpty()) 0.0 else overlap(versionWords, nameWords)
        val added = nameWords - spotifyWords - words(c.channel)
        val unwanted = added.count { it in UNWANTED }
        // Other extra words (show names, dates, credits) hint at something other than the plain song.
        val extra = added.count { it !in UNWANTED && it !in NOISE }
        val diff = if (song.seconds > 0 && c.seconds > 0) abs(c.seconds - song.seconds) else 30
        val score = 3 * titleMatch + 1.5 * artistMatch + 1.5 * versionMatch -
            min(diff, 60L) / 6.0 - 3 * unwanted - min(extra * 0.2, 1.0) + (if (c.music) 0.5 else 0.0) -
            // A different song name never beats the right one, however close its length.
            (if (titleMatch < 0.5) 5.0 else 0.0)
        return c.copy(
            score = score, titleMatch = titleMatch, artistMatch = artistMatch,
            versionMatch = if (versionWords.isEmpty()) 1.0 else versionMatch,
        )
    }

    /** The song name without "(feat. …)", "[…]" or " - From …" parts. */
    fun coreTitle(title: String): String {
        var end = title.length
        for (mark in listOf(" (", " [", " - ", "(", "[")) {
            val i = title.indexOf(mark)
            if (i in 1 until end) end = i
        }
        return title.substring(0, end).trim()
    }

    private fun extraText(title: String) = title.removePrefix(coreTitle(title))

    /** "(From …)", "- From …", "(feat. …)" and "(with …)" parts: a film or album name, or guests. */
    private val CREDITS = Regex("""(?i)[(\[]\s*(from|feat\.?|ft\.?|featuring|with)\b[^)\]]*[)\]]|\s-\s*(from|feat\.?|with)\b.*$""")

    /** Words that name a particular version, e.g. "jp", "ver", "tokyo", "dome", "live". */
    private fun versionWords(song: SpotifySong): Set<String> =
        words(extraText(song.title).replace(CREDITS, " ")).filterTo(HashSet()) { w ->
            w !in NEUTRAL && !(w.length == 4 && w.all(Char::isDigit))
        } - words(song.artists)

    private fun hasVersionText(song: SpotifySong) = versionWords(song).any { w -> w.any(Char::isLetter) }

    /** Words only, so search operators like a leading "-" can't sneak into a query. */
    private fun plainWords(text: String) = normalize(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.joinToString(" ")

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace('’', '\'')
            .replace("'", "")

    private fun words(text: String): Set<String> =
        normalize(text).split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }.toSet()

    /** Share of [wanted] words found in [found]; long words may differ by one letter (spellings vary). */
    private fun overlap(wanted: Set<String>, found: Set<String>): Double {
        if (wanted.isEmpty()) return 1.0
        val hits = wanted.count { w -> w in found || (w.length >= 5 && found.any { it.length >= 4 && closeSpelling(w, it) }) }
        return hits.toDouble() / wanted.size
    }

    private fun closeSpelling(a: String, b: String): Boolean {
        if (abs(a.length - b.length) > 1) return false
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length] <= 1
    }
}
