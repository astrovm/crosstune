package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** Which sound a song's heard through: the clean one music apps play, or the visuals' tap. */
class CleanFirstTest {
    /** Plays [sound] over and over, or gives nothing more once [ends]; how often it's opened and closed. */
    private class Sound(private val sound: Short, private val ends: Boolean = false) : Microphone {
        var opened = 0
        var closed = 0
        var read = 0

        override fun open() = object : Recording {
            override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
                if (ends) return 0
                // All that's asked for, as Android gives it, waiting until it's played.
                buffer.fill(sound, offset, offset + count)
                read += count
                return count
            }

            override fun close() {
                closed++
            }
        }.also { opened++ }
    }

    private val tap = Sound(100)

    @Test
    fun soundHeardCleanlyIsHeardFromNowOnTheVisualsTapLeftAlone() {
        val clean = Sound(3)
        val recording = CleanFirst(clean, tap).open()!!
        // Its first tenth of a second said there's sound, without waiting out the rest; what's heard starts after it, in a recording of its own.
        assertEquals(ShazamSignature.SAMPLE_RATE / 10, clean.read)
        assertEquals(2, clean.opened)
        assertEquals(1, clean.closed)
        assertEquals(0, tap.opened)
        recording.close()
        assertEquals(2, clean.closed)
    }

    @Test
    fun silenceHeardCleanlyForHalfASecondIsHeardThroughTheVisualsTap() {
        // As Spotify plays, and any app at volume 0, but for a little noise.
        val clean = Sound(-2)
        CleanFirst(clean, tap).open()!!
        assertEquals(CleanFirst.PROBE_SAMPLES, clean.read)
        assertEquals(1, clean.opened)
        assertEquals(1, clean.closed)
        assertEquals(1, tap.opened)
    }

    @Test
    fun cleanSoundThatEndsOrCantBeHadIsHeardThroughTheVisualsTap() {
        val ends = Sound(100, ends = true)
        CleanFirst(ends, tap).open()
        assertEquals(1, ends.closed)
        CleanFirst(Microphone { null }, tap).open()
        CleanFirst(null, tap).open()
        assertEquals(3, tap.opened)

        // Heard at first, then gone before it's opened again.
        var opens = 0
        val gone = Microphone { if (opens++ == 0) Sound(100).open() else null }
        CleanFirst(gone, tap).open()
        assertEquals(4, tap.opened)
    }

    @Test
    fun withNeitherThereIsNothingToHear() {
        assertNull(CleanFirst(null, Microphone { null }).open())
        val recording = Sound(5).open()
        assertSame(recording, CleanFirst(Microphone { null }, Microphone { recording }).open())
    }
}
