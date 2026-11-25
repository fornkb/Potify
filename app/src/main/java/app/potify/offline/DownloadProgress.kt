package app.potify.offline

import java.util.concurrent.ConcurrentHashMap

/** In-memory progress (0..1) of tracks that are downloading right now, keyed by track id. */
object DownloadProgress {
    private val progress = ConcurrentHashMap<String, Float>()

    @JvmStatic
    fun set(trackId: String, fraction: Float) {
        progress[trackId] = fraction.coerceIn(0f, 1f)
    }

    @JvmStatic
    fun clear(trackId: String) {
        progress.remove(trackId)
    }

    fun snapshot(): Map<String, Float> = HashMap(progress)
}
