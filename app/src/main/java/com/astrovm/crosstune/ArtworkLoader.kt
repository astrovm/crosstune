package com.astrovm.crosstune

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.coroutines.executeAsync
import java.io.IOException

/**
 * Downloads cover art with the app's HTTP client and keeps the last few in memory.
 * Artwork is decoration, so any failure just means no image.
 */
internal class ArtworkLoader(
    private val client: OkHttpClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    private val cache = LruCache<String, ImageBitmap>(MAX_CACHED)

    suspend fun load(url: String): ImageBitmap? {
        cache[url]?.let { return it }
        val httpUrl = url.toHttpUrlOrNull() ?: return null
        val request = Request.Builder().url(httpUrl).get().build()
        val bitmap = try {
            client.newCall(request).executeAsync().use { response ->
                if (!response.isSuccessful) return null
                withContext(ioDispatcher) { decode(response.body.bytes()) }
            }
        } catch (_: IOException) {
            null
        } ?: return null
        return bitmap.asImageBitmap().also { cache.put(url, it) }
    }

    /** Covers are shown at most ~100dp, so large ones (Deezer's are 1000px) are sampled down to save memory. */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sampleSize * 2) >= MAX_SIZE_PX) sampleSize *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
    }

    private companion object {
        /** Room for the result plus a full history list. */
        const val MAX_CACHED = 32
        const val MAX_SIZE_PX = 400
    }
}
