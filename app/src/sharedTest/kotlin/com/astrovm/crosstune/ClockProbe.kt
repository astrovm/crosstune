package com.astrovm.crosstune

/** Shared with the framework runner so its failure paths can be tested without a device. */
internal suspend fun finishClockProbe(stop: suspend () -> Unit, unregister: () -> Unit, release: () -> Unit) {
    stop()
    unregister()
    release()
}

internal suspend fun <T> awaitClockRecords(expected: Int, read: () -> List<T>): List<T> = read().also {
    check(it.size >= expected) { "Expected $expected clock records, got ${it.size}" }
}
