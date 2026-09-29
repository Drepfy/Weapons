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
        assertTrue(settings.alerts().enabled());
        assertTrue(settings.autoBan().enabled(), "auto-ban is on by default");
        assertEquals(30L * 24 * 3600 * 1000, settings.autoBan().durationMs());
        assertEquals("", settings.autoBan().command());
        assertFalse(settings.general().bypassPermission(), "wildcard permissions must not disable checks");
        assertFalse(settings.general().passiveMode());
        assertFalse(ConfigLoader.isLegacyLayout(bundled()));
    }

    @Test
    void bundledConfigMatchesSpecDefaults() throws Exception {
        YamlConfiguration yaml = bundled();
        for (CheckType type : CheckType.values()) {
            CheckSpec.Defaults d = CheckSpec.of(type).defaults();
            ConfigurationSection section = yaml.getConfigurationSection("anticheat.checks." + type.id());
            assertNotNull(section, "config.yml is missing anticheat.checks." + type.id());
            assertEquals(d.enabled(), section.getBoolean("enabled"), type.id() + ".enabled");
            assertEquals(d.banVl(), section.getDouble("ban-at"), 1e-9, type.id() + ".ban-at");
        }
        Settings settings = ConfigLoader.load(yaml);
        for (CheckType type : CheckType.values()) {
            assertEquals(CheckSettings.defaults(type), settings.check(type), type.id());
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
        assertEquals(ConfigLoader.DEFAULT_MESSAGES.get("flagged"), settings.alerts().format());
        assertEquals(io.github.drepfy.vigil.moderation.Durations.PERMANENT, settings.moderation().defaultBanMs());
        assertTrue(settings.moderation().mutedBlockedCommands().contains("msg"));
        assertTrue(settings.messages().get("prefix").contains("ᴠᴀɴɪʟʟᴀ sᴍᴘ"));
    }

    @Test
    void legacyLayoutIsDetectedAndReported() throws Exception {
        YamlConfiguration old = yaml("""
                config-version: 1
                general:
                  enabled: true
                checks:
                  speed:
                    alert-vl: 3
                """);
        assertTrue(ConfigLoader.isLegacyLayout(old));
        Settings settings = ConfigLoader.load(old);
        assertTrue(settings.warnings().stream().anyMatch(w -> w.contains("old 1.x layout")), settings.warnings().toString());
        // Old check tuning is ignored: the new defaults apply.
        assertEquals(CheckSettings.defaults(CheckType.SPEED), settings.check(CheckType.SPEED));
    }

    @Test
    void shorthandAndRenamedChecksAreAccepted() throws Exception {
        Settings settings = ConfigLoader.load(yaml("""
                anticheat:
                  checks:
                    speed: false
                    hit-angle:
                      ban-at: 4
                """));
        assertFalse(settings.check(CheckType.SPEED).enabled());
        assertEquals(4.0, settings.check(CheckType.KILLAURA).banVl(), 1e-9);
        assertTrue(settings.warnings().stream().anyMatch(w -> w.contains("renamed to killaura")),
                settings.warnings().toString());
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
                anticheat:
                  enabled: maybe
                  auto-ban:
                    duration: soon
                  checks:
                    speed:
                      leniency: 0.2
                      ban-at: -3
                      typo-option: 1
                    reach:
                      leniency: 1.5
                      cancel-leniency: 1.0
                    not-a-check:
                      enabled: true
                advanced:
                  lag-protection:
                    min-tps: 42
                    max-ping-ms: fast
                """));
        assertTrue(settings.general().enabled());
        assertEquals(30L * 24 * 3600 * 1000, settings.autoBan().durationMs());
        assertEquals(17.0, settings.lag().minTps(), 1e-9);
        assertEquals(400, settings.lag().maxPingMs());
        assertEquals(1.25, settings.check(CheckType.SPEED).num("leniency"), 1e-9);
        assertEquals(15.0, settings.check(CheckType.SPEED).banVl(), 1e-9);
        // cancel-leniency may never be stricter than leniency.
        assertEquals(1.5, settings.check(CheckType.REACH).num("cancel-leniency"), 1e-9);
        List<String> warnings = settings.warnings();
        for (String expected : List.of("anticheat.enabled", "auto-ban.duration", "min-tps", "max-ping-ms",
                "checks.speed.leniency", "checks.speed.ban-at", "typo-option", "not-a-check", "cancel-leniency")) {
            assertTrue(warnings.stream().anyMatch(w -> w.contains(expected)), expected + " in " + warnings);
        }
    }

    @Test
    void numbersWrittenAsTextAreAccepted() throws Exception {
        Settings settings = ConfigLoader.load(yaml("""
                anticheat:
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
