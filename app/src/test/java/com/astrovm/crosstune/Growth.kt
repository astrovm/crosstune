package com.astrovm.crosstune

import java.io.File
import java.lang.invoke.MethodHandle
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType

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
 * libraries and the JDK, its patterns too, but in what runs the JVM and the agent. The growthTests task runs it.
 * Work on other threads isn't counted, and isn't wanted: it's another test's. Each reader here reads on the thread that
 * asks, and one that starts, wakes or tells another thread fails.
 *
 * Each pair's growth goes to `build/growth/TASK.tsv`, emptied when the Gradle task starts, by test and text, so how near
 * the line each one is can be seen.
 */
internal object Growth {
    /** How many steps a reading takes, from the work-count agent, or null where it isn't in this JVM. */
    private val steps: MethodHandle? = runCatching { Class.forName("com.astrovm.crosstune.workcount.Work", true, null) }.getOrNull()?.let {
        MethodHandles.publicLookup().findStatic(it, "of", MethodType.methodType(Long::class.javaPrimitiveType, Runnable::class.java))
    }

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
     * As [outOfStep], counted or not, as [counting] says. Counted, a reading that starts, wakes or tells another thread
     * fails, with its text's name: its work may be done there, where nothing counts it.
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
            try {
                Work(stepsOf(once, read), stepsOf(longer, read))
            } catch (handed: IllegalStateException) {
                throw IllegalStateException("${name(longer)}: ${handed.message}", handed)
            }
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
            grown.joinToString("") { (text, work) -> "$test\t${text.replace(spaces, " ")}\t${"%.2f".format(work.growth)}\t${work.once}\t${work.longer}\t$unit\n" }
        )
        return grown.filter { it.second.growth > margin }.map { (text, work) -> "$text ${"%.1f".format(work.growth)} times as much" }
    }

    /** The work of a text and of it [TIMES] as long. */
    private class Work(val once: Long, val longer: Long) {
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
