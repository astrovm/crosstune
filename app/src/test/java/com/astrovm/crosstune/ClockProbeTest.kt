package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

class ClockProbeTest {
    @Test
    fun aCancelledObservationStillRemovesItsCallbackAndReleasesItsSession() = runTest {
        var unregistered = false
        var released = false
        val follower = Job()
        val observation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                finishClockProbe({ follower.cancelAndJoin() }, { unregistered = true }, { released = true })
            }
        }
        observation.cancelAndJoin()
        assertTrue(follower.isCancelled)
        assertTrue(unregistered)
        assertTrue(released)
    }

    @Test
    fun recordsCanArriveAfterTheOtherControllersCallback() = runTest {
        var reads = 0
        val records = List(4) { it }
        assertEquals(records, awaitClockRecords(4) { reads++; if (reads < 3) emptyList() else records })
        assertEquals(3, reads)
    }

    @Test
    fun aMissingRecordHasABoundedWait() = runTest {
        var reads = 0
        try {
            awaitClockRecords<Int>(4) { reads++; emptyList() }
            fail("The missing report must time out")
        } catch (expected: TimeoutCancellationException) {
            assertTrue(reads > 1)
        }
    }

    @Test
    fun aFailedUnregisterStillReleasesTheSessionAndKeepsTheFailure() = runTest {
        var released = false
        val failure = IllegalStateException("Fixture unregister failed")
        try {
            finishClockProbe({}, { throw failure }, { released = true })
            fail("The callback failure must stay visible")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual.cause ?: actual)
        }
        assertTrue(released)
    }

    @Test
    fun aFailedJoinStillRemovesTheCallbackAndReleasesTheSession() = runTest {
        var unregistered = false
        var released = false
        val failure = IllegalStateException("Fixture join failed")
        try {
            finishClockProbe({ throw failure }, { unregistered = true }, { released = true })
            fail("The follower failure must stay visible")
        } catch (actual: IllegalStateException) {
            assertSame(failure, actual.cause ?: actual)
        }
        assertTrue(unregistered)
        assertTrue(released)
    }
}
