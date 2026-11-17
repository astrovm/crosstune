package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineDispatcher
import java.util.concurrent.LinkedBlockingQueue
import kotlin.coroutines.CoroutineContext

/** Holds what's sent away from the screen, once [held], until let go, so a test can look while it's still being worked out. */
internal class Gate : CoroutineDispatcher() {
    @Volatile
    var held = false
    val queued = LinkedBlockingQueue<Runnable>()

    override fun isDispatchNeeded(context: CoroutineContext) = held

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        if (held) queued += block else block.run()
    }

    fun letGo() {
        held = false
        generateSequence { queued.poll() }.forEach { it.run() }
    }
}
