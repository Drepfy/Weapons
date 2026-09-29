package io.github.drepfy.vigil.model;

/**
 * Counts events within the last second (clicks, block placements...). Keeps at most
 * {@link #CAPACITY} timestamps, so a flood of events costs constant memory.
 */
public final class RateCounter {

    private static final int CAPACITY = 256;
    private static final long WINDOW_MS = 1000;

    private final long[] times = new long[CAPACITY];
    private int head;
    private int size;

    /** Records an event and returns how many happened within the last second (including it). */
    public int record(long nowMs) {
        times[head] = nowMs;
        head = (head + 1) % CAPACITY;
        if (size < CAPACITY) {
            size++;
        }
        return count(nowMs);
    }

    /** Events within the last second. */
    public int count(long nowMs) {
        int count = 0;
        for (int i = 0; i < size; i++) {
            long time = times[Math.floorMod(head - 1 - i, CAPACITY)];
            if (nowMs - time >= WINDOW_MS) {
                break;
            }
            count++;
        }
        return count;
    }

    public void clear() {
        size = 0;
        head = 0;
    }
}
