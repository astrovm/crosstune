package com.astrovm.crosstune.workcount;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The work one thread does, in steps: each call, each time a loop goes round, each element an array copy moves, a new
 * array holds or a clone copies, each frame a stack trace holds, and each byte a checksum or zlib reads or writes, in
 * the classes {@link Agent} counts in. Only the thread {@link #of} counts for adds to them, so the work of the threads
 * beside it, as the test runner's, doesn't count. The bootstrap class loader holds this class, as the JDK's own classes
 * count in it too.
 *
 * <p>And the processor time every other thread takes while one counts ({@link #beside}): work handed to another thread
 * isn't counted, however it's handed over, but it takes that thread's time.
 */
public final class Work {
    private static volatile Thread counted;
    private static long steps;

    /** Whether a thread counts, or is about to: each other thread that ends keeps how much processor time it took. */
    private static volatile boolean measured;

    /** The processor time each other thread took, in nanoseconds, as it ended while one counted. */
    private static final Map<Long, Long> ended = new ConcurrentHashMap<>();

    /** The name of each thread that ended while one counted. */
    private static final Map<Long, String> endedNames = new ConcurrentHashMap<>();

    private static long beside;
    private static String busiest = "";

    private Work() {
    }

    /** One step, put at the start of each method and before each jump back. */
    public static void step() {
        if (Thread.currentThread() == counted) steps++;
    }

    /**
     * As many steps as elements the JVM goes through at once, in code of its own: put before each array copy, each new
     * array and each clone of one, with how many elements it holds, and after each stack trace is taken, with how many
     * frames it holds. A length below 0 throws there, and counts nothing.
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

    private static final long BYTES = 0x7fff_ffffL;

    /**
     * Put where each thread ends: one that ends while another counts keeps the processor time it took, which can't be
     * read once it's gone.
     */
    public static void ended() {
        // It runs first in Thread.exit: a throw here would skip the rest of it, so nothing leaves.
        try {
            Thread thread = Thread.currentThread();
            if (!measured || thread == counted) return;
            ended.put(thread.getId(), threads().getCurrentThreadCpuTime());
            endedNames.put(thread.getId(), thread.getName());
        } catch (Throwable lost) {
            // The thread's time is lost, so the reading may miss it: no worse than before it was measured.
        }
    }

    /**
     * How many steps {@code work} takes, on this thread. One thread counts at a time. The processor time the other
     * threads take meanwhile is {@link #beside} after.
     */
    public static synchronized long of(Runnable work) {
        ThreadMXBean threads = threads();
        if (!threads.isThreadCpuTimeSupported() || !threads.isCurrentThreadCpuTimeSupported() || !threads.isThreadCpuTimeEnabled()) {
            throw new IllegalStateException("This JVM doesn't measure each thread's processor time, which tells work handed to another thread");
        }
        measured = true;
        Map<Long, Long> before = quiet(threads);
        ended.clear();
        endedNames.clear();
        steps = 0;
        counted = Thread.currentThread();
        try {
            work.run();
        } finally {
            counted = null;
            Map<Long, Long> after = times(threads);
            measured = false;
            beside(threads, before, after);
        }
        return steps;
    }

    /**
     * The processor time, in nanoseconds, the threads beside the one counted took, all of them together, while it last
     * counted: those that were there before, those it started, and those that ended. Not the JVM's own, as its compiler
     * and its garbage collector, which run no code of a reading's.
     */
    public static synchronized long beside() {
        return beside;
    }

    /** The name of the thread beside that took the most processor time while one last counted, or none where none took any. */
    public static synchronized String busiest() {
        return busiest;
    }

    private static void beside(ThreadMXBean threads, Map<Long, Long> before, Map<Long, Long> after) {
        Set<Long> all = new HashSet<>(after.keySet());
        all.addAll(ended.keySet());
        long sum = 0;
        long most = 0;
        long mostId = -1;
        for (long id : all) {
            long took = ended.getOrDefault(id, after.getOrDefault(id, 0L)) - before.getOrDefault(id, 0L);
            sum += took;
            if (took > most) {
                most = took;
                mostId = id;
            }
        }
        beside = sum;
        busiest = mostId < 0 ? "" : name(threads, mostId);
    }

    /** The name of thread [id], gone or not. */
    private static String name(ThreadMXBean threads, long id) {
        String gone = endedNames.get(id);
        if (gone != null) return gone;
        ThreadInfo info = threads.getThreadInfo(id);
        return info == null ? "" : info.getThreadName();
    }

    /**
     * The processor time each thread but this one has taken, once they're quiet: once they take at most
     * {@link #QUIET_NANOS} in a millisecond, or after a second. The test runner's own threads send what the test
     * before did, a failure's whole stack trace too, which took 1.2 ms from the first reading of the next test.
     */
    private static Map<Long, Long> quiet(ThreadMXBean threads) {
        // A reader may leave this thread interrupted: the wait still goes on, and the flag is set again after.
        boolean interrupted = Thread.interrupted();
        Map<Long, Long> before = times(threads);
        for (int each = 0; each < QUIET_TRIES; each++) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException again) {
                interrupted = true;
            }
            Map<Long, Long> after = times(threads);
            long took = 0;
            for (Map.Entry<Long, Long> time : after.entrySet()) took += time.getValue() - before.getOrDefault(time.getKey(), 0L);
            before = after;
            if (took <= QUIET_NANOS) break;
        }
        if (interrupted) Thread.currentThread().interrupt();
        return before;
    }

    /** How much processor time the threads beside may take in a millisecond and be quiet. */
    private static final long QUIET_NANOS = 50_000;

    /** How many milliseconds to wait at most for the threads beside to be quiet. */
    private static final int QUIET_TRIES = 1000;

    /** The processor time each thread but this one has taken, in nanoseconds, by its id. */
    private static Map<Long, Long> times(ThreadMXBean threads) {
        Map<Long, Long> times = new HashMap<>();
        long self = Thread.currentThread().getId();
        for (long id : threads.getAllThreadIds()) {
            long time = id == self ? -1 : threads.getThreadCpuTime(id);
            if (time >= 0) times.put(id, time);
        }
        return times;
    }

    private static ThreadMXBean threads() {
        return ManagementFactory.getThreadMXBean();
    }
}
