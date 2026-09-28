package io.github.drepfy.vigil.model;

/**
 * Wall-clock based "distance budget" for horizontal speed.
 *
 * <p>Allowance regenerates at {@code limitPerTick} for every 50 ms of real time
 * (not server ticks, so server lag never causes false positives) and is capped at
 * {@code capacity}. Travelled distance is subtracted. A negative balance means the
 * player covered more ground than physically possible over the last second or so.
 *
 * <p>Two forms of temporary credit protect legitimate players:
 * <ul>
 *   <li>a <b>bonus</b> granted when knockback/velocity is received (expires after
 *   a fixed time), and</li>
 *   <li><b>stall credit</b>: allowance that could not be banked during a gap of at
 *   least {@link #STALL_MS} without movement packets. The client kept moving during
 *   the gap and its queued packets arrive in a burst, so stall credit can only be
 *   spent by packets arriving in a burst ({@link TimerBalance#BURST_GAP_MS}). A
 *   player who stands still and then speeds at a normal packet pace cannot use it.
 *   It survives chained stalls and expires after {@link #STALL_FORGIVENESS_MS} of
 *   movement without new credit.</li>
 * </ul>
 */
public final class SpeedBudget {

    /** Gaps at least this long bank their unused allowance (normal packet pace is 50 ms). */
    public static final long STALL_MS = 150;
    public static final long STALL_FORGIVENESS_MS = 3000;
    /** Stall credit never exceeds this many ticks of allowance (30 seconds). */
    private static final double MAX_STALL_TICKS = 600.0;
    private static final double MAX_BONUS = 1000.0;

    private boolean initialised;
    private boolean fillOnNext;
    private long lastMs;
    private double balance;
    private double stallCredit;
    private double flowSinceCreditMs;
    private double bonus;
    private long bonusExpiryMs;

    /** Forgets all state; the next call to {@link #consume} starts with a full budget. */
    public void reset() {
        initialised = false;
        stallCredit = 0.0;
        flowSinceCreditMs = 0.0;
        bonus = 0.0;
        bonusExpiryMs = 0;
    }

    /**
     * Restarts with a full budget while keeping {@code nowMs} as the reference time,
     * so a connection stall that begins right now is still measured correctly. Used
     * for every movement during grace periods. Knockback bonus is kept.
     */
    public void restart(long nowMs) {
        initialised = true;
        fillOnNext = true;
        lastMs = nowMs;
        stallCredit = 0.0;
        flowSinceCreditMs = 0.0;
    }

    /**
     * Regenerates allowance, consumes the travelled distance and returns the
     * deficit. A result greater than zero means the player moved further than allowed.
     */
    public double consume(long nowMs, double distance, double limitPerTick, double capacity) {
        if (!initialised) {
            initialised = true;
            lastMs = nowMs;
            balance = capacity;
        }
        if (fillOnNext) {
            fillOnNext = false;
            balance = capacity;
        }
        long elapsed = Math.max(0L, nowMs - lastMs);
        lastMs = nowMs;

        double regen = limitPerTick * (elapsed / 50.0);
        double room = Math.max(0.0, capacity - balance);
        if (elapsed >= STALL_MS && regen > room) {
            stallCredit = Math.min(limitPerTick * MAX_STALL_TICKS, stallCredit + regen - room);
            flowSinceCreditMs = 0.0;
        } else {
            flowSinceCreditMs += elapsed;
            if (flowSinceCreditMs >= STALL_FORGIVENESS_MS) {
                stallCredit = 0.0;
            }
        }
        balance = Math.min(capacity, balance + regen);

        balance -= Math.max(0.0, distance);
        if (stallCredit > 0.0 && elapsed <= TimerBalance.BURST_GAP_MS) {
            // Part of a burst of queued packets: restore the budget from the stall so the
            // slightly compressed packets that trail a burst are covered as well.
            double used = Math.min(stallCredit, Math.max(0.0, capacity - balance));
            stallCredit -= used;
            balance += used;
        }
        if (balance < 0.0 && bonus > 0.0) {
            if (nowMs <= bonusExpiryMs) {
                double used = Math.min(bonus, -balance);
                bonus -= used;
                balance += used;
            } else {
                bonus = 0.0;
            }
        }
        return balance < 0.0 ? -balance : 0.0;
    }

    /** Grants temporary extra distance (e.g. after knockback). */
    public void grantBonus(double blocks, long nowMs, long durationMs) {
        if (blocks <= 0.0) {
            return;
        }
        if (nowMs > bonusExpiryMs) {
            bonus = 0.0;
        }
        bonus = Math.min(MAX_BONUS, bonus + blocks);
        bonusExpiryMs = Math.max(bonusExpiryMs, nowMs + durationMs);
    }

    /** Refills the budget, used after a flag so each flag needs a fresh burst of over-speed. */
    public void refill(double capacity) {
        balance = capacity;
    }

    public double balance() {
        return balance;
    }

    /** Knockback bonus plus stall credit currently available (for debug output). */
    public double bonus() {
        return bonus + stallCredit;
    }
}
