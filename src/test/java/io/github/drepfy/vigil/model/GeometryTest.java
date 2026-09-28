package io.github.drepfy.vigil.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeometryTest {

    @Test
    void boxDistanceIsZeroInsideAndEuclideanOutside() {
        Box box = Box.ofEntity(0, 0, 0, 0.6, 1.8);
        assertEquals(0.0, box.distanceTo(0, 1, 0), 1e-9);
        assertEquals(2.7, box.distanceTo(3.0, 1.0, 0.0), 1e-9);
        assertEquals(Math.sqrt(2 * 0.7 * 0.7), box.distanceTo(1.0, 1.0, 1.0), 1e-9);
    }

    @Test
    void rayIntersection() {
        Box box = new Box(2, 0, -0.5, 3, 2, 0.5);
        assertTrue(box.intersectsRay(0, 1, 0, 1, 0, 0));
        assertFalse(box.intersectsRay(0, 1, 0, -1, 0, 0));
        assertFalse(box.intersectsRay(0, 1, 0, 0, 0, 1));
        assertTrue(box.intersectsRay(0, 1, 0, 1, 0.2, 0.1));
    }

    @Test
    void directionFollowsMinecraftConventions() {
        double[] south = AngleMath.direction(0, 0);
        assertEquals(1.0, south[2], 1e-9);
        double[] west = AngleMath.direction(90, 0);
        assertEquals(-1.0, west[0], 1e-9);
        double[] down = AngleMath.direction(0, 90);
        assertEquals(-1.0, down[1], 1e-9);
    }

    @Test
    void angleToBoxIsZeroWhenLookingAtIt() {
        Box target = Box.ofEntity(0, 0, 3, 0.6, 1.8);
        assertEquals(0.0, AngleMath.angleToBox(0, 1.62, 0, AngleMath.direction(0, 0), target), 1e-9);
        double behind = AngleMath.angleToBox(0, 1.62, 0, AngleMath.direction(180, 0), target);
        // Closest point is a bottom corner of the box: atan2(sqrt(0.3^2 + 1.62^2), 2.7) from straight behind.
        assertTrue(behind > 145, "target behind the player, angle " + behind);
    }

    @Test
    void rotationArcCoversAFlickThroughTheTarget() {
        Box target = Box.ofEntity(0, 0, 3, 0.6, 1.8);
        // Server saw the player looking 90 degrees left, then 90 degrees right: the flick
        // passed through the target in between.
        double arc = AngleMath.minAngleOverArc(0, 1.62, 0, 90, 0, -90, 0, List.of(target));
        assertEquals(0.0, arc, 1e-9);
        double noFlick = AngleMath.minAngleOverArc(0, 1.62, 0, 150, 0, 170, 0, List.of(target));
        assertTrue(noFlick > 90, "target never in view, angle " + noFlick);
    }

    @Test
    void wrapDegrees() {
        assertEquals(-170.0, AngleMath.wrapDegrees(190), 1e-9);
        assertEquals(170.0, AngleMath.wrapDegrees(-190), 1e-9);
        assertEquals(0.0, AngleMath.wrapDegrees(720), 1e-9);
    }

    @Test
    void positionHistoryCompensatesLag() {
        PositionHistory history = new PositionHistory(40);
        // Target walks away from the attacker at 0.25 blocks per tick along +Z.
        for (int tick = 0; tick < 30; tick++) {
            history.record(tick * 50L, 0, 0, 2.0 + tick * 0.25, 0.6, 1.8);
        }
        long now = 29 * 50L;
        double current = history.minDistanceSince(now, 0, 1.62, 0);
        double compensated = history.minDistanceSince(now - 300, 0, 1.62, 0);
        // A zero-length window still includes the previous sample (the position being left).
        assertEquals(2.0 + 28 * 0.25 - 0.3, current, 1e-9);
        assertTrue(compensated < current - 1.4, "300 ms of history should be ~1.5 blocks closer");
        // Interpolated boxes exist between samples.
        assertTrue(history.boxesSince(now - 100).size() > 3);
        assertEquals(0.25, history.averageHorizontalSpeed(now - 1000), 1e-9);
    }

    @Test
    void physicsReferenceValues() {
        assertEquals(1.2522, Physics.jumpApex(0.42), 1e-3);
        assertEquals(0.52, Physics.jumpVelocity(0.42, 0), 1e-9);
        assertEquals(0.42, Physics.jumpVelocity(0.42, -1), 1e-9);
        double oneSecond = Physics.freeFallDistance(20);
        assertTrue(oneSecond > 12 && oneSecond < 16, "free fall in 1s " + oneSecond);
        assertEquals(0.6, Physics.maxSingleTickAscent(0.42, 0.6), 1e-9);
    }
}
