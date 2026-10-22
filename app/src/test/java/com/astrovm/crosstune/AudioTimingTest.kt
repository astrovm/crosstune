package com.astrovm.crosstune

import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

class AudioTimingTest {
    private fun points(vararg shifts: Long) = shifts.mapIndexed { index, shift -> SyncPoint(index * 10_000L, index * 10_000L + shift) }

    @Test fun separateHearingsAgreeBeforeAConstantShiftIsConfirmed() {
        assertTrue(AudioTiming.confirmed(emptyList()).isEmpty())
        assertTrue(AudioTiming.confirmed(points(10_000, 10_000)).isEmpty())
        assertTrue(AudioTiming.confirmed(listOf(SyncPoint(0, 10_000), SyncPoint(40_000, 50_000))).isEmpty())
        assertEquals(listOf(SyncPoint(0, 10_000)), AudioTiming.confirmed(points(10_000, 10_200, 9_900)))
        assertEquals(listOf(SyncPoint(0, 10_000)), AudioTiming.confirmed(points(10_000, 10_200, 9_900, 40_000)))
        assertTrue(AudioTiming.confirmed(listOf(SyncPoint(0, 1), SyncPoint(100, 101), SyncPoint(200, 201))).isEmpty())
        assertTrue(AudioTiming.confirmed(List(4) { SyncPoint(0, 10_000) }).isEmpty())
        assertTrue(AudioTiming.confirmed(listOf(SyncPoint(0, 0), SyncPoint(1_000, 5_000), SyncPoint(2_000, 10_000), SyncPoint(9_000, 30_000))).isEmpty())
    }

    @Test fun steadyDriftIsDistinctFromUnrelatedOffsets() {
        val drift = listOf(SyncPoint(0, 5_000), SyncPoint(20_000, 27_000), SyncPoint(40_000, 49_000))
        assertEquals(listOf(drift.first(), drift.last()), AudioTiming.confirmed(drift))
        assertTrue(AudioTiming.confirmed(points(0, 5_000, 10_000, 15_000)).isEmpty())
        assertTrue(AudioTiming.confirmed(listOf(drift.first(), SyncPoint(20_000, 15_000), drift.last())).isEmpty())
        assertTrue(AudioTiming.confirmed(points(0, 2_000, 4_000)).isEmpty())
        val falling = listOf(SyncPoint(0, 5_000), SyncPoint(20_000, 23_000), SyncPoint(40_000, 41_000))
        assertEquals(listOf(falling.first(), falling.last()), AudioTiming.confirmed(falling))
    }

    @Test fun generatedNoisyOffsetsStayNearTheActualShift() {
        val random = Random(847)
        repeat(300) {
            val offset = random.nextLong(-20_000, 20_000)
            val samples = (0..6).map { SyncPoint(it * 10_000L, it * 10_000L + offset + random.nextLong(-500, 501)) }
            val result = AudioTiming.confirmed(samples.shuffled(random)).single()
            assertTrue(kotlin.math.abs(result.songMs - result.lineMs - offset) <= 500)
        }
    }
}
