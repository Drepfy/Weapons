package io.github.drepfy.vigil.config;

import io.github.drepfy.vigil.api.CheckType;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static YamlConfiguration bundled() throws Exception {
        try (Reader reader = new InputStreamReader(
                Objects.requireNonNull(ConfigLoaderTest.class.getResourceAsStream("/config.yml")),
                StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    private static YamlConfiguration yaml(String text) throws Exception {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.loadFromString(text);
        return yaml;
    }

    @Test
    void bundledConfigLoadsWithoutWarnings() throws Exception {
        Settings settings = ConfigLoader.load(bundled());
        assertEquals(List.of(), settings.warnings());
        assertTrue(settings.general().enabled());
        assertFalse(settings.punishments().enabled(), "automatic punishments must be off by default");
        assertTrue(settings.punishments().dryRun());
    }

    @Test
    void bundledConfigMatchesSpecDefaultsExactly() throws Exception {
        YamlConfiguration yaml = bundled();
        for (CheckType type : CheckType.values()) {
            CheckSpec spec = CheckSpec.of(type);
            ConfigurationSection section = yaml.getConfigurationSection("checks." + type.id());
            assertNotNull(section, "config.yml is missing checks." + type.id());
            CheckSpec.Defaults d = spec.defaults();
            assertEquals(d.enabled(), section.getBoolean("enabled"), type.id() + ".enabled");
            assertEquals(d.alertVl(), section.getDouble("alert-vl"), 1e-9, type.id() + ".alert-vl");
            assertEquals(d.reviewVl(), section.getDouble("review-vl"), 1e-9, type.id() + ".review-vl");
            assertEquals(d.decayPerMinute(), section.getDouble("decay-per-minute"), 1e-9, type.id());
            assertEquals(d.vlPerFlag(), section.getDouble("vl-per-flag"), 1e-9, type.id());
            assertEquals(d.bufferThreshold(), section.getDouble("buffer-threshold"), 1e-9, type.id());
            assertEquals(d.mitigate(), section.getBoolean("mitigate"), type.id() + ".mitigate");
            for (CheckSpec.NumberOption option : spec.numbers()) {
                assertTrue(section.isSet(option.key()), "config.yml is missing " + type.id() + "." + option.key());
                assertEquals(option.defaultValue(), section.getDouble(option.key()), 1e-9,
                        type.id() + "." + option.key());
            }
            for (CheckSpec.ListOption option : spec.lists()) {
                assertEquals(option.defaultValue(), section.getStringList(option.key()), type.id() + "." + option.key());
            }
        }
    }

    @Test
    void bundledModerationAndMessagesMatchBuiltInDefaults() throws Exception {
        Settings settings = ConfigLoader.load(bundled());
        for (io.github.drepfy.vigil.moderation.PunishmentType type
                : io.github.drepfy.vigil.moderation.PunishmentType.values()) {
            assertEquals(ConfigLoader.DEFAULT_REASONS.get(type), settings.moderation().reasons(type), type.key());
        }
        for (java.util.Map.Entry<String, String> entry : ConfigLoader.DEFAULT_MESSAGES.entrySet()) {
            assertEquals(entry.getValue(), settings.messages().get(entry.getKey()), "messages." + entry.getKey());
        }
        assertEquals(ConfigLoader.DEFAULT_ALERT_FORMAT, settings.alerts().format());
        assertEquals(io.github.drepfy.vigil.moderation.Durations.PERMANENT, settings.moderation().defaultBanMs());
        assertTrue(settings.moderation().mutedBlockedCommands().contains("msg"));
        assertTrue(settings.messages().get("prefix").contains("ᴠᴀɴɪʟʟᴀ sᴍᴘ"));
    }

    @Test
    void invalidModerationValuesAreReported() throws Exception {
        Settings settings = ConfigLoader.load(yaml("""
                moderation:
                  broadcast: everyone
                  default-duration:
                    ban: forever-ish
                  reasons:
                    ban:
                      Cheating: 30d
                      Weird: 5x
                    kick: [AFK, Spam]
                """));
        assertEquals("staff", settings.moderation().broadcast());
        assertEquals(io.github.drepfy.vigil.moderation.Durations.PERMANENT, settings.moderation().defaultBanMs());
        var ban = settings.moderation().reasons(io.github.drepfy.vigil.moderation.PunishmentType.BAN);
        assertEquals(2, ban.size());
        assertEquals(30L * 24 * 3600 * 1000, ban.get(0).defaultDuration());
        assertEquals(null, ban.get(1).defaultDuration());
        assertEquals(2, settings.moderation().reasons(io.github.drepfy.vigil.moderation.PunishmentType.KICK).size());
        // Types without a section keep their defaults.
        assertEquals(ConfigLoader.DEFAULT_REASONS.get(io.github.drepfy.vigil.moderation.PunishmentType.MUTE),
                settings.moderation().reasons(io.github.drepfy.vigil.moderation.PunishmentType.MUTE));
        assertEquals(3, settings.warnings().size(), settings.warnings().toString());
    }

    @Test
    void emptyConfigUsesDefaults() throws Exception {
        Settings settings = ConfigLoader.load(yaml(""));
        assertEquals(List.of(), settings.warnings());
        for (CheckType type : CheckType.values()) {
            assertEquals(CheckSettings.defaults(type), settings.check(type));
        }
    }

    @Test
    void invalidValuesFallBackToDefaultsWithWarnings() throws Exception {
        Settings settings = ConfigLoader.load(yaml("""
                general:
                  enabled: maybe
                lag-protection:
                  min-tps: 42
                  max-ping-ms: fast
                checks:
                  speed:
                    leniency: 0.2
                    alert-vl: -3
                    typo-option: 1
                  reach:
                    leniency: 1.5
                    cancel-leniency: 1.0
                  not-a-check:
                    enabled: true
                """));
        assertTrue(settings.general().enabled());
        assertEquals(17.0, settings.lag().minTps(), 1e-9);
        assertEquals(400, settings.lag().maxPingMs());
        assertEquals(1.25, settings.check(CheckType.SPEED).num("leniency"), 1e-9);
        assertEquals(3.0, settings.check(CheckType.SPEED).alertVl(), 1e-9);
        // cancel-leniency may never be stricter than leniency.
        assertEquals(1.5, settings.check(CheckType.REACH).num("cancel-leniency"), 1e-9);
        List<String> warnings = settings.warnings();
        assertTrue(warnings.stream().anyMatch(w -> w.contains("general.enabled")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("min-tps")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("max-ping-ms")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("checks.speed.leniency")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("typo-option")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("not-a-check")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.contains("cancel-leniency")), warnings.toString());
    }

    @Test
    void actionRulesAreParsedAndValidated() throws Exception {
        Settings settings = ConfigLoader.load(yaml("""
                checks:
                  flight:
                    actions:
                      - vl: 40
                        commands: ["/kick {player} bye"]
                      - vl: 20
                        commands: ["say {player} flagged"]
                      - vl: -1
                        commands: ["ban {player}"]
                      - commands: []
                """));
        List<ActionRule> rules = settings.check(CheckType.FLIGHT).actions();
        assertEquals(2, rules.size());
        assertEquals(20.0, rules.get(0).vl(), 1e-9);
        assertEquals(List.of("kick {player} bye"), rules.get(1).commands());
        assertEquals(2, settings.warnings().size(), settings.warnings().toString());
    }

    @Test
    void numbersWrittenAsTextAreAccepted() throws Exception {
        Settings settings = ConfigLoader.load(yaml("""
                checks:
                  timer:
                    max-debt-ms: "800"
                """));
        assertEquals(800.0, settings.check(CheckType.TIMER).num("max-debt-ms"), 1e-9);
        assertEquals(List.of(), settings.warnings());
    }

    @Test
    void checkIdsAreUniqueAndResolvable() {
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (CheckType type : CheckType.values()) {
            assertTrue(ids.add(type.id()), "duplicate id " + type.id());
            assertEquals(type, CheckType.fromId(type.id()));
            assertEquals(type, CheckType.fromId(type.displayName().toUpperCase()));
            assertTrue(type.bypassPermission().startsWith("vigil.bypass."));
        }
    }
}
