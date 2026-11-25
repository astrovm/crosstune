package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubePlayEvidenceTest {
    private val evidence = YouTubePlayEvidence()
    private val music = "com.google.android.apps.youtube.music"
    private val id = "abcdefghijk"

    @Test
    fun onlyTheActiveExactRequestAndCurrentRawLengthCanConfirmAnAd() {
        assertNull(evidence.adDuration(music, id, 33_204))
        val first = evidence.start(music, id, null)
        assertNull(evidence.adDuration(music, id, 33_204))
        val mine = evidence.start(music, id, null)
        evidence.remember(first, 253_000)
        assertNull(evidence.adDuration(music, id, 33_204))
        evidence.remember(mine, 253_000)
        assertEquals(253_000L, evidence.adDuration(music, id, 60_046))
        assertEquals(253_000L, evidence.adDuration(music, id, MIN_SHORT_AD_MS))
        assertNull(evidence.adDuration(music, id, MIN_SHORT_AD_MS - 1))
        assertEquals(253_000L, evidence.adDuration(music, id, MEASURED_SHORT_AD_MS))
        assertNull(evidence.adDuration(music, id, MEASURED_SHORT_AD_MS + 1))
        assertNull(evidence.adDuration(music, id, 0))
        assertNull(evidence.adDuration(music, null, 33_204))
        assertNull(evidence.adDuration(music, "different-id", 33_204))
        assertNull(evidence.adDuration("com.google.android.youtube", id, 33_204))
        evidence.clear(first)
        assertEquals(253_000L, evidence.adDuration(music, id, 33_204))
        evidence.clear(mine)
        assertNull(evidence.adDuration(music, id, 33_204))
    }

    @Test
    fun aShortVideoIsNotAdEvidenceForAnEvenShorterReport() {
        val request = evidence.start(music, id, 81_000)
        assertNull(evidence.adDuration(music, id, 30_000))
        evidence.clear(request)
        val longestBeforeAdInference = evidence.start(music, id, AD_DURATION_HINT_MS)
        assertEquals(AD_DURATION_HINT_MS, evidence.adDuration(music, id, 30_000))
        evidence.clear(longestBeforeAdInference)
    }

    @Test
    fun diagnosticHashesMatchOnlyWithinTheSameProcessSalt() {
        val first = ClockItemHashes()
        val nextProcess = ClockItemHashes()
        val key = "Requested song"
        assertEquals(first.of(key), first.of(key))
        assertNotEquals(first.of(key), nextProcess.of(key))
        assertNotEquals(first.of(key), first.of("Different song"))
    }
}
