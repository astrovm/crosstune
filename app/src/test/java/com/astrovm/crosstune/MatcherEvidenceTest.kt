package com.astrovm.crosstune

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MatcherEvidenceTest {
    @Test
    fun untimedVideoNeedsTheSameTitleAndAnUntimedFirstVideo() {
        val known = setOf("song", "othername")
        assertTrue(sameVideoEvidence("song", "song", known, null, null, emptyList()))
        assertFalse(sameVideoEvidence("song", "song", known, 240_000, null, listOf(240_000)))
        assertFalse(sameVideoEvidence("song", "othername", known, null, null, emptyList()))
        assertFalse(sameVideoEvidence("song", "unknown", known, null, null, emptyList()))
    }

    @Test
    fun timedVideoMustFitBothTheFirstVideoAndTheSourceAtThreeSeconds() {
        val known = setOf("song")
        assertTrue(sameVideoEvidence("song", "song", known, 240_000, 243_000, listOf(240_000)))
        assertFalse(sameVideoEvidence("song", "song", known, 240_000, 243_001, listOf(240_000)))
        assertFalse(sameVideoEvidence("song", "song", known, null, 243_001, listOf(240_000)))
        assertTrue(sameVideoEvidence("song", "song", known, null, 243_001, emptyList()))
    }

    @Test
    fun deezerArtistNeedsTheSameNameOrAnIndependentAlias() = runBlocking {
        var calls = 0
        suspend fun aliases(): Set<String>? { calls++; return setOf("Kenshi Yonezu", "米津玄師") }
        assertEquals(true, deezerArtistMatches("Artist", "Artist", ::aliases))
        assertEquals(0, calls)
        assertEquals(true, deezerArtistMatches("米津玄師", "Kenshi Yonezu", ::aliases))
        assertEquals(1, calls)
        assertEquals(false, deezerArtistMatches("米津玄師", "Other", ::aliases))
        assertNull(deezerArtistMatches("米津玄師", "Kenshi Yonezu") { null })
        assertEquals(false, deezerArtistMatches("", "Artist", ::aliases))
        assertEquals(false, deezerArtistMatches("Artist", "", ::aliases))
    }
}
