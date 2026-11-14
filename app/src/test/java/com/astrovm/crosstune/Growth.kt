package com.astrovm.crosstune

import java.io.File
import java.lang.management.ManagementFactory

/**
 * Whether reading takes time in step with what's read, told by how the work grows and not by a number of
 * milliseconds, which a slow or busy machine goes past. Each text is read at a length and at [TIMES] it: in step,
 * four times the text takes about 4 times the work, and a reader that looks on to the end from each place 16 times
 * as much. Past [MARGIN] times as much, halfway between the two, is out of step.
 *
 * The work is counted where it can be, as the letters a pattern looks at, which no machine changes. Where it can't,
 * it's the thread's own time on the processor, which work beside it on the machine doesn't add to. Each text is
 * read once first, so that the code is compiled before a reading counts, and then in turn with its longer one, the
 * best of each kept, until the code is done being compiled. A quick reading is timed over many rounds of it, as a
 * hiccup of the machine's, a few microseconds, would otherwise count for more than the reading.
 *
 * Each pair's growth goes to `build/growth.tsv`, by test and text, so how near the line each one is can be seen.
 */
internal object Growth {
    /** How many times as long the longer text is. */
    const val TIMES = 4

    /** How many times the work the longer text may take: about 4 in step, 16 for time squared. */
    const val MARGIN = 8.0

    /** How many times each text is read in turn at least, the best of them kept. */
    private const val READINGS = 3

    /** How many times each text is read in turn at most, while the code is still being compiled. */
    private const val MOST_READINGS = 9

    /** How long, at least, a reading of the shorter text takes, read over as many rounds as that needs. */
    private const val LEAST_NANOS = 1_000_000L

    /** How many rounds a reading takes at most, for one too quick for the clock to see. */
    private const val MOST_ROUNDS = 10_000L

    /** How much quicker a reading is than the best before it when the code that reads it was compiled in between. */
    private const val QUICKER = 1.1

    /**
     * Each of [texts] made at [length] and at [TIMES] it, timed as [read] reads it: for each out of step, its [name]
     * and its growth. The texts at each length come in the same order.
     */
    fun <T> outOfStep(texts: (Int) -> List<T>, length: Int, name: (T) -> String, read: (T) -> Any?): List<String> {
        val (short, long) = made(texts, length)
        // Each once first, so that what's timed is the reading and not the start-up and compiling of the code that reads it.
        short.forEach { read(it) }
        return judged(short, long, name, "ns") { once, longer -> timed(once, longer, read) }
    }

    /** As [outOfStep], with the work that reading a text takes counted by [count], which is the same each time. */
    fun <T> outOfStepCounting(texts: (Int) -> List<T>, length: Int, name: (T) -> String, count: (T) -> Long): List<String> {
        val (short, long) = made(texts, length)
        return judged(short, long, name, "counted") { once, longer -> Work(count(once), count(longer)) }
    }

    /** How many times as long [longer], [once] at [TIMES] the length, takes to [read], at its best. */
    fun <T> growth(once: T, longer: T, read: (T) -> Any?): Double = timed(once, longer, read).growth

    private fun <T> made(texts: (Int) -> List<T>, length: Int): Pair<List<T>, List<T>> {
        val short = texts(length)
        val long = texts(TIMES * length)
        check(short.size == long.size) { "${short.size} texts at $length, ${long.size} at $TIMES times it" }
        return short to long
    }

    private fun <T> judged(short: List<T>, long: List<T>, name: (T) -> String, unit: String, work: (T, T) -> Work): List<String> {
        val grown = short.zip(long).map { (once, longer) -> name(longer) to work(once, longer) }
        val test = askingTest()
        log.parentFile?.mkdirs()
        log.appendText(
            grown.joinToString("") { (text, work) ->
                "$test\t${text.replace(spaces, " ")}\t${"%.2f".format(work.growth)}\t${work.once}\t${work.longer}\t$unit\n"
            }
        )
        return grown.filter { it.second.growth > MARGIN }.map { (text, work) -> "$text ${"%.1f".format(work.growth)} times as much" }
    }

    /**
     * The best times of [once] and [longer], read in turn: [READINGS] times, and more while a reading is much quicker
     * than the best before it, as when the code that reads it was compiled in between, up to [MOST_READINGS].
     */
    private fun <T> timed(once: T, longer: T, read: (T) -> Any?): Work {
        var best = Work(Long.MAX_VALUE, Long.MAX_VALUE)
        var readings = 0
        do {
            val before = best
            // Rounds enough for the shorter text, at its best so far, to take [LEAST_NANOS]: one, at first.
            val rounds = (LEAST_NANOS / best.once.coerceAtLeast(1) + 1).coerceAtMost(MOST_ROUNDS)
            // The longer one first: a first reading slower than the rest then only makes the pair read again.
            val longerNow = nanos(longer, read, rounds)
            val onceNow = nanos(once, read, rounds)
            best = Work(minOf(best.once, onceNow), minOf(best.longer, longerNow))
            readings++
        } while (readings < MOST_READINGS && (readings < READINGS || before.quickerIn(best)))
        return best
    }

    /** The time of a reading of [text], each of [rounds] of it. */
    private fun <T> nanos(text: T, read: (T) -> Any?, rounds: Long): Long {
        val began = threads.currentThreadCpuTime
        for (round in 1..rounds) read(text)
        return (threads.currentThreadCpuTime - began) / rounds
    }

    /** The work, at its best, of a text and of it [TIMES] as long. */
    private class Work(val once: Long, val longer: Long) {
        val growth get() = longer.toDouble() / once.coerceAtLeast(1)

        /** Whether [now] is much quicker than this for either text. */
        fun quickerIn(now: Work) = once > now.once * QUICKER || longer > now.longer * QUICKER
    }

    /** The test that asks, as `Class.method`. */
    private fun askingTest(): String {
        val asking = Throwable().stackTrace.first { it.className.substringBefore('$').endsWith("Test") }
        return "${asking.className.substringAfterLast('.').substringBefore('$')}.${asking.methodName.substringBefore('$')}"
    }

    private val threads = ManagementFactory.getThreadMXBean()
    private val log = File("build/growth.tsv")
    private val spaces = Regex("\\s")
}
