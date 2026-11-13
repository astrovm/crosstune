package com.astrovm.crosstune

/**
 * Mutation testing runs the tests that cover a change once for each change, fastest first, until one fails. A fuzz
 * that reads many titles takes seconds, so each change it alone catches first waits for some 1,400 faster tests, and
 * each it misses waits for all of them: the job ran out of time. There, it reads a few titles, in milliseconds, so it
 * runs before the app's slow tests. The test task reads them all.
 */
object Fuzz {
    /** Set by the pitest task in `app/build.gradle.kts`. */
    private val underMutationTesting = System.getProperty("crosstune.mutationTesting") == "true"

    /** [all] in the test task, [few] under mutation testing. */
    fun count(all: Int, few: Int) = if (underMutationTesting) few else all
}
