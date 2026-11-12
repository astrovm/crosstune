package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Test

/** A title with tags cut off its end reads as the text it stands for, whatever part of it is asked for. */
class KeptTest {
    @Test
    fun aKeptTextReadsAsTheTextItStandsFor() {
        val kept = Kept("Song (Live) (Remix)", 11, "\n")
        assertEquals("Song (Live)\n", kept.toString())
        assertEquals(12, kept.length)
        assertEquals('\n', kept[11])
        // Within what's kept of the title, within what follows, and across both.
        assertEquals("Song", kept.subSequence(0, 4))
        assertEquals("\n", kept.subSequence(11, 12))
        assertEquals("Live)\n", kept.subSequence(6, 12))
    }

    @Test
    fun aTagCutOffKeepsWhatFollowedIt() {
        val kept = Kept("Song (Live) (Remix)", 11, "\n")
        assertEquals("Song\n", kept.without(4..10).toString())
        assertEquals("Song \n", kept.without(5..10).toString())
        assertEquals("Song (Live)", kept.without(11..11).toString())
    }

    @Test
    fun spacesAreTrimmedAsATextsTrimTakesThem() {
        assertEquals("Song", Kept("Song \u3000 (Live)", 7, "").trimmed().toString())
        assertEquals("Song", Kept("Song  ", 5, "\n ").trimmed().toString())
        assertEquals("Song  x", Kept("Song  ", 5, " x ").trimmed().toString())
        // The spaces it starts with go only once nothing of the title is left before them.
        assertEquals("x", Kept("   ", 0, " x ").trimmed().toString())
        assertEquals("", Kept("   ", 3, "").trimmed().toString())
    }
}
