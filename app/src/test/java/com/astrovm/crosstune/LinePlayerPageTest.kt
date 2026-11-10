package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page the small YouTube player is in: what it's told about the line, and which video it loads. */
class LinePlayerPageTest {
    @Test
    fun theCallSaysWhereTheLineIsInSecondsTheSongsLengthAndWhetherToPlay() {
        assertEquals("line(10.0, 15.5, 200000, true);", LinePlayerPage.call(10_000, 15_500, 200_000, play = true))
        // A length that isn't known is 0, which the page takes as nothing to check.
        assertEquals("line(0.5, 1.0, 0, false);", LinePlayerPage.call(500, 1_000, null, play = false))
    }

    @Test
    fun thePageLoadsOnlyTheVideoAsksForAndMakesTheFirstCallOnceItCan() {
        val html = LinePlayerPage.html("abc_-123XYZ", "line(1.0, 2.0, 0, true);")
        assertTrue(html.contains("videoId: 'abc_-123XYZ'"))
        assertTrue(html.contains("https://www.youtube.com/iframe_api"))
        assertTrue(html.contains("line(1.0, 2.0, 0, true);"))
        // The player shows no controls, and YouTube asks for nothing it can't be given.
        assertTrue(html.contains("controls: 0"))
        assertTrue(html.contains("setInterval(watch, ${LinePlayerPage.POLL_MS})"))
        assertTrue(html.contains("Math.abs(player.getDuration() * 1000 - want.expected) > ${LinePlayerPage.SAME_LENGTH_MS}"))
    }

    @Test
    fun anIdWithAnythingButLettersDigitsAndDashesIsCleanedSoItCantBreakOutOfThePage() {
        val html = LinePlayerPage.html("a');alert(1);//", "")
        assertFalse(html.contains("alert(1);//"))
        assertTrue(html.contains("videoId: 'aalert1'"))
    }

    @Test
    fun theSoundIsHeldBackUntilTheVideoIsKnownToBeTheSong() {
        val html = LinePlayerPage.html("abc", "")
        // Muted while its length isn't checked, and only let out when it matches.
        assertTrue(html.contains("if (!checked) player.mute();"))
        assertTrue(html.contains("player.unMute();"))
        assertTrue(html.contains("Line.failed()"))
        assertTrue(html.contains("Line.ended()"))
    }

    @Test
    fun theBaseAddressIsNotYouTubesOwnWhichRefusesToPlayInAnApp() {
        assertEquals("https://crosstune.cc", LinePlayerPage.BASE_URL)
    }
}
