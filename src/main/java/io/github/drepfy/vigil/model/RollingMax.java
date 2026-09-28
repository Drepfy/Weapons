package io.github.drepfy.vigil.model;

/**
 * Maximum of a value over a sliding time window, bucketed per second. Used to keep
 * the highest recent ping so short lag spikes still make checks more lenient.
 */
public final class RollingMax {

    private final long[] bucketTimes;
    private final double[] bucketValues;
    private final long bucketMs;

    public RollingMax(int buckets, long bucketMs) {
        this.bucketTimes = new long[buckets];
        this.bucketValues = new double[buckets];
        this.bucketMs = bucketMs;
        java.util.Arrays.fill(bucketTimes, Long.MIN_VALUE);
    }

    public void record(long nowMs, double value) {
        long bucket = Math.floorDiv(nowMs, bucketMs);
        int index = (int) Math.floorMod(bucket, (long) bucketTimes.length);
        if (bucketTimes[index] != bucket) {
            bucketTimes[index] = bucket;
            bucketValues[index] = value;
        } else {
            bucketValues[index] = Math.max(bucketValues[index], value);
        }
    }

    /** Maximum recorded within the window ending now, or {@code fallback} if empty. */
    public double max(long nowMs, double fallback) {
        long current = Math.floorDiv(nowMs, bucketMs);
        long oldest = current - bucketTimes.length + 1;
        double best = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < bucketTimes.length; i++) {
            if (bucketTimes[i] >= oldest && bucketTimes[i] <= current) {
                best = Math.max(best, bucketValues[i]);
            }
        }
        return best == Double.NEGATIVE_INFINITY ? fallback : best;
    }
}
