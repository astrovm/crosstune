package com.astrovm.crosstune

import java.io.File
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.lang.management.ManagementFactory

/**
 * Whether reading takes work in step with what's read, told by how the work grows. Each text is read at a length and at
 * [TIMES] it: in step, four times the text takes about 4 times the work, and a reader that looks on to the end from each
 * place 16 times as much. Past [MARGIN] times as much is out of step.
 *
 * The work is counted, never timed: a count is the same on every machine and each time, where a time is not. Timed, the
 * growths moved by as much as the line between in step and out of step on CI's runners, a runner of their own too, and
 * failed at random. Where the letters a pattern looks at can be counted, they are ([outOfStepCounting]). Elsewhere, the
 * work-count agent (`tools/work-count`) counts the reading thread's steps: each call and each time a loop goes round,
 * each element of an array made, cloned or copied, and each byte a checksum or zlib reads or writes, in the app, its
 * libraries and the JDK, its patterns too, but in what runs the JVM and the agent. A matcher reset or pattern change
 * also counts one full input length: Android copies it into its native engine, while the JDK keeps a reference.
 * The growthTests task runs it.
 * Work on other threads isn't counted, and isn't wanted: it's another test's. Each reader here reads on the thread that
 * asks, and a reading that leaves work to another thread fails, however it hands it over, when it waits for it: the agent measures the
 * processor time the other threads take as it reads ([BESIDE_NANOS]). So no other thread may be busy then.
 *
 * Each pair's growth goes to `build/growth/TASK.tsv`, emptied when the Gradle task starts, by test and text, with the
 * processor time beside each reading in microseconds, so how near each line each one is can be seen.
 */
internal object Growth {
    /** The work-count agent's count, or null where it isn't in this JVM. */
    private val counter: Class<*>? = runCatching { Class.forName("com.astrovm.crosstune.workcount.Work", true, null) }.getOrNull()

    /** How many steps a reading takes. */
    private val steps: MethodHandle? = counter?.let { counted(it, "of", Long::class.javaPrimitiveType, Runnable::class.java) }

    /** The processor time the threads beside took as it last counted, in nanoseconds, and the name of the busiest. */
    private val beside: MethodHandle? = counter?.let { counted(it, "beside", Long::class.javaPrimitiveType) }
    private val busiest: MethodHandle? = counter?.let { counted(it, "busiest", String::class.java) }

    private fun counted(work: Class<*>, name: String, returned: Class<*>?, vararg taken: Class<*>): MethodHandle =
        MethodHandles.publicLookup().findStatic(work, name, MethodType.methodType(returned, taken))

    /**
     * Whether work is counted: in the growthTests task, where the work-count agent is in the JVM. Elsewhere each text is
     * read once, [UNCOUNTED_SHORTER] times shorter, for what it runs, and nothing is judged: counting runs the JVM without
     * its compiler, many times slower, and the code a reading runs counts for Kover at any length.
     */
    val COUNTING = steps != null

    /** How many times shorter than the shorter length each text is when its work isn't counted. */
    const val UNCOUNTED_SHORTER = 10

    /** How many times as long the longer text is. */
    const val TIMES = 4

    /**
     * How many times the work the longer text may take: 4 in step, and 16 for time squared. A count has no noise, so the
     * line is near 4: the app's patterns, counted by the letters they look at on 14,208 texts, grew 4.03 times at most,
     * and the readers, counted in steps, 4.04. Work that grows linearly, with a part that grows squared, grows past
     * it once that part, at the shorter length, is over 1/23 of the linear part: 4(1 + 4f) / (1 + f) is 4.5 at f = 1/23.
     */
    const val MARGIN = 4.5

    /**
     * Each of [texts] made at [length] and at [TIMES] it, read by [read] with its steps counted: for each out of step,
     * its [name] and its growth. The texts at each length come in the same order.
     */
    fun <T : CharSequence> outOfStep(texts: (Int) -> List<T>, length: Int, name: (T) -> String, read: (T) -> Any?): List<String> =
        outOfStep(texts, length, name, COUNTING, read)

    /**
     * The processor time the threads beside a reading may take while it's counted, all together. Work handed to another
     * thread isn't counted, however it's handed over, so a reading that leaves them more fails. Work a reading doesn't wait
     * for, and work on the JVM's own threads, like the garbage collector's, go unseen. In two runs of the
     * growth tests, 8,834 readings, the threads beside took 107 µs at most, as the JDK cleaned up after the garbage
     * collector. A reading that hands a squared reader's work on 1,000 letters to another thread
     * leaves it 4.3 ms, and on 4,000 letters, 67 ms. That is for a JVM that runs at the speed of a plain machine: where it
     * runs slower, the limit grows by how much ([slowdown]).
     */
    const val BESIDE_NANOS = 1_000_000L

    /**
     * As [outOfStep], counted or not, as [counting] says. Counted, a reading that leaves the threads beside more than
     * [BESIDE_NANOS] fails, with its text's name: its work may be done there, where nothing counts it.
     */
    fun <T : CharSequence> outOfStep(texts: (Int) -> List<T>, length: Int, name: (T) -> String, counting: Boolean, read: (T) -> Any?): List<String> {
        if (!counting) {
            texts(length / UNCOUNTED_SHORTER).forEach { read(it) }
            return emptyList()
        }
        // Once first, at another length, so the classes it loads and sets up don't count, and no text is read twice, as
        // a reader that keeps what it read would read it the second time for nothing.
        texts(length / 2).forEach { read(it) }
        // And each class it loaded while another was written, which the JDK skips, is written now.
        agent("ready")
        val (short, long) = made(texts, length)
        val out = judged(short, long, name, "steps", MARGIN) { once, longer ->
            val first = reading(once, read, name(longer))
            val second = reading(longer, read, name(longer))
            Work(first.steps, second.steps, first.beside, second.beside)
        }
        check(uncounted().isEmpty()) { "Not counted in: ${uncounted()}" }
        return out
    }

    /** As [outOfStep], with the work that reading a text takes counted by [count], which is the same each time. */
    fun <T> outOfStepCounting(texts: (Int) -> List<T>, length: Int, name: (T) -> String, count: (T) -> Long): List<String> {
        val (short, long) = made(texts, length)
        return judged(short, long, name, "counted", MARGIN) { once, longer -> Work(count(once), count(longer)) }
    }

    /** How many steps reading [text] with [read] takes, as the work-count agent counts them. */
    fun <T> stepsOf(text: T, read: (T) -> Any?): Long {
        val counter = checkNotNull(steps) { "The work-count agent counts steps: run the growthTests task" }
        return counter.invoke(Runnable { read(text) }) as Long
    }

    /** One reading's steps, and the processor time the threads beside it took, in nanoseconds. */
    private class Reading(val steps: Long, val beside: Long)

    /** Reads [text] with [read], counted, and fails, by [name], where it leaves the threads beside more than [BESIDE_NANOS]. */
    private fun <T> reading(text: T, read: (T) -> Any?, name: String): Reading {
        val counted = stepsOf(text, read)
        val took = checkNotNull(beside).invoke() as Long
        val limit = besideLimit(SLOWDOWN)
        check(took <= limit) {
            "$name: other threads took ${"%.2f".format(took / MILLI)} ms of processor time as it read, most of it ${checkNotNull(busiest).invoke()}, " +
                "past the ${"%.2f".format(limit / MILLI)} ms a reading may leave them (${"%.2f".format(BESIDE_NANOS / MILLI)} ms, " +
                "times ${"%.1f".format(SLOWDOWN)} for how much slower this JVM runs): work on another thread isn't counted"
        }
        return Reading(counted, took)
    }

    /** The processor time the threads beside may take as a reading is counted, where this JVM runs [slowdown] times slower than the line was set on. */
    fun besideLimit(slowdown: Double): Long = (BESIDE_NANOS * slowdown).toLong()

    /**
     * How many times slower than on the machines [BESIDE_NANOS] was set on this JVM runs, never below 1, told by how long
     * [SPIN_NANOS] says a fixed piece of work should take its thread: under emulation, as the Linux x86-64 box runs on an
     * Apple Silicon Mac, every thread takes several times the processor time for the same work, the JVM's own idle ones
     * too, and a limit that doesn't grow with it fails where nothing is wrong. A reading that hands work to another
     * thread takes as much longer there, so it fails by the same margin.
     */
    fun slowdown(spinNanos: Long): Double = (spinNanos.toDouble() / SPIN_NANOS).coerceAtLeast(1.0)

    /** The processor time, in nanoseconds, the fixed piece of work takes this thread, at best of a few tries once it's warm. */
    private fun spin(): Long {
        val threads = ManagementFactory.getThreadMXBean()
        var sink = 0
        fun once(): Long {
            val start = threads.currentThreadCpuTime
            val array = IntArray(SPIN_LENGTH)
            repeat(SPIN_LAPS) { lap -> for (each in array.indices) array[each] = array[each] * 31 + each + lap }
            sink += array[SPIN_LENGTH / 2]
            return threads.currentThreadCpuTime - start
        }
        repeat(SPIN_TRIES) { once() }
        val best = (1..SPIN_TRIES).minOf { once() }
        check(sink != 0 || best >= 0)
        return best
    }

    /** How much slower this JVM runs, measured once. The growth log's directory keeps it, beside the readings it scales. */
    private val SLOWDOWN: Double by lazy {
        val took = spin()
        File("${log.path}.speed").also { it.parentFile?.mkdirs() }.writeText("spin\t$took\nslowdown\t${slowdown(took)}\n")
        slowdown(took)
    }

    private const val SPIN_LENGTH = 1 shl 14
    private const val SPIN_LAPS = 256
    private const val SPIN_TRIES = 5

    /**
     * The processor time, in nanoseconds, [spin] takes at the speed of a plain machine: 4.3 ms on the machine the readings'
     * time beside was measured on, in runs the same to 1%, and a little over it, so a machine a bit slower isn't scaled.
     * Under emulation on that machine (qemu-x86_64) it took 34 to 124 ms, and the threads beside up to 1.6 ms.
     */
    const val SPIN_NANOS = 5_000_000L

    private const val MILLI = 1e6
    private const val MICRO = 1_000

    /** The classes the agent should count in and doesn't, with why. */
    private fun uncounted(): List<*> = agent("uncounted") as List<*>

    private fun agent(method: String): Any? =
        Class.forName("com.astrovm.crosstune.workcount.Agent", true, ClassLoader.getSystemClassLoader()).getMethod(method).invoke(null)

    private fun <T> made(texts: (Int) -> List<T>, length: Int): Pair<List<T>, List<T>> {
        val short = texts(length)
        val long = texts(TIMES * length)
        check(short.size == long.size) { "${short.size} texts at $length, ${long.size} at $TIMES times it" }
        return short to long
    }

    private fun <T> judged(short: List<T>, long: List<T>, name: (T) -> String, unit: String, margin: Double, work: (T, T) -> Work): List<String> {
        val grown = short.zip(long).map { (once, longer) -> name(longer) to work(once, longer) }
        val test = askingTest()
        log.parentFile?.mkdirs()
        log.appendText(
            grown.joinToString("") { (text, work) ->
                val times = listOfNotNull(work.besideOnce, work.besideLonger).joinToString("") { "\t${it / MICRO}" }
                "$test\t${text.replace(spaces, " ")}\t${"%.2f".format(work.growth)}\t${work.once}\t${work.longer}\t$unit$times\n"
            }
        )
        return grown.filter { it.second.growth > margin }.map { (text, work) -> "$text ${"%.1f".format(work.growth)} times as much" }
    }

    /** The work of a text and of it [TIMES] as long, and where counted, the processor time beside each reading. */
    private class Work(val once: Long, val longer: Long, val besideOnce: Long? = null, val besideLonger: Long? = null) {
        val growth get() = longer.toDouble() / once.coerceAtLeast(1)
    }

    /** The test that asks, as `Class.method`. */
    private fun askingTest(): String {
        val asking = Throwable().stackTrace.first { it.className.substringBefore('$').endsWith("Test") }
        return "${asking.className.substringAfterLast('.').substringBefore('$')}.${asking.methodName.substringBefore('$')}"
    }

    private val log = File(System.getProperty("crosstune.growthLog") ?: "build/growth.tsv")
    private val spaces = Regex("\\s")
}
