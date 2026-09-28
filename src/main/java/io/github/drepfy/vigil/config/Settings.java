package io.github.drepfy.vigil.config;

import io.github.drepfy.vigil.api.CheckType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable snapshot of the whole configuration. A new snapshot replaces the old
 * one atomically on reload, so checks never observe a half-loaded configuration.
 */
public record Settings(General general,
                       Lag lag,
                       Alerts alerts,
                       Violations violations,
                       Review review,
                       Punishments punishments,
                       Map<CheckType, CheckSettings> checks,
                       Messages messages,
                       List<String> warnings) {

    public Settings {
        EnumMap<CheckType, CheckSettings> copy = new EnumMap<>(CheckType.class);
        copy.putAll(checks);
        for (CheckType type : CheckType.values()) {
            copy.putIfAbsent(type, CheckSettings.defaults(type));
        }
        checks = java.util.Collections.unmodifiableMap(copy);
        warnings = List.copyOf(warnings);
    }

    public CheckSettings check(CheckType type) {
        return checks.get(type);
    }

    public record General(boolean enabled,
                          boolean passiveMode,
                          boolean exemptCreativeAndSpectator,
                          Set<String> disabledWorlds,
                          boolean useClientTickEvents,
                          List<String> platformEntities,
                          boolean debug) {
        public General {
            disabledWorlds = Set.copyOf(disabledWorlds);
            platformEntities = List.copyOf(platformEntities);
        }
    }

    public record Lag(double minTps,
                      long lagSpikeThresholdMs,
                      long lagSpikeGraceMs,
                      long maxPingMs,
                      long joinGraceMs,
                      long respawnGraceMs,
                      long teleportGraceMs,
                      long worldChangeGraceMs,
                      long gamemodeChangeGraceMs,
                      long velocityGraceMs,
                      long vehicleExitGraceMs,
                      long elytraGraceMs,
                      long riptideGraceMs,
                      double disturbanceRadius,
                      long disturbanceGraceMs) {
    }

    public record Alerts(boolean enabled, boolean console, long cooldownMs, String format, boolean clickable) {
    }

    public record Violations(int historySize, boolean logToFile, int logRetentionDays) {
    }

    public record Review(boolean enabled, int maxEvidence, boolean notifyStaff, int maxCases) {
    }

    public record Punishments(boolean enabled, boolean dryRun, int minFlagsInWindow, long windowMs, long cooldownMs) {
    }

    public record Messages(Map<String, String> values) {
        public Messages {
            values = Map.copyOf(values);
        }

        public String get(String key) {
            String value = values.get(key);
            return value != null ? value : ConfigLoader.DEFAULT_MESSAGES.getOrDefault(key, key);
        }
    }
}
