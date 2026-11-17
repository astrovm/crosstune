package com.astrovm.crosstune

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Shared with the framework runner so its failure paths can be tested without a device. */
internal suspend fun finishClockProbe(stop: suspend () -> Unit, unregister: () -> Unit, release: () -> Unit) {
    withContext(NonCancellable) {
        try {
            stop()
        } finally {
            try {
                unregister()
            } finally {
                release()
            }
        }
    }
}

internal suspend fun <T> awaitClockRecords(expected: Int, read: () -> List<T>): List<T> = withTimeout(5_000) {
    var records = read()
    while (records.size < expected) {
        delay(25)
        records = read()
    }
    records
}
