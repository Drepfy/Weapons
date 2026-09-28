package io.github.drepfy.vigil.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmallModelsTest {

    @Test
    void violationLevelDecaysLinearlyPerMinute() {
        ViolationLevel level = new ViolationLevel();
        assertEquals(1.0, level.add(1.0, 0, 1.0), 1e-9);
        assertEquals(3.0, level.add(2.0, 0, 1.0), 1e-9);
        assertEquals(2.0, level.get(60_000, 1.0), 1e-9);
        assertEquals(0.0, level.get(10 * 60_000, 1.0), 1e-9);
        assertEquals(2, level.totalFlags());
    }

    @Test
    void suspicionBufferDecaysAndReduces() {
        SuspicionBuffer buffer = new SuspicionBuffer();
        assertEquals(1.0, buffer.add(1.0, 0, 0.5), 1e-9);
        assertEquals(1.5, buffer.add(1.0, 1000, 0.5), 1e-9);
        buffer.reduce(1.0);
        assertEquals(0.5, buffer.value(), 1e-9);
        assertEquals(0.0, buffer.decay(10_000, 0.5), 1e-9);
    }

    @Test
    void rollingMaxForgetsOldSpikes() {
        RollingMax max = new RollingMax(10, 1000);
        max.record(0, 300);
        max.record(1000, 50);
        assertEquals(300, max.max(5_000, 0), 1e-9);
        assertEquals(50, max.max(10_500, 0), 1e-9);
        assertEquals(7, max.max(60_000, 7), 1e-9);
    }

    @Test
    void rateLimiterRefills() {
        RateLimiter limiter = new RateLimiter();
        int granted = 0;
        for (int i = 0; i < 100; i++) {
            if (limiter.tryAcquire(0, 5)) {
                granted++;
            }
        }
        assertEquals(5, granted);
        assertFalse(limiter.tryAcquire(100, 5));
        assertTrue(limiter.tryAcquire(1000, 5));
    }
}
