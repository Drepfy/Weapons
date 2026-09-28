package io.github.drepfy.vigil.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilTest {

    @Test
    void globMatching() {
        assertTrue(Glob.matches("*SHULKER_BOX", "RED_SHULKER_BOX"));
        assertTrue(Glob.matches("*SHULKER_BOX", "SHULKER_BOX"));
        assertFalse(Glob.matches("*SHULKER_BOX", "SHULKER_BOX_X"));
        assertTrue(Glob.matches("CHEST", "chest"));
        assertFalse(Glob.matches("CHEST", "TRAPPED_CHEST"));
        assertTrue(Glob.matches("OAK_*", "OAK_DOOR"));
        assertTrue(Glob.matches("*BOAT*", "OAK_CHEST_BOAT"));
        assertTrue(Glob.containsAny(List.of("BOAT", "HAPPY_GHAST"), "happy_ghast"));
        assertFalse(Glob.containsAny(List.of("BOAT"), "ZOMBIE"));
    }

    @Test
    void textFormatting() {
        assertEquals("1.5", Text.num(1.5));
        assertEquals("2", Text.num(2.0));
        assertEquals("0.33", Text.num(1.0 / 3.0));
        assertEquals("0", Text.num(-0.0001));
        assertEquals("a=1 b=x", Text.replace("a={a} b={b}", "a", 1, "b", "x"));
        assertEquals("3m 12s", Text.duration(192_000));
        assertEquals("2h 0m", Text.duration(7_200_000));
    }

    @Test
    void clockIsMonotonic() {
        long first = Clock.now();
        long second = Clock.now();
        assertTrue(second >= first);
    }
}
