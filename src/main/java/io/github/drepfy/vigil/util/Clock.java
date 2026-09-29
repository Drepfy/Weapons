package io.github.drepfy.vigil.util;

/**
 * Monotonic millisecond clock. All durations inside Vigil use this rather than
 * {@link System#currentTimeMillis()}: a wall-clock step (NTP correction) must never
 * make a player look faster than they are.
 */
public final class Clock {

    private static final long ORIGIN = System.nanoTime();
    private static volatile java.util.function.LongSupplier testSource;

    private Clock() {
    }

    /** Milliseconds since plugin class loading, monotonic. */
    public static long now() {
        java.util.function.LongSupplier source = testSource;
        return source != null ? source.getAsLong() : (System.nanoTime() - ORIGIN) / 1_000_000L;
    }

    /** Tests only: replaces the time source ({@code null} restores the real clock). */
    public static void setTestSource(java.util.function.LongSupplier source) {
        testSource = source;
    }
}
