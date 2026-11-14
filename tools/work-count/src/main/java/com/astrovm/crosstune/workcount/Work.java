package com.astrovm.crosstune.workcount;

/**
 * The work one thread does, in steps: each call, each time a loop goes round, each element an array copy moves, a new
 * array holds or a clone copies, and each byte a checksum or zlib reads or writes, in the classes {@link Agent} counts
 * in. Only the thread {@link #of} counts for adds to them, so the work of the threads beside it, as the test runner's,
 * doesn't count. The bootstrap class loader holds this class, as the JDK's own classes count in it too.
 */
public final class Work {
    private static volatile Thread counted;
    private static long steps;
    private static boolean handed;
    private static final long BYTES = 0x7fff_ffffL;

    private Work() {
    }

    /** One step, put at the start of each method and before each jump back. */
    public static void step() {
        if (Thread.currentThread() == counted) steps++;
    }

    /**
     * As many steps as elements the JVM goes through at once, in code of its own: put before each array copy, each new
     * array and each clone of one, with how many elements it holds. A length below 0 throws there, and counts nothing.
     */
    public static void elements(long count) {
        if (count > 0 && Thread.currentThread() == counted) steps += count;
    }

    /** As {@link #elements(long)}, for each new array of arrays, with the length of each level: as many as all levels hold. */
    public static void elements(int[] lengths) {
        if (lengths == null) return;
        long each = 1;
        long all = 0;
        for (int length : lengths) {
            each *= length;
            all += each;
        }
        elements(all);
    }

    /**
     * As {@link #elements(long)}, for a clone: put before each call of {@code clone()} that can be an array's, as
     * Kotlin calls an array's on {@code Object}. An object's copies its fields, which no text makes more of.
     */
    public static void cloned(Object cloned) {
        if (cloned != null && cloned.getClass().isArray()) elements(java.lang.reflect.Array.getLength(cloned));
    }

    /**
     * As {@link #elements(long)}, for zlib's work: put after each squeeze and unsqueeze, with what it gives back, which
     * holds how many bytes it read, and 31 bits up how many it wrote, as {@code java.util.zip} reads it.
     */
    public static void zlib(long result) {
        elements((result & BYTES) + (result >>> 31 & BYTES));
    }

    /**
     * Put where a thread starts another, or tells those that wait: a reading that does may hand its work to a thread
     * where nothing counts it.
     */
    public static void handed() {
        if (Thread.currentThread() == counted) handed = true;
    }

    /** As {@link #handed}, put where a thread wakes {@code thread}: one that wakes itself hands nothing on. */
    public static void woke(Object thread) {
        if (thread != Thread.currentThread()) handed();
    }

    /**
     * How many steps {@code work} takes, on this thread. One thread counts at a time. Throws where it starts, wakes or
     * tells another thread, which its work may then be done on, where nothing counts it.
     */
    public static synchronized long of(Runnable work) {
        steps = 0;
        handed = false;
        counted = Thread.currentThread();
        try {
            work.run();
        } finally {
            counted = null;
        }
        if (handed) throw new IllegalStateException("it started, woke or told another thread, which may do its work where nothing counts it");
        return steps;
    }
}
