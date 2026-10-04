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
        if (sub.equals("zone")) {
            zone(sender, args, label);
            return true;
        }
        if (args.length < 2 || !List.of("info", "tag", "untag").contains(sub)) {
            send(sender, "&cUsage: /" + label + " [info|tag|untag <player> | zone | reload]");
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

    /**
     * {@code /combat zone pos1|pos2}, {@code create <name> [radius]}, {@code delete <name>}, {@code list}.
     * Zones go from bedrock to the sky.
     */
    private void zone(CommandSender sender, String[] args, String label) {
        String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
        ZoneStore zones = plugin.zones();
        switch (action) {
            case "list" -> {
                List<SafeZone> all = zones.all();
                send(sender, all.isEmpty() ? "&7There are no safe zones. Make one with &f/" + label
                        + " zone create <name> <radius>" : "&7Safe zones (" + all.size() + "):");
                for (SafeZone zone : all) {
                    sender.sendMessage(Text.color("  &f") + zone.describe());
                }
            }
            case "pos1", "pos2" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "&cOnly players can pick corners.");
                    return;
                }
                org.bukkit.Location[] corners = plugin.selection(player);
                org.bukkit.Location here = player.getLocation().getBlock().getLocation();
                corners[action.equals("pos1") ? 0 : 1] = here;
                send(sender, "&7Corner " + action.charAt(3) + " set to &f" + here.getBlockX() + " " + here.getBlockZ()
                        + "&7.");
            }
            case "create" -> {
                if (args.length < 3 || args.length > 4 || !args[2].matches("[A-Za-z0-9_-]{1,32}")) {
                    send(sender, "&cUsage: /" + label + " zone create <name> [radius]  &7(name: letters, numbers, - and _)");
                    return;
                }
                if (!(sender instanceof Player player)) {
                    send(sender, "&cOnly players can create zones (they are made where you stand).");
                    return;
                }
                SafeZone zone;
                if (args.length == 4) {
                    int radius;
                    try {
                        radius = Integer.parseInt(args[3]);
                    } catch (NumberFormatException e) {
                        radius = -1;
                    }
                    if (radius < 1 || radius > 100_000) {
                        send(sender, "&c'" + args[3] + "' is not a radius (a whole number of blocks, e.g. 50).");
                        return;
                    }
                    org.bukkit.Location here = player.getLocation();
                    zone = SafeZone.of(args[2], player.getWorld().getName(), here.getBlockX() - radius,
                            here.getBlockZ() - radius, here.getBlockX() + radius, here.getBlockZ() + radius);
                } else {
                    org.bukkit.Location[] corners = plugin.selection(player);
                    if (corners[0] == null || corners[1] == null) {
                        send(sender, "&cStand on one corner and use &f/" + label + " zone pos1&c, then on the opposite "
                                + "corner &f/" + label + " zone pos2&c. Or give a radius: &f/" + label
                                + " zone create " + args[2] + " 50");
                        return;
                    }
                    if (!corners[0].getWorld().equals(corners[1].getWorld())) {
                        send(sender, "&cBoth corners must be in the same world.");
                        return;
                    }
                    zone = SafeZone.of(args[2], corners[0].getWorld().getName(), corners[0].getBlockX(),
                            corners[0].getBlockZ(), corners[1].getBlockX(), corners[1].getBlockZ());
                }
                boolean replaced = zones.get(zone.name()) != null;
                zones.put(zone);
                send(sender, "&aSafe zone " + (replaced ? "updated" : "created") + ": &f" + zone.describe()
                        + "&a. Players in combat cannot enter it.");
            }
            case "delete" -> {
                if (args.length != 3) {
                    send(sender, "&cUsage: /" + label + " zone delete <name>");
                } else if (zones.remove(args[2])) {
                    send(sender, "&7Safe zone &f" + args[2] + " &7deleted.");
                } else {
                    send(sender, "&cThere is no safe zone called &f" + args[2] + "&c.");
                }
            }
            default -> {
                send(sender, "&7Safe zones: players in combat cannot enter them.");
                sender.sendMessage(Text.color("  &f/" + label + " zone create <name> <radius> &8- &7a square around you"));
                sender.sendMessage(Text.color("  &f/" + label + " zone pos1&7, &f/" + label + " zone pos2&7, then &f/"
                        + label + " zone create <name> &8- &7between two corners"));
                sender.sendMessage(Text.color("  &f/" + label + " zone list&7, &f/" + label + " zone delete <name>"));
            }
        }
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
            options.addAll(List.of("info", "tag", "untag", "zone", "reload"));
        } else if (args[0].equalsIgnoreCase("zone")) {
            if (args.length == 2) {
                options.addAll(List.of("create", "pos1", "pos2", "delete", "list"));
            } else if (args.length == 3 && args[1].equalsIgnoreCase("delete")) {
                for (SafeZone zone : plugin.zones().all()) {
                    options.add(zone.name());
                }
            } else if (args.length == 3 && args[1].equalsIgnoreCase("create")) {
                options.add("spawn");
            } else if (args.length == 4 && args[1].equalsIgnoreCase("create")) {
                options.addAll(List.of("25", "50", "100"));
            }
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
