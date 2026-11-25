package app.potify.offline

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata

object MediaItems {
    /** A full, playable item for a saved track: local audio file plus title, artist and artwork. */
    fun from(library: Library, track: Library.Track): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title())
            .setArtist(track.artist())
            .setIsPlayable(true)
            .setIsBrowsable(false)
        val thumb = track.thumb()
        if (!thumb.isNullOrEmpty()) {
            val file = library.thumbFile(thumb)
            if (file.isFile) metadata.setArtworkUri(Uri.fromFile(file))
        }
        val builder = MediaItem.Builder().setMediaId(track.id()).setMediaMetadata(metadata.build())
        track.file()?.let { builder.setUri(Uri.fromFile(library.audioFile(it))) }
        return builder.build()
    }

    /** Fills in file and artwork for items that only carry a track id. Unknown ids are kept as they are. */
    fun resolve(library: Library, items: List<MediaItem>): List<MediaItem> {
        val tracks = library.tracksByIds(items.map { it.mediaId })
        return items.map { item -> tracks[item.mediaId]?.takeIf { it.isSaved }?.let { from(library, it) } ?: item }
    }
}

/** Remembers the play queue so the app reopens where you left off. */
class QueueStore(context: Context) {
    data class Saved(
        val ids: List<String>,
        val index: Int,
        val position: Long,
        val shuffle: Boolean,
        val repeat: Int,
        val title: String?,
    )

    private val prefs = context.applicationContext.getSharedPreferences("playback", Context.MODE_PRIVATE)

    fun save(state: Saved) {
        prefs.edit()
            .putString("ids", state.ids.joinToString(","))
            .putInt("index", state.index)
            .putLong("position", state.position)
            .putBoolean("shuffle", state.shuffle)
            .putInt("repeat", state.repeat)
            .putString("title", state.title)
            .apply()
    }

    fun load(): Saved? {
        val ids = prefs.getString("ids", null)?.split(",")?.filter { it.isNotBlank() }.orEmpty()
        if (ids.isEmpty()) return null
        return Saved(
            ids = ids,
            index = prefs.getInt("index", 0),
            position = prefs.getLong("position", 0),
            shuffle = prefs.getBoolean("shuffle", false),
            repeat = prefs.getInt("repeat", 0),
            title = prefs.getString("title", null),
        )
    }

    fun clear() = prefs.edit().clear().apply()
}
