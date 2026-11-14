package com.astrovm.crosstune;

/** Tells each thread that waits on a lock: in Java, as Kotlin has no notify. */
final class Told {
    private Told() {
    }

    /** Tells each thread that waits on [lock], and gives it back. */
    static Object everyone(Object lock) {
        synchronized (lock) {
            lock.notifyAll();
        }
        return lock;
    }
}
