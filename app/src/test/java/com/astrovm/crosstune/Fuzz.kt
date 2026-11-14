package com.astrovm.crosstune

/**
 * Mutation testing runs the tests that cover a change once for each change, fastest first, until one fails. A fuzz
 * that reads many titles takes seconds, so each change it alone catches first waits for some 1,400 faster tests, and
 * each it misses waits for all of them: the job ran out of time. There, it reads a few titles, in milliseconds, so it
 * runs before the app's slow tests. The test task reads them all.
 *
 * The fuzzes that take most of a minute read one case in [SAMPLED] in the test task, and all of them with `-PfuzzFull`,
 * as CI's fuzz job runs them: read whole beside the other tests, they used the processor the other tests needed, and
 * took the test job near its time limit.
 */
object Fuzz {
    /** Set by the pitest task in `app/build.gradle.kts`. */
    private val underMutationTesting = System.getProperty("crosstune.mutationRun") == "true"

    /** Set by `-PfuzzFull` in `app/build.gradle.kts`. */
    private val full = System.getProperty("crosstune.fuzzFull") == "true"

    /** One in how many cases a sampled fuzz reads in the test task. */
    const val SAMPLED = 20

    /** One in how many cases a sampled fuzz reads: each with `-PfuzzFull`, one in [SAMPLED] otherwise. */
    val every = if (full) 1 else SAMPLED

    /** [all] in the test task, [few] under mutation testing. */
    fun count(all: Int, few: Int) = if (underMutationTesting) few else all

    /** As [count], for a sampled fuzz: one in [every] of [all] in the test task. */
    fun sampled(all: Int, few: Int) = if (underMutationTesting) few else (all + every - 1) / every

    /** The cases of [all] a sampled fuzz reads: the first, and each [every] after it. */
    fun <T> sample(all: List<T>): List<T> = all.filterIndexed { at, _ -> at % every == 0 }
}
