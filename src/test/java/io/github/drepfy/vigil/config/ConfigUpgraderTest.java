package io.github.drepfy.vigil.config;

import io.github.drepfy.vigil.moderation.Durations;
import io.github.drepfy.vigil.moderation.PunishmentType;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUpgraderTest {

    private static YamlConfiguration resource(String name) throws Exception {
        try (Reader reader = new InputStreamReader(
                Objects.requireNonNull(ConfigUpgraderTest.class.getResourceAsStream(name), name),
                StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    /** Saves and reloads, like the plugin does, so the result must also survive YAML. */
    private static YamlConfiguration roundTrip(YamlConfiguration yaml) throws Exception {
        YamlConfiguration copy = new YamlConfiguration();
        copy.loadFromString(yaml.saveToString());
        return copy;
    }

    @Test
    void untouchedOldConfigGetsEverythingNew() throws Exception {
        YamlConfiguration old = resource("/config-2.2.yml");
        List<String> changes = ConfigUpgrader.upgrade(old, resource("/config.yml"));
        for (String expected : List.of("messages.ban-screen", "messages.ban-screen-permanent",
                "messages.ban-screen-anticheat", "moderation.reasons.ban", "moderation.reasons.mute",
                "anticheat.auto-ban.duration", "moderation.appeal", "moderation.warn-escalation", "discord")) {
            assertTrue(changes.contains(expected), expected + " in " + changes);
        }

        YamlConfiguration saved = roundTrip(old);
        assertEquals(List.of(), ConfigUpgrader.upgrade(saved, resource("/config.yml")), "the next start changes nothing");
        Settings upgraded = ConfigLoader.load(saved);
        assertEquals(List.of(), upgraded.warnings());
        Settings fresh = ConfigLoader.load(resource("/config.yml"));
        assertEquals(fresh.moderation(), upgraded.moderation());
        assertEquals(fresh.messages(), upgraded.messages());
        assertEquals(fresh.autoBan(), upgraded.autoBan());
        assertEquals(fresh.discord(), upgraded.discord());
        assertEquals(Durations.PERMANENT, upgraded.autoBan().durationFor(1), "second auto-ban is permanent");
    }

    @Test
    void ownerChangesAreKept() throws Exception {
        YamlConfiguration old = resource("/config-2.2.yml");
        old.set("messages.ban-screen", List.of("&cGo away, {player}", "{reason}"));
        old.set("moderation.reasons.ban.Cheating", "90d");
        old.set("anticheat.auto-ban.duration", "14d");
        ConfigUpgrader.upgrade(old, resource("/config.yml"));

        Settings upgraded = ConfigLoader.load(roundTrip(old));
        assertEquals("&cGo away, {player}\n{reason}", upgraded.messages().get("ban-screen"));
        var cheating = io.github.drepfy.vigil.moderation.ReasonPreset.find(
                upgraded.moderation().reasons(PunishmentType.BAN), "Cheating");
        assertEquals(List.of(90L * 24 * 3600 * 1000), cheating.durations(), "edited presets are not replaced");
        assertEquals(List.of(14L * 24 * 3600 * 1000), upgraded.autoBan().durations());
        // New options still arrive.
        assertTrue(old.contains("moderation.warn-escalation"));
        assertTrue(old.contains("messages.ban-screen-permanent"));
    }

    @Test
    void currentConfigNeedsNoChanges() throws Exception {
        assertEquals(List.of(), ConfigUpgrader.upgrade(resource("/config.yml"), resource("/config.yml")));
        YamlConfiguration notVigil = new YamlConfiguration();
        notVigil.set("config-version", 1);
        assertEquals(List.of(), ConfigUpgrader.upgrade(notVigil, resource("/config.yml")));
        assertFalse(notVigil.contains("discord"), "1.x files are left to the migration");
    }
}
