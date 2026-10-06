package io.github.drepfy.legendary.command;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.config.Settings;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.registry.WeaponRecord;
import io.github.drepfy.legendary.util.Durations;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code /legendary give|remove|list|inspect|reload}. Without arguments it explains the
 * controls (anyone may use that).
 */
public final class LegendaryCommand implements TabExecutor {

    private final LegendaryPlugin plugin;

    public LegendaryCommand(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> {
                if (allowed(sender, "give")) {
                    give(sender, args, label);
                }
            }
            case "remove", "take" -> {
                if (allowed(sender, "remove")) {
                    remove(sender, args, label);
                }
            }
            case "list" -> {
                if (allowed(sender, "list")) {
                    list(sender);
                }
            }
            case "inspect" -> {
                if (allowed(sender, "inspect")) {
                    inspect(sender, args, label);
                }
            }
            case "reload" -> {
                if (allowed(sender, "reload")) {
                    List<String> warnings = plugin.reload();
                    plugin.send(sender, "reloaded", "warnings", warnings.size());
                    for (String warning : warnings) {
                        sender.sendMessage(Text.color("  &e" + warning));
                    }
                }
            }
            default -> help(sender, label);
        }
        return true;
    }

    private boolean allowed(CommandSender sender, String what) {
        if (sender.hasPermission("legendary." + what)) {
            return true;
        }
        plugin.send(sender, "no-permission");
        return false;
    }

    private void help(CommandSender sender, String label) {
        line(sender, "&6&lLegendary Weapons");
        Settings.Controls controls = plugin.settings().controls();
        line(sender, "&7" + controls.key() + " &8» &fthe weapon's first ability");
        line(sender, "&7" + controls.sneakKey() + " &8» &fits second ability");
        if (controls.offhand()) {
            line(sender, "&8(F is your swap-offhand key; the weapon stays in your hand)");
        }
        line(sender, "&7Cooldowns show as bars at the top of your screen while you hold one.");
        line(sender, "&7They cannot go in containers, bundles or item frames, and drop when you die.");
        if (sender.hasPermission("legendary.give")) {
            line(sender, "&f/" + label + " give <player> <weapon> &8- &7give a legendary");
        }
        if (sender.hasPermission("legendary.remove")) {
            line(sender, "&f/" + label + " remove <player|*> <weapon|all> &8- &7take one away (also offline)");
        }
        if (sender.hasPermission("legendary.list")) {
            line(sender, "&f/" + label + " list &8- &7every legendary and where it is");
        }
        if (sender.hasPermission("legendary.inspect")) {
            line(sender, "&f/" + label + " inspect <player> &8- &7a player's legendaries and cooldowns");
        }
        if (sender.hasPermission("legendary.reload")) {
            line(sender, "&f/" + label + " reload &8- &7reload config.yml");
        }
        line(sender, "&7Weapons: &f" + weaponKeys());
    }

    private static void line(CommandSender sender, String text) {
        sender.sendMessage(Text.color(text));
    }

    private static String weaponKeys() {
        List<String> keys = new ArrayList<>();
        for (WeaponType type : WeaponType.values()) {
            keys.add(type.key());
        }
        return String.join(", ", keys);
    }

    // ---- give ---------------------------------------------------------------------------------------------

    private void give(CommandSender sender, String[] args, String label) {
        if (args.length < 3) {
            line(sender, "&cUsage: /" + label + " give <player> <weapon>  &7(" + weaponKeys() + ")");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            plugin.send(sender, "player-not-found", "player", args[1]);
            return;
        }
        WeaponType type = WeaponType.byKey(args[2]);
        if (type == null) {
            plugin.send(sender, "unknown-weapon", "weapon", args[2], "weapons", weaponKeys());
            return;
        }
        String name = plugin.items().displayName(type);
        if (plugin.settings().oneOfEach()) {
            List<WeaponRecord> existing = plugin.registry().existing(type);
            if (!existing.isEmpty()) {
                WeaponRecord record = existing.get(0);
                String holder = record.state() == WeaponRecord.State.HELD ? record.holderName() : "*";
                plugin.send(sender, "give-exists", "weapon", name, "where", plugin.tracker().where(record),
                        "holder", holder, "key", type.key());
                return;
            }
            // A lost one that turns up later must not become a second copy.
            for (WeaponRecord old : new ArrayList<>(plugin.registry().all())) {
                if (old.type() == type && old.state() == WeaponRecord.State.LOST) {
                    plugin.registry().removed(old, "replaced by a new " + type.key());
                }
            }
        }
        WeaponRecord record = plugin.registry().create(type, sender.getName());
        plugin.registry().held(record, target.getUniqueId(), target.getName(), target.getLocation());
        plugin.tracker().give(target, plugin.items().create(type, record.id()));
        plugin.registry().log("GIVE " + type.key() + " #" + WeaponItems.shortId(record.id()) + " to " + target.getName()
                + " by " + sender.getName());
        plugin.send(sender, "given", "weapon", name, "player", target.getName(), "id", WeaponItems.shortId(record.id()));
        if (!sender.equals(target)) {
            plugin.send(target, "received", "weapon", name);
        }
        plugin.fx().soundTo(target, "received");
    }

    // ---- remove -------------------------------------------------------------------------------------------

    private void remove(CommandSender sender, String[] args, String label) {
        if (args.length < 3) {
            line(sender, "&cUsage: /" + label + " remove <player|*> <weapon|all>");
            return;
        }
        Set<WeaponType> types = EnumSet.noneOf(WeaponType.class);
        if (args[2].equalsIgnoreCase("all")) {
            types.addAll(EnumSet.allOf(WeaponType.class));
        } else {
            WeaponType type = WeaponType.byKey(args[2]);
            if (type == null) {
                plugin.send(sender, "unknown-weapon", "weapon", args[2], "weapons", weaponKeys());
                return;
            }
            types.add(type);
        }
        String why = "removed by " + sender.getName();
        String weaponName = types.size() == 1 ? plugin.items().displayName(types.iterator().next()) : "all legendaries";

        if (args[1].equals("*")) {
            int count = 0;
            for (WeaponRecord record : new ArrayList<>(plugin.registry().all())) {
                if (!types.contains(record.type()) || record.state() == WeaponRecord.State.REMOVED) {
                    continue;
                }
                if (record.state() == WeaponRecord.State.HELD && record.holder() != null) {
                    Player holder = Bukkit.getPlayer(record.holder());
                    if (holder != null) {
                        plugin.tracker().takeFrom(holder, tag -> tag.id().equals(record.id()));
                        plugin.send(holder, "revoked", "weapon", plugin.items().displayName(record.type()));
                    }
                }
                if (record.state() == WeaponRecord.State.GROUND && record.entity() != null
                        && Bukkit.getEntity(record.entity()) instanceof Item item) {
                    item.remove();
                }
                plugin.registry().removed(record, why);
                plugin.registry().log("REMOVED " + record.type().key() + " #" + WeaponItems.shortId(record.id()) + " (" + why + ")");
                count++;
            }
            if (count == 0) {
                plugin.send(sender, "removed-none", "player", "Nobody", "weapon", weaponName);
            } else {
                plugin.send(sender, "removed-everywhere", "weapon", weaponName, "count", count);
            }
            return;
        }

        Player online = Bukkit.getPlayerExact(args[1]);
        int count = 0;
        if (online != null) {
            for (WeaponItems.Tag tag : plugin.tracker().takeFrom(online, tag -> types.contains(tag.type()))) {
                WeaponRecord record = plugin.registry().get(tag.id());
                if (record != null && record.state() != WeaponRecord.State.REMOVED) {
                    plugin.registry().removed(record, why);
                }
                plugin.registry().log("REMOVED " + tag.type().key() + " #" + tag.shortId() + " from " + online.getName()
                        + " (" + why + ")");
                plugin.send(sender, "removed", "weapon", plugin.items().displayName(tag.type()), "id", tag.shortId(),
                        "player", online.getName());
                plugin.send(online, "revoked", "weapon", plugin.items().displayName(tag.type()));
                count++;
            }
        }
        // Offline (or not found on them): whatever the registry says they hold disappears when they next join.
        for (WeaponRecord record : new ArrayList<>(plugin.registry().all())) {
            if (types.contains(record.type()) && record.state() == WeaponRecord.State.HELD
                    && record.holderName() != null && record.holderName().equalsIgnoreCase(args[1])) {
                plugin.registry().removed(record, why);
                plugin.registry().log("REMOVED " + record.type().key() + " #" + WeaponItems.shortId(record.id())
                        + " from " + record.holderName() + " (offline; " + why + ")");
                plugin.send(sender, "removed-offline", "weapon", plugin.items().displayName(record.type()),
                        "id", WeaponItems.shortId(record.id()), "player", record.holderName());
                count++;
            }
        }
        if (count == 0) {
            plugin.send(sender, "removed-none", "player", args[1], "weapon", weaponName);
        }
    }

    // ---- list and inspect ---------------------------------------------------------------------------------

    private void list(CommandSender sender) {
        line(sender, "&6&lLegendary weapons");
        for (WeaponType type : WeaponType.values()) {
            List<WeaponRecord> shown = new ArrayList<>();
            for (WeaponRecord record : plugin.registry().all()) {
                if (record.type() == type && record.state() != WeaponRecord.State.REMOVED) {
                    shown.add(record);
                }
            }
            if (shown.isEmpty()) {
                line(sender, "&8- " + plugin.items().displayName(type) + " &7not given out");
                continue;
            }
            for (WeaponRecord record : shown) {
                String colour = switch (record.state()) {
                    case HELD -> "&a";
                    case GROUND -> "&e";
                    default -> "&c";
                };
                line(sender, "&8- " + plugin.items().displayName(type) + " &8#" + WeaponItems.shortId(record.id()) + " "
                        + colour + plugin.tracker().where(record) + " &8(" + ago(record.updated()) + ")");
            }
        }
    }

    private void inspect(CommandSender sender, String[] args, String label) {
        if (args.length < 2) {
            line(sender, "&cUsage: /" + label + " inspect <player>");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        long now = plugin.tick();
        if (target != null) {
            List<WeaponItems.Tag> carried = plugin.tracker().carried(target);
            if (carried.isEmpty()) {
                line(sender, "&7" + target.getName() + " carries no legendary weapons.");
            } else {
                line(sender, "&6" + target.getName() + "'s legendary weapons:");
            }
            for (WeaponItems.Tag tag : carried) {
                WeaponRecord record = plugin.registry().get(tag.id());
                line(sender, "&8- " + plugin.items().displayName(tag.type()) + " &8#" + tag.shortId()
                        + (record == null ? " &cnot registered" : record.state() == WeaponRecord.State.HELD
                        && target.getUniqueId().equals(record.holder()) ? " &aregistered to them"
                        : " &c" + plugin.tracker().where(record)));
                List<String> abilities = new ArrayList<>();
                for (Ability ability : tag.type().actives()) {
                    String name = plugin.settings().ability(ability).name();
                    long active = plugin.abilities().active(target, tag, ability, now);
                    long left = plugin.abilities().cooldown(tag, ability, now);
                    abilities.add("&f" + name + " " + (active > 0 ? "&dactive " + Text.countdown(active)
                            : left > 0 ? "&c" + Text.countdown(left) : "&aready"));
                }
                line(sender, "    " + String.join("&7, ", abilities));
                if (record != null && record.previousHolderName() != null) {
                    line(sender, "    &7Previous holder: &f" + record.previousHolderName());
                }
            }
            return;
        }
        boolean any = false;
        for (WeaponRecord record : plugin.registry().all()) {
            if (record.state() == WeaponRecord.State.HELD && record.holderName() != null
                    && record.holderName().equalsIgnoreCase(args[1])) {
                if (!any) {
                    line(sender, "&6" + record.holderName() + " &7(offline) holds:");
                    any = true;
                }
                line(sender, "&8- " + plugin.items().displayName(record.type()) + " &8#" + WeaponItems.shortId(record.id())
                        + " &7last seen " + ago(record.updated()) + " at " + record.world() + " " + Math.round(record.x())
                        + " " + Math.round(record.y()) + " " + Math.round(record.z()));
            }
        }
        if (!any) {
            plugin.send(sender, "player-not-found", "player", args[1]);
        }
    }

    private String ago(long epochMs) {
        long millis = Math.max(0, plugin.now() - epochMs);
        return millis < 60_000 ? "just now" : Durations.format(millis) + " ago";
    }

    // ---- tab completion -----------------------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : List.of("give", "remove", "list", "inspect", "reload")) {
                if (sender.hasPermission("legendary." + sub)) {
                    options.add(sub);
                }
            }
        } else if (args.length == 2 && List.of("give", "remove", "inspect").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                options.add(player.getName());
            }
            if (args[0].equalsIgnoreCase("remove")) {
                options.add("*");
            }
        } else if (args.length == 3 && List.of("give", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
            for (WeaponType type : WeaponType.values()) {
                options.add(type.key());
            }
            if (args[0].equalsIgnoreCase("remove")) {
                options.add("all");
            }
        }
        String typed = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
