package io.github.drepfy.vigil.config;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.moderation.Durations;
import io.github.drepfy.vigil.moderation.PunishmentType;
import io.github.drepfy.vigil.moderation.ReasonPreset;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Turns a raw YAML tree into validated {@link Settings}.
 *
 * <p>Loading never fails because of a bad value: every invalid or out-of-range
 * value is replaced by its default and reported as a warning. Missing keys silently
 * use their defaults, so a short config.yml is a complete config.yml.
 */
public final class ConfigLoader {

    public static final int SUPPORTED_CONFIG_VERSION = 2;

    public static final String DEFAULT_PREFIX = "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ » &r";
    /** "Vigil" in small capitals with a red-orange gradient. */
    public static final String DEFAULT_BRAND = "<gradient:#FF3C3C:#FFA53C>&lV\u026a\u0262\u026a\u029f</gradient>";

    private static final String OLD_BAN_SCREEN = "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ\n\n&cYou are banned from this server.\n\n"
                + "&7Reason: &f{reason}\n&7Duration: &f{duration}\n&7Expires in: &f{expires}\n&7Banned by: &f{staff}";
    private static final String OLD_KICK_SCREEN = "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ\n\n&cYou were kicked from this server.\n\n"
                + "&7Reason: &f{reason}\n&7Kicked by: &f{staff}";
    private static final String OLD_BAN_SUCCESS = "&7You have banned player &b{player} &7for &b{reason} &7for &b{duration}&7.";
    private static final String OLD_MUTE_SUCCESS = "&7You have muted player &b{player} &7for &b{reason} &7for &b{duration}&7.";
    private static final String OLD_MUTE_NOTIFY = "&cYou have been muted for &f{reason}&c. &7Duration: &f{duration}";
    private static final String OLD_MUTED_CHAT = "&cYou are muted for &f{reason}&c. &7Expires in: &f{expires}";

    /** Message values of older versions that are upgraded to the current defaults automatically. */
    static final Map<String, String> OLD_DEFAULT_MESSAGES = Map.of(
            "ban-screen", OLD_BAN_SCREEN,
            "kick-screen", OLD_KICK_SCREEN,
            "ban-success", OLD_BAN_SUCCESS,
            "mute-success", OLD_MUTE_SUCCESS,
            "mute-notify", OLD_MUTE_NOTIFY,
            "muted-chat", OLD_MUTED_CHAT);

    static final Map<String, String> DEFAULT_MESSAGES;

    static {
        Map<String, String> messages = new LinkedHashMap<>();
        messages.put("prefix", DEFAULT_PREFIX);
        // The plugin's name as shown on ban messages; {brand} in any message is replaced by it.
        messages.put("brand", DEFAULT_BRAND);
        // Anti-cheat. Placeholders: {player} {reason} {check} {vl} {detail}
        messages.put("flagged", "&f{player} &7has been flagged for &c{reason} &8(VL {vl})");
        messages.put("auto-banned", String.join("\n",
                "&8&m                                                  ",
                "  {brand} &8&l| &7Anti-Cheat",
                "",
                "  &f{player} &7has been banned for cheating.",
                "  &7Detected: &c{reason}",
                "&8&m                                                  "));
        messages.put("ban-title", "&c&lBanned");
        messages.put("ban-subtitle", "&7Detected: &c{reason}");
        messages.put("client-blocked", String.join("\n",
                "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ",
                "&8&m                                    ",
                "&cYour client is not allowed on this server.",
                "",
                "&7Detected: &f{client}",
                "&7Please reconnect using an unmodified Minecraft client.",
                "&8&m                                    "));
        messages.put("client-blocked-alert", "&f{player} &7was disconnected for using &c{client}&7.");
        messages.put("alerts-enabled", "&aAnti-cheat alerts enabled.");
        messages.put("alerts-disabled", "&eAnti-cheat alerts disabled.");
        messages.put("reloaded", "&aConfiguration reloaded. &7({warnings} warning(s), see console)");
        messages.put("reload-failed", "&cReload failed, previous configuration kept: &f{error}");
        messages.put("no-permission", "&cYou do not have permission to do that.");
        messages.put("player-not-found", "&cPlayer not found: &f{player}");
        // Moderation. Placeholders: {player} {staff} {reason} {duration} {expires} {count}
        messages.put("no-reason", "No Reason");
        messages.put("permanent", "Permanent");
        messages.put("ban-success", "&7You have banned player &b{player} &7for &b{reason} &7for &b{duration}&7. &8({offence} offence, #{id})");
        messages.put("unban-success", "&7You have unbanned player &b{player} &7for &b{reason}&7.");
        messages.put("mute-success", "&7You have muted player &b{player} &7for &b{reason} &7for &b{duration}&7. &8({offence} offence, #{id})");
        messages.put("unmute-success", "&7You have unmuted player &b{player} &7for &b{reason}&7.");
        messages.put("warn-success", "&7You have warned player &b{player} &7for &b{reason} &7for &b{duration}&7. &8(active warnings: {count}, #{id})");
        messages.put("unwarn-success", "&7You have removed a warning from &b{player} &7for &b{reason}&7. &8(active warnings: {count})");
        messages.put("kick-success", "&7You have kicked player &b{player} &7for &b{reason}&7.");
        messages.put("ban-broadcast", "&b{staff} &7banned &b{player} &7for &b{reason} &7for &b{duration}&7.");
        messages.put("unban-broadcast", "&b{staff} &7unbanned &b{player} &7for &b{reason}&7.");
        messages.put("mute-broadcast", "&b{staff} &7muted &b{player} &7for &b{reason} &7for &b{duration}&7.");
        messages.put("unmute-broadcast", "&b{staff} &7unmuted &b{player} &7for &b{reason}&7.");
        messages.put("warn-broadcast", "&b{staff} &7warned &b{player} &7for &b{reason} &7for &b{duration}&7.");
        messages.put("unwarn-broadcast", "&b{staff} &7removed a warning from &b{player} &7for &b{reason}&7.");
        messages.put("kick-broadcast", "&b{staff} &7kicked &b{player} &7for &b{reason}&7.");
        messages.put("ban-screen", String.join("\n",
                "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ",
                "&8&m                                    ",
                "&cYou are banned from this server.",
                "&7Time remaining: &f{expires}",
                "",
                "&7Reason: &f{reason} &8({offence} offence)",
                "&7Banned by: &f{staff}",
                "&7Date: &f{date}",
                "&7Expires: &f{expires-date}",
                "&7Ban ID: &f#{id}",
                "",
                "&7Appeal: &b{appeal}",
                "&8&m                                    "));
        messages.put("ban-screen-permanent", String.join("\n",
                "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ",
                "&8&m                                    ",
                "&cYou are permanently banned from this server.",
                "",
                "&7Reason: &f{reason} &8({offence} offence)",
                "&7Banned by: &f{staff}",
                "&7Date: &f{date}",
                "&7Ban ID: &f#{id}",
                "",
                "&7Appeal: &b{appeal}",
                "&8&m                                    "));
        messages.put("ban-screen-anticheat", String.join("\n",
                "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ",
                "&8&m                                    ",
                "&cYou have been banned by {brand}&c.",
                "",
                "&7Detected: &f{reason}",
                "&7Length: &f{duration} &8({offence} offence)",
                "&7Date: &f{date}",
                "&7Expires: &f{expires-date}",
                "&7Ban ID: &f#{id}",
                "",
                "&7If you believe this is a mistake, you may appeal: &b{appeal}",
                "&8&m                                    "));
        messages.put("kick-screen", String.join("\n",
                "&b&lᴠᴀɴɪʟʟᴀ sᴍᴘ",
                "&8&m                                    ",
                "&eYou have been kicked from this server.",
                "",
                "&7Reason: &f{reason}",
                "&7Kicked by: &f{staff}",
                "",
                "&7You may reconnect at any time.",
                "&8&m                                    "));
        messages.put("mute-notify", "&cYou have been muted for &f{reason}&c. &7Length: &f{duration} &8(until {expires-date})");
        messages.put("muted-chat", "&cYou are muted for &f{reason}&c. &7Unmuted in &f{expires}&7.");
        messages.put("unmute-notify", "&aYou have been unmuted.");
        messages.put("warn-notify", "&cYou have been warned for &f{reason}&c. &7This warning expires in &f{duration}&7. &8(active warnings: {count})");
        messages.put("unwarn-notify", "&aOne of your warnings has been removed. &7(active warnings: {count})");
        // Shown in bold above the hotbar.
        messages.put("mute-actionbar", "&c&lYou have been muted for {reason}");
        messages.put("unmute-actionbar", "&a&lYou have been unmuted");
        messages.put("warn-actionbar", "&c&lYou have been warned for {reason}");
        messages.put("unwarn-actionbar", "&a&lA warning has been removed");
        messages.put("warn-time-required", "&cA warning needs a time between &f{min} &cand &f{max}&c, for example: &f/warn {player} 1d Spam");
        messages.put("not-banned", "&c{player} is not banned.");
        messages.put("not-muted", "&c{player} is not muted.");
        messages.put("not-warned", "&c{player} has no active warnings.");
        messages.put("cannot-punish", "&cYou cannot punish {player}.");
        messages.put("moderation-disabled", "&cModeration commands are disabled in the configuration.");
        messages.put("never", "Never");
        messages.put("presets-header", "&7Preset reasons &8(time for the 1st, 2nd, 3rd offence...)&7:");
        messages.put("warn-escalation-reason", "Too many warnings ({count})");
        // Tickets. Placeholders: {id} {player} {author} {message} {category} {staff} {reason} {count}
        messages.put("ticket-usage", "&7Need help? Open a ticket with staff: &f/ticket <message>");
        messages.put("ticket-opened", "&aYour ticket &f#{id} &ahas been opened. Staff will reply as soon as possible.");
        messages.put("ticket-added", "&7Your message was added to ticket &f#{id}&7.");
        messages.put("ticket-reply", "&8[&bTicket #{id}&8] &f{author}&7: &f{message}");
        messages.put("ticket-staff-new", "&8[&bTicket #{id}&8] &f{player} &7opened a ticket &8({category})&7: &f{message}");
        messages.put("ticket-staff-message", "&8[&bTicket #{id}&8] &f{author}&7: &f{message}");
        messages.put("ticket-claimed", "&7Ticket &f#{id} &7is now handled by &f{staff}&7.");
        messages.put("ticket-closed", "&7Ticket &f#{id} &7has been closed.");
        messages.put("ticket-closed-notify", "&7Your ticket &f#{id} &7was closed by &f{staff}&7. &8({reason})");
        messages.put("ticket-none", "&7You have no open tickets.");
        messages.put("ticket-not-found", "&cTicket not found: &f{id}");
        messages.put("ticket-choose", "&7You have more than one open ticket. Add its number, for example: &f/ticket reply {id} <message>");
        messages.put("ticket-unread", "&7You have a new reply on ticket &f#{id}&7. Use &f/ticket view {id} &7to read it.");
        messages.put("ticket-cooldown", "&cPlease wait a moment before sending another message.");
        messages.put("ticket-full", "&cThis ticket is full. Please open a new one.");
        messages.put("ticket-too-many", "&cYou already have {count} open tickets. Please wait for staff to answer them.");
        messages.put("tickets-header", "&7Open tickets &8({count})&7:");
        messages.put("tickets-empty", "&7There are no open tickets.");
        messages.put("tickets-open-join", "&7Open tickets waiting for staff: &f{count}&7. Use &f/tickets &7to see them.");
        messages.put("tickets-disabled", "&cTickets are turned off on this server.");
        messages.put("report-usage", "&7Report a player to staff: &f/report <player> <reason>");
        messages.put("report-sent", "&aThank you. Your report about &f{player} &ahas been sent to staff as ticket &f#{id}&a.");
        messages.put("report-self", "&cYou cannot report yourself.");
        // Discord chat shown in game. Placeholders: {name} {message}
        messages.put("discord-chat", "&9Discord &8| &f{name}&7: {message}");
        DEFAULT_MESSAGES = java.util.Collections.unmodifiableMap(messages);
    }

    /** Blocks that give bases away to ESP and base finders. */
    static final List<String> DEFAULT_HIDDEN_STORAGE = List.of("CHEST", "TRAPPED_CHEST", "BARREL", "ENDER_CHEST",
            "*SHULKER_BOX", "HOPPER", "DROPPER", "DISPENSER", "CRAFTER", "FURNACE", "BLAST_FURNACE", "SMOKER",
            "BREWING_STAND", "*_BED", "ENCHANTING_TABLE");

    static final long DEFAULT_WARN_MIN_MS = 60L * 60 * 1000;
    static final long DEFAULT_WARN_MAX_MS = 10L * 24 * 60 * 60 * 1000;
    static final String DEFAULT_APPEAL = "Ask a staff member on our Discord";
    static final String DEFAULT_DATE_FORMAT = "dd MMM yyyy, HH:mm";
    static final List<Settings.WarnStep> DEFAULT_WARN_ESCALATION = List.of(
            new Settings.WarnStep(3, PunishmentType.MUTE, 60L * 60 * 1000),
            new Settings.WarnStep(5, PunishmentType.BAN, 24L * 3600 * 1000),
            new Settings.WarnStep(7, PunishmentType.BAN, 7L * 24 * 3600 * 1000));

    /** Hacked clients that put their name into the client brand. */
    static final List<String> DEFAULT_BLOCKED_BRANDS = List.of("*meteor*", "*wurst*", "*liquidbounce*",
            "*aristois*", "*impact*", "*rusherhack*", "*inertia*", "*lambda*", "*kami*", "*salhack*", "*konas*",
            "*thunderhack*", "*boze*", "*sigma*", "*vape*", "*novoline*", "*astolfo*", "*tenacity*", "*wolfram*");

    /** Plugin channels of hacked clients and world downloaders (base stealing). */
    static final List<String> DEFAULT_BLOCKED_CHANNELS = List.of("wdl:*", "wdl|*", "*worlddownloader*",
            "*meteor*", "*wurst*", "*liquidbounce*", "*aristois*", "*rusherhack*", "*baritone*");

    /** Ores worth x-raying for; hidden until seen, also in caves. */
    static final List<String> DEFAULT_HIDDEN_ORES = List.of("DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE", "ANCIENT_DEBRIS");

    static final List<String> DEFAULT_MUTED_BLOCKED_COMMANDS = List.of("msg", "tell", "w", "whisper", "r", "reply",
            "me", "say", "mail", "m", "t", "pm", "dm", "message", "emsg", "etell", "ewhisper", "er", "ereply");

    /** Default preset reasons; underscores are shown as spaces. */
    static final Map<PunishmentType, List<ReasonPreset>> DEFAULT_REASONS;

    static {
        // Lengths per offence: "7d, 30d, perm" = 7 days the 1st time, 30 days the 2nd, permanent after that.
        Map<PunishmentType, List<ReasonPreset>> reasons = new EnumMap<>(PunishmentType.class);
        reasons.put(PunishmentType.BAN, presets(
                "Cheating", "7d, 30d, perm",
                "Hacked_Client", "14d, 30d, perm",
                "Kill_Aura", "14d, 30d, perm",
                "Fly_Hacks", "7d, 14d, 30d, perm",
                "Speed_Hacks", "7d, 14d, 30d, perm",
                "X-Ray", "7d, 14d, 30d, perm",
                "Reach", "7d, 14d, 30d, perm",
                "Auto_Clicker", "3d, 7d, 14d, 30d",
                "Mace_Exploit", "14d, 30d, perm",
                "Duping", "30d, perm",
                "Exploiting", "7d, 14d, 30d, perm",
                "Griefing", "3d, 7d, 30d, perm",
                "Stealing", "1d, 3d, 7d, 30d",
                "Scamming", "3d, 7d, 30d, perm",
                "Harassment", "3d, 7d, 30d, perm",
                "Hate_Speech", "7d, 30d, perm",
                "Threats", "14d, 30d, perm",
                "Doxxing", "perm",
                "Advertising", "1d, 7d, 30d, perm",
                "Spam", "1d, 3d, 7d",
                "Inappropriate_Build", "1d, 3d, 7d, 30d",
                "Inappropriate_Skin", "1d, 3d, 7d",
                "Inappropriate_Name", "perm",
                "Lag_Machine", "3d, 7d, 30d, perm",
                "Staff_Disrespect", "1d, 3d, 7d",
                "Ban_Evasion", "perm",
                "Alt_Account", "perm",
                "Chargeback", "perm"));
        reasons.put(PunishmentType.MUTE, presets(
                "Spam", "15m, 1h, 6h, 1d",
                "Chat_Flood", "15m, 1h, 6h, 1d",
                "Excessive_Caps", "10m, 30m, 1h, 6h",
                "Swearing", "30m, 2h, 1d, 7d",
                "Toxicity", "1h, 6h, 1d, 7d",
                "Harassment", "6h, 1d, 7d, 30d",
                "Hate_Speech", "1d, 7d, 30d, perm",
                "Threats", "1d, 7d, 30d, perm",
                "Advertising", "1h, 1d, 7d, 30d",
                "Inappropriate_Language", "30m, 2h, 1d, 7d",
                "Arguing_With_Staff", "30m, 2h, 1d",
                "Spoilers", "15m, 1h, 6h",
                "Begging", "30m, 2h, 1d",
                "Impersonation", "1d, 7d, 30d",
                "Politics_Or_Religion", "1h, 6h, 1d"));
        reasons.put(PunishmentType.WARN, presets(
                "Spam", "", "Excessive_Caps", "", "Swearing", "", "Toxicity", "", "Harassment", "",
                "Advertising", "", "Inappropriate_Language", "", "Arguing_With_Staff", "", "Begging", "",
                "Griefing", "", "Stealing", "", "Minor_Exploit", "", "Inappropriate_Build", "", "Unfair_PvP", "",
                "Trapping", "", "Ignoring_Staff", "", "Build_Too_Close", ""));
        reasons.put(PunishmentType.KICK, presets(
                "AFK", "", "Spam", "", "Toxicity", "", "Glitching", "", "Inappropriate_Skin", "",
                "Inappropriate_Name", "", "Suspicious_Activity", "", "Staff_Request", "", "Server_Maintenance", ""));
        reasons.put(PunishmentType.UNBAN, presets(
                "Appeal_Accepted", "", "False_Ban", "", "Served_Time", "", "Staff_Decision", ""));
        reasons.put(PunishmentType.UNMUTE, presets(
                "Appeal_Accepted", "", "False_Mute", "", "Served_Time", "", "Staff_Decision", ""));
        reasons.put(PunishmentType.UNWARN, presets(
                "Appeal_Accepted", "", "False_Warning", "", "Staff_Decision", ""));
        DEFAULT_REASONS = java.util.Collections.unmodifiableMap(reasons);
    }

    private static List<ReasonPreset> presets(String... pairs) {
        List<ReasonPreset> result = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            result.add(new ReasonPreset(pairs[i], parseLadder(pairs[i + 1], null, null)));
        }
        return List.copyOf(result);
    }

    /**
     * Lengths per offence from {@code 30d}, {@code "7d, 30d, perm"}, {@code 7d > 30d} or a
     * YAML list. Invalid entries are reported (when a reader is given) and skipped.
     */
    static List<Long> parseLadder(Object value, Reader r, String path) {
        List<String> parts = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object element : list) {
                if (element != null) {
                    parts.add(element.toString());
                }
            }
        } else if (value != null) {
            for (String part : value.toString().split("[,>\u2192]+")) {
                parts.add(part);
            }
        }
        List<Long> ladder = new ArrayList<>();
        for (String part : parts) {
            String text = part.trim();
            if (text.isEmpty() || text.equalsIgnoreCase("none")) {
                continue;
            }
            Long duration = Durations.parse(text);
            if (duration == null) {
                if (r != null) {
                    r.warn(path + " has an invalid duration '" + text + "' (use e.g. 30m, 12h, 7d, perm); it is skipped.");
                }
                continue;
            }
            ladder.add(duration);
        }
        return ladder;
    }

    private static final Set<String> COMMON_CHECK_KEYS = Set.of("enabled", "ban-at", "alert-at",
            "decay-per-minute", "vl-per-flag", "buffer-threshold", "mitigate");

    private static final String CHECKS = "anticheat.checks";
    private static final String LAG = "advanced.lag-protection.";

    private ConfigLoader() {
    }

    /** Settings built purely from built-in defaults (used when the file is unreadable). */
    public static Settings defaults(List<String> warnings) {
        return load(new org.bukkit.configuration.MemoryConfiguration(), warnings);
    }

    public static Settings load(ConfigurationSection root) {
        return load(root, new ArrayList<>());
    }

    /**
     * Whether {@code root} uses the version 1 layout ({@code general:}, {@code checks:} ...
     * at the root) that needs migrating to the current one.
     */
    public static boolean isLegacyLayout(ConfigurationSection root) {
        return !root.isConfigurationSection("anticheat")
                && (root.isConfigurationSection("general") || root.isConfigurationSection("checks")
                || root.isConfigurationSection("review") || root.isConfigurationSection("punishments"));
    }

    /** Loads settings, prepending {@code initialWarnings} to the reported warnings. */
    public static Settings load(ConfigurationSection root, List<String> initialWarnings) {
        Reader r = new Reader(root, new ArrayList<>(initialWarnings));

        int version = (int) r.number("config-version", SUPPORTED_CONFIG_VERSION, 0, Integer.MAX_VALUE);
        if (version > SUPPORTED_CONFIG_VERSION) {
            r.warn("config-version " + version + " is newer than this plugin supports ("
                    + SUPPORTED_CONFIG_VERSION + "); unknown options are ignored.");
        }
        if (isLegacyLayout(root)) {
            r.warn("config.yml uses the old 1.x layout; its anti-cheat settings are ignored. Delete it (or rename it) "
                    + "and restart to get the new, shorter config.yml.");
        }

        Settings.General general = new Settings.General(
                r.bool("anticheat.enabled", true),
                r.bool("advanced.passive-mode", false),
                r.bool("anticheat.exempt-creative-and-spectator", true),
                new HashSet<>(r.stringList("anticheat.disabled-worlds", List.of())),
                r.bool("advanced.use-client-tick-events", true),
                upper(r.stringList("advanced.platform-entities",
                        List.of("BOAT", "RAFT", "MINECART", "SHULKER", "HAPPY_GHAST"))),
                r.bool("advanced.debug", false),
                r.bool("anticheat.bypass-permission", false));

        Settings.Lag lag = new Settings.Lag(
                r.number(LAG + "min-tps", 17.0, 0.0, 20.0),
                r.millis(LAG + "lag-spike-threshold-ms", 400, 100, 60_000),
                r.millis(LAG + "lag-spike-grace-ms", 3000, 0, 120_000),
                r.millis(LAG + "max-ping-ms", 400, 50, 10_000),
                r.millis(LAG + "join-grace-ms", 3000, 0, 120_000),
                r.millis(LAG + "respawn-grace-ms", 2000, 0, 120_000),
                r.millis(LAG + "teleport-grace-ms", 1000, 0, 120_000),
                r.millis(LAG + "world-change-grace-ms", 2000, 0, 120_000),
                r.millis(LAG + "gamemode-change-grace-ms", 1500, 0, 120_000),
                r.millis(LAG + "velocity-grace-ms", 1500, 0, 120_000),
                r.millis(LAG + "vehicle-exit-grace-ms", 1000, 0, 120_000),
                r.millis(LAG + "elytra-grace-ms", 2500, 0, 120_000),
                r.millis(LAG + "riptide-grace-ms", 3000, 0, 120_000),
                r.number(LAG + "disturbance-radius", 8.0, 0.0, 64.0),
                r.millis(LAG + "disturbance-grace-ms", 3000, 0, 120_000));

        Map<String, String> messageValues = new HashMap<>();
        for (Map.Entry<String, String> entry : DEFAULT_MESSAGES.entrySet()) {
            messageValues.put(entry.getKey(), r.text("messages." + entry.getKey(), entry.getValue()));
        }
        // The prefix lives at the top of the file so it is easy to find.
        messageValues.put("prefix", r.text("prefix", messageValues.get("prefix")));
        Settings.Messages messages = new Settings.Messages(messageValues);

        Settings.Alerts alerts = new Settings.Alerts(
                r.bool("anticheat.alerts", true),
                r.bool("advanced.alert-console", true),
                r.millis("advanced.alert-cooldown-ms", 3000, 0, 3_600_000),
                messages.get("flagged"),
                r.bool("advanced.alert-clickable", true));

        Settings.Violations violations = new Settings.Violations(
                (int) r.number("advanced.history-size", 50, 1, 1000),
                r.bool("advanced.log-to-file", true),
                (int) r.number("advanced.log-retention-days", 30, 0, 36500));

        Object rawAutoBanDuration = r.root.get("anticheat.auto-ban.duration");
        List<Long> autoBanDurations = parseLadder(rawAutoBanDuration == null ? "30d, perm" : rawAutoBanDuration, r,
                "anticheat.auto-ban.duration");
        Settings.AutoBan autoBan = new Settings.AutoBan(
                r.bool("anticheat.auto-ban.enabled", true),
                autoBanDurations,
                r.string("anticheat.auto-ban.reason", "Cheating ({reason})"),
                r.bool("anticheat.auto-ban.broadcast", true),
                stripSlash(r.string("anticheat.auto-ban.command", "").trim()),
                r.bool("anticheat.auto-ban.animation", true));

        Settings.AntiXray antiXray = new Settings.AntiXray(r.bool("anticheat.setup-paper-anti-xray", true));
        Settings.AntiEsp antiEsp = new Settings.AntiEsp(
                r.bool("anticheat.hide-storage-from-esp", true),
                r.bool("anticheat.hide-ores-from-xray", true),
                r.number("advanced.anti-esp.reveal-distance", 8.0, 5.0, 64.0),
                r.number("advanced.anti-esp.look-distance", 48.0, 8.0, 128.0),
                upper(r.stringList("advanced.anti-esp.blocks", DEFAULT_HIDDEN_STORAGE)),
                upper(r.stringList("advanced.anti-esp.ores", DEFAULT_HIDDEN_ORES)));

        Settings.ClientCheck clientCheck = new Settings.ClientCheck(
                r.bool("anticheat.client-check.enabled", true),
                r.stringList("anticheat.client-check.blocked-brands", DEFAULT_BLOCKED_BRANDS),
                r.stringList("anticheat.client-check.blocked-channels", DEFAULT_BLOCKED_CHANNELS),
                r.bool("anticheat.client-check.block-all-mods", false));

        Settings.Discord discord = new Settings.Discord(
                r.string("discord.webhook", "").trim(),
                r.bool("discord.send-punishments", true),
                r.bool("discord.send-auto-bans", true),
                r.bool("discord.send-alerts", false),
                loadBot(r));
        if (!discord.webhookUrl().isEmpty() && !discord.enabled()) {
            r.warn("discord.webhook must be a https:// Discord webhook URL; the webhook is off.");
        }

        long keepClosed = r.duration("tickets.keep-closed", 30L * 24 * 3600 * 1000);
        Settings.Tickets tickets = new Settings.Tickets(
                r.bool("tickets.enabled", true),
                r.bool("tickets.reports", true),
                keepClosed);

        Settings.Moderation moderation = loadModeration(r);

        Map<CheckType, String> sections = resolveCheckSections(r);
        Map<CheckType, CheckSettings> checks = new EnumMap<>(CheckType.class);
        for (CheckType type : CheckType.values()) {
            checks.put(type, loadCheck(r, type, sections.get(type)));
        }

        return new Settings(general, lag, alerts, violations, autoBan, antiXray, antiEsp, clientCheck, discord,
                moderation, tickets, checks, messages, r.warnings);
    }

    /** Maps every check to its configuration path, accepting (with a warning) the 1.x check names. */
    private static Map<CheckType, String> resolveCheckSections(Reader r) {
        Map<CheckType, String> sections = new EnumMap<>(CheckType.class);
        for (CheckType type : CheckType.values()) {
            sections.put(type, CHECKS + "." + type.id());
        }
        ConfigurationSection checksSection = r.root.getConfigurationSection(CHECKS);
        if (checksSection == null) {
            return sections;
        }
        Set<String> keys = checksSection.getKeys(false);
        for (String key : keys) {
            CheckType type = CheckType.fromId(key);
            if (type == null) {
                r.warn(CHECKS + "." + key + " is not a known check and is ignored.");
            } else if (!type.id().equals(key)) {
                if (keys.contains(type.id())) {
                    r.warn(CHECKS + "." + key + " is an old name of " + type.id() + " and is ignored.");
                } else {
                    r.warn(CHECKS + "." + key + " was renamed to " + type.id() + "; please rename it.");
                    sections.put(type, CHECKS + "." + key);
                }
            }
        }
        return sections;
    }

    private static final String BOT = "discord.bot.";

    /** {@code discord.bot}: IDs are checked so a pasted name or link is reported instead of failing silently. */
    private static Settings.Bot loadBot(Reader r) {
        boolean enabled = r.bool(BOT + "enabled", false);
        String token = r.string(BOT + "token", "").trim();
        if (token.regionMatches(true, 0, "Bot ", 0, 4)) {
            token = token.substring(4).trim();
        }
        String serverId = r.snowflake(BOT + "server-id");
        List<String> roles = new ArrayList<>();
        for (String role : r.stringList(BOT + "staff-roles", List.of())) {
            String id = role.trim();
            if (id.startsWith("<@&") && id.endsWith(">")) {
                id = id.substring(3, id.length() - 1);
            }
            if (isSnowflake(id)) {
                roles.add(id);
            } else {
                r.warn(BOT + "staff-roles: '" + role + "' is not a role ID (right-click the role > Copy Role ID); "
                        + "it is ignored.");
            }
        }
        Settings.Bot bot = new Settings.Bot(enabled, token, serverId, roles,
                r.snowflake(BOT + "punishments-channel"),
                r.snowflake(BOT + "alerts-channel"),
                r.snowflake(BOT + "tickets-category"),
                r.snowflake(BOT + "ticket-log-channel"),
                r.snowflake(BOT + "chat-channel"),
                r.bool(BOT + "read-messages", true),
                r.bool(BOT + "ping-staff", true),
                r.string(BOT + "status", "Watching {online} players"));
        if (enabled && token.isEmpty()) {
            r.warn(BOT + "enabled is true but there is no token; the Discord bot is off.");
        } else if (enabled && serverId.isEmpty()) {
            r.warn(BOT + "enabled is true but server-id is not set; the Discord bot is off.");
        }
        return bot;
    }

    static boolean isSnowflake(String text) {
        return text.length() >= 15 && text.length() <= 21 && text.chars().allMatch(c -> c >= '0' && c <= '9');
    }

    private static Settings.Moderation loadModeration(Reader r) {
        long warnMin = r.duration("moderation.warn-time.min", DEFAULT_WARN_MIN_MS);
        long warnMax = r.duration("moderation.warn-time.max", DEFAULT_WARN_MAX_MS);
        if (warnMin == Durations.PERMANENT || warnMax == Durations.PERMANENT || warnMin > warnMax) {
            r.warn("moderation.warn-time needs a min and max time with min <= max (e.g. 1h and 10d); using 1h to 10d.");
            warnMin = DEFAULT_WARN_MIN_MS;
            warnMax = DEFAULT_WARN_MAX_MS;
        }
        String broadcast = r.string("moderation.broadcast", "staff").trim().toLowerCase(Locale.ROOT);
        if (!broadcast.equals("staff") && !broadcast.equals("all") && !broadcast.equals("none")) {
            r.warn("moderation.broadcast must be staff, all or none (got '" + broadcast + "'); using staff.");
            broadcast = "staff";
        }
        Map<PunishmentType, List<ReasonPreset>> reasons = new EnumMap<>(PunishmentType.class);
        for (PunishmentType type : PunishmentType.values()) {
            reasons.put(type, loadReasons(r, type));
        }
        Set<String> blocked = new HashSet<>();
        for (String command : r.stringList("moderation.muted-blocked-commands", DEFAULT_MUTED_BLOCKED_COMMANDS)) {
            String cleaned = command.trim().toLowerCase(Locale.ROOT);
            blocked.add(cleaned.startsWith("/") ? cleaned.substring(1) : cleaned);
        }
        return new Settings.Moderation(
                r.bool("moderation.enabled", true),
                broadcast,
                r.duration("moderation.default-duration.ban", Durations.PERMANENT),
                r.duration("moderation.default-duration.mute", Durations.PERMANENT),
                blocked,
                reasons,
                r.string("moderation.appeal", DEFAULT_APPEAL),
                dateFormat(r),
                r.duration("moderation.warnings-expire-after", 30L * 24 * 3600 * 1000),
                warnEscalation(r),
                warnMin,
                warnMax);
    }

    private static String dateFormat(Reader r) {
        String pattern = r.string("moderation.date-format", DEFAULT_DATE_FORMAT);
        try {
            java.time.format.DateTimeFormatter.ofPattern(pattern);
            return pattern;
        } catch (IllegalArgumentException e) {
            r.warn("moderation.date-format '" + pattern + "' is not a valid date pattern; using the default.");
            return DEFAULT_DATE_FORMAT;
        }
    }

    /** {@code moderation.warn-escalation}: "number of warnings: mute 1h | ban 7d | kick". */
    private static List<Settings.WarnStep> warnEscalation(Reader r) {
        String path = "moderation.warn-escalation";
        Object raw = r.root.get(path);
        if (raw == null) {
            return DEFAULT_WARN_ESCALATION;
        }
        List<Settings.WarnStep> steps = new ArrayList<>();
        if (!(raw instanceof ConfigurationSection section)) {
            r.warn(path + " must be a map like '3: mute 1h'; warnings do not escalate.");
            return steps;
        }
        for (String key : section.getKeys(false)) {
            Settings.WarnStep step = parseWarnStep(key, String.valueOf(section.get(key)));
            if (step == null) {
                r.warn(path + "." + key + " must look like '3: mute 1h', '5: ban 7d' or '4: kick'; it is ignored.");
            } else {
                steps.add(step);
            }
        }
        return steps;
    }

    static Settings.WarnStep parseWarnStep(String warnings, String action) {
        try {
            int count = Integer.parseInt(warnings.trim());
            String[] parts = action.trim().toLowerCase(Locale.ROOT).split("\\s+");
            PunishmentType type = switch (parts[0]) {
                case "ban" -> PunishmentType.BAN;
                case "mute" -> PunishmentType.MUTE;
                case "kick" -> PunishmentType.KICK;
                default -> null;
            };
            if (count < 1 || type == null) {
                return null;
            }
            long duration = Durations.PERMANENT;
            if (type != PunishmentType.KICK && parts.length > 1) {
                Long parsed = Durations.parse(parts[1]);
                if (parsed == null) {
                    return null;
                }
                duration = parsed;
            }
            return new Settings.WarnStep(count, type, duration);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Reads {@code moderation.reasons.<type>}: a map of name to length(s) per offence, or a
     * plain list of names without lengths.
     */
    private static List<ReasonPreset> loadReasons(Reader r, PunishmentType type) {
        String path = "moderation.reasons." + type.key();
        Object raw = r.root.get(path);
        if (raw == null) {
            return DEFAULT_REASONS.get(type);
        }
        List<ReasonPreset> presets = new ArrayList<>();
        if (raw instanceof ConfigurationSection section) {
            for (String name : section.getKeys(false)) {
                presets.add(new ReasonPreset(name, parseLadder(section.get(name), r, path + "." + name)));
            }
        } else if (raw instanceof List<?> list) {
            for (Object element : list) {
                if (element != null && !element.toString().isBlank()) {
                    presets.add(new ReasonPreset(element.toString().trim(), List.of()));
                }
            }
        } else {
            r.warn(path + " must be a list or a map of reason: duration; using the defaults.");
            return DEFAULT_REASONS.get(type);
        }
        return presets;
    }

    private static CheckSettings loadCheck(Reader r, CheckType type, String path) {
        CheckSpec spec = CheckSpec.of(type);
        CheckSpec.Defaults d = spec.defaults();
        String base = path + ".";

        // "speed: false" is accepted as a shorthand for "speed: {enabled: false}".
        Object raw = r.root.get(path);
        if (raw != null && !(raw instanceof ConfigurationSection)) {
            boolean enabled = r.bool(path, d.enabled());
            CheckSettings defaults = CheckSettings.defaults(type);
            return new CheckSettings(type, enabled, defaults.alertVl(), defaults.banVl(), defaults.decayPerMinute(),
                    defaults.vlPerFlag(), defaults.bufferThreshold(), defaults.mitigate(), defaults.numbers(),
                    defaults.lists());
        }

        Map<String, Double> numbers = new HashMap<>();
        for (CheckSpec.NumberOption option : spec.numbers()) {
            numbers.put(option.key(), r.number(base + option.key(), option.defaultValue(), option.min(), option.max()));
        }
        Map<String, List<String>> lists = new HashMap<>();
        for (CheckSpec.ListOption option : spec.lists()) {
            lists.put(option.key(), upper(r.stringList(base + option.key(), option.defaultValue())));
        }

        // Never cancel something the check would not even flag.
        if (numbers.containsKey("cancel-leniency") && numbers.containsKey("leniency")
                && numbers.get("cancel-leniency") < numbers.get("leniency")) {
            r.warn(base + "cancel-leniency is lower than leniency; using leniency instead.");
            numbers.put("cancel-leniency", numbers.get("leniency"));
        }

        ConfigurationSection section = r.root.getConfigurationSection(path);
        if (section != null) {
            Set<String> known = new HashSet<>(COMMON_CHECK_KEYS);
            spec.numbers().forEach(option -> known.add(option.key()));
            spec.lists().forEach(option -> known.add(option.key()));
            for (String key : section.getKeys(false)) {
                if (!known.contains(key)) {
                    r.warn(base + key + " is not a known option and is ignored.");
                }
            }
        }

        return new CheckSettings(type,
                r.bool(base + "enabled", d.enabled()),
                r.number(base + "alert-at", d.alertVl(), 0.0, 1_000_000.0),
                r.number(base + "ban-at", d.banVl(), 0.0, 1_000_000.0),
                r.number(base + "decay-per-minute", d.decayPerMinute(), 0.0, 1_000_000.0),
                r.number(base + "vl-per-flag", d.vlPerFlag(), 0.01, 1000.0),
                r.number(base + "buffer-threshold", d.bufferThreshold(), 1.0, 1000.0),
                r.bool(base + "mitigate", d.mitigate()),
                numbers,
                lists);
    }

    private static String stripSlash(String command) {
        return command.startsWith("/") ? command.substring(1) : command;
    }

    private static List<String> upper(List<String> values) {
        List<String> result = new ArrayList<>(values.size());
        for (String value : values) {
            result.add(value.trim().toUpperCase(Locale.ROOT));
        }
        return result;
    }

    /** Typed, validating accessor that records a warning for every rejected value. */
    private static final class Reader {
        private final ConfigurationSection root;
        private final List<String> warnings;

        Reader(ConfigurationSection root, List<String> warnings) {
            this.root = root;
            this.warnings = warnings;
        }

        void warn(String message) {
            warnings.add(message);
        }

        boolean bool(String path, boolean def) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            if (value instanceof Boolean b) {
                return b;
            }
            String text = value.toString().trim().toLowerCase(Locale.ROOT);
            if (text.equals("true") || text.equals("yes") || text.equals("on")) {
                return true;
            }
            if (text.equals("false") || text.equals("no") || text.equals("off")) {
                return false;
            }
            warn(path + " must be true or false (got '" + value + "'); using default " + def + ".");
            return def;
        }

        double number(String path, double def, double min, double max) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            Double parsed = toDouble(value);
            if (parsed == null || !Double.isFinite(parsed)) {
                warn(path + " must be a number (got '" + value + "'); using default " + format(def) + ".");
                return def;
            }
            if (parsed < min || parsed > max) {
                warn(path + " must be between " + format(min) + " and " + format(max) + " (got " + format(parsed)
                        + "); using default " + format(def) + ".");
                return def;
            }
            return parsed;
        }

        long millis(String path, long def, long min, long max) {
            return Math.round(number(path, def, min, max));
        }

        String string(String path, String def) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            if (value instanceof ConfigurationSection || value instanceof List<?>) {
                warn(path + " must be text; using the default.");
                return def;
            }
            return value.toString();
        }

        /** Like {@link #string} but also accepts a list of lines; a literal {@code \n} starts a new line. */
        String text(String path, String def) {
            Object value = root.get(path);
            if (value instanceof List<?> lines) {
                List<String> parts = new ArrayList<>();
                for (Object line : lines) {
                    parts.add(line == null ? "" : line.toString());
                }
                return String.join("\n", parts);
            }
            return string(path, def).replace("\\n", "\n");
        }

        /** A Discord ID (digits); empty when not set or invalid (invalid values are reported). */
        String snowflake(String path) {
            Object value = root.get(path);
            if (value == null) {
                return "";
            }
            String text = value instanceof Number number ? Long.toString(number.longValue()) : value.toString().trim();
            if (text.isEmpty() || text.equals("0")) {
                return "";
            }
            if (!isSnowflake(text)) {
                warn(path + " must be a Discord ID (turn on Developer Mode in Discord, then right-click > Copy ID); "
                        + "got '" + text + "'. It is not used.");
                return "";
            }
            return text;
        }

        long duration(String path, long def) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            Long parsed = Durations.parse(value.toString());
            if (parsed == null) {
                warn(path + " must be a duration such as 30m, 12h, 7d or perm (got '" + value + "'); using the default.");
                return def;
            }
            return parsed;
        }

        List<String> stringList(String path, List<String> def) {
            Object value = root.get(path);
            if (value == null) {
                return def;
            }
            if (!(value instanceof List<?> list)) {
                warn(path + " must be a list; using the default.");
                return def;
            }
            List<String> result = new ArrayList<>();
            for (Object element : list) {
                if (element != null && !element.toString().isBlank()) {
                    result.add(element.toString().trim());
                }
            }
            return result;
        }

        static Double toDouble(Object value) {
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value instanceof String text) {
                try {
                    return Double.parseDouble(text.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
            return null;
        }

        private static String format(double value) {
            return value == Math.rint(value) ? Long.toString((long) value) : Double.toString(value);
        }
    }
}
