package io.github.drepfy.vigil.moderation;

import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.util.ActionBar;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Staff punishment commands. Syntax:
 * <pre>
 * /ban &lt;player&gt; [duration] [reason]      /unban &lt;player&gt; [reason]
 * /mute &lt;player&gt; [duration] [reason]     /unmute &lt;player&gt; [reason]
 * /warn &lt;player&gt; &lt;time&gt; [reason]         /unwarn &lt;player&gt; [reason]
 * /kick &lt;player&gt; [reason]
 * </pre>
 * A warning always needs a time (between {@code moderation.warn-time.min} and
 * {@code max}, 1h to 10d by default); it stops counting after that time.
 * A reason that matches a preset (e.g. {@code Cheating}) uses the preset's time for the
 * player's next offence (7 days, then 30 days, then permanent...) unless a time is
 * given. Without a reason, "No Reason" is used. Typing a command without arguments
 * lists its presets. Reaching a number of warnings can mute or ban automatically
 * ({@code moderation.warn-escalation}).
 */
public final class ModerationCommand implements CommandExecutor, TabCompleter {

    /** Staff who see everyone's punishments (same as anti-cheat alerts). */
    public static final String NOTIFY_PERMISSION = "vigil.alerts";
    /** Players who cannot be punished by other players (and are never banned automatically). */
    public static final String EXEMPT_PERMISSION = "vigil.protect";
    private static final List<String> DURATION_SUGGESTIONS = List.of("30m", "1h", "6h", "12h", "1d", "3d", "7d",
            "14d", "30d", "perm");

    private static final List<String> WARN_TIME_SUGGESTIONS = List.of("1h", "6h", "12h", "1d", "3d", "7d", "10d");

    /** A player to punish: online, or known to the server from an earlier visit. */
    public record Target(UUID uuid, String name, Player online) {
    }

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final ModerationService service;
    private final ModerationListener formatter;
    private final Logger logger;
    private final java.util.function.Consumer<Punishment> discord;

    public ModerationCommand(Plugin plugin, Supplier<Settings> settings, ModerationService service,
                             ModerationListener formatter, Logger logger,
                             java.util.function.Consumer<Punishment> discord) {
        this.plugin = plugin;
        this.settings = settings;
        this.service = service;
        this.formatter = formatter;
        this.logger = logger;
        this.discord = discord;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!sender.hasPermission(permission(name))) {
            send(sender, message("no-permission"));
            return true;
        }
        if (!settings.get().moderation().enabled()) {
            send(sender, message("moderation-disabled"));
            return true;
        }
        switch (name) {
            case "ban" -> punish(sender, PunishmentType.BAN, args, label);
            case "mute" -> punish(sender, PunishmentType.MUTE, args, label);
            case "unban" -> lift(sender, PunishmentType.UNBAN, args, label);
            case "unmute" -> lift(sender, PunishmentType.UNMUTE, args, label);
            case "warn" -> warn(sender, args, label);
            case "unwarn" -> unwarn(sender, args, label);
            case "kick" -> kick(sender, args, label);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- ban / mute ----------------------------------------------------------------------------

    private void punish(CommandSender sender, PunishmentType type, String[] args, String label) {
        if (args.length < 1) {
            usage(sender, label + " <player> [time] [reason]");
            showPresets(sender, type);
            return;
        }
        Target target = resolve(args[0]);
        if (target == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[0]));
            return;
        }
        if (!mayPunish(sender, target)) {
            return;
        }
        int reasonStart = 1;
        Long duration = null;
        if (args.length > 1) {
            duration = Durations.parse(args[1]);
            if (duration != null) {
                reasonStart = 2;
            }
        }
        String input = join(args, reasonStart);
        List<ReasonPreset> presets = settings.get().moderation().reasons(type);
        ReasonPreset preset = ReasonPreset.find(presets, input);
        String reason = reasonText(preset, input);
        if (preset == null && reasonStart < args.length) {
            // "/ban Steve Cheating with kill aura": keep the full text, use the Cheating times.
            preset = ReasonPreset.find(presets, args[reasonStart]);
        }
        if (duration == null) {
            duration = presetDuration(target.uuid(), type, preset);
        }
        execute(sender, target, type, reason, duration);
    }

    /** The preset's time for this player's next offence, or the configured default. */
    private long presetDuration(UUID uuid, PunishmentType type, ReasonPreset preset) {
        if (preset != null && !preset.durations().isEmpty()) {
            return preset.durationFor(service.previousOffences(uuid, type, preset::matches, Integer.MAX_VALUE));
        }
        Settings.Moderation moderation = settings.get().moderation();
        return type == PunishmentType.BAN ? moderation.defaultBanMs() : moderation.defaultMuteMs();
    }

    /** Bans or mutes, tells the player, the staff member and whoever should know. */
    private Punishment execute(CommandSender sender, Target target, PunishmentType type, String reason, long duration) {
        String staff = staffName(sender);
        Punishment punishment = type == PunishmentType.BAN
                ? service.ban(target.uuid(), target.name(), reason, staff, duration)
                : service.mute(target.uuid(), target.name(), reason, staff, duration);
        Player online = Bukkit.getPlayer(target.uuid());
        if (online != null) {
            if (type == PunishmentType.BAN) {
                online.kickPlayer(formatter.banScreen(punishment));
            } else {
                online.sendMessage(Text.color(message("prefix") + formatter.fill(message("mute-notify"), punishment)));
                ActionBar.show(plugin, online, formatter.fill(message("mute-actionbar"), punishment));
            }
        }
        String key = type.key();
        send(sender, formatter.fill(message(key + "-success"), punishment));
        broadcast(sender, formatter.fill(message(key + "-broadcast"), punishment));
        discord.accept(punishment);
        return punishment;
    }

    /** Lists the preset reasons of a command with their time per offence. */
    private void showPresets(CommandSender sender, PunishmentType type) {
        List<ReasonPreset> presets = settings.get().moderation().reasons(type);
        if (presets.isEmpty()) {
            return;
        }
        sender.sendMessage(Text.color(message("presets-header")));
        for (ReasonPreset preset : presets) {
            String times = preset.durations().isEmpty() ? "" : " &8- &f" + compactLadder(preset);
            sender.sendMessage(Text.color("  &b" + preset.display() + times));
        }
    }

    private static String compactLadder(ReasonPreset preset) {
        List<String> parts = new ArrayList<>();
        for (Long duration : preset.durations()) {
            parts.add(duration == Durations.PERMANENT ? "perm" : Durations.compact(duration));
        }
        return String.join("&8, &f", parts);
    }

    // ---- unban / unmute --------------------------------------------------------------------------

    private void lift(CommandSender sender, PunishmentType type, String[] args, String label) {
        if (args.length < 1) {
            usage(sender, label + " <player> [reason]");
            return;
        }
        boolean ban = type == PunishmentType.UNBAN;
        Target target = resolve(args[0]);
        UUID uuid = target != null ? target.uuid() : null;
        Punishment active = uuid != null ? (ban ? service.activeBan(uuid) : service.activeMute(uuid)) : null;
        if (active == null) {
            // The name may only be known from the punishment itself.
            active = ban ? service.activeBanByName(args[0]) : service.activeMuteByName(args[0]);
        }
        if (active == null) {
            send(sender, Text.replace(message(ban ? "not-banned" : "not-muted"), "player",
                    target != null ? target.name() : args[0]));
            return;
        }
        String input = join(args, 1);
        String reason = reasonText(ReasonPreset.find(settings.get().moderation().reasons(type), input), input);
        String staff = staffName(sender);
        Punishment lifted = ban ? service.unban(active.uuid(), staff, reason) : service.unmute(active.uuid(), staff, reason);
        if (lifted == null) {
            send(sender, Text.replace(message(ban ? "not-banned" : "not-muted"), "player", active.name()));
            return;
        }
        String text = Text.replace(message(type.key() + "-success"), "reason", reason);
        String broadcastText = Text.replace(message(type.key() + "-broadcast"), "reason", reason, "staff", staff);
        send(sender, formatter.fill(text, lifted));
        broadcast(sender, formatter.fill(broadcastText, lifted));
        discord.accept(lifted);
        Player online = Bukkit.getPlayer(lifted.uuid());
        if (!ban && online != null) {
            online.sendMessage(Text.color(message("prefix") + message("unmute-notify")));
            ActionBar.show(plugin, online, formatter.fill(message("unmute-actionbar"), lifted));
        }
    }

    // ---- warn / unwarn / kick ------------------------------------------------------------------------

    private void warn(CommandSender sender, String[] args, String label) {
        Settings.Moderation moderation = settings.get().moderation();
        if (args.length < 1) {
            usage(sender, label + " <player> <time> [reason]");
            showPresets(sender, PunishmentType.WARN);
            return;
        }
        Target target = resolve(args[0]);
        if (target == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[0]));
            return;
        }
        Long duration = args.length > 1 ? Durations.parse(args[1]) : null;
        if (duration == null || duration == Durations.PERMANENT
                || duration < moderation.warnMinMs() || duration > moderation.warnMaxMs()) {
            String permanent = message("permanent");
            send(sender, Text.replace(message("warn-time-required"), "player", target.name(),
                    "min", Durations.format(moderation.warnMinMs(), permanent),
                    "max", Durations.format(moderation.warnMaxMs(), permanent)));
            return;
        }
        if (!mayPunish(sender, target)) {
            return;
        }
        String input = join(args, 2);
        String reason = reasonText(ReasonPreset.find(moderation.reasons(PunishmentType.WARN), input), input);
        Punishment warning = service.warn(target.uuid(), target.name(), reason, staffName(sender), duration);
        int warnings = activeWarnings(target.uuid());
        String count = String.valueOf(warnings);
        Player online = Bukkit.getPlayer(target.uuid());
        if (online != null) {
            online.sendMessage(Text.color(message("prefix")
                    + Text.replace(formatter.fill(message("warn-notify"), warning), "count", count)));
            ActionBar.show(plugin, online, formatter.fill(message("warn-actionbar"), warning));
        }
        send(sender, Text.replace(formatter.fill(message("warn-success"), warning), "count", count));
        broadcast(sender, Text.replace(formatter.fill(message("warn-broadcast"), warning), "count", count));
        discord.accept(warning);
        escalate(sender, target, warnings);
    }

    private void unwarn(CommandSender sender, String[] args, String label) {
        if (args.length < 1) {
            usage(sender, label + " <player> [reason]");
            return;
        }
        Target target = resolve(args[0]);
        if (target == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[0]));
            return;
        }
        String input = join(args, 1);
        String reason = reasonText(ReasonPreset.find(settings.get().moderation().reasons(PunishmentType.UNWARN), input),
                input);
        String staff = staffName(sender);
        Punishment lifted = service.unwarn(target.uuid(), staff, reason, legacyWarningExpireMs());
        if (lifted == null) {
            send(sender, Text.replace(message("not-warned"), "player", target.name()));
            return;
        }
        String count = String.valueOf(activeWarnings(target.uuid()));
        String text = Text.replace(message("unwarn-success"), "reason", reason, "count", count);
        String broadcastText = Text.replace(message("unwarn-broadcast"), "reason", reason, "staff", staff);
        send(sender, formatter.fill(text, lifted));
        broadcast(sender, formatter.fill(broadcastText, lifted));
        discord.accept(lifted);
        Player online = Bukkit.getPlayer(target.uuid());
        if (online != null) {
            online.sendMessage(Text.color(message("prefix") + Text.replace(message("unwarn-notify"), "count", count)));
            ActionBar.show(plugin, online, formatter.fill(message("unwarn-actionbar"), lifted));
        }
    }

    private void kick(CommandSender sender, String[] args, String label) {
        if (args.length < 1) {
            usage(sender, label + " <player> [reason]");
            showPresets(sender, PunishmentType.KICK);
            return;
        }
        Target target = resolve(args[0]);
        if (target == null || target.online() == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[0]));
            return;
        }
        if (!mayPunish(sender, target)) {
            return;
        }
        String input = join(args, 1);
        String reason = reasonText(ReasonPreset.find(settings.get().moderation().reasons(PunishmentType.KICK), input),
                input);
        Punishment kick = service.kick(target.uuid(), target.name(), reason, staffName(sender));
        target.online().kickPlayer(formatter.screen("kick-screen", kick));
        send(sender, formatter.fill(message("kick-success"), kick));
        broadcast(sender, formatter.fill(message("kick-broadcast"), kick));
        discord.accept(kick);
    }

    /** Warnings that still count (not removed and not expired). */
    private int activeWarnings(UUID uuid) {
        return service.activeWarnings(uuid, legacyWarningExpireMs()).size();
    }

    /** How long warnings from before 2.4 (given without a time) count. */
    private long legacyWarningExpireMs() {
        return settings.get().moderation().warningsExpireMs();
    }

    /** Applies moderation.warn-escalation when a player reaches a number of warnings. */
    private void escalate(CommandSender sender, Target target, int warnings) {
        Settings.WarnStep step = settings.get().moderation().warnStep(warnings);
        if (step == null) {
            return;
        }
        String reason = Text.replace(message("warn-escalation-reason"), "count", warnings);
        switch (step.type()) {
            case BAN, MUTE -> {
                Punishment current = step.type() == PunishmentType.BAN
                        ? service.activeBan(target.uuid()) : service.activeMute(target.uuid());
                if (!outlasts(current, step.durationMs())) {
                    execute(sender, target, step.type(), reason, step.durationMs());
                }
            }
            case KICK -> {
                Player online = Bukkit.getPlayer(target.uuid());
                if (online != null) {
                    Punishment kick = service.kick(target.uuid(), target.name(), reason, staffName(sender));
                    online.kickPlayer(formatter.screen("kick-screen", kick));
                    send(sender, formatter.fill(message("kick-success"), kick));
                    broadcast(sender, formatter.fill(message("kick-broadcast"), kick));
                    discord.accept(kick);
                }
            }
            default -> {
                // Only bans, mutes and kicks can be configured.
            }
        }
    }

    /** Whether an active punishment already lasts at least as long as a new one would (never shorten it). */
    private static boolean outlasts(Punishment current, long durationMs) {
        if (current == null) {
            return false;
        }
        if (current.isPermanent()) {
            return true;
        }
        return durationMs != Durations.PERMANENT
                && current.expiresEpochMs() >= System.currentTimeMillis() + durationMs;
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private boolean mayPunish(CommandSender sender, Target target) {
        if (sender instanceof Player player && player.getUniqueId().equals(target.uuid())) {
            send(sender, "&cYou cannot punish yourself.");
            return false;
        }
        // Only the server console may punish protected players; staff in game and on Discord may not.
        if ((sender instanceof Player || sender instanceof RemoteStaff)
                && target.online() != null && target.online().hasPermission(EXEMPT_PERMISSION)) {
            send(sender, Text.replace(message("cannot-punish"), "player", target.name()));
            return false;
        }
        return true;
    }

    private String reasonText(ReasonPreset preset, String input) {
        if (preset != null) {
            return preset.display();
        }
        return input.isBlank() ? message("no-reason") : input;
    }

    /** Resolves an online player, a UUID, or a player who joined before (no web lookup). Server thread. */
    public static Target resolve(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return new Target(online.getUniqueId(), online.getName(), online);
        }
        try {
            UUID uuid = UUID.fromString(input);
            OfflinePlayer offline = Bukkit.getOfflinePlayer(uuid);
            return new Target(uuid, offline.getName() != null ? offline.getName() : input, Bukkit.getPlayer(uuid));
        } catch (IllegalArgumentException ignored) {
            // Not a UUID.
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(input)) {
                return new Target(offline.getUniqueId(), offline.getName(), null);
            }
        }
        return null;
    }

    private void broadcast(CommandSender sender, String text) {
        String mode = settings.get().moderation().broadcast();
        String colored = Text.color(message("prefix") + text);
        logger.info(ChatColor.stripColor(colored));
        if (mode.equals("none")) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.equals(sender)) {
                continue;
            }
            if (mode.equals("all") || player.hasPermission(NOTIFY_PERMISSION)) {
                player.sendMessage(colored);
            }
        }
    }

    private static String staffName(CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getName();
        }
        return sender instanceof RemoteStaff remote ? remote.staffName() : "Console";
    }

    private static String join(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length)).trim();
    }

    /** Every command has its own permission, e.g. {@code vigil.unban}. */
    private static String permission(String command) {
        return "vigil." + command;
    }

    private String message(String key) {
        return settings.get().messages().get(key);
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(Text.color(message("prefix") + text));
    }

    private void usage(CommandSender sender, String usage) {
        send(sender, "&cUsage: /" + usage);
    }

    // ---- tab completion ----------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (!sender.hasPermission(permission(name))) {
            return List.of();
        }
        Settings.Moderation moderation = settings.get().moderation();
        if (args.length == 1) {
            List<String> names = switch (name) {
                case "unban" -> service.bannedNames();
                case "unmute" -> service.mutedNames();
                case "unwarn" -> warnedOnlineNames();
                default -> onlineNames();
            };
            return filter(names, args[0]);
        }
        List<String> options = new ArrayList<>();
        switch (name) {
            case "ban", "mute" -> {
                PunishmentType type = name.equals("ban") ? PunishmentType.BAN : PunishmentType.MUTE;
                if (args.length == 2) {
                    options.addAll(presetNames(moderation.reasons(type)));
                    options.addAll(DURATION_SUGGESTIONS);
                } else if (args.length == 3 && Durations.parse(args[1]) != null) {
                    options.addAll(presetNames(moderation.reasons(type)));
                }
            }
            case "warn" -> {
                if (args.length == 2) {
                    options.addAll(WARN_TIME_SUGGESTIONS);
                } else if (args.length == 3) {
                    options.addAll(presetNames(moderation.reasons(PunishmentType.WARN)));
                }
            }
            case "kick", "unban", "unmute", "unwarn" -> {
                if (args.length == 2) {
                    options.addAll(presetNames(moderation.reasons(PunishmentType.valueOf(name.toUpperCase(Locale.ROOT)))));
                }
            }
            default -> {
                return List.of();
            }
        }
        return filter(options, args[args.length - 1]);
    }

    private static List<String> presetNames(List<ReasonPreset> presets) {
        List<String> names = new ArrayList<>(presets.size());
        for (ReasonPreset preset : presets) {
            names.add(preset.name());
        }
        return names;
    }

    private List<String> warnedOnlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (activeWarnings(player.getUniqueId()) > 0) {
                names.add(player.getName());
            }
        }
        return names;
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
