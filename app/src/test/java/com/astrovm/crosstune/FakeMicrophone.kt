package com.astrovm.crosstune

import java.util.concurrent.LinkedBlockingQueue
import kotlin.math.min

/** A microphone that hears only what a test plays into it, and waits for more until it's ended. */
internal class FakeMicrophone(private val works: Boolean = true) : Microphone {
    private val sound = LinkedBlockingQueue<ShortArray>()

    @Volatile
    var opened = 0
        private set

    @Volatile
    private var closes = 0

    /** Every recording opened has been closed. */
    val closed get() = opened > 0 && closes == opened

    /** Something to hear for [seconds]; any sound does, nothing Shazam would know. */
    fun play(seconds: Double) = sound.put(ShortArray((ShazamSignature.SAMPLE_RATE * seconds).toInt()) { (it % 100).toShort() })

    /** The microphone stops giving sound. */
    fun end() = sound.put(ShortArray(0))

    override fun open(): Recording? {
        if (!works) return null
        opened++
        return object : Recording {
            private var chunk = ShortArray(0)
            private var at = 0

            override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
                if (at == chunk.size) {
                    chunk = sound.take()
                    at = 0
                    if (chunk.isEmpty()) return -1
                }
                val read = min(count, chunk.size - at)
                chunk.copyInto(buffer, offset, at, at + read)
                at += read
                return read
            }

            override fun close() {
                closes++
            }
        }
    }
}
