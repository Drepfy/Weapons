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
                       Discord discord,
                       Moderation moderation,
                       Tickets tickets,
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
     * @param durations  ban length per automatic ban: first, second, ... ({@code -1} = permanent)
     * @param reason     ban reason; {@code {reason}} is replaced by the check reason, e.g. "Flying"
     * @param broadcast  announce the ban to every player (staff are always told)
     * @param command    console command to run instead of Vigil's own ban (empty = use Vigil's ban)
     */
    public record AutoBan(boolean enabled, List<Long> durations, String reason, boolean broadcast, String command,
                          boolean animation) {
        public AutoBan {
            durations = durations.isEmpty() ? List.of(30L * 24 * 3600 * 1000) : List.copyOf(durations);
        }

        /** Length of a first automatic ban. */
        public long durationMs() {
            return durations.get(0);
        }

        /** Length after {@code previousBans} earlier automatic bans (the last step repeats). */
        public long durationFor(int previousBans) {
            return durations.get(Math.min(Math.max(0, previousBans), durations.size() - 1));
        }
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
     * @param warningsExpireMs how long old warnings without their own time count
     * @param warnMinMs      shortest time a warning may be given for
     * @param warnMaxMs      longest time a warning may be given for
     */
    public record Moderation(boolean enabled,
                             String broadcast,
                             long defaultBanMs,
                             long defaultMuteMs,
                             Set<String> mutedBlockedCommands,
                             Map<PunishmentType, List<ReasonPreset>> reasons,
                             String appeal,
                             String dateFormat,
                             long warningsExpireMs,
                             List<WarnStep> warnEscalation,
                             long warnMinMs,
                             long warnMaxMs) {
        public Moderation {
            warnEscalation = List.copyOf(warnEscalation);
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

        /** The automatic punishment for reaching exactly this many warnings, or {@code null}. */
        public WarnStep warnStep(int warnings) {
            for (WarnStep step : warnEscalation) {
                if (step.warnings() == warnings) {
                    return step;
                }
            }
            return null;
        }
    }

    /**
     * An automatic punishment for collecting warnings.
     *
     * @param type       {@link PunishmentType#BAN}, {@link PunishmentType#MUTE} or {@link PunishmentType#KICK}
     * @param durationMs length for bans and mutes ({@code -1} = permanent)
     */
    public record WarnStep(int warnings, PunishmentType type, long durationMs) {
    }

    /**
     * Discord: punishments and alerts through a webhook, or a bot that also runs tickets,
     * staff commands and a chat bridge.
     *
     * @param webhookUrl  Discord webhook URL, empty = no webhook
     * @param punishments post bans, mutes, warnings and kicks given by staff
     * @param autoBans    post automatic anti-cheat bans
     * @param alerts      post every anti-cheat alert through the webhook (the bot uses its alerts channel)
     */
    public record Discord(String webhookUrl, boolean punishments, boolean autoBans, boolean alerts, Bot bot) {
        /** Whether the webhook is set. */
        public boolean enabled() {
            return webhookUrl != null && webhookUrl.startsWith("https://");
        }
    }

    /**
     * The Discord bot. Channel and role settings are Discord IDs; empty = not used.
     *
     * @param staffRoles   roles that may use the staff commands and see tickets (admins always can)
     * @param readMessages read normal messages in ticket and chat channels (needs the Message Content intent)
     * @param pingStaff    mention the staff roles when a ticket is opened
     * @param status       text under the bot's name; {online} is the number of players online
     */
    public record Bot(boolean enabled,
                      String token,
                      String serverId,
                      List<String> staffRoles,
                      String punishmentsChannel,
                      String alertsChannel,
                      String ticketsCategory,
                      String ticketLogChannel,
                      String chatChannel,
                      boolean readMessages,
                      boolean pingStaff,
                      String status) {
        public Bot {
            staffRoles = List.copyOf(staffRoles);
        }

        /** Switched on with a token and a server. */
        public boolean active() {
            return enabled && !token.isEmpty() && !serverId.isEmpty();
        }

        /** Never prints the token (settings can end up in logs). */
        @Override
        public String toString() {
            return "Bot[enabled=" + enabled + ", token=" + (token.isEmpty() ? "" : "<hidden>") + ", serverId=" + serverId
                    + ", staffRoles=" + staffRoles + ", punishmentsChannel=" + punishmentsChannel + ", alertsChannel="
                    + alertsChannel + ", ticketsCategory=" + ticketsCategory + ", ticketLogChannel=" + ticketLogChannel
                    + ", chatChannel=" + chatChannel + ", readMessages=" + readMessages + ", pingStaff=" + pingStaff
                    + ", status=" + status + "]";
        }
    }

    /**
     * Support tickets (/ticket, /report, and the Discord ticket panel).
     *
     * @param reports      players may use /report
     * @param keepClosedMs closed tickets are deleted after this long ({@code -1} = kept forever)
     */
    public record Tickets(boolean enabled, boolean reports, long keepClosedMs) {
    }

    public record Messages(Map<String, String> values) {
        public Messages {
            values = Map.copyOf(values);
        }

        /** The message; {@code {brand}} inside it becomes the {@code brand} message (the plugin's name). */
        public String get(String key) {
            String value = raw(key);
            return value.contains("{brand}") && !key.equals("brand") ? value.replace("{brand}", raw("brand")) : value;
        }

        private String raw(String key) {
            String value = values.get(key);
            return value != null ? value : ConfigLoader.DEFAULT_MESSAGES.getOrDefault(key, key);
        }
    }
}
