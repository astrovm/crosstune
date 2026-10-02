package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Holds work sent to it until a test runs it, standing in for a background thread whose work
 * finishes whenever the test says, and in whichever order.
 */
internal class QueueDispatcher : CoroutineDispatcher() {
    private val queued = ArrayDeque<Runnable>()

    val size: Int get() = queued.size

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        queued.addLast(block)
    }

    fun runAll() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    /** Runs the work sent last, ahead of anything sent before it. */
    fun runLast() = queued.removeLast().run()
}
