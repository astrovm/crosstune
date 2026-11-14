package com.astrovm.crosstune

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The licences' pieces, kept while Settings → About → Licenses is shown: each read and split once. */
class LicenseSplitsTest {

    private val split = LicenseSplits.split
    private val jmdict = Notices.all.first { it.name == "JMdict" }
    private val pieces = listOf(LicenseUnit("The licence.", starts = true, ends = true))

    /** Two threads to ask from, kept so a test sees when one waits for the other. */
    private val started = Collections.synchronizedList(mutableListOf<Thread>())
    private val threads = Executors.newFixedThreadPool(2) { Thread(it).also(started::add) }

    /** Waits until [thread] waits for a lock another thread holds: then it asked while the read went on. */
    private fun waitsForALock(thread: Thread) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (thread.state != Thread.State.BLOCKED) {
            check(System.nanoTime() < until) { "${thread.name} never waited for the read: ${thread.state}" }
            Thread.yield()
        }
    }

    @Before
    fun setUp() = LicenseSplits.clear()

    @After
    fun tearDown() {
        // Each read a test held is let go by then. It ends on its own: interrupted, it would end in a failure of its own.
        threads.shutdown()
        assertTrue("A read still runs after the test", threads.awaitTermination(10, TimeUnit.SECONDS))
        LicenseSplits.split = split
        LicenseSplits.clear()
    }

    @Test
    fun twoPassesAskingForTheSameWorkAtOnceReadItOnce() {
        val reads = AtomicInteger()
        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        LicenseSplits.split = {
            reads.incrementAndGet()
            reading.countDown()
            assertTrue(letGo.await(5, TimeUnit.SECONDS))
            pieces
        }
        try {
            val first = threads.submit { LicenseSplits.make(jmdict) }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            // The second asks while the first is still reading, and waits for it rather than reading again.
            val second = threads.submit { LicenseSplits.make(jmdict) }
            waitsForALock(started[1])
            letGo.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
        } finally {
            letGo.countDown()
        }
        assertEquals(1, reads.get())
        assertSame(pieces, LicenseSplits.of("JMdict"))
    }

    @Test
    fun twoEqualCopiesOfANoticeAskedForAtOnceReadItOnce() {
        val reads = AtomicInteger()
        val reading = CountDownLatch(1)
        val letGo = CountDownLatch(1)
        LicenseSplits.split = {
            reads.incrementAndGet()
            reading.countDown()
            assertTrue(letGo.await(5, TimeUnit.SECONDS))
            pieces
        }
        try {
            val first = threads.submit { LicenseSplits.make(jmdict) }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            // An equal copy, not the same object: the same work all the same.
            val second = threads.submit { LicenseSplits.make(jmdict.copy()) }
            waitsForALock(started[1])
            letGo.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
        } finally {
            letGo.countDown()
        }
        assertEquals(1, reads.get())
    }

    @Test
    fun aSplitThatEndsAfterThePageWasLeftIsNotKept() {
        LicenseSplits.split = {
            // The page is left while the text is read.
            LicenseSplits.clear()
            pieces
        }
        LicenseSplits.make(jmdict)
        assertNull(LicenseSplits.of("JMdict"))
        // Opened again on the next visit, it is read again and kept.
        LicenseSplits.split = { pieces }
        LicenseSplits.make(jmdict)
        assertEquals(pieces, LicenseSplits.of("JMdict"))
    }

    @Test
    fun aKeptWorkIsNotReadAgain() {
        val reads = AtomicInteger()
        LicenseSplits.split = { reads.incrementAndGet(); pieces }
        LicenseSplits.make(jmdict)
        LicenseSplits.make(jmdict)
        assertEquals(1, reads.get())
        assertEquals(pieces, LicenseSplits.of("JMdict"))
    }
}
