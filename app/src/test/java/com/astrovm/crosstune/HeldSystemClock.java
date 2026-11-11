package com.astrovm.crosstune;

import android.os.SystemClock;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.Resetter;
import org.robolectric.shadows.ShadowPausedSystemClock;

/**
 * Android's clock, held where a test sets it, and counting how often it's asked the time. Robolectric
 * moves its own clock on by a frame each time something asks to be drawn, so a song followed by it
 * moves on by itself. Held, the song is where the test says. In Java, as it hides a static method of
 * Robolectric's clock, which Kotlin can't.
 */
@Implements(SystemClock.class)
public class HeldSystemClock extends ShadowPausedSystemClock {
    private static boolean holding = false;

    private static long held = 0;

    /** How often it was asked since the test started. */
    public static int asked = 0;

    @Implementation
    protected static long elapsedRealtime() {
        asked++;
        return holding ? held : ShadowPausedSystemClock.elapsedRealtime();
    }

    /** Holds it where Robolectric's is now: from here it moves only as {@link #moveOn} moves it. */
    public static void hold() {
        held = ShadowPausedSystemClock.elapsedRealtime();
        holding = true;
    }

    /** Moves it on by {@code ms}. */
    public static void moveOn(long ms) {
        held += ms;
    }

    /** Robolectric runs only this shadow's own resetter, so it runs Robolectric's too. */
    @Resetter
    public static void resetAll() {
        holding = false;
        held = 0;
        asked = 0;
        ShadowPausedSystemClock.reset();
    }
}
