package io.github.drepfy.vigil.model;

/**
 * Detects clients that simulate more ticks than real time allows ("timer").
 *
 * <p>Every client tick (movement packet or client tick-end packet) costs 50 ms of
 * budget; real elapsed time adds budget. The balance is capped so a timer cannot run
 * on time banked while idle.
 *
 * <p>Connection stalls and client freezes: the client keeps ticking (or catches up
 * several ticks at once) and its packets then arrive in a burst. Elapsed time above
 * the cap is therefore not thrown away but kept as <i>stall credit</i>, which:
 * <ul>
 *   <li>can only be spent by packets arriving in a burst (at most
 *   {@link #BURST_GAP_MS} apart), exactly how queued packets arrive after a stall,
 *   so a timer running at a normal packet pace can never use banked time;</li>
 *   <li>survives chained stalls and expires once packets have been arriving
 *   without adding new credit for the forgiveness period.</li>
 * </ul>
 * Credit only ever represents real elapsed time, so a client can never gain ticks
 * through it; it can only be forgiven for delivering them late.
 */
public final class TimerBalance {

    private static final double MS_PER_TICK = 50.0;
    /** Packets arriving at most this far apart belong to a burst. */
    public static final long BURST_GAP_MS = 20;
    /** Longer than any connection survives without keep-alives. */
    private static final double MAX_STALL_CREDIT_MS = 60_000.0;

    private boolean initialised;
    private boolean fillOnNext;
    private long lastMs;
    private double balanceMs;
    private double stallCreditMs;
    private double flowSinceCreditMs;

    /** Forgets everything; the next client tick only establishes the reference time. */
    public void reset() {
        initialised = false;
        balanceMs = 0.0;
        stallCreditMs = 0.0;
        flowSinceCreditMs = 0.0;
    }

    /**
     * Restarts with full credit while keeping {@code nowMs} as the reference time,
     * so a connection stall that begins right now is still measured correctly.
     * Used for every client tick during grace periods.
     */
    public void restart(long nowMs) {
        initialised = true;
        fillOnNext = true;
        lastMs = nowMs;
        stallCreditMs = 0.0;
        flowSinceCreditMs = 0.0;
    }

    /**
     * Records one client tick.
     *
     * @return the debt in milliseconds if it exceeds {@code maxDebtMs}, otherwise 0
     */
    public double onClientTick(long nowMs, double maxCreditMs, long stallForgivenessMs, double maxDebtMs) {
        if (!initialised) {
            // Start with full credit: the first packets after a reset often arrive bunched.
            initialised = true;
            lastMs = nowMs;
            balanceMs = maxCreditMs;
            return 0.0;
        }
        if (fillOnNext) {
            fillOnNext = false;
            balanceMs = maxCreditMs;
        }
        long gap = Math.max(0L, nowMs - lastMs);
        lastMs = nowMs;

        double total = balanceMs + gap;
        if (total > maxCreditMs) {
            stallCreditMs = Math.min(MAX_STALL_CREDIT_MS, stallCreditMs + total - maxCreditMs);
            balanceMs = maxCreditMs;
            flowSinceCreditMs = 0.0;
        } else {
            balanceMs = total;
            flowSinceCreditMs += gap;
            if (flowSinceCreditMs >= stallForgivenessMs) {
                stallCreditMs = 0.0;
            }
        }

        balanceMs -= MS_PER_TICK;
        if (stallCreditMs > 0.0 && gap <= BURST_GAP_MS) {
            // Part of a burst of queued packets: restore the balance from the stall so the
            // slightly compressed packets that trail a burst are covered as well.
            double used = Math.min(stallCreditMs, Math.max(0.0, maxCreditMs - balanceMs));
            stallCreditMs -= used;
            balanceMs += used;
        }

        if (balanceMs < -maxDebtMs) {
            double debt = -balanceMs;
            balanceMs = 0.0;
            return debt;
        }
        return 0.0;
    }

    public double balanceMs() {
        return balanceMs;
    }

    public double stallCreditMs() {
        return stallCreditMs;
    }
}
