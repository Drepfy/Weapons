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
                       AutoBan autoBan,
                       AntiXray antiXray,
                       AntiEsp antiEsp,
                       ClientCheck clientCheck,
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

    /**
     * @param bypassPermission whether {@code vigil.bypass(.<check>)} is honoured at all. Off by
     *                         default so wildcard ("*") permissions never silently disable checks.
     */
    public record General(boolean enabled,
                          boolean passiveMode,
                          boolean exemptCreativeAndSpectator,
                          Set<String> disabledWorlds,
                          boolean useClientTickEvents,
                          List<String> platformEntities,
                          boolean debug,
                          boolean bypassPermission) {
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

    /**
     * Automatic bans once a check's VL reaches its {@code ban-at}.
     *
     * @param durationMs ban length ({@code -1} = permanent)
     * @param reason     ban reason; {@code {reason}} is replaced by the check reason, e.g. "Flying"
     * @param broadcast  announce the ban to every player (staff are always told)
     * @param command    console command to run instead of Vigil's own ban (empty = use Vigil's ban)
     */
    public record AutoBan(boolean enabled, long durationMs, String reason, boolean broadcast, String command,
                          boolean animation) {
    }

    /**
     * Kicks clients that identify themselves as hacked clients or world downloaders.
     *
     * @param blockedBrands   client brand patterns that are kicked (e.g. {@code *meteor*})
     * @param blockedChannels plugin channel patterns that are kicked (e.g. {@code wdl:*})
     * @param blockModLoaders also kick every Fabric/Forge/Quilt client (blocks all mods)
     */
    public record ClientCheck(boolean enabled, List<String> blockedBrands, List<String> blockedChannels,
                              boolean blockModLoaders) {
        public ClientCheck {
            blockedBrands = List.copyOf(blockedBrands);
            blockedChannels = List.copyOf(blockedChannels);
        }
    }

    /**
     * @param setupPaper turn on Paper's own anti-xray (engine-mode 2) once, with backups
     */
    public record AntiXray(boolean setupPaper) {
    }

    /**
     * Blocks a player cannot see are shown to them as stone until they are close or in
     * line of sight (anti-ESP, anti-freecam, cave anti-xray). Paper only.
     *
     * @param hideStorage    hide storage blocks (chests, shulkers, beds...)
     * @param hideOres       hide valuable ores (diamonds, ancient debris), also in caves
     * @param revealDistance blocks within this distance are always shown
     * @param lookDistance   blocks within this distance are shown while in line of sight
     * @param blocks         storage block type patterns
     * @param ores           ore block type patterns
     */
    public record AntiEsp(boolean hideStorage, boolean hideOres, double revealDistance, double lookDistance,
                          List<String> blocks, List<String> ores) {
        public AntiEsp {
            blocks = List.copyOf(blocks);
            ores = List.copyOf(ores);
        }
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
