package com.astrovm.crosstune

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeMusicAdEndTest {
    @Test
    fun sixCapturedEndsConfirmTheLaterOriginEvenWhenTheGetterHasAlreadyPassedBuffering() {
        // Samsung session + 16 kHz playback capture, Nov 26, 2026. Keep the reported positions,
        // not the audio positions: the later start was closer to the audio in all six transitions.
        val ends = listOf(
            Capture(426, 18_436, 22_778, 1_675_199_163, 1_675_217_524, 55, 1_675_218_181, 47),
            Capture(426, 22_778, 227_601, 1_675_218_181, 1_675_241_052, 46, 1_675_241_266, 9),
            Capture(451, 37_848, 224_235, 1_675_509_528, 1_675_547_302, 65, 1_675_547_881, 16),
            Capture(451, 224_235, 12_074_051, 1_675_547_881, 1_675_772_235, 81, 1_675_772_638, 15),
            Capture(502, 13_056, 30_069, 1_676_079_391, 1_676_092_357, 72, 1_676_092_865, 6),
            Capture(502, 30_069, 366_898, 1_676_092_865, 1_676_123_117, 44, 1_676_123_353, 22),
        )
        for ((id, oldLength, newLength, oldStart, first, metadataAfter, later, position) in ends) {
            val history = YouTubeMusicAdEnd()
            val old = item(oldLength, id)
            val next = item(newLength, id)
            assertNull(history.heard(old, PlaybackClock(0, oldStart), oldStart, oldStart))
            assertNull(history.heard(old, PlaybackClock(0, first), first, first))
            val metadataAt = first + metadataAfter
            assertNull(history.heard(next, PlaybackClock(metadataAfter, metadataAt), metadataAt, metadataAt))
            assertTrue(history.waitsFor(next))
            // A redelivery of the first origin with a projected getter is still the same start.
            assertNull(history.heard(next, PlaybackClock(metadataAfter + 1, metadataAt + 1), metadataAt + 1, metadataAt + 1))
            val readAt = later + position
            assertEquals(old, history.heard(next, PlaybackClock(position, readAt), readAt, readAt))
            assertFalse(history.waitsFor(next))
            assertNull(history.heard(next, PlaybackClock(position + 1, readAt + 1), readAt + 1, readAt + 1))
        }
    }

    @Test
    fun aLateObserverWithOnlyTheFirstOriginCannotDistinguishACorrectionFromAnAd() {
        val history = YouTubeMusicAdEnd()
        val old = item(224_235)
        val next = item(12_074_051)
        // Without the earlier 224 s of play, a correction and an ad's end give this exact same prefix.
        val start = 1_675_772_235L
        assertNull(history.heard(old, PlaybackClock(0, start), start, start))
        assertNull(history.heard(next, PlaybackClock(81, start + 81), start + 81, start + 81))
        assertNull(history.heard(next, PlaybackClock(130, start + 130), start + 130, start + 130))
        assertFalse(history.waitsFor(next))
        assertNull(history.heard(next, null, start + 400, start + 400))
        assertNull(history.heard(next, PlaybackClock(0, start + 403), start + 403, start + 403))
    }

    @Test
    fun theResetCanComeBeforeOrAfterTheLength() {
        val old = item(20_000)
        val next = item(240_000)
        val start = 50_000L
        val oldClock = PlaybackClock(0, start)
        val end = start + old.length
        val load = PlaybackClock(0, end, playing = false)
        for (lengthFirst in listOf(false, true)) {
            val history = YouTubeMusicAdEnd()
            assertNull(history.heard(old, oldClock, start, start))
            if (lengthFirst) {
                assertNull(history.heard(next, oldClock, end, end))
                assertFalse(history.waitsFor(next))
            } else {
                assertNull(history.heard(old, load, end, end))
            }
            assertNull(history.heard(next, load, end, end))
            assertTrue(history.waitsFor(next))
            // This start can have the same update time as BUFFERING.
            assertEquals(old, history.heard(next, PlaybackClock(0, end), end, end))
        }
    }

    @Test
    fun timeSpentPausedDoesNotCountAsPlaybackReachingTheOldEnd() {
        val old = item(20_000)
        val next = item(240_000)
        val start = 50_000L
        val end = start + old.length
        val history = YouTubeMusicAdEnd()
        assertNull(history.heard(old, PlaybackClock(0, start), start, start))
        assertNull(history.heard(old, null, start + 500, start + 500))
        assertNull(history.heard(old, PlaybackClock(500, end), end, end))
        assertNull(history.heard(next, PlaybackClock(501, end + 1), end + 1, end + 1))
        assertFalse(history.waitsFor(next))
    }

    @Test
    fun lateMetadataCanConfirmAStartAlreadyReadFromTheGetter() {
        val old = item(30_069)
        val next = item(366_898)
        val start = 50_000L
        val end = start + old.length
        val history = YouTubeMusicAdEnd()
        assertNull(history.heard(old, PlaybackClock(0, start), start, start))
        assertNull(history.heard(old, PlaybackClock(0, end), end, end))
        assertNull(history.heard(old, PlaybackClock(0, end + 236), end + 236, end + 236))
        assertEquals(old, history.heard(next, PlaybackClock(0, end + 236), end + 240, end + 240))
        assertFalse(history.waitsFor(next))
    }

    @Test
    fun aCandidateThatNeverStartsDoesNotRemoveAnOldLength() {
        val old = item(20_000)
        val next = item(240_000)
        val start = 50_000L
        val end = start + old.length
        val history = YouTubeMusicAdEnd()
        assertNull(history.heard(old, PlaybackClock(0, start), start, start))
        assertNull(history.heard(old, PlaybackClock(0, end), end, end))
        assertNull(history.heard(next, PlaybackClock(0, end), end, end))
        assertTrue(history.waitsFor(next))
        history.expire(end + AD_END_MS - 1)
        assertTrue(history.waitsFor(next))
        history.expire(end + AD_END_MS)
        assertFalse(history.waitsFor(next))
        assertNull(history.heard(next, PlaybackClock(0, end + AD_END_MS), end + AD_END_MS, end + AD_END_MS))
    }

    @Test
    fun anAdWaitDoesNotMoveFromAnUnknownQueueItemToAClaimedOne() {
        val old = item(20_000).copy(queueId = null)
        val next = old.copy(length = 240_000)
        val start = 50_000L
        val end = start + old.length
        val history = YouTubeMusicAdEnd()
        assertNull(history.heard(old, PlaybackClock(0, start), start, start))
        assertNull(history.heard(old, PlaybackClock(0, end), end, end))
        assertNull(history.heard(next, PlaybackClock(0, end), end, end))
        assertTrue(history.waitsFor(next))
        assertNull(history.heard(next.copy(queueId = 451), PlaybackClock(0, end + 236), end + 236, end + 236))
        assertFalse(history.waitsFor(next))
    }

    @Test
    fun aResetCannotCrossQueueItemsThroughAnUnknownId() {
        val history = YouTubeMusicAdEnd()
        val old = item(20_000, 26)
        val next = item(40_000, 27)
        history.heard(old, PlaybackClock(0, 10_000), 10_000, 10_000)
        history.heard(old.copy(queueId = null), PlaybackClock(0, 30_000), 30_000, 30_000)
        history.heard(next, PlaybackClock(0, 30_000), 30_020, 30_020)
        assertNull(history.heard(next, PlaybackClock(0, 30_500), 30_500, 30_500))
    }

    @Test
    fun aRateChangeAtTheCurrentPositionCannotConfirmARestart() {
        val pending = pendingRestart()
        assertNull(pending.heard(item(40_000), PlaybackClock(400, 30_400, speed = 2f), 30_400, 30_400))
    }

    @Test
    fun aLateRefreshMayOnlyConfirmAStartWithinTheWindow() {
        val late = pendingRestart()
        assertNull(late.heard(item(40_000), PlaybackClock(100, 31_900), 31_900, 31_900))
        val onTime = pendingRestart()
        assertEquals(item(20_000), onTime.heard(item(40_000), PlaybackClock(500, 31_900), 31_900, 31_900))
    }

    @Test
    fun aResetWithAnUnknownQueueIdStillEndsTheSameItemsAd() {
        // Issue #1291: item 26 plays to its 20 s end; its reset says no queue ID, then its length changes, still on 26.
        val history = YouTubeMusicAdEnd()
        val old = item(20_000, 26)
        val next = item(40_000, 26)
        assertNull(history.heard(old, PlaybackClock(0, 10_000), 10_000, 10_000))
        assertNull(history.heard(old.copy(queueId = null), PlaybackClock(0, 30_000), 30_000, 30_000))
        assertNull(history.heard(next, PlaybackClock(0, 30_000), 30_020, 30_020))
        assertTrue(history.waitsFor(next))
        // The second start, half a second after the reset, confirms it, as the one with the same ID throughout would.
        assertEquals(old, history.heard(next, PlaybackClock(0, 30_500), 30_500, 30_500))
    }

    @Test
    fun aLengthChangeExactlyAClockStepAheadStillContinuesThePlay() {
        // Issue #1291: the new length comes at the old end, said exactly SAME_CLOCK_MS ahead of where the old play got to.
        val history = YouTubeMusicAdEnd()
        val old = item(20_000)
        val next = item(40_000)
        assertNull(history.heard(old, PlaybackClock(0, 10_000), 10_000, 10_000))
        assertNull(history.heard(next, PlaybackClock(20_000 + SAME_CLOCK_MS, 30_000), 30_000, 30_000))
        assertNull(history.heard(next, PlaybackClock(0, 30_100), 30_100, 30_100))
        assertTrue(history.waitsFor(next))
        assertEquals(old, history.heard(next, PlaybackClock(0, 30_500), 30_500, 30_500))
    }

    @Test
    fun aPositionAnAdsLengthIntoTheNewItemIsNoReset() {
        // Issue #1291: said 1.5 s in, its start projected back to the old end, it's past where a reset could be.
        val history = YouTubeMusicAdEnd()
        val old = item(20_000)
        val next = item(40_000)
        assertNull(history.heard(old, PlaybackClock(0, 10_000), 10_000, 10_000))
        assertNull(history.heard(next, PlaybackClock(AD_END_MS, 30_000 + AD_END_MS), 30_000 + AD_END_MS, 30_000 + AD_END_MS))
        assertFalse(history.waitsFor(next))
    }

    private fun pendingRestart() = YouTubeMusicAdEnd().apply {
        heard(item(20_000), PlaybackClock(0, 10_000), 10_000, 10_000)
        heard(item(20_000), PlaybackClock(0, 30_000), 30_000, 30_000)
        heard(item(40_000), PlaybackClock(0, 30_000), 30_000, 30_000)
    }

    private fun item(length: Long, id: Long = 451) = YouTubeMusicAdEnd.Item("Same title", "Same artist", length, id)

    private data class Capture(
        val id: Long,
        val oldLength: Long,
        val newLength: Long,
        val oldStart: Long,
        val first: Long,
        val metadataAfter: Long,
        val later: Long,
        val position: Long,
    )
}
