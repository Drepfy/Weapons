package io.github.drepfy.vigil.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The flight tracker with the default parameters (1000 ms window, 1.0 block minimum
 * descent, 1.0 block ascend tolerance, 4000 ms impulse rise).
 */
class FlightTrackerTest {

    private static final FlightTracker.Params PARAMS = new FlightTracker.Params(500, 0.5, 0.8, 4000);
    private static final double APEX = Physics.jumpApex(Physics.DEFAULT_JUMP_STRENGTH);

    /** Simulates a vertical trajectory from an initial velocity until landing on groundY. */
    private static int flagsForTrajectory(FlightTracker tracker, double startY, double initialVelocity,
                                          double groundY, double apex) {
        int flags = 0;
        double y = startY;
        double vy = initialVelocity;
        for (int tick = 0; tick < 2000; tick++) {
            y += vy;
            vy = (vy - Physics.GRAVITY) * Physics.VERTICAL_DRAG;
            boolean landed = y <= groundY;
            if (landed) {
                y = groundY;
            }
            if (tracker.sample(y, landed, false, 50, apex, PARAMS) != FlightTracker.Verdict.NONE) {
                flags++;
            }
            if (landed) {
                break;
            }
        }
        return flags;
    }

    @Test
    void jumpApexMatchesVanilla() {
        assertEquals(1.2522, APEX, 0.001);
    }

    @Test
    void repeatedJumpsNeverFlag() {
        FlightTracker tracker = new FlightTracker();
        tracker.reset(64, false);
        for (int i = 0; i < 200; i++) {
            assertEquals(0, flagsForTrajectory(tracker, 64, 0.42, 64, APEX));
        }
    }

    @Test
    void jumpBoostJumpsNeverFlag() {
        for (int amplifier = 0; amplifier <= 20; amplifier++) {
            double velocity = Physics.jumpVelocity(Physics.DEFAULT_JUMP_STRENGTH, amplifier);
            FlightTracker tracker = new FlightTracker();
            tracker.reset(64, false);
            assertEquals(0, flagsForTrajectory(tracker, 64, velocity, 64, Physics.jumpApex(velocity)),
                    "jump boost amplifier " + amplifier);
        }
    }

    @Test
    void fallingOffACliffNeverFlags() {
        FlightTracker tracker = new FlightTracker();
        tracker.reset(200, false);
        assertEquals(0, flagsForTrajectory(tracker, 200, 0.0, -60, APEX));
    }

    @Test
    void knockbackLaunchNeverFlags() {
        FlightTracker tracker = new FlightTracker();
        tracker.reset(64, false);
        tracker.impulse(64);
        // Launched upwards far above a normal jump (e.g. by an explosion).
        assertEquals(0, flagsForTrajectory(tracker, 64, 3.0, 64, APEX));
    }

    @Test
    void slimeBounceNeverFlags() {
        FlightTracker tracker = new FlightTracker();
        tracker.reset(100, false);
        // Standing on slime: the next takeoff counts as an impulse.
        tracker.sample(64, true, true, 50, APEX, PARAMS);
        assertEquals(0, flagsForTrajectory(tracker, 64, 2.5, 64, APEX));
    }

    @Test
    void frozenClientNeverAccumulatesAirtime() {
        FlightTracker tracker = new FlightTracker();
        tracker.reset(64, false);
        tracker.sample(65, false, false, 50, APEX, PARAMS);
        for (int tick = 0; tick < 20 * 60; tick++) {
            // No packets: position unchanged and no client time passes.
            assertEquals(FlightTracker.Verdict.NONE, tracker.sample(65, false, false, 0, APEX, PARAMS));
        }
    }

    @Test
    void clientWaitingForChunksSinksSlowlyWithoutFlag() {
        FlightTracker tracker = new FlightTracker();
        tracker.reset(100, false);
        double y = 100;
        for (int tick = 0; tick < 20 * 30; tick++) {
            // Vanilla client in an unloaded chunk sinks at 0.1 blocks per tick.
            y -= 0.1;
            assertEquals(FlightTracker.Verdict.NONE, tracker.sample(y, false, false, 50, APEX, PARAMS));
        }
    }

    @Test
    void hoveringIsDetectedWithinTwoSeconds() {
        FlightTracker tracker = new FlightTracker();
        tracker.reset(64, false);
        int firstVerdict = -1;
        for (int tick = 0; tick < 100 && firstVerdict < 0; tick++) {
            if (tracker.sample(70, false, false, 50, APEX, PARAMS) == FlightTracker.Verdict.HOVER) {
                firstVerdict = tick;
            }
        }
        assertTrue(firstVerdict > 0 && firstVerdict <= 40, "hover verdict at tick " + firstVerdict);
    }

    @Test
    void slowGlideAndBobbingAreDetected() {
        FlightTracker glide = new FlightTracker();
        glide.reset(100, false);
        FlightTracker bob = new FlightTracker();
        bob.reset(100, false);
        int glideVerdicts = 0;
        int bobVerdicts = 0;
        for (int tick = 0; tick < 200; tick++) {
            if (glide.sample(100 - tick * 0.03, false, false, 50, APEX, PARAMS) != FlightTracker.Verdict.NONE) {
                glideVerdicts++;
            }
            double bobY = 100 + (tick % 2 == 0 ? 0.05 : 0.0);
            if (bob.sample(bobY, false, false, 50, APEX, PARAMS) != FlightTracker.Verdict.NONE) {
                bobVerdicts++;
            }
        }
        assertTrue(glideVerdicts >= 5, "glide verdicts " + glideVerdicts);
        assertTrue(bobVerdicts >= 5, "bobbing verdicts " + bobVerdicts);
    }

    @Test
    void flyingUpIsDetected() {
        FlightTracker tracker = new FlightTracker();
        tracker.reset(64, false);
        FlightTracker.Verdict verdict = FlightTracker.Verdict.NONE;
        for (int tick = 1; tick < 40 && verdict == FlightTracker.Verdict.NONE; tick++) {
            verdict = tracker.sample(64 + tick * 0.3, false, false, 50, APEX, PARAMS);
        }
        assertEquals(FlightTracker.Verdict.ASCEND, verdict);
    }

    @Test
    void impulseDoesNotAllowEndlessRise() {
        FlightTracker tracker = new FlightTracker();
        tracker.reset(64, false);
        tracker.impulse(64);
        FlightTracker.Verdict verdict = FlightTracker.Verdict.NONE;
        for (int tick = 1; tick < 400 && verdict == FlightTracker.Verdict.NONE; tick++) {
            verdict = tracker.sample(64 + tick * 0.3, false, false, 50, APEX, PARAMS);
        }
        assertEquals(FlightTracker.Verdict.ASCEND, verdict);
    }
}
