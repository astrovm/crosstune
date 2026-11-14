package com.astrovm.crosstune

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** The licences' pieces, kept while Settings → About → Licenses is shown: each read and split once. */
class LicenseSplitsTest {

    private val split = LicenseSplits.split
    private val jmdict = Notices.all.first { it.name == "JMdict" }
    private val pieces = listOf(LicenseUnit("The licence.", starts = true, ends = true))

    @Before
    fun setUp() = LicenseSplits.clear()

    @After
    fun tearDown() {
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
        val threads = Executors.newFixedThreadPool(2)
        try {
            val first = threads.submit { LicenseSplits.make(jmdict) }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            // The second asks while the first is still reading, and waits for it rather than reading again.
            val second = threads.submit { LicenseSplits.make(jmdict) }
            Thread.sleep(100)
            letGo.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
        } finally {
            threads.shutdownNow()
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
        val threads = Executors.newFixedThreadPool(2)
        try {
            val first = threads.submit { LicenseSplits.make(jmdict) }
            assertTrue(reading.await(5, TimeUnit.SECONDS))
            // An equal copy, not the same object: the same work all the same.
            val second = threads.submit { LicenseSplits.make(jmdict.copy()) }
            Thread.sleep(100)
            letGo.countDown()
            first.get(5, TimeUnit.SECONDS)
            second.get(5, TimeUnit.SECONDS)
        } finally {
            threads.shutdownNow()
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
