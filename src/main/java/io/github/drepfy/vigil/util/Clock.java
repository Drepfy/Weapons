package io.github.drepfy.vigil.util;

/**
 * Monotonic millisecond clock. All durations inside Vigil use this rather than
 * {@link System#currentTimeMillis()}: a wall-clock step (NTP correction) must never
 * make a player look faster than they are.
 */
public final class Clock {

    private static final long ORIGIN = System.nanoTime();

    private Clock() {
    }

    /** Milliseconds since plugin class loading, monotonic. */
    public static long now() {
        return (System.nanoTime() - ORIGIN) / 1_000_000L;
    }
}
