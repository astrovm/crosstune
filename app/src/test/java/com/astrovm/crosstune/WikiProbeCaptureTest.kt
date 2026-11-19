package com.astrovm.crosstune

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class WikiProbeCaptureTest {
    @Test fun aDecisionAndItsCountsAreSealedTogether() {
        val capture = WikiProbeCapture()
        capture.record { mapOf("type" to "settings_before_load") }
        val answer = capture.resource(1, { "fixture" }, { mapOf("decision" to it) }) { "not_intercepted" }
        val sealed = capture.close()
        assertEquals("not_intercepted", answer)
        assertEquals(2, sealed.events.size)
        assertEquals(mapOf(1 to 1), sealed.activities)
        assertEquals(mapOf("fixture" to 1), sealed.hosts)
        assertTrue(sealed.complete)
        capture.record { mapOf("late" to true) }
        assertSame(sealed, capture.close())
        assertEquals(2, sealed.events.size)
    }

    @Test fun aCallbackAcrossCloseCannotClaimACompleteLogOrChangeTheNextLookup() {
        val capture = WikiProbeCapture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val request = executor.submit<String> {
                capture.resource(2, { "fixture" }, { mapOf("decision" to it) }) {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    "not_intercepted"
                }
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val sealed = capture.close()
            assertFalse(sealed.complete)
            assertEquals(1, sealed.pending)
            assertTrue(sealed.events.isEmpty())
            val next = WikiProbeCapture()
            release.countDown()
            assertEquals("not_intercepted", request.get(5, TimeUnit.SECONDS))
            assertSame(sealed, capture.close())
            assertEquals(1, sealed.pending)
            assertTrue(next.close().complete)
            assertTrue(next.close().events.isEmpty())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun exceedingTheBoundIsReportedInTheSameSnapshot() {
        val capture = WikiProbeCapture(limit = 1)
        val mutable = mutableMapOf<String, Any>("first" to true)
        capture.record { mutable }
        mutable["first"] = false
        capture.record { mapOf("second" to true) }
        val sealed = capture.close()
        assertEquals(listOf(mapOf("first" to true)), sealed.events)
        assertTrue(sealed.truncated)
        assertFalse(sealed.complete)
    }

    @Test fun aFailedDecisionCannotClaimACompleteLog() {
        val capture = WikiProbeCapture()
        val failure = runCatching {
            capture.resource<Unit>(1, { "fixture" }, { emptyMap() }) { error("fixture") }
        }
        assertTrue(failure.isFailure)
        assertTrue(capture.close().failed)
        assertFalse(capture.close().complete)
    }

    @Test fun callbacksAfterCloseAreDelegatedWithoutChangingTheObservationWindow() {
        val capture = WikiProbeCapture()
        val sealed = capture.close()
        var called = false
        capture.resource(1, { "fixture" }, { mapOf("decision" to it) }) { called = true; "not_intercepted" }
        assertTrue(called)
        assertTrue(sealed.complete)
        assertTrue(sealed.events.isEmpty())
        assertTrue(sealed.activities.isEmpty())
    }
    @Test fun anErrorEventFailureDoesNotReplaceTheProductionCallback() {
        val capture = WikiProbeCapture()
        var forwarded = false
        capture.record({ error("fixture metadata") }) { forwarded = true }
        assertTrue(forwarded)
        val sealed = capture.close()
        assertTrue(sealed.failed)
        assertEquals(0, sealed.pending)
        assertFalse(sealed.complete)
    }

    @Test fun anErrorCallbackAfterCloseStillReachesTheProductionClient() {
        val capture = WikiProbeCapture()
        val sealed = capture.close()
        var forwarded = false
        capture.record({ error("closed metadata must not be read") }) { forwarded = true }
        assertTrue(forwarded)
        assertSame(sealed, capture.close())
        assertTrue(sealed.complete)
        assertTrue(sealed.events.isEmpty())
    }

    @Test fun aFailedHostDoesNotReplaceTheProductionResponse() {
        val capture = WikiProbeCapture()
        val response = capture.resource(1, { error("fixture host") }, { mapOf("decision" to it) }) { "fixture response" }
        assertEquals("fixture response", response)
        assertTrue(capture.close().failed)
        assertFalse(capture.close().complete)
    }

    @Test fun aFailedEventDoesNotReplaceTheProductionResponse() {
        val capture = WikiProbeCapture()
        val response = capture.resource(1, { "fixture" }, { error("fixture event") }) { "fixture response" }
        assertEquals("fixture response", response)
        assertTrue(capture.close().failed)
        assertFalse(capture.close().complete)
    }

    @Test fun aProductionErrorKeepsItsIdentity() {
        val capture = WikiProbeCapture()
        val failure = IllegalStateException("fixture production failure")
        val result = runCatching { capture.record({ mapOf("type" to "http_error") }) { throw failure } }
        assertSame(failure, result.exceptionOrNull())
        assertTrue(capture.close().failed)
    }

    @Test fun anErrorCallbackIsCountedBeforeItsEventIsBuilt() {
        whileBuilding { capture, hold -> capture.record { hold(); mapOf("type" to "http_error") } }
    }

    @Test fun aResourceCallbackIsCountedBeforeItsHostIsRead() {
        whileBuilding { capture, hold ->
            val answer = capture.resource(1, { hold(); "fixture" }, { mapOf("decision" to it) }) { "not_intercepted" }
            assertEquals("not_intercepted", answer)
        }
    }

    @Test fun aResourceReturningAfterCloseDoesNotBuildDiscardedMetadata() {
        afterClose { capture, forward, metadata ->
            val response = capture.resource(1, { "fixture" }, { metadata() }) { forward(); "fixture response" }
            assertEquals("fixture response", response)
        }
    }

    @Test fun anErrorReturningAfterCloseDoesNotBuildDiscardedMetadata() {
        afterClose { capture, forward, metadata -> capture.record(metadata, forward) }
    }

    private fun afterClose(callback: (WikiProbeCapture, () -> Unit, () -> Map<String, Any>) -> Unit) {
        val capture = WikiProbeCapture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val metadataRead = AtomicBoolean(false)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit {
                callback(capture, {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                }, { metadataRead.set(true); mapOf("type" to "fixture") })
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val sealed = capture.close()
            assertFalse(sealed.complete)
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            assertFalse(metadataRead.get())
            assertSame(sealed, capture.close())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    private fun whileBuilding(callback: (WikiProbeCapture, () -> Unit) -> Unit) {
        val capture = WikiProbeCapture()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val pending = executor.submit { callback(capture) {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            } }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val sealed = capture.close()
            assertFalse(sealed.complete)
            assertEquals(1, sealed.pending)
            release.countDown()
            pending.get(5, TimeUnit.SECONDS)
            assertSame(sealed, capture.close())
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

}
