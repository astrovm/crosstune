package com.astrovm.crosstune

import android.app.Instrumentation
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.os.Bundle
import android.os.SystemClock
import java.io.File
import java.net.URI

/** Times the real bitmap-cover reader on Android, with a made-up cover and no network or account. */
class CoverEncodingCheck : Instrumentation() {
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        start()
    }

    override fun onStart() {
        try {
            val bitmap = Bitmap.createBitmap(1_024, 1_024, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(1_024 * 1_024) { i ->
                0xff000000.toInt() or (((i * 31) and 255) shl 16) or (((i / 1_024) and 255) shl 8) or (i and 255)
            }
            bitmap.setPixels(pixels, 0, 1_024, 0, 0, 1_024, 1_024)
            val metadata = MediaMetadata.Builder().putBitmap(MediaMetadata.METADATA_KEY_ART, bitmap).build()
            val playback = MediaSessionPlayback(targetContext)
            val method = MediaSessionPlayback::class.java.getDeclaredMethod("ownCover", MediaMetadata::class.java).apply { isAccessible = true }
            var cover: String? = null
            fun read(): Long {
                var took = 0L
                runOnMainSync {
                    val start = System.nanoTime()
                    cover = method.invoke(playback, metadata) as? String
                    took = System.nanoTime() - start
                }
                return took
            }
            val first = read()
            val pending = LongArray(30) { read() }
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (cover == null && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(10)
                read()
            }
            check(cover?.let { File(URI(it)).isFile } == true) { "The cover was not saved" }
            val kept = LongArray(100) { read() }.sorted()
            bitmap.recycle()
            finish(0, Bundle().apply {
                putString("dimensions", "1024x1024")
                putLong("first_main_ns", first)
                putLong("pending_main_max_ns", pending.max())
                putLong("kept_main_median_ns", kept[kept.size / 2])
                putLong("kept_main_max_ns", kept.last())
                putInt("kept_reads", kept.size)
                putString("cover", cover?.substringAfterLast('/'))
            })
        } catch (error: Exception) {
            finish(1, Bundle().apply { putString("error", error.toString()) })
        }
    }
}
