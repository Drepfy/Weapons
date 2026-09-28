package io.github.drepfy.vigil.task;

/**
 * Measures real tick intervals to estimate TPS and detect lag spikes. Fed once per
 * tick by the main task; read on the server thread.
 */
public final class TpsMonitor {

    private static final int WINDOW = 100;
    private static final long NEVER = Long.MIN_VALUE / 4;

    private final long[] intervals = new long[WINDOW];
    private int index;
    private int count;
    private long sum;
    private long lastTickMs = NEVER;
    private long lastSpikeMs = NEVER;
    private long lastIntervalMs = 50;

    public void onTick(long nowMs, long spikeThresholdMs) {
        if (lastTickMs != NEVER) {
            long interval = Math.max(0L, nowMs - lastTickMs);
            lastIntervalMs = interval;
            if (count == WINDOW) {
                sum -= intervals[index];
            } else {
                count++;
            }
            intervals[index] = interval;
            sum += interval;
            index = (index + 1) % WINDOW;
            if (interval > spikeThresholdMs) {
                lastSpikeMs = nowMs;
            }
        }
        lastTickMs = nowMs;
    }

    /** Ticks per second over the last ~5 seconds, capped at 20. */
    public double tps() {
        if (count == 0 || sum <= 0) {
            return 20.0;
        }
        return Math.min(20.0, 1000.0 * count / sum);
    }

    public boolean recentlySpiked(long nowMs, long graceMs) {
        return nowMs - lastSpikeMs < graceMs;
    }

    public long lastIntervalMs() {
        return lastIntervalMs;
    }
}
