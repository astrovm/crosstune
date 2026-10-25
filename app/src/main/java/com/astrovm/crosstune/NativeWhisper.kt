package com.astrovm.crosstune

import java.io.Closeable
import java.io.File

/** whisper.cpp on the phone, through `libsungwords`: hears the words sung, with [model]. */
internal class NativeWhisper private constructor(private var handle: Long) : WordHearing, Closeable {
    override fun words(samples: FloatArray, language: String?): List<HeardWord> =
        HeardWords.parse(nativeWords(handle, samples, language, THREADS))

    override fun close() {
        if (handle != 0L) nativeClose(handle)
        handle = 0L
    }

    private external fun nativeWords(instance: Long, samples: FloatArray, language: String?, threads: Int): ByteArray
    private external fun nativeClose(instance: Long)

    companion object {
        /** Half the phone's cores, up to four: the rest keep the music and the words going. */
        private val THREADS = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, 4)

        /** The model in [model] loaded, or null when it won't load, or the library isn't in this build. */
        fun open(model: File): NativeWhisper? = try {
            System.loadLibrary("sungwords")
            nativeOpen(model.path).takeIf { it != 0L }?.let(::NativeWhisper)
        } catch (_: UnsatisfiedLinkError) {
            null
        }

        @JvmStatic
        private external fun nativeOpen(model: String): Long
    }
}
