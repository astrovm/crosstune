package com.astrovm.crosstune.workcount;

/**
 * The work one thread does, in steps: each call, each time a loop goes round, and each element an array copy moves, in
 * the classes {@link Agent} counts in. Only the thread {@link #of} counts for adds to them, so the work of the threads
 * beside it, as the test runner's, doesn't count. The bootstrap class loader holds this class, as the JDK's own classes
 * count in it too.
 */
public final class Work {
    private static volatile Thread counted;
    private static long steps;

    private Work() {
    }

    /** One step, put at the start of each method and before each jump back. */
    public static void step() {
        if (Thread.currentThread() == counted) steps++;
    }

    /** As many steps as an array copy moves elements, put before each call to {@code System.arraycopy}. */
    public static void copied(int length) {
        if (Thread.currentThread() == counted) steps += length;
    }

    /** How many steps {@code work} takes, on this thread. One thread counts at a time. */
    public static synchronized long of(Runnable work) {
        steps = 0;
        counted = Thread.currentThread();
        try {
            work.run();
        } finally {
            counted = null;
        }
        return steps;
    }
}
