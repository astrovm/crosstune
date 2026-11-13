package com.astrovm.crosstune

import java.lang.management.ManagementFactory

/**
 * Whether reading takes time in step with what's read, told by how the time grows and not by a number of
 * milliseconds, which a slow or busy machine goes past. Each text is read at a length and at twice it: in step,
 * twice the text takes about twice as long, and a reader that looks on to the end from each place four times
 * as long. Past [MARGIN] times as long, between the two, is out of step.
 *
 * The time is the thread's own, on the processor: a busy machine that gives the processor to other work for a
 * while slows a long reading more often than a short one, and that time isn't the reader's.
 */
internal object Growth {
    /** How many times as long twice the text may take: about 2 in step, 4 for time squared. */
    const val MARGIN = 3.0

    /** How many times more a pair that looks out of step is read, the quickest reading of each kept. */
    private const val AGAIN = 5

    /**
     * How long each reading again lasts at least, read over and over: a quick reading on a busy machine can fall
     * where nothing else runs beside it, and a longer one can't, so each reading again lasts as long.
     */
    private const val READING_MS = 20.0

    /** At twice the length, a reading quicker than this is too quick to tell its growth from the clock's noise. */
    private const val QUICK_MS = 2.0

    /**
     * Each of [texts] made at [length] and at twice it, read by [read]: for each out of step, its [name] and
     * its two times. The texts at each length come in the same order.
     */
    fun <T> outOfStep(texts: (Int) -> List<T>, length: Int, name: (T) -> String, read: (T) -> Any?): List<String> {
        // Each once, shorter, so that what's timed is the reading and not the start-up of the code that reads it.
        texts(length / 10).forEach { read(it) }
        val short = texts(length)
        val long = texts(2 * length)
        check(short.size == long.size) { "${short.size} texts at $length, ${long.size} at twice it" }
        return short.zip(long).mapNotNull { (once, twice) -> outOfStep(once, twice, read)?.let { "${name(twice)} $it" } }
    }

    /** Null when [twice], [once] at twice the length, takes no more than [MARGIN] times as long to [read], else the two times. */
    fun <T> outOfStep(once: T, twice: T, read: (T) -> Any?): String? {
        // The longer one first: a first reading slower than the rest, as code not yet compiled makes it, then only
        // makes the pair look out of step and read again, and never hides a reader that is.
        var twiceMs = millis(twice, read)
        var onceMs = millis(once, read)
        // Each read again, in turn: work beside it on the machine slows a reading or two, and a reader out of step every one.
        if (twiceMs > MARGIN * onceMs) {
            twiceMs = Double.MAX_VALUE
            onceMs = Double.MAX_VALUE
            repeat(AGAIN) {
                twiceMs = minOf(twiceMs, millis(twice, read, READING_MS))
                onceMs = minOf(onceMs, millis(once, read, READING_MS))
            }
        }
        return "%.1f ms, twice as long %.1f ms".format(onceMs, twiceMs).takeIf { twiceMs > MARGIN * onceMs && twiceMs >= QUICK_MS }
    }

    /** How long [read] takes to read [text], over as many readings as last [atLeastMs] in all. */
    private fun <T> millis(text: T, read: (T) -> Any?, atLeastMs: Double = 0.0): Double {
        val began = threads.currentThreadCpuTime
        var readings = 0
        do {
            read(text)
            readings++
        } while (threads.currentThreadCpuTime - began < atLeastMs * 1e6)
        return (threads.currentThreadCpuTime - began) / 1e6 / readings
    }

    private val threads = ManagementFactory.getThreadMXBean()
}
