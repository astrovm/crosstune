package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.abs

/** Which sound a song's heard through: the clean one music apps play, or the visuals' tap. */
class CleanFirstTest {
    /** Plays [sound] over and over, or gives nothing more once [ends]; how often it's opened and closed. */
    private class Sound(private val sound: Short, private val ends: Boolean = false, private val atMost: Int = Int.MAX_VALUE) : Microphone {
        var opened = 0
        var closed = 0
        var read = 0

        override fun open() = object : Recording {
            override fun read(buffer: ShortArray, offset: Int, count: Int): Int {
                if (ends) return 0
                // Never more than there's room for, as Android's AudioRecord throws then.
                check(offset + count <= buffer.size) { "Asked for $count at $offset of ${buffer.size}" }
                // All that's asked for, as Android gives it, waiting until it's played, unless it gives less.
                val given = minOf(count, atMost)
                buffer.fill(sound, offset, offset + given)
                read += given
                return given
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
        val recording = checkNotNull(CleanFirst(clean, tap).open())
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
        assertNotNull(CleanFirst(clean, tap).open())
        assertEquals(CleanFirst.PROBE_SAMPLES, clean.read)
        assertEquals(1, clean.opened)
        assertEquals(1, clean.closed)
        assertEquals(1, tap.opened)
    }

    @Test
    fun silenceThatComesInOddSizesIsStillHalfASecondNoMore() {
        // Less than a tenth at a time, so the last read has less room left than a tenth.
        val clean = Sound(0, atMost = 1000)
        assertNotNull(CleanFirst(clean, tap).open())
        assertEquals(CleanFirst.PROBE_SAMPLES, clean.read)
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

    /**
     * Issue #1100: Shazam listens while the singing still records, each through a recording of its own. On the
     * Samsung (Android 16), two playback captures at once both heard TIDAL, loudest 32767 during the overlap;
     * Spotify, which no playback capture hears even alone, was heard by both through the visuals' tap.
     */
    @Test
    fun shazamHearsWhatPlaysWhileTheSingingStillRecordsIt() {
        // What each hears: the clean sound, or, with none, the visuals' tap.
        for ((case, clean, loudest) in listOf(Triple("heard cleanly", Sound(3000), 3000), Triple("as Spotify plays", Sound(0), 100))) {
            val tap = Sound(100)
            val singing = checkNotNull(CleanFirst(clean, tap).open())
            val heard = ShortArray(ShazamSignature.SAMPLE_RATE / 10)
            singing.read(heard, 0, heard.size)
            // Opened while the singing's recording is still open, as listening along does.
            val shazam = checkNotNull(CleanFirst(clean, tap).open())
            assertEquals(case, heard.size, shazam.read(heard, 0, heard.size))
            assertEquals(case, loudest, heard.maxOf { abs(it.toInt()) })
            // The singing hears on meanwhile.
            heard.fill(0)
            assertEquals(case, heard.size, singing.read(heard, 0, heard.size))
            assertEquals(case, loudest, heard.maxOf { abs(it.toInt()) })
            shazam.close()
            singing.close()
            assertEquals(case, clean.opened, clean.closed)
            assertEquals(case, tap.opened, tap.closed)
        }
    }

    @Test
    fun withNeitherThereIsNothingToHear() {
        assertNull(CleanFirst(null, Microphone { null }).open())
        val recording = Sound(5).open()
        assertSame(recording, CleanFirst(Microphone { null }, Microphone { recording }).open())
    }
}
