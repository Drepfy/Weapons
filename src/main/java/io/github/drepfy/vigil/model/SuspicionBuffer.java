package io.github.drepfy.vigil.model;

/**
 * Accumulates suspicious observations so a single odd event never produces a
 * flag. The value decays linearly over time and can be reduced by legitimate
 * observations.
 */
public final class SuspicionBuffer {

    private double value;
    private long lastUpdateMs = -1;

    /** Adds suspicion after applying time decay and returns the new value. */
    public double add(double amount, long nowMs, double decayPerSecond) {
        decay(nowMs, decayPerSecond);
        value += amount;
        return value;
    }

    /** Applies time based decay without adding anything. */
    public double decay(long nowMs, double decayPerSecond) {
        if (lastUpdateMs >= 0 && nowMs > lastUpdateMs && decayPerSecond > 0) {
            value = Math.max(0.0, value - decayPerSecond * (nowMs - lastUpdateMs) / 1000.0);
        }
        lastUpdateMs = nowMs;
        return value;
    }

    /** Reduces suspicion after a clearly legitimate observation. */
    public void reduce(double amount) {
        value = Math.max(0.0, value - amount);
    }

    public double value() {
        return value;
    }

    public void set(double newValue) {
        value = Math.max(0.0, newValue);
    }

    public void reset() {
        value = 0.0;
        lastUpdateMs = -1;
    }
}
