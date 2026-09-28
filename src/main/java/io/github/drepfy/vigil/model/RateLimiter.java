package io.github.drepfy.vigil.model;

/**
 * Simple token bucket used as a performance guard for expensive operations.
 */
public final class RateLimiter {

    private double tokens = -1;
    private long lastMs;

    /** Takes one token if available. Refills at {@code perSecond} tokens per second. */
    public boolean tryAcquire(long nowMs, double perSecond) {
        if (perSecond <= 0) {
            return false;
        }
        if (tokens < 0) {
            tokens = perSecond;
            lastMs = nowMs;
        }
        long elapsed = Math.max(0L, nowMs - lastMs);
        lastMs = nowMs;
        tokens = Math.min(perSecond, tokens + perSecond * elapsed / 1000.0);
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }
}
