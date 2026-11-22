package com.astrovm.crosstune

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class AudioTimingTest {
    private fun points(vararg shifts: Long) = shifts.mapIndexed { index, shift -> SyncPoint(index * 10_000L, index * 10_000L + shift) }

    @Test fun separateHearingsAgreeBeforeAConstantShiftIsConfirmed() {
        assertNull(AudioTiming.confirmedShift(emptyList()))
        assertNull(AudioTiming.confirmedShift(points(10_000, 10_000)))
        assertNull(AudioTiming.confirmedShift(listOf(SyncPoint(0, 10_000), SyncPoint(40_000, 50_000))))
        assertEquals(10_000L, AudioTiming.confirmedShift(points(10_000, 10_200, 9_900)))
        assertEquals(10_000L, AudioTiming.confirmedShift(points(10_000, 10_200, 9_900, 40_000)))
        assertNull(AudioTiming.confirmedShift(listOf(SyncPoint(0, 1), SyncPoint(100, 101), SyncPoint(200, 201))))
        assertNull(AudioTiming.confirmedShift(List(4) { SyncPoint(0, 10_000) }))
        assertNull(AudioTiming.confirmedShift(listOf(SyncPoint(0, 0), SyncPoint(1_000, 5_000), SyncPoint(2_000, 10_000), SyncPoint(9_000, 30_000))))
    }

    @Test fun linesThatDriftApartAreNoLead() {
        // Each line heard 2 s further off than the last: no one shift fits three of them within 1.5 s.
        assertNull(AudioTiming.confirmedShift(listOf(SyncPoint(0, 5_000), SyncPoint(20_000, 27_000), SyncPoint(40_000, 49_000))))
        assertNull(AudioTiming.confirmedShift(listOf(SyncPoint(0, 5_000), SyncPoint(20_000, 23_000), SyncPoint(40_000, 41_000))))
        assertNull(AudioTiming.confirmedShift(points(0, 5_000, 10_000, 15_000)))
        assertNull(AudioTiming.confirmedShift(points(0, 2_000, 4_000)))
    }

    @Test fun aShiftNeedsItsLinesEightSecondsApartAndWithinASecondAndAHalf() {
        assertEquals(5_000L, AudioTiming.confirmedShift(listOf(SyncPoint(0, 5_000), SyncPoint(4_000, 9_000), SyncPoint(8_000, 13_000))))
        assertNull(AudioTiming.confirmedShift(listOf(SyncPoint(0, 5_000), SyncPoint(4_000, 9_000), SyncPoint(7_999, 12_999))))
        // The median is 6,500: 5,000 is 1.5 s from it and counts, 4,999 is past it and leaves two lines.
        assertEquals(6_500L, AudioTiming.confirmedShift(listOf(SyncPoint(0, 5_000), SyncPoint(4_000, 10_500), SyncPoint(8_000, 14_500))))
        assertNull(AudioTiming.confirmedShift(listOf(SyncPoint(0, 4_999), SyncPoint(4_000, 10_500), SyncPoint(8_000, 14_500))))
    }

    @Test fun generatedNoisyOffsetsStayNearTheActualShift() {
        val random = Random(847)
        repeat(300) {
            val offset = random.nextLong(-20_000, 20_000)
            val samples = (0..6).map { SyncPoint(it * 10_000L, it * 10_000L + offset + random.nextLong(-500, 501)) }
            val result = AudioTiming.confirmedShift(samples.shuffled(random))
            assertTrue("$result for $offset", result != null && kotlin.math.abs(result - offset) <= 500)
        }
    }

    @Test fun threeAgreeingLinesThatAllSitCloseTogetherAreNotEnoughToConfirm() {
        // An odd one out far earlier leaves only the three together, within two seconds.
        val bunched = listOf(SyncPoint(0, 20_000), SyncPoint(10_000, 15_000), SyncPoint(11_000, 16_000), SyncPoint(12_000, 17_000))
        assertNull(AudioTiming.confirmedShift(bunched))
    }
}
