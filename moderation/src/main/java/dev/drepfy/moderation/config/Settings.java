package dev.drepfy.moderation.config;

import dev.drepfy.moderation.log.AuditLog;
import dev.drepfy.moderation.model.Length;
import dev.drepfy.moderation.model.PunishmentType;
import dev.drepfy.moderation.storage.SqlDialect;
import dev.drepfy.moderation.storage.StorageSettings;
import org.bukkit.configuration.ConfigurationSection;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Immutable snapshot of config.yml. Invalid values are reported and replaced with safe defaults so
 * a typo never disables enforcement.
 */
public record Settings(
        String serverName,
        StorageSettings storage,
        String storageError,
        boolean denyLoginOnDatabaseError,
        int loginTimeoutSeconds,
        int syncIntervalSeconds,
        Map<PunishmentType, TypeSettings> types,
        Map<PunishmentType, Map<String, Preset>> presets,
        Map<Integer, EscalationRule> escalation,
        DurationLimits limits,
        List<String> blockedCommands,
        boolean blockSigns,
        boolean blockBooks,
        boolean blockLegacyChatEvent,
        int voiceNotifyCooldownSeconds,
        boolean allowUnknownPlayers,
        int historyPageSize,
        DateTimeFormatter dateFormat,
        AuditLog.Settings logging,
        boolean logBlockedAttempts) {

    /**
     * @param defaultLength length used when staff don't give one (unused for kicks)
     */
    public record TypeSettings(Length defaultLength, boolean requireReason, boolean broadcast) {
    }

    /**
     * @param storage      connection settings, or {@code null} if they are invalid
     * @param storageError why the storage settings are invalid, or {@code null}
     */
    public Settings {
        if ((storage == null) == (storageError == null)) {
            throw new IllegalArgumentException("exactly one of storage and storageError must be set");
        }
    }

    public TypeSettings type(PunishmentType type) {
        return types.get(type);
    }

    public Optional<Preset> preset(PunishmentType type, String name) {
        return Optional.ofNullable(presets.getOrDefault(type, Map.of()).get(name.toLowerCase(Locale.ROOT)));
    }

    public static Settings load(ConfigurationSection config, Logger logger) {
        Loader loader = new Loader(logger);

        Map<PunishmentType, TypeSettings> types = new EnumMap<>(PunishmentType.class);
        for (PunishmentType type : PunishmentType.values()) {
            String path = "punishments." + type.key() + ".";
            Length fallback = switch (type) {
                case BAN -> Length.PERMANENT;
                case WARN -> Length.of(Duration.ofDays(30));
                default -> Length.of(Duration.ofHours(1));
            };
            Length length = type.timed() ? loader.length(config, path + "default-duration", fallback) : Length.PERMANENT;
            types.put(type, new TypeSettings(length,
                    config.getBoolean(path + "require-reason", type == PunishmentType.WARN),
                    config.getBoolean(path + "broadcast", type == PunishmentType.BAN)));
        }

        // Invalid storage settings must not stop the plugin from enabling: it then stays up with the
        // database marked unavailable, which refuses logins rather than letting banned players in.
        StorageSettings storage = null;
        String storageError = null;
        try {
            storage = loader.storage(config);
        } catch (IllegalArgumentException e) {
            storageError = e.getMessage();
            loader.warn(storageError);
        }

        return new Settings(
                config.getString("server-name", "server"),
                storage,
                storageError,
                config.getBoolean("security.deny-login-on-database-error", true),
                loader.atLeast(config, "security.login-timeout-seconds", 10, 1),
                loader.atLeast(config, "security.sync-interval-seconds", 30, 0),
                Map.copyOf(types),
                loader.presets(config.getConfigurationSection("presets")),
                loader.escalation(config.getConfigurationSection("warnings.escalation")),
                loader.limits(config.getConfigurationSection("duration-limits")),
                List.copyOf(config.getStringList("mute.blocked-commands")),
                config.getBoolean("mute.block-signs", true),
                config.getBoolean("mute.block-books", true),
                config.getBoolean("mute.block-legacy-chat-event", true),
                loader.atLeast(config, "voice-chat.notify-cooldown-seconds", 5, 0),
                config.getBoolean("lookup.allow-unknown-players", true),
                Math.min(50, loader.atLeast(config, "history.page-size", 8, 1)),
                loader.dateFormat(config.getString("history.date-format", "yyyy-MM-dd HH:mm"),
                        config.getString("history.time-zone", "")),
                new AuditLog.Settings(
                        config.getBoolean("logging.file", true),
                        config.getBoolean("logging.console", true),
                        loader.atLeast(config, "logging.retention-days", 90, 0)),
                config.getBoolean("logging.blocked-attempts", true));
    }

    private record Loader(Logger logger) {

        void warn(String message) {
            logger.warning("config.yml: " + message);
        }

        int atLeast(ConfigurationSection config, String path, int fallback, int minimum) {
            int value = config.getInt(path, fallback);
            if (value < minimum) {
                warn(path + " must be at least " + minimum + "; using " + fallback);
                return fallback;
            }
            return value;
        }

        Length length(ConfigurationSection config, String path, Length fallback) {
            String value = config.getString(path);
            if (value == null) {
                return fallback;
            }
            Optional<Length> parsed = Length.parse(value.strip());
            if (parsed.isEmpty()) {
                warn(path + ": invalid duration \"" + value + "\"; using the default");
            }
            return parsed.orElse(fallback);
        }

        StorageSettings storage(ConfigurationSection config) {
            String type = config.getString("storage.type", "sqlite");
            SqlDialect dialect = SqlDialect.fromConfig(type).orElseThrow(
                    () -> new IllegalArgumentException("storage.type must be sqlite or mysql, not \"" + type + "\""));
            Map<String, String> properties = new LinkedHashMap<>();
            ConfigurationSection section = config.getConfigurationSection("storage.mysql.properties");
            if (section != null) {
                for (String key : section.getKeys(false)) {
                    properties.put(key, String.valueOf(section.get(key)));
                }
            }
            return new StorageSettings(
                    dialect,
                    config.getString("storage.table-prefix", "sm_"),
                    config.getString("storage.sqlite.file", "punishments.db"),
                    config.getString("storage.mysql.host", "localhost"),
                    config.getInt("storage.mysql.port", 3306),
                    config.getString("storage.mysql.database", "minecraft"),
                    config.getString("storage.mysql.username", "minecraft"),
                    config.getString("storage.mysql.password", ""),
                    config.getInt("storage.mysql.pool-size", 4),
                    properties);
        }

        Map<PunishmentType, Map<String, Preset>> presets(ConfigurationSection section) {
            Map<PunishmentType, Map<String, Preset>> result = new EnumMap<>(PunishmentType.class);
            if (section == null) {
                return Map.copyOf(result);
            }
            for (String typeKey : section.getKeys(false)) {
                Optional<PunishmentType> type = PunishmentType.fromKey(typeKey).filter(PunishmentType::timed);
                ConfigurationSection presets = section.getConfigurationSection(typeKey);
                if (type.isEmpty() || presets == null) {
                    warn("presets." + typeKey + " isn't a punishment type that takes a duration; ignoring it");
                    continue;
                }
                Map<String, Preset> byName = new LinkedHashMap<>();
                for (String name : presets.getKeys(false)) {
                    String path = "presets." + typeKey + "." + name;
                    ConfigurationSection preset = presets.getConfigurationSection(name);
                    String reason = preset == null ? null : preset.getString("reason");
                    String duration = preset == null ? null : preset.getString("duration");
                    Optional<Length> length = duration == null ? Optional.empty() : Length.parse(duration.strip());
                    if (reason == null || reason.isBlank() || length.isEmpty()) {
                        warn(path + " needs a valid duration and reason; ignoring it");
                        continue;
                    }
                    String key = name.toLowerCase(Locale.ROOT);
                    byName.put(key, new Preset(key, length.get(), reason.strip()));
                }
                result.put(type.get(), Map.copyOf(byName));
            }
            return Map.copyOf(result);
        }

        Map<Integer, EscalationRule> escalation(ConfigurationSection section) {
            Map<Integer, EscalationRule> rules = new TreeMap<>();
            if (section == null) {
                return Map.of();
            }
            for (String key : section.getKeys(false)) {
                try {
                    int count = Integer.parseInt(key.strip());
                    rules.put(count, EscalationRule.parse(count, String.valueOf(section.get(key))));
                } catch (IllegalArgumentException e) {
                    warn("warnings.escalation." + key + ": " + e.getMessage() + "; ignoring it");
                }
            }
            return Map.copyOf(rules);
        }

        DurationLimits limits(ConfigurationSection section) {
            if (section == null) {
                return DurationLimits.NONE;
            }
            List<DurationLimits.Tier> tiers = new ArrayList<>();
            for (String name : section.getKeys(false)) {
                ConfigurationSection tier = section.getConfigurationSection(name);
                String permission = tier == null ? null : tier.getString("permission");
                if (permission == null || permission.isBlank()) {
                    warn("duration-limits." + name + " needs a permission; ignoring it");
                    continue;
                }
                Map<PunishmentType, Duration> caps = new EnumMap<>(PunishmentType.class);
                for (String key : tier.getKeys(false)) {
                    if (key.equals("permission")) {
                        continue;
                    }
                    Optional<PunishmentType> type = PunishmentType.fromKey(key).filter(PunishmentType::timed);
                    if (type.isEmpty()) {
                        warn("duration-limits." + name + "." + key + " isn't a punishment type that takes a duration; ignoring it");
                        continue;
                    }
                    Optional<Length> cap = Length.parse(String.valueOf(tier.get(key)).strip());
                    if (cap.isEmpty()) {
                        // Fail closed: an unreadable cap must not silently become "no cap".
                        warn("duration-limits." + name + "." + key + " has an invalid duration; capping it at 1h until fixed");
                        caps.put(type.get(), Duration.ofHours(1));
                        continue;
                    }
                    if (!cap.get().permanent()) {
                        caps.put(type.get(), cap.get().duration());
                    }
                }
                tiers.add(new DurationLimits.Tier(name, permission.strip(), caps));
            }
            return new DurationLimits(tiers);
        }

        DateTimeFormatter dateFormat(String pattern, String zone) {
            ZoneId zoneId = ZoneId.systemDefault();
            if (!zone.isBlank()) {
                try {
                    zoneId = ZoneId.of(zone.strip());
                } catch (DateTimeException e) {
                    warn("history.time-zone \"" + zone + "\" is unknown; using " + zoneId);
                }
            }
            try {
                return DateTimeFormatter.ofPattern(pattern).withZone(zoneId);
            } catch (IllegalArgumentException e) {
                warn("history.date-format \"" + pattern + "\" is invalid; using the default");
                return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zoneId);
            }
        }
    }
}
