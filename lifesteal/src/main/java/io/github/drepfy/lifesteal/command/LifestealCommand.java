package io.github.drepfy.lifesteal.command;

import io.github.drepfy.lifesteal.LifestealPlugin;
import io.github.drepfy.lifesteal.config.Settings;
import io.github.drepfy.lifesteal.data.LifestealStore;
import io.github.drepfy.lifesteal.heart.HeartService;
import io.github.drepfy.lifesteal.util.Durations;
import io.github.drepfy.lifesteal.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Staff commands:
 * <pre>
 * /lifesteal reload
 * /lifesteal sethearts|addhearts|removehearts &lt;player&gt; &lt;amount&gt;
 * /lifesteal giveheart &lt;player&gt; [amount]
 * /lifesteal resetcooldowns &lt;player&gt;
 * /lifesteal info &lt;player&gt;
 * /lifesteal alts link|unlink|allow|disallow &lt;player&gt; &lt;player&gt;
 * </pre>
 */
public final class LifestealCommand implements CommandExecutor, TabCompleter {

    private record Sub(String name, String permission, String usage, String description) {
    }

    private static final List<Sub> SUBS = List.of(
            new Sub("reload", "lifesteal.admin.reload", "reload", "Reload config.yml"),
            new Sub("sethearts", "lifesteal.admin.hearts", "sethearts <player> <amount>", "Set a player's hearts"),
            new Sub("addhearts", "lifesteal.admin.hearts", "addhearts <player> <amount>", "Give a player hearts"),
            new Sub("removehearts", "lifesteal.admin.hearts", "removehearts <player> <amount>", "Take hearts away"),
            new Sub("giveheart", "lifesteal.admin.give", "giveheart <player> [amount]", "Give Heart items"),
            new Sub("resetcooldowns", "lifesteal.admin.cooldowns", "resetcooldowns <player>",
                    "Clear a player's heart-steal cooldowns"),
            new Sub("info", "lifesteal.admin.alts", "info <player>", "Hearts, linked accounts and shared IPs"),
            new Sub("alts", "lifesteal.admin.alts", "alts <link|unlink|allow|disallow> <player> <player>",
                    "Mark accounts as the same person (link) or as different people (allow)"));

    private final LifestealPlugin plugin;

    public LifestealCommand(LifestealPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        Sub sub = null;
        for (Sub candidate : SUBS) {
            if (candidate.name().equals(name)) {
                sub = candidate;
            }
        }
        if (sub == null) {
            help(sender, label);
            return true;
        }
        if (!sender.hasPermission(sub.permission())) {
            send(sender, message("no-permission"));
            return true;
        }
        switch (name) {
            case "reload" -> reload(sender);
            case "sethearts", "addhearts", "removehearts" -> changeHearts(sender, name, args, label, sub);
            case "giveheart" -> giveHeart(sender, args, label, sub);
            case "resetcooldowns" -> resetCooldowns(sender, args, label, sub);
            case "info" -> info(sender, args, label, sub);
            default -> alts(sender, args, label, sub);
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        sender.sendMessage(Text.color("&c&lLifesteal &7" + plugin.getDescription().getVersion()));
        sender.sendMessage(Text.color("  &f/withdraw <amount> &8- &7Turn hearts into Heart items"));
        sender.sendMessage(Text.color("  &f/hearts [player|top] &8- &7Show hearts"));
        for (Sub sub : SUBS) {
            if (sender.hasPermission(sub.permission())) {
                sender.sendMessage(Text.color("  &f/" + label + " " + sub.usage() + " &8- &7" + sub.description()));
            }
        }
    }

    private void reload(CommandSender sender) {
        try {
            List<String> warnings = plugin.reload();
            send(sender, message("reloaded"), "warnings", warnings.size());
            for (String warning : warnings) {
                sender.sendMessage(Text.color("&e- ") + warning);
            }
        } catch (RuntimeException e) {
            send(sender, message("reload-failed"), "error", String.valueOf(e.getMessage()));
        }
    }

    private void changeHearts(CommandSender sender, String name, String[] args, String label, Sub sub) {
        if (args.length != 3) {
            usage(sender, label, sub);
            return;
        }
        Target target = Target.find(plugin.store(), args[1]);
        if (target == null) {
            send(sender, message("player-not-found"), "player", args[1]);
            return;
        }
        Integer amount = WithdrawCommand.parse(args[2]);
        if (amount == null || (amount == 0 && !name.equals("sethearts"))) {
            send(sender, message("invalid-number"), "input", args[2]);
            return;
        }
        HeartService hearts = plugin.hearts();
        int current = target.online() != null ? hearts.hearts(target.online()) : hearts.hearts(target.uuid());
        long wanted = switch (name) {
            case "addhearts" -> (long) current + amount;
            case "removehearts" -> (long) current - amount;
            default -> amount;
        };
        int value = (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, wanted));
        int after = hearts.set(target.uuid(), target.name(), target.online(), value);
        send(sender, message("hearts-set"), "player", target.name(), "hearts", after);
        if (target.online() != null && !target.online().equals(sender)) {
            send(target.online(), message("hearts-changed-notify"), "hearts", after);
        }
        plugin.log(sender.getName() + " used " + name + " " + amount + " on " + target.name() + ": " + current + " -> "
                + after);
    }

    private void giveHeart(CommandSender sender, String[] args, String label, Sub sub) {
        if (args.length < 2 || args.length > 3) {
            usage(sender, label, sub);
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            send(sender, message("player-not-found"), "player", args[1]);
            return;
        }
        Integer amount = args.length == 3 ? WithdrawCommand.parse(args[2]) : Integer.valueOf(1);
        if (amount == null || amount < 1 || amount > 2304) {
            send(sender, message("invalid-number"), "input", args.length == 3 ? args[2] : "");
            return;
        }
        int dropped = plugin.hearts().give(target, amount);
        send(sender, message("given"), "count", amount, "s", Text.plural(amount), "player", target.getName());
        if (dropped > 0) {
            send(target, message("inventory-full"), "count", dropped, "s", Text.plural(dropped));
        }
        plugin.log(sender.getName() + " gave " + amount + " Heart item(s) to " + target.getName());
    }

    private void resetCooldowns(CommandSender sender, String[] args, String label, Sub sub) {
        if (args.length != 2) {
            usage(sender, label, sub);
            return;
        }
        Target target = Target.find(plugin.store(), args[1]);
        if (target == null) {
            send(sender, message("player-not-found"), "player", args[1]);
            return;
        }
        int removed = plugin.store().clearCooldowns(target.uuid());
        send(sender, "&7Cleared &c" + removed + " &7cooldown" + Text.plural(removed) + " of &f{player}&7.",
                "player", target.name());
    }

    private void info(CommandSender sender, String[] args, String label, Sub sub) {
        if (args.length != 2) {
            usage(sender, label, sub);
            return;
        }
        LifestealStore store = plugin.store();
        Target target = Target.find(store, args[1]);
        if (target == null) {
            send(sender, message("player-not-found"), "player", args[1]);
            return;
        }
        int count = target.online() != null ? plugin.hearts().hearts(target.online()) : plugin.hearts().hearts(target.uuid());
        sender.sendMessage(Text.format("&c&l{player} &7(" + (target.online() != null ? "online" : "offline") + ")",
                "player", target.name()));
        sender.sendMessage(Text.color("  &7Hearts: &c" + count));
        long now = plugin.now();
        List<String> cooldowns = new ArrayList<>();
        for (UUID other : store.players().keySet()) {
            long until = store.cooldownUntil(target.uuid(), other);
            if (until > now) {
                cooldowns.add(nameOf(other) + " (" + Durations.format(until - now) + ")");
            }
        }
        sender.sendMessage(Text.format("  &7Cannot steal from again yet: &f{list}", "list",
                cooldowns.isEmpty() ? "nobody" : String.join(", ", cooldowns)));
        sender.sendMessage(Text.format("  &7Linked by staff: &f{list}", "list", names(store.linksOf(target.uuid()))));
        sender.sendMessage(Text.format("  &7Allowed (not alts): &f{list}", "list", names(store.allowedOf(target.uuid()))));
        sender.sendMessage(Text.format("  &7Same IP address: &f{list}", "list",
                names(new ArrayList<>(plugin.alts().sharingIp(target.uuid())))));
    }

    private void alts(CommandSender sender, String[] args, String label, Sub sub) {
        if (args.length != 4) {
            usage(sender, label, sub);
            return;
        }
        String action = args[1].toLowerCase(Locale.ROOT);
        if (!Set.of("link", "unlink", "allow", "disallow").contains(action)) {
            usage(sender, label, sub);
            return;
        }
        LifestealStore store = plugin.store();
        Target first = Target.find(store, args[2]);
        Target second = Target.find(store, args[3]);
        if (first == null || second == null) {
            send(sender, message("player-not-found"), "player", first == null ? args[2] : args[3]);
            return;
        }
        if (first.uuid().equals(second.uuid())) {
            send(sender, "&cPick two different players.");
            return;
        }
        boolean changed = switch (action) {
            case "link" -> store.setLinked(first.uuid(), second.uuid(), true);
            case "unlink" -> store.setLinked(first.uuid(), second.uuid(), false);
            case "allow" -> store.setAllowed(first.uuid(), second.uuid(), true);
            default -> store.setAllowed(first.uuid(), second.uuid(), false);
        };
        String result = switch (action) {
            case "link" -> "are now treated as the same person: kills between them never move hearts.";
            case "unlink" -> "are no longer linked.";
            case "allow" -> "are now treated as different people even if they share an IP address.";
            default -> "are no longer allowed to share an IP address.";
        };
        send(sender, changed ? "&f{a} &7and &f{b} &7" + result : "&7Nothing changed for &f{a} &7and &f{b}&7.",
                "a", first.name(), "b", second.name());
        if (changed) {
            plugin.log(sender.getName() + " alts " + action + " " + first.name() + " " + second.name());
        }
    }

    private String names(List<UUID> uuids) {
        if (uuids.isEmpty()) {
            return "nobody";
        }
        List<String> names = new ArrayList<>();
        for (UUID uuid : uuids) {
            names.add(nameOf(uuid));
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return String.join(", ", names);
    }

    private String nameOf(UUID uuid) {
        LifestealStore.Entry entry = plugin.store().entry(uuid);
        return entry != null && entry.name() != null ? entry.name() : uuid.toString();
    }

    private void usage(CommandSender sender, String label, Sub sub) {
        send(sender, "&cUsage: /" + label + " " + sub.usage());
    }

    private String message(String key) {
        return plugin.settings().messages().get(key);
    }

    private void send(CommandSender sender, String template, Object... pairs) {
        sender.sendMessage(Text.color(message("prefix")) + Text.format(template, pairs));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = new ArrayList<>();
        if (args.length == 1) {
            for (Sub sub : SUBS) {
                if (sender.hasPermission(sub.permission())) {
                    options.add(sub.name());
                }
            }
        } else {
            String name = args[0].toLowerCase(Locale.ROOT);
            boolean altsAction = name.equals("alts") && args.length == 2;
            if (altsAction) {
                options.addAll(List.of("link", "unlink", "allow", "disallow"));
            } else if ((name.equals("alts") && (args.length == 3 || args.length == 4))
                    || (!name.equals("alts") && !name.equals("reload") && args.length == 2)) {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    options.add(player.getName());
                }
            } else if (args.length == 3 && List.of("sethearts", "addhearts", "removehearts", "giveheart").contains(name)) {
                options.addAll(List.of("1", "5", "10"));
            }
        }
        String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }
}
