package app.potify.offline

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Saves a square cover image for each track, cropped from the video thumbnail, so artwork works
 * offline. Runs in the background and fills in whatever is missing.
 */
object Artwork {
    private const val SIZE = 480
    private val runner = Executors.newSingleThreadExecutor()
    private val fetchers = Executors.newFixedThreadPool(4)
    private val queued = AtomicBoolean(false)
    private val http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    @JvmStatic
    fun fetchMissing(library: Library) {
        if (!queued.compareAndSet(false, true)) return
        runner.execute {
            queued.set(false)
            try {
                fill(library)
            } catch (e: Exception) {
                Log.w("Potify", "Artwork paused", e)
            }
        }
    }

    private fun fill(library: Library) {
        val skipped = HashSet<String>()
        while (true) {
            val ids = library.tracksMissingThumb(40 + skipped.size).filterNot { it in skipped }
            if (ids.isEmpty()) return
            val results = fetchers.invokeAll(ids.map { id -> Callable { id to save(library, id) } })
            for (result in results) {
                val (id, name) = try {
                    result.get()
                } catch (e: ExecutionException) {
                    // Most likely offline. Try again next time the app starts or a playlist is added.
                    throw e.cause ?: e
                }
                if (name != null) library.setThumb(id, name) else skipped += id
            }
            if (skipped.size > 200) return
        }
    }

    /** Returns the saved file name, "" if the video has no thumbnail, or null to retry later. */
    private fun save(library: Library, id: String): String? {
        for (variant in listOf("hq720", "mqdefault")) {
            val request = Request.Builder().url("https://i.ytimg.com/vi/$id/$variant.jpg").build()
            val bytes = http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> response.body?.bytes()
                    404 -> null
                    else -> throw IOException("Artwork request failed: ${response.code}")
                }
            } ?: continue
            val bitmap = decodeSquare(bytes) ?: continue
            library.thumbDir.mkdirs()
            val name = "$id.jpg"
            val temp = File(library.thumbDir, "$name.tmp")
            FileOutputStream(temp).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it) }
            bitmap.recycle()
            if (!temp.renameTo(library.thumbFile(name))) {
                temp.delete()
                return null
            }
            return name
        }
        return ""
    }

    private fun decodeSquare(bytes: ByteArray): Bitmap? {
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(source.width, source.height)
        val cropped = Bitmap.createBitmap(source, (source.width - side) / 2, (source.height - side) / 2, side, side)
        if (cropped !== source) source.recycle()
        if (side <= SIZE) return cropped
        val scaled = Bitmap.createScaledBitmap(cropped, SIZE, SIZE, true)
        if (scaled !== cropped) cropped.recycle()
        return scaled
    }
}
