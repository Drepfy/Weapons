package io.github.drepfy.combat;

import io.github.drepfy.combat.util.Durations;
import io.github.drepfy.combat.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /combat}: your combat time and pearl cooldown. Staff ({@code combat.admin}):
 * {@code /combat info|tag|untag <player>}, {@code /combat reload}.
 */
final class CombatCommand implements CommandExecutor, TabCompleter {

    private static final String ADMIN = "combat.admin";

    private final CombatPlugin plugin;

    CombatCommand(CombatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                send(sender, "&cUsage: /" + label + " info <player>");
                return true;
            }
            status(sender, player);
            return true;
        }
        if (!sender.hasPermission(ADMIN)) {
            send(sender, plugin.message("no-permission"));
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            try {
                List<String> warnings = plugin.reload();
                send(sender, Text.format(plugin.message("reloaded"), "warnings", warnings.size()));
                warnings.forEach(warning -> sender.sendMessage(Text.color("&e- ") + warning));
            } catch (RuntimeException e) {
                send(sender, "&cReload failed, the previous settings are kept: &f" + e.getMessage());
            }
            return true;
        }
        if (args.length < 2 || !List.of("info", "tag", "untag").contains(sub)) {
            send(sender, "&cUsage: /" + label + " [info|tag|untag <player> | reload]");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, Text.format(plugin.message("player-not-found"), "player", args[1]));
            return true;
        }
        switch (sub) {
            case "info" -> {
                status(sender, target);
                Map<UUID, Long> opponents = plugin.tracker().opponents(target.getUniqueId(), plugin.now());
                List<String> names = new ArrayList<>();
                for (Map.Entry<UUID, Long> opponent : opponents.entrySet()) {
                    String name = opponent.getKey().equals(CombatTracker.STAFF) ? "staff"
                            : String.valueOf(Bukkit.getOfflinePlayer(opponent.getKey()).getName());
                    names.add(name + " (" + CombatPlugin.seconds(opponent.getValue()) + "s)");
                }
                sender.sendMessage(Text.format("&7In combat with: &f{list}", "list",
                        names.isEmpty() ? "nobody" : String.join(", ", names)));
            }
            case "tag" -> {
                long duration = plugin.settings().combatMs();
                if (args.length > 2) {
                    Long parsed = Durations.parse(args[2]);
                    if (parsed == null || parsed <= 0) {
                        send(sender, "&c'" + args[2] + "' is not a time (e.g. 30s, 2m).");
                        return true;
                    }
                    duration = parsed;
                }
                plugin.tracker().tag(target.getUniqueId(), CombatTracker.STAFF, plugin.now(), duration);
                send(sender, Text.format("&f{player} &7is in combat for &c{seconds}s&7.", "player", target.getName(),
                        "seconds", CombatPlugin.seconds(plugin.combatRemaining(target))));
            }
            default -> {
                plugin.endCombat(target, true);
                send(sender, Text.format("&f{player} &7is no longer in combat.", "player", target.getName()));
            }
        }
        return true;
    }

    private void status(CommandSender sender, Player player) {
        long combat = plugin.combatRemaining(player);
        send(sender, combat > 0 ? Text.format(plugin.message("status-combat"), "seconds", CombatPlugin.seconds(combat))
                : Text.color(plugin.message("status-no-combat")));
        long pearl = plugin.pearlCooldown(player);
        send(sender, pearl > 0 ? Text.format(plugin.message("status-pearl"), "seconds", CombatPlugin.seconds(pearl))
                : Text.color(plugin.message("status-pearl-ready")));
    }

    private void send(CommandSender sender, String text) {
        sender.sendMessage(Text.color(plugin.message("prefix")) + Text.color(text));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (!sender.hasPermission(ADMIN)) {
            return options;
        }
        if (args.length == 1) {
            options.addAll(List.of("info", "tag", "untag", "reload"));
        } else if (args.length == 2 && !args[0].equalsIgnoreCase("reload")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                options.add(player.getName());
            }
        }
        String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }
}
