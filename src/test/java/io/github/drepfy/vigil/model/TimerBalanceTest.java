package io.github.drepfy.vigil.model;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Timer detection with the default settings (600 ms debt, 1000 ms credit, 2500 ms
 * stall forgiveness).
 */
class TimerBalanceTest {

    private static final double MAX_CREDIT = 1000;
    private static final long FORGIVENESS = 2500;
    private static final double MAX_DEBT = 600;

    private static double tick(TimerBalance balance, long arrival) {
        return balance.onClientTick(arrival, MAX_CREDIT, FORGIVENESS, MAX_DEBT);
    }

    @Test
    void steadyClientNeverFlags() {
        TimerBalance balance = new TimerBalance();
        for (int i = 0; i < 20 * 600; i++) {
            assertEquals(0.0, tick(balance, i * 50L));
        }
    }

    @Test
    void jitterLagSpikesAndCatchUpNeverFlag() {
        Random random = new Random(7);
        for (int run = 0; run < 50; run++) {
            TimerBalance balance = new TimerBalance();
            balance.restart(0);
            long lastArrival = 0;
            int frozenUntil = -1;
            for (int i = 0; i < 20 * 300; i++) {
                // A client freeze of k ticks: those ticks are simulated back to back (catch-up)
                // when the freeze ends, so they are all sent at the same moment.
                if (i > frozenUntil && random.nextInt(600) == 0) {
                    frozenUntil = i + random.nextInt(10);
                }
                long sent = 50L * Math.max(i, frozenUntil);
                long latency = 20 + random.nextInt(200);
                if (random.nextInt(300) == 0) {
                    latency += 1000 + random.nextInt(8000);
                }
                long arrival = Math.max(lastArrival, sent + latency);
                lastArrival = arrival;
                assertEquals(0.0, tick(balance, arrival), "run " + run + " packet " + i);
            }
        }
    }

    @Test
    void chainedStallsNeverFlag() {
        // Packet 200 is held for 5 s; while its backlog drains, packet 230 is held for 9 s.
        TimerBalance balance = new TimerBalance();
        long lastArrival = 0;
        for (int i = 0; i < 20 * 60; i++) {
            long latency = i == 200 ? 5000 : i == 230 ? 9000 : 100;
            long arrival = Math.max(lastArrival, i * 50L + latency);
            lastArrival = arrival;
            assertEquals(0.0, tick(balance, arrival), "packet " + i);
        }
    }

    @Test
    void speedUpIsDetected() {
        TimerBalance balance = new TimerBalance();
        int flaggedAt = -1;
        // 1.2x timer: a tick every ~41.7 ms.
        for (int i = 0; i < 20 * 120 && flaggedAt < 0; i++) {
            if (tick(balance, Math.round(i * 50 / 1.2)) > 0) {
                flaggedAt = i;
            }
        }
        assertTrue(flaggedAt > 0 && flaggedAt < 20 * 30, "1.2x timer should flag within 30s, was " + flaggedAt);
    }

    @Test
    void bankedIdleTimeIsCapped() {
        TimerBalance balance = new TimerBalance();
        tick(balance, 0);
        // Standing still for a minute banks at most max-credit (not a minute).
        long start = 60_000;
        int flaggedAt = -1;
        for (int i = 0; i < 400 && flaggedAt < 0; i++) {
            if (tick(balance, start + i * 25L) > 0) {
                flaggedAt = i;
            }
        }
        assertTrue(flaggedAt > 0, "2x timer after idling must still be detected");
    }
}
