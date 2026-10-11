package com.astrovm.crosstune

import kotlinx.coroutines.CompletableDeferred

/**
 * Shizuku as a test runs it: [ready] or not, the user allowing it or not, once [answer]ed, and each
 * command doing what [does] says and working unless it says otherwise.
 */
internal class FakeShell(
    var ready: Boolean = true,
    var allows: Boolean = true,
    private val does: (List<String>) -> Boolean = { true }
) : Shell {
    val ran = mutableListOf<List<String>>()
    /** Told when Shizuku starts or stops, while watched. */
    var changed: (() -> Unit)? = null
    /** Completed once the user answers; already, unless a test holds it. */
    var answer = CompletableDeferred(Unit)

    override fun ready() = ready

    override fun watch(changed: () -> Unit): () -> Unit {
        this.changed = changed
        return { this.changed = null }
    }

    override suspend fun allowed(): Boolean {
        answer.await()
        return allows
    }

    override suspend fun run(command: List<String>): Boolean {
        ran += command
        return does(command)
    }
}
