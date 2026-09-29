package io.github.drepfy.vigil.config;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.moderation.PunishmentType;
import io.github.drepfy.vigil.moderation.ReasonPreset;

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
                       Moderation moderation,
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

    /**
     * Manual moderation (/ban, /mute, /warn, /kick ...).
     *
     * @param broadcast      who else hears about a punishment: {@code staff}, {@code all} or {@code none}
     * @param defaultBanMs   duration of a ban without a duration or preset ({@code -1} = permanent)
     * @param defaultMuteMs  duration of a mute without a duration or preset ({@code -1} = permanent)
     * @param mutedBlockedCommands commands (without slash, lower case) muted players cannot use
     * @param reasons        preset reasons per punishment type
     */
    public record Moderation(boolean enabled,
                             String broadcast,
                             long defaultBanMs,
                             long defaultMuteMs,
                             Set<String> mutedBlockedCommands,
                             Map<PunishmentType, List<ReasonPreset>> reasons) {
        public Moderation {
            mutedBlockedCommands = Set.copyOf(mutedBlockedCommands);
            EnumMap<PunishmentType, List<ReasonPreset>> copy = new EnumMap<>(PunishmentType.class);
            for (PunishmentType type : PunishmentType.values()) {
                copy.put(type, List.copyOf(reasons.getOrDefault(type, List.of())));
            }
            reasons = java.util.Collections.unmodifiableMap(copy);
        }

        public List<ReasonPreset> reasons(PunishmentType type) {
            return reasons.get(type);
        }
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
