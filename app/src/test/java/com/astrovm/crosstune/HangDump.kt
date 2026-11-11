package com.astrovm.crosstune

import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A test still running after [afterMs] notes, every [everyMs], what each thread does, how much CPU the
 * test got since the last note, and how starved the machine is, so a test that hangs says why: stuck on
 * one frame, busy in a loop, or waiting on a machine out of memory or CPU (Issue #852). The notes go
 * to stderr, and with a failure, onto it, so Gradle's log shows them.
 */
class HangDump(private val afterMs: Long = 20_000, private val everyMs: Long = 5_000) : TestRule {

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val test = Thread.currentThread()
            val start = System.nanoTime()
            val notes = CopyOnWriteArrayList<String>()
            val watch = thread(isDaemon = true, name = "HangDump") {
                try {
                    Thread.sleep(afterMs)
                    var cpuBefore = cpuMs(test)
                    while (true) {
                        val cpu = cpuMs(test)
                        val note = note(description, test, (System.nanoTime() - start) / 1_000_000, cpu - cpuBefore)
                        cpuBefore = cpu
                        notes += note
                        System.err.println(note)
                        Thread.sleep(everyMs)
                    }
                } catch (_: InterruptedException) {
                    // The test ended.
                }
            }
            try {
                base.evaluate()
            } catch (failure: Throwable) {
                notes.forEach { failure.addSuppressed(Hung(it)) }
                throw failure
            } finally {
                watch.interrupt()
            }
        }
    }

    /** One note, carried by a failure so its stack shows with it. */
    private class Hung(note: String) : Throwable(note, null, false, false)

    private fun cpuMs(thread: Thread): Long = ManagementFactory.getThreadMXBean().getThreadCpuTime(thread.threadId()) / 1_000_000

    private fun note(description: Description, test: Thread, elapsedMs: Long, testCpuMs: Long): String = buildString {
        val gcMs = ManagementFactory.getGarbageCollectorMXBeans().sumOf { it.collectionTime }
        val load = ManagementFactory.getOperatingSystemMXBean().systemLoadAverage
        appendLine("HangDump: ${description.displayName} still running after $elapsedMs ms.")
        appendLine("  The test got $testCpuMs ms of CPU since the last note. GC took $gcMs ms in all. Load $load.")
        appendLine("  Memory stalls: ${pressure("memory")}. CPU stalls: ${pressure("cpu")}.")
        Thread.getAllStackTraces().entries.sortedByDescending { it.key == test }.forEach { (thread, stack) ->
            val mine = thread == test
            appendLine("  \"${thread.name}\" ${thread.state}, CPU ${cpuMs(thread)} ms${if (mine) ", the test" else ""}")
            stack.take(if (mine) stack.size else 8).forEach { appendLine("      at $it") }
        }
    }

    /** Linux's pressure stall line for [what]: the share of the last 10 s that work waited on it, in the box if there's one. */
    private fun pressure(what: String): String =
        listOf("/sys/fs/cgroup/$what.pressure", "/proc/pressure/$what").firstNotNullOfOrNull { path ->
            runCatching { File(path).readLines().joinToString("; ") { it.substringBefore(" avg60") } }.getOrNull()
        } ?: "unknown"
}
