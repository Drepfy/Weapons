package io.github.drepfy.vigil.model;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Feeds simulated vanilla movement through the speed budget with the same limit
 * formula and default settings as {@code SpeedCheck}, and checks that legitimate
 * movement never produces a deficit while speed cheats do.
 */
class SpeedBudgetTest {

    private static final double LENIENCY = 1.25;
    private static final double BURST_TICKS = 20;
    private static final double EXTRA_BLOCKS = 2.0;
    private static final double ICE_MULTIPLIER = 3.2;
    private static final double CEILING_MULTIPLIER = 1.8;
    private static final double SLIME_MULTIPLIER = 2.0;

    private static double limit(double surfaceMultiplier, double speedFactor) {
        return Physics.SPRINT_JUMP_SPEED * surfaceMultiplier * speedFactor * LENIENCY;
    }

    private static double capacity(double limit) {
        return limit * BURST_TICKS + EXTRA_BLOCKS;
    }

    /** Runs {@code ticks} client ticks delivered every 50 ms; returns the largest deficit. */
    private static double run(VanillaMovementSim sim, int ticks, double limit, double distanceScale) {
        SpeedBudget budget = new SpeedBudget();
        double worst = 0.0;
        for (int tick = 0; tick < ticks; tick++) {
            double moved = sim.tick() * distanceScale;
            worst = Math.max(worst, budget.consume(tick * 50L, moved, limit, capacity(limit)));
        }
        return worst;
    }

    @Test
    void flatSprintJumpingNeverFlags() {
        VanillaMovementSim sim = new VanillaMovementSim(VanillaMovementSim.NORMAL, -1, true, true,
                Double.POSITIVE_INFINITY);
        assertEquals(0.0, run(sim, 20 * 120, limit(1.0, 1.0), 1.0));
    }

    @Test
    void headHitterSprintJumpingNeverFlags() {
        VanillaMovementSim sim = new VanillaMovementSim(VanillaMovementSim.NORMAL, -1, true, true, 0.2);
        assertEquals(0.0, run(sim, 20 * 120, limit(CEILING_MULTIPLIER, 1.0), 1.0));
    }

    @Test
    void iceAndBlueIceHeadHitterNeverFlag() {
        VanillaMovementSim ice = new VanillaMovementSim(VanillaMovementSim.ICE, -1, true, true, 0.2);
        assertEquals(0.0, run(ice, 20 * 120, limit(ICE_MULTIPLIER, 1.0), 1.0));
        VanillaMovementSim blueIce = new VanillaMovementSim(VanillaMovementSim.BLUE_ICE, -1, true, true, 0.2);
        assertEquals(0.0, run(blueIce, 20 * 120, limit(ICE_MULTIPLIER, 1.0), 1.0));
    }

    @Test
    void slimeSprintJumpingNeverFlags() {
        VanillaMovementSim sim = new VanillaMovementSim(VanillaMovementSim.SLIME, -1, true, true,
                Double.POSITIVE_INFINITY);
        assertEquals(0.0, run(sim, 20 * 120, limit(SLIME_MULTIPLIER, 1.0), 1.0));
    }

    @Test
    void speedTwoWithEverySurfaceNeverFlags() {
        double factor = 1.0 + 0.2 * 2;
        assertEquals(0.0, run(new VanillaMovementSim(VanillaMovementSim.NORMAL, 1, true, true,
                Double.POSITIVE_INFINITY), 20 * 120, limit(1.0, factor), 1.0));
        assertEquals(0.0, run(new VanillaMovementSim(VanillaMovementSim.NORMAL, 1, true, true, 0.2),
                20 * 120, limit(CEILING_MULTIPLIER, factor), 1.0));
        assertEquals(0.0, run(new VanillaMovementSim(VanillaMovementSim.BLUE_ICE, 1, true, true, 0.2),
                20 * 120, limit(ICE_MULTIPLIER, factor), 1.0));
    }

    @Test
    void networkJitterStallsAndClientFreezesNeverFlag() {
        Random random = new Random(42);
        for (int run = 0; run < 50; run++) {
            VanillaMovementSim sim = new VanillaMovementSim(VanillaMovementSim.NORMAL, -1, true, true, 0.2);
            SpeedBudget budget = new SpeedBudget();
            budget.restart(0);
            double limit = limit(CEILING_MULTIPLIER, 1.0);
            long lastArrival = 0;
            int frozenUntil = -1;
            for (int tick = 0; tick < 20 * 300; tick++) {
                // Client freeze: the frozen ticks are simulated back to back when it ends.
                if (tick > frozenUntil && random.nextInt(600) == 0) {
                    frozenUntil = tick + random.nextInt(10);
                }
                long sent = 50L * Math.max(tick, frozenUntil);
                long latency = 20 + random.nextInt(200);
                if (random.nextInt(300) == 0) {
                    // Connection hiccup: this and the following queued packets arrive late together.
                    latency += 300 + random.nextInt(9000);
                }
                long arrival = Math.max(lastArrival, sent + latency);
                lastArrival = arrival;
                double deficit = budget.consume(arrival, sim.tick(), limit, capacity(limit));
                assertEquals(0.0, deficit, "run " + run + " tick " + tick);
            }
        }
    }

    @Test
    void chainedStallsNeverFlag() {
        VanillaMovementSim sim = new VanillaMovementSim(VanillaMovementSim.NORMAL, -1, true, true, 0.2);
        SpeedBudget budget = new SpeedBudget();
        double limit = limit(CEILING_MULTIPLIER, 1.0);
        long lastArrival = 0;
        for (int tick = 0; tick < 20 * 60; tick++) {
            long latency = tick == 200 ? 5000 : tick == 230 ? 9000 : 100;
            long arrival = Math.max(lastArrival, tick * 50L + latency);
            lastArrival = arrival;
            assertEquals(0.0, budget.consume(arrival, sim.tick(), limit, capacity(limit)), "tick " + tick);
        }
    }

    @Test
    void speedCheatOnGroundIsDetected() {
        // 1.5x sprinting speed on flat ground (no jumping).
        VanillaMovementSim sim = new VanillaMovementSim(VanillaMovementSim.NORMAL, -1, true, false,
                Double.POSITIVE_INFINITY);
        SpeedBudget budget = new SpeedBudget();
        double limit = limit(1.0, 1.0);
        int flaggedAt = -1;
        for (int tick = 0; tick < 20 * 30 && flaggedAt < 0; tick++) {
            if (budget.consume(tick * 50L, sim.tick() * 2.0, limit, capacity(limit)) > 0) {
                flaggedAt = tick;
            }
        }
        assertTrue(flaggedAt > 0 && flaggedAt < 20 * 10, "2x ground speed should flag within 10s, was " + flaggedAt);
    }

    @Test
    void standingStillCannotBankAllowanceForSpeeding() {
        SpeedBudget budget = new SpeedBudget();
        double limit = limit(1.0, 1.0);
        budget.consume(0, 0.0, limit, capacity(limit));
        // Ten seconds without movement packets, then 3x speed at a normal packet pace.
        long start = 10_000;
        int flaggedAt = -1;
        for (int tick = 0; tick < 20 * 10 && flaggedAt < 0; tick++) {
            if (budget.consume(start + tick * 50L, limit / LENIENCY * 3.0, limit, capacity(limit)) > 0) {
                flaggedAt = tick;
            }
        }
        assertTrue(flaggedAt > 0 && flaggedAt < 20 * 2, "banked idle time must not hide speeding, was " + flaggedAt);
    }

    @Test
    void boostedSprintJumpingIsDetected() {
        VanillaMovementSim sim = new VanillaMovementSim(VanillaMovementSim.NORMAL, -1, true, true,
                Double.POSITIVE_INFINITY);
        double worst = run(sim, 20 * 30, limit(1.0, 1.0), 1.6);
        assertTrue(worst > 0, "1.6x sprint-jumping should exhaust the budget");
    }

    @Test
    void bonusCoversKnockbackBurst() {
        SpeedBudget budget = new SpeedBudget();
        double limit = limit(1.0, 1.0);
        budget.consume(0, 0.2, limit, capacity(limit));
        budget.grantBonus(1.5 * 15 + 2.0, 0, 3000);
        // A strong launch: 1.5 blocks/tick decaying with air drag.
        double velocity = 1.5;
        for (int tick = 1; tick < 40; tick++) {
            assertEquals(0.0, budget.consume(tick * 50L, velocity, limit, capacity(limit)));
            velocity *= 0.91;
        }
    }
}
