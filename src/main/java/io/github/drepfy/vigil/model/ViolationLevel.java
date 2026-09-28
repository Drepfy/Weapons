package io.github.drepfy.vigil.model;

/**
 * A violation level with lazy linear decay (applied whenever it is read or changed).
 */
public final class ViolationLevel {

    private double value;
    private long lastUpdateMs = -1;
    private long lastFlagMs = -1;
    private int totalFlags;

    public double get(long nowMs, double decayPerMinute) {
        applyDecay(nowMs, decayPerMinute);
        return value;
    }

    public double add(double amount, long nowMs, double decayPerMinute) {
        applyDecay(nowMs, decayPerMinute);
        value += Math.max(0.0, amount);
        lastFlagMs = nowMs;
        totalFlags++;
        return value;
    }

    public void reset() {
        value = 0.0;
        lastUpdateMs = -1;
    }

    public long lastFlagMs() {
        return lastFlagMs;
    }

    /** Flags since the player's data was created (this session). */
    public int totalFlags() {
        return totalFlags;
    }

    private void applyDecay(long nowMs, double decayPerMinute) {
        if (lastUpdateMs >= 0 && nowMs > lastUpdateMs && decayPerMinute > 0 && value > 0) {
            value = Math.max(0.0, value - decayPerMinute * (nowMs - lastUpdateMs) / 60_000.0);
        }
        lastUpdateMs = nowMs;
    }
}
