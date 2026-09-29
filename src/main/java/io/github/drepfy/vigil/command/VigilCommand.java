package io.github.drepfy.vigil.command;

import io.github.drepfy.vigil.VigilPlugin;
import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.FlagRecord;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.moderation.Durations;
import io.github.drepfy.vigil.moderation.ModerationService;
import io.github.drepfy.vigil.moderation.Punishment;
import io.github.drepfy.vigil.moderation.PunishmentType;
import io.github.drepfy.vigil.storage.PlayerRecord;
import io.github.drepfy.vigil.util.Clock;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@code /ac} (aliases {@code /anticheat}, {@code /vigil}): the only anti-cheat command.
 * <pre>
 * /ac alerts                  toggle alerts for yourself          vigil.alerts
 * /ac check &lt;player&gt;          violations, flags and punishments   vigil.check
 * /ac reset &lt;player&gt; [check]  clear violation levels              vigil.admin
 * /ac debug &lt;player&gt; [check]  stream live check values            vigil.admin
 * /ac reload                  reload config.yml                   vigil.admin
 * /ac preview                 watch the ban animation (no ban)    vigil.admin
 * </pre>
 */
public final class VigilCommand implements CommandExecutor, TabCompleter {

    private static final int SHOWN_FLAGS = 5;
    private static final int SHOWN_PUNISHMENTS = 5;

    private record SubCommand(String name, String permission, String usage, String description) {
    }

    private static final List<SubCommand> SUBCOMMANDS = List.of(
            new SubCommand("alerts", "vigil.alerts", "alerts", "Turn anti-cheat alerts on/off"),
            new SubCommand("check", "vigil.check", "check <player>", "Violations, recent flags and punishments"),
            new SubCommand("reset", "vigil.admin", "reset <player> [check]", "Clear a player's violation levels"),
            new SubCommand("debug", "vigil.admin", "debug <player> [check]", "Show live check values (tuning)"),
            new SubCommand("reload", "vigil.admin", "reload", "Reload config.yml"),
            new SubCommand("preview", "vigil.admin", "preview", "See the ban animation on yourself (no ban)"));

    private final VigilPlugin plugin;

    public VigilCommand(VigilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        SubCommand sub = args.length == 0 ? null : find(args[0]);
        if (sub == null) {
            help(sender, label);
            return true;
        }
        if (!sender.hasPermission(sub.permission())) {
            send(sender, message("no-permission"));
            return true;
        }
        try {
            switch (sub.name()) {
                case "alerts" -> alerts(sender);
                case "check" -> check(sender, args, label);
                case "reset" -> reset(sender, args, label);
                case "debug" -> debug(sender, args, label);
                case "reload" -> reload(sender);
                case "preview" -> preview(sender);
                default -> help(sender, label);
            }
        } catch (RuntimeException e) {
            send(sender, "&cSomething went wrong: " + e.getMessage());
            plugin.getLogger().log(Level.WARNING, "Command failed: /" + label + " " + String.join(" ", args), e);
        }
        return true;
    }

    // ---- sub-commands --------------------------------------------------------------------------

    private void help(CommandSender sender, String label) {
        boolean any = false;
        for (SubCommand sub : SUBCOMMANDS) {
            if (sender.hasPermission(sub.permission())) {
                if (!any) {
                    send(sender, "&fAnti-cheat &7" + plugin.getDescription().getVersion());
                    any = true;
                }
                sender.sendMessage(Text.color(" &b/" + label + " " + sub.usage() + " &8- &7" + sub.description()));
            }
        }
        if (!any) {
            send(sender, message("no-permission"));
        }
    }

    private void alerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&7The console always receives alerts (advanced.alert-console).");
            return;
        }
        boolean enabled = plugin.alerts().toggle(player.getUniqueId());
        send(sender, message(enabled ? "alerts-enabled" : "alerts-disabled"));
    }

    private void check(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            usage(sender, label, "check <player>");
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        if (online != null) {
            showOnline(sender, online);
            return;
        }
        OfflinePlayer offline = findOffline(args[1]);
        if (offline == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[1]));
            return;
        }
        UUID uuid = offline.getUniqueId();
        String name = offline.getName() != null ? offline.getName() : args[1];
        plugin.records().loadExisting(uuid).thenAccept(record -> Bukkit.getScheduler().runTask(plugin, () -> {
            send(sender, "&b" + name + " &8(offline)");
            if (record == null) {
                line(sender, "&7No anti-cheat record.");
            } else {
                line(sender, "&7Lifetime flags: &f" + lifetime(record) + " &8| &7last seen &f"
                        + Text.duration(System.currentTimeMillis() - record.lastSeenEpochMs()) + " ago");
                List<String> flags = record.recentFlags();
                for (String flag : flags.subList(0, Math.min(SHOWN_FLAGS, flags.size()))) {
                    line(sender, " &8- &7" + flag);
                }
            }
            showPunishments(sender, uuid);
        }));
    }

    private void showOnline(CommandSender sender, Player target) {
        PlayerData data = plugin.players().get(target);
        long now = Clock.now();
        Settings settings = plugin.settings();
        int ping = plugin.checks().recentPing(target, data, now);
        send(sender, "&b" + target.getName() + " &8(&7" + target.getGameMode().name().toLowerCase(Locale.ROOT)
                + ", " + ping + "ms, TPS " + Text.num(plugin.tpsMonitor().tps()) + "&8)");
        List<String> exempt = exemptionReasons(target, data, settings, now);
        line(sender, "&7Checked: " + (exempt.isEmpty() ? "&ayes" : "&cno &7(" + String.join(", ", exempt) + ")"));

        List<String> levels = new ArrayList<>();
        for (CheckType type : CheckType.values()) {
            CheckSettings check = settings.check(type);
            double vl = data.violation(type).get(now, check.decayPerMinute());
            if (vl >= 0.05) {
                levels.add("&f" + type.reason() + " &c" + Text.num(Math.round(vl * 10) / 10.0)
                        + (check.banVl() > 0 ? "&7/" + Text.num(check.banVl()) : ""));
            }
        }
        line(sender, "&7Violations: " + (levels.isEmpty() ? "&anone" : String.join("&7, ", levels)));
        List<FlagRecord> recent = data.recentFlags();
        if (!recent.isEmpty()) {
            line(sender, "&7Recent flags:");
            long nowEpoch = System.currentTimeMillis();
            for (FlagRecord flag : recent.subList(0, Math.min(SHOWN_FLAGS, recent.size()))) {
                line(sender, " &8- &7" + Text.duration(nowEpoch - flag.timeMs()) + " ago &c" + flag.check().reason()
                        + "&7: " + flag.detail());
            }
        }
        PlayerRecord record = data.record;
        if (record != null && record.totalLifetimeFlags() > 0) {
            line(sender, "&7Lifetime flags: &f" + lifetime(record));
        }
        showPunishments(sender, target.getUniqueId());
    }

    /** Why checks are currently not running for a player (empty = everything runs). */
    private List<String> exemptionReasons(Player target, PlayerData data, Settings settings, long now) {
        List<String> reasons = new ArrayList<>();
        if (!settings.general().enabled()) {
            reasons.add("anti-cheat disabled");
        }
        if (settings.general().exemptCreativeAndSpectator()
                && (target.getGameMode() == GameMode.CREATIVE || target.getGameMode() == GameMode.SPECTATOR)) {
            reasons.add(target.getGameMode().name().toLowerCase(Locale.ROOT) + " mode");
        }
        if (target.getAllowFlight()) {
            reasons.add("flying allowed (movement only)");
        }
        if (settings.general().disabledWorlds().contains(target.getWorld().getName())) {
            reasons.add("disabled world");
        }
        if (settings.general().bypassPermission() && target.hasPermission("vigil.bypass")) {
            reasons.add("vigil.bypass");
        }
        if (!data.activeExemptions(now).isEmpty()) {
            reasons.add("exempted by a plugin: " + String.join(", ", data.activeExemptions(now).keySet()));
        }
        return reasons;
    }

    private void showPunishments(CommandSender sender, UUID uuid) {
        ModerationService moderation = plugin.moderation();
        List<Punishment> history = moderation.history(uuid);
        Punishment ban = moderation.activeBan(uuid);
        Punishment mute = moderation.activeMute(uuid);
        line(sender, "&7Banned: " + (ban != null ? "&cyes" : "&ano") + " &8| &7Muted: "
                + (mute != null ? "&cyes" : "&ano") + " &8| &7Warnings: &f" + moderation.warningCount(uuid));
        String permanent = message("permanent");
        long now = System.currentTimeMillis();
        for (Punishment p : history.subList(0, Math.min(SHOWN_PUNISHMENTS, history.size()))) {
            StringBuilder text = new StringBuilder(" &8#").append(p.id()).append(" &e")
                    .append(p.type().name().toLowerCase(Locale.ROOT)).append(" &f").append(p.reason());
            if (p.type() == PunishmentType.BAN || p.type() == PunishmentType.MUTE) {
                text.append(" &7(").append(Durations.format(p.durationMs(), permanent)).append(')');
                text.append(p.revoked() ? " &a[lifted]" : p.isInEffect(now) ? " &c[active]" : " &8[expired]");
            }
            text.append(" &7by &f").append(p.staff()).append(" &8").append(Text.duration(now - p.createdEpochMs()))
                    .append(" ago");
            line(sender, text.toString());
        }
    }

    private void reset(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            usage(sender, label, "reset <player> [check]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[1]));
            return;
        }
        CheckType type = null;
        if (args.length >= 3) {
            type = CheckType.fromId(args[2]);
            if (type == null) {
                send(sender, "&cUnknown check: &f" + args[2]);
                return;
            }
        }
        plugin.players().get(target).resetViolations(type);
        send(sender, "&7Cleared " + (type == null ? "all violation levels" : type.reason() + " violations")
                + " of &b" + target.getName() + "&7.");
        plugin.getLogger().info(sender.getName() + " reset " + (type == null ? "all" : type.id()) + " VL of "
                + target.getName());
    }

    private void debug(CommandSender sender, String[] args, String label) {
        if (!(sender instanceof Player viewer)) {
            send(sender, "&7Only players can view debug output; set advanced.debug for console output.");
            return;
        }
        if (args.length < 2) {
            usage(sender, label, "debug <player> [check]");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, Text.replace(message("player-not-found"), "player", args[1]));
            return;
        }
        CheckType filter = null;
        if (args.length >= 3) {
            filter = CheckType.fromId(args[2]);
            if (filter == null) {
                send(sender, "&cUnknown check: &f" + args[2]);
                return;
            }
        }
        boolean on = plugin.debugService().toggle(viewer.getUniqueId(), target.getUniqueId(), filter);
        send(sender, (on ? "&aShowing" : "&7Stopped showing") + " debug values of &b" + target.getName()
                + (filter != null ? " &7(" + filter.displayName() + ")" : "") + "&7.");
    }

    private void preview(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            send(sender, "&7Only players can watch the preview.");
            return;
        }
        plugin.autoBan().playAnimation(player, "Flying");
        player.sendMessage(io.github.drepfy.vigil.violation.AutoBanService.banner(plugin.settings(), player.getName(),
                "Flying", "Flight", "30 days"));
        send(sender, "&7This is what everyone sees when the anti-cheat bans someone. &8(You were not banned.)");
    }

    private void reload(CommandSender sender) {
        try {
            List<String> warnings = plugin.reload();
            send(sender, Text.replace(message("reloaded"), "warnings", warnings.size()));
            for (String warning : warnings.subList(0, Math.min(5, warnings.size()))) {
                line(sender, " &e- " + warning);
            }
        } catch (IllegalStateException e) {
            send(sender, Text.replace(message("reload-failed"), "error", e.getMessage()));
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    private static String lifetime(PlayerRecord record) {
        StringBuilder text = new StringBuilder();
        record.lifetimeFlags().forEach((type, count) -> text.append(text.length() == 0 ? "" : ", ")
                .append(type.reason()).append(' ').append(count));
        return text.length() == 0 ? "none" : text.toString();
    }

    /** A player who joined before, by name or UUID (no web lookups). */
    private static OfflinePlayer findOffline(String input) {
        try {
            OfflinePlayer byId = Bukkit.getOfflinePlayer(UUID.fromString(input));
            return byId.hasPlayedBefore() || byId.getName() != null ? byId : null;
        } catch (IllegalArgumentException ignored) {
            // Not a UUID.
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(input)) {
                return offline;
            }
        }
        return null;
    }

    private static SubCommand find(String name) {
        for (SubCommand sub : SUBCOMMANDS) {
            if (sub.name().equalsIgnoreCase(name)) {
                return sub;
            }
        }
        return null;
    }

    private String message(String key) {
        return plugin.settings().messages().get(key);
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(Text.color(message("prefix") + text));
    }

    private static void line(CommandSender sender, String text) {
        sender.sendMessage(Text.color(text));
    }

    private void usage(CommandSender sender, String label, String usage) {
        send(sender, "&cUsage: /" + label + " " + usage);
    }

    // ---- tab completion ------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> names = new ArrayList<>();
            for (SubCommand sub : SUBCOMMANDS) {
                if (sender.hasPermission(sub.permission())) {
                    names.add(sub.name());
                }
            }
            return filter(names, args[0]);
        }
        SubCommand sub = find(args[0]);
        if (sub == null || !sender.hasPermission(sub.permission())) {
            return List.of();
        }
        boolean takesPlayer = sub.name().equals("check") || sub.name().equals("reset") || sub.name().equals("debug");
        if (args.length == 2 && takesPlayer) {
            List<String> names = new ArrayList<>();
            for (Player player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return filter(names, args[1]);
        }
        if (args.length == 3 && (sub.name().equals("reset") || sub.name().equals("debug"))) {
            List<String> ids = new ArrayList<>();
            for (CheckType type : CheckType.values()) {
                ids.add(type.id());
            }
            return filter(ids, args[2]);
        }
        return List.of();
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
