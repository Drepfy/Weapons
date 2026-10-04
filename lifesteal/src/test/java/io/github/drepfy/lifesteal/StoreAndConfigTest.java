package io.github.drepfy.lifesteal;

import io.github.drepfy.lifesteal.config.Settings;
import io.github.drepfy.lifesteal.config.SettingsLoader;
import io.github.drepfy.lifesteal.data.LifestealStore;
import io.github.drepfy.lifesteal.util.Durations;
import io.github.drepfy.lifesteal.util.Text;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreAndConfigTest {

    private static final Logger LOGGER = Logger.getLogger("test");

    private static YamlConfiguration bundled() throws Exception {
        try (Reader reader = new InputStreamReader(
                Objects.requireNonNull(StoreAndConfigTest.class.getResourceAsStream("/config.yml")),
                StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    @Test
    void theBundledConfigIsTheSpec() throws Exception {
        Settings settings = SettingsLoader.load(bundled());
        assertEquals(List.of(), settings.warnings());
        assertEquals(10, settings.startHearts());
        assertEquals(3, settings.minHearts());
        assertEquals(20, settings.maxHearts());
        assertEquals(1, settings.perKill());
        assertEquals(30L * 60 * 1000, settings.cooldownMs());
        assertEquals(17, settings.withdraw().maxPerCommand());
        assertEquals(Material.RED_DYE, settings.item().material());
        assertEquals(List.of("DND", "DSD", "DND"), settings.recipe().shape());
        assertEquals(Material.NETHER_STAR, settings.recipe().ingredients().get('S'));
        assertTrue(settings.alts().enabled());
        // Every message has a default, and the file lists them all.
        for (String key : List.of("steal-killer", "withdraw-too-low", "alt-staff", "consume")) {
            assertTrue(bundled().contains("messages." + key), key);
        }
        assertEquals(SettingsLoader.load(new YamlConfiguration()).messages(), settings.messages(),
                "the file and the built-in defaults say the same");
    }

    @Test
    void badValuesFallBackAndAreReported() throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString("""
                hearts: {min: 25, max: 20, start: 50, per-kill: zero}
                cooldown: {time: soon}
                heart-item: {material: NOT_A_THING}
                recipe:
                  shape: ["AB", "ABC"]
                  ingredients: {A: DIAMOND}
                resource-pack: {url: "ftp://example.com/pack.zip", sha1: "xyz"}
                """);
        Settings settings = SettingsLoader.load(yaml);
        assertEquals(3, settings.minHearts());
        assertEquals(20, settings.maxHearts());
        assertEquals(10, settings.startHearts());
        assertEquals(1, settings.perKill());
        assertEquals(30L * 60 * 1000, settings.cooldownMs());
        assertEquals(Material.RED_DYE, settings.item().material());
        assertEquals(List.of("DND", "DSD", "DND"), settings.recipe().shape(), "a broken recipe uses the default");
        assertEquals("", settings.resourcePack().url());
        assertEquals("", settings.resourcePack().sha1());
        String warnings = String.join("\n", settings.warnings());
        for (String part : List.of("hearts.min", "hearts.start", "hearts.per-kill", "cooldown.time",
                "heart-item.material", "recipe.shape", "resource-pack.url", "resource-pack.sha1")) {
            assertTrue(warnings.contains(part), part + " in " + warnings);
        }
    }

    @Test
    void dataSurvivesRestartsAndIpsAreNotStoredAsWritten(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.yml");
        LifestealStore store = new LifestealStore(LOGGER, file);
        UUID steve = UUID.randomUUID();
        UUID alex = UUID.randomUUID();
        UUID alt = UUID.randomUUID();
        long now = 1_700_000_000_000L;
        store.setHearts(store.entry(steve, "Steve", 10), 17);
        store.entry(alex, "Alex", 10);
        store.entry(alt, "AlexAlt", 10);
        store.setCooldown(steve, alex, now + 60_000);
        store.setCooldown(alex, steve, now - 1);
        store.setLinked(alex, alt, true);
        store.setAllowed(steve, alex, true);
        store.recordIp(alex, "203.0.113.9", now);
        store.recordIp(alt, "203.0.113.9", now);
        store.recordIp(steve, "198.51.100.1", now - 40L * 24 * 3600 * 1000);
        store.close(now, now - 30L * 24 * 3600 * 1000);

        String written = Files.readString(file);
        assertFalse(written.contains("203.0.113.9"), "only a scrambled code of the address is kept");

        LifestealStore loaded = new LifestealStore(LOGGER, file);
        assertEquals(17, loaded.entry(steve).hearts());
        assertEquals("Alex", loaded.entry(alex).name());
        assertEquals(steve, loaded.findByName("steve"));
        assertEquals(now + 60_000, loaded.cooldownUntil(steve, alex));
        assertEquals(0L, loaded.cooldownUntil(alex, steve), "finished cooldowns are dropped");
        assertTrue(loaded.linked(alt, alex));
        assertTrue(loaded.allowed(alex, steve));
        assertEquals(loaded.ipsSince(alex, 0), loaded.ipsSince(alt, 0), "same address, same code");
        assertEquals(2, loaded.accountsOnIp(loaded.ipsSince(alex, 0).iterator().next()).size());
        assertTrue(loaded.ipsSince(steve, 0).isEmpty(), "addresses older than 30 days are forgotten");
        loaded.close(now, 0);
    }

    @Test
    void unreadableDataIsMovedAsideNotLost(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("data.yml");
        Files.writeString(file, "players: [broken");
        LifestealStore store = new LifestealStore(LOGGER, file);
        assertNull(store.entry(UUID.randomUUID()));
        try (var files = Files.list(dir)) {
            assertTrue(files.anyMatch(path -> path.getFileName().toString().startsWith("data.yml.corrupt-")));
        }
        store.close(0, 0);
    }

    @Test
    void timesAndText() {
        assertEquals(30L * 60 * 1000, Durations.parse("30m"));
        assertEquals(90L * 60 * 1000, Durations.parse("1h30m"));
        assertEquals(0L, Durations.parse("0"));
        assertNull(Durations.parse("soon"));
        assertNull(Durations.parse("30"));
        assertEquals("29m 59s", Durations.format(30L * 60 * 1000 - 1000));
        assertEquals("1h 0m", Durations.format(3_600_000L));
        assertEquals("You have §c{x} hearts", Text.format("You have &c{n} hearts", "n", "{x}"),
                "names are inserted as typed");
        assertEquals("", Text.plural(1));
        assertEquals("s", Text.plural(2));
    }
}
