package io.github.drepfy.vigil.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UtilTest {

    @Test
    void hexColoursAndGradients() {
        assertEquals("\u00a7x\u00a7f\u00a7f\u00a78\u00a78\u00a70\u00a70x", Text.color("&#FF8800x"));
        // Two letters: the first gets the first colour, the last the last colour.
        assertEquals("\u00a7x\u00a7f\u00a7f\u00a70\u00a70\u00a70\u00a70a\u00a7x\u00a70\u00a70\u00a70\u00a70\u00a7f\u00a7fb",
                Text.color("<gradient:#FF0000:#0000FF>ab</gradient>"));
        // Bold inside a gradient is re-applied after every colour (a colour resets formatting).
        assertEquals("\u00a7x\u00a7f\u00a7f\u00a70\u00a70\u00a70\u00a70\u00a7la\u00a7x\u00a70\u00a70\u00a70\u00a70\u00a7f\u00a7f\u00a7lb",
                Text.color("<gradient:#FF0000:#0000FF>&lab</gradient>"));
        // Three colours: the middle letter gets the middle colour.
        String three = Text.color("<gradient:#FF0000:#00FF00:#0000FF>abc</gradient>");
        assertTrue(three.contains("\u00a7x\u00a70\u00a70\u00a7f\u00a7f\u00a70\u00a70b"), three);
        // Text around the gradient and normal codes still work.
        String brand = "<gradient:#FF3C3C:#FFA53C>&lV\u026a\u0262\u026a\u029f</gradient>";
        assertEquals("V\u026a\u0262\u026a\u029f | x", org.bukkit.ChatColor.stripColor(Text.color(brand + " &8| &7x")));
        assertEquals("V\u026a\u0262\u026a\u029f x y", Text.strip(brand + " &#123456x &cy"));
        assertEquals("", Text.color(null));
    }

    @Test
    void droppedIoWorkIsReported() throws Exception {
        java.util.logging.Logger logger = java.util.logging.Logger.getLogger("test");
        io.github.drepfy.vigil.storage.IoExecutor io = new io.github.drepfy.vigil.storage.IoExecutor(logger);
        assertTrue(io.execute("ok", () -> { }));
        io.shutdown(1000);
        assertFalse(io.execute("late", () -> { }), "work after shutdown is reported as dropped");
        // A record load that cannot be queued still completes, so joins never hang, and it keeps the
        // player's history instead of starting a fresh record that would overwrite it.
        java.nio.file.Path dir = java.nio.file.Files.createTempDirectory("vigil");
        java.util.UUID uuid = java.util.UUID.randomUUID();
        java.nio.file.Files.writeString(dir.resolve(uuid + ".yml"), "name: Steve\nlifetime-flags:\n  speed: 7\n");
        var store = new io.github.drepfy.vigil.storage.PlayerRecordStore(dir, io, logger);
        var record = store.loadOrCreate(uuid, "Steve").get(1, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(uuid, record.uuid());
        assertEquals(7, record.totalLifetimeFlags(), "existing history is kept");
        java.util.UUID fresh = java.util.UUID.randomUUID();
        assertEquals(0, store.loadOrCreate(fresh, "Alex").get(1, java.util.concurrent.TimeUnit.SECONDS)
                .totalLifetimeFlags());
        assertNull(store.loadExisting(fresh).get(1, java.util.concurrent.TimeUnit.SECONDS));
    }

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
