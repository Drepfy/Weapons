package io.github.drepfy.vigil.moderation;

import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

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
 * /warn &lt;player&gt; [reason]                /kick &lt;player&gt; [reason]
 * </pre>
 * A reason that matches a preset (e.g. {@code Cheating}) uses the preset's default
 * duration unless a duration is given. Without a reason, "No Reason" is used. A
 * player's punishment history is part of {@code /ac check}.
 */
public final class ModerationCommand implements CommandExecutor, TabCompleter {

    /** Staff who see everyone's punishments (same as anti-cheat alerts). */
    public static final String NOTIFY_PERMISSION = "vigil.alerts";
    /** Players who cannot be punished by other players (and are never banned automatically). */
    public static final String EXEMPT_PERMISSION = "vigil.protect";
    private static final List<String> DURATION_SUGGESTIONS = List.of("30m", "1h", "6h", "12h", "1d", "3d", "7d",
            "14d", "30d", "perm");

    private record Target(UUID uuid, String name, Player online) {
    }

    private final Supplier<Settings> settings;
    private final ModerationService service;
    private final ModerationListener formatter;
    private final Logger logger;

    public ModerationCommand(Supplier<Settings> settings, ModerationService service, ModerationListener formatter,
                             Logger logger) {
        this.settings = settings;
        this.service = service;
        this.formatter = formatter;
        this.logger = logger;
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
            case "warn" -> warnOrKick(sender, PunishmentType.WARN, args, label);
            case "kick" -> warnOrKick(sender, PunishmentType.KICK, args, label);
            default -> {
                return false;
            }
        }
        return true;
    }

    // ---- ban / mute ----------------------------------------------------------------------------

    private void punish(CommandSender sender, PunishmentType type, String[] args, String label) {
        if (args.length < 1) {
            usage(sender, label + " <player> [duration] [reason]");
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
            // "/ban Steve Cheating with kill aura": keep the full text, use the Cheating duration.
            preset = ReasonPreset.find(presets, args[reasonStart]);
        }
        if (duration == null) {
            Settings.Moderation moderation = settings.get().moderation();
            duration = preset != null && preset.defaultDuration() != null ? preset.defaultDuration()
                    : type == PunishmentType.BAN ? moderation.defaultBanMs() : moderation.defaultMuteMs();
        }

        String staff = staffName(sender);
        Punishment punishment = type == PunishmentType.BAN
                ? service.ban(target.uuid(), target.name(), reason, staff, duration)
                : service.mute(target.uuid(), target.name(), reason, staff, duration);

        if (target.online() != null) {
            if (type == PunishmentType.BAN) {
                target.online().kickPlayer(formatter.screen("ban-screen", punishment));
            } else {
                target.online().sendMessage(Text.color(message("prefix")
                        + formatter.fill(message("mute-notify"), punishment)));
            }
        }
        String key = type.key();
        send(sender, formatter.fill(message(key + "-success"), punishment));
        broadcast(sender, formatter.fill(message(key + "-broadcast"), punishment));
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
        Player online = Bukkit.getPlayer(lifted.uuid());
        if (!ban && online != null) {
            online.sendMessage(Text.color(message("prefix") + message("unmute-notify")));
        }
    }

    // ---- warn / kick ------------------------------------------------------------------------------

    private void warnOrKick(CommandSender sender, PunishmentType type, String[] args, String label) {
        if (args.length < 1) {
            usage(sender, label + " <player> [reason]");
            return;
        }
        Target target = resolve(args[0]);
        if (target == null || (type == PunishmentType.KICK && target.online() == null)) {
            send(sender, Text.replace(message("player-not-found"), "player", args[0]));
            return;
        }
        if (!mayPunish(sender, target)) {
            return;
        }
        String input = join(args, 1);
        String reason = reasonText(ReasonPreset.find(settings.get().moderation().reasons(type), input), input);
        String staff = staffName(sender);
        Punishment punishment;
        String count = "";
        if (type == PunishmentType.WARN) {
            punishment = service.warn(target.uuid(), target.name(), reason, staff);
            count = String.valueOf(service.warningCount(target.uuid()));
            if (target.online() != null) {
                target.online().sendMessage(Text.color(message("prefix")
                        + Text.replace(formatter.fill(message("warn-notify"), punishment), "count", count)));
            }
        } else {
            punishment = service.kick(target.uuid(), target.name(), reason, staff);
            target.online().kickPlayer(formatter.screen("kick-screen", punishment));
        }
        String key = type.key();
        send(sender, Text.replace(formatter.fill(message(key + "-success"), punishment), "count", count));
        broadcast(sender, Text.replace(formatter.fill(message(key + "-broadcast"), punishment), "count", count));
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private boolean mayPunish(CommandSender sender, Target target) {
        if (sender instanceof Player player) {
            if (player.getUniqueId().equals(target.uuid())) {
                send(sender, "&cYou cannot punish yourself.");
                return false;
            }
            if (target.online() != null && target.online().hasPermission(EXEMPT_PERMISSION)) {
                send(sender, Text.replace(message("cannot-punish"), "player", target.name()));
                return false;
            }
        }
        return true;
    }

    private String reasonText(ReasonPreset preset, String input) {
        if (preset != null) {
            return preset.display();
        }
        return input.isBlank() ? message("no-reason") : input;
    }

    /** Resolves an online player, a UUID, or a player who joined before (no web lookup). */
    private static Target resolve(String input) {
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
        return sender instanceof Player player ? player.getName() : "Console";
    }

    private static String join(String[] args, int from) {
        return from >= args.length ? "" : String.join(" ", Arrays.copyOfRange(args, from, args.length)).trim();
    }

    private static String permission(String command) {
        return switch (command) {
            case "unban" -> "vigil.ban";
            case "unmute" -> "vigil.mute";
            default -> "vigil." + command;
        };
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
            case "warn", "kick", "unban", "unmute" -> {
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
