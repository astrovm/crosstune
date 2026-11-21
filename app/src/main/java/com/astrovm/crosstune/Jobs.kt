package com.astrovm.crosstune

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Launches [block] with its job handed to [keep] before it runs, as every job kept to be stopped or checked later is.
 * On the main thread it runs at once, up to where it first waits, and may ask for itself again meanwhile, as listening
 * along does when the first song it hears takes over the words. Kept only once launch returns, that ask finds none
 * running and starts a second one, then the first takes its place, and nothing stops the second (Issue #1145).
 * `JobsKeptTest` holds the code to it.
 */
internal fun CoroutineScope.launchKept(keep: (Job) -> Unit, block: suspend CoroutineScope.() -> Unit): Job =
    launch(start = CoroutineStart.LAZY, block = block).also { keep(it); it.start() }

/**
 * [launchKept] for an answer: kept before it runs, so what's done once it's over, like dropping it from where it's kept,
 * comes after it's kept even when it's over at once, as a lookup answered from what's kept is.
 */
internal fun <T> CoroutineScope.asyncKept(keep: (Deferred<T>) -> Unit, block: suspend CoroutineScope.() -> T): Deferred<T> =
    async(start = CoroutineStart.LAZY, block = block).also { keep(it); it.start() }
