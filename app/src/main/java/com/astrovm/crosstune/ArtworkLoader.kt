package com.astrovm.crosstune

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest

/** Where covers are kept, in the app's cache. */
internal const val ARTWORK_DIR = "artwork"

/**
 * Downloads cover art with the app's HTTP client and keeps the last few in memory, and, given a
 * [cacheDir], on disk, so Recent shows its covers straight away after the app restarts.
 * Artwork is decoration, so any failure just means no image.
 */
internal class ArtworkLoader(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val cacheDir: File? = null
) : Covers {
    /** Sized by memory rather than count, since a playlist's covers come by the hundred. */
    private val cache = object : LruCache<String, ImageBitmap>(MAX_CACHED_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * BYTES_PER_PIXEL
    }

    /** The color each cover leans to, once worked out. */
    private val colors = LruCache<String, Color>(MAX_COLORS)

    override fun cover(url: String): ImageBitmap? = cache[url]

    override fun color(url: String): Color? = colors[url]

    override fun keepColor(url: String, color: Color) {
        colors.put(url, color)
    }

    suspend fun load(url: String): ImageBitmap? {
        cache[url]?.let { return it }
        // A cover a music app showed, kept here as a file; none other is read.
        if (url.startsWith("file:")) return withContext(ioDispatcher) { kept(url)?.let(::readCached) }?.let { bitmap -> bitmap.asImageBitmap().also { cache.put(url, it) } }
        val file = cacheDir?.let { File(it, fileName(url)) }
        val bitmap = file?.let { withContext(ioDispatcher) { readCached(it) } } ?: download(url, file) ?: return null
        return bitmap.asImageBitmap().also { cache.put(url, it) }
    }

    /** The file [url] names, when it's one kept with the covers. */
    private fun kept(url: String): File? {
        val dir = (cacheDir ?: return null).canonicalFile
        val file = runCatching { File(URI(url)).canonicalFile }.getOrNull() ?: return null
        return file.takeIf { it.parentFile == dir }
    }

    private suspend fun download(url: String, file: File?): Bitmap? {
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder().url(httpUrl).get().build()
        return try {
            client.newCall(request).answer(ioDispatcher) { response ->
                if (!response.isSuccessful) return@answer null
                val bytes = response.body.bytesAtMost()
                decode(bytes)?.also { if (file != null) save(file, bytes) }
            }
        } catch (_: IOException) {
            null
        }
    }

    private fun readCached(file: File): Bitmap? =
        if (file.isFile) file.readBytes().let(::decode) else null

    /** Keeps the newest covers: a few long playlists' worth, besides Recent. */
    private fun save(file: File, bytes: ByteArray) {
        try {
            // A cover's file is always inside the cache directory, so it has a parent.
            val dir = file.parentFile
            dir.mkdirs()
            file.writeBytes(bytes)
            dir.listFiles()?.let { kept -> kept.sortedByDescending(File::lastModified).drop(MAX_ON_DISK).forEach(File::delete) }
        } catch (_: IOException) {
            // A cover that isn't kept is just downloaded again next time.
        }
    }

    private fun fileName(url: String): String =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    /** Covers are shown at most ~100dp, so large ones (Deezer's are 1000px) are sampled down to save memory. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_SIZE_PX) sampleSize *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }

    private companion object {
        /** Room for the result, Recent and a screenful or two of a playlist's songs. */
        const val MAX_CACHED_BYTES = 24 * 1024 * 1024
        const val BYTES_PER_PIXEL = 4
        const val MAX_ON_DISK = 600
        const val MAX_COLORS = 200
        const val MAX_SIZE_PX = 400
    }
}
