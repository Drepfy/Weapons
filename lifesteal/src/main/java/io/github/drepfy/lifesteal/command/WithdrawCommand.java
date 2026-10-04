package io.github.drepfy.lifesteal.command;

import io.github.drepfy.lifesteal.config.Settings;
import io.github.drepfy.lifesteal.heart.HeartService;
import io.github.drepfy.lifesteal.util.Text;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * {@code /withdraw <amount>}: turns hearts into Heart items. At most {@code max-per-command}
 * at once, and the player always keeps the minimum. {@code /withdraw all} takes as many as allowed.
 */
public final class WithdrawCommand implements CommandExecutor, TabCompleter {

    public static final String PERMISSION = "lifesteal.withdraw";

    private final Supplier<Settings> settings;
    private final HeartService hearts;
    private final Consumer<String> log;

    public WithdrawCommand(Supplier<Settings> settings, HeartService hearts, Consumer<String> log) {
        this.settings = settings;
        this.hearts = hearts;
        this.log = log;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Settings config = settings.get();
        if (!sender.hasPermission(PERMISSION)) {
            send(sender, config.messages().get("no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            send(sender, config.messages().get("players-only"));
            return true;
        }
        if (!config.withdraw().enabled()) {
            send(player, config.messages().get("withdraw-disabled"));
            return true;
        }
        if (args.length != 1) {
            send(player, config.messages().get("withdraw-usage"));
            return true;
        }
        int maxPerCommand = config.withdraw().maxPerCommand();
        int current = hearts.hearts(player);
        int available = current - config.minHearts();
        int amount;
        if (args[0].equalsIgnoreCase("all") || args[0].equalsIgnoreCase("max")) {
            amount = Math.min(available, maxPerCommand);
        } else {
            Integer parsed = parse(args[0]);
            if (parsed == null || parsed < 1) {
                send(player, config.messages().get("withdraw-invalid"), "input", args[0], "max", maxPerCommand);
                return true;
            }
            if (parsed > maxPerCommand) {
                send(player, config.messages().get("withdraw-too-many"), "max", maxPerCommand);
                return true;
            }
            amount = parsed;
        }
        if (available <= 0) {
            send(player, config.messages().get("withdraw-none"), "min", config.minHearts());
            return true;
        }
        if (amount > available) {
            send(player, config.messages().get("withdraw-too-low"), "min", config.minHearts(), "available", available);
            return true;
        }
        int after = hearts.set(player, current - amount, false);
        int dropped = hearts.give(player, amount);
        send(player, config.messages().get("withdraw-success"), "count", amount, "s", Text.plural(amount),
                "hearts", after);
        if (dropped > 0) {
            send(player, config.messages().get("inventory-full"), "count", dropped, "s", Text.plural(dropped));
        }
        log.accept(player.getName() + " withdrew " + amount + " heart(s): " + current + " -> " + after);
        return true;
    }

    /** A whole number 0 or more ({@code null} for text, decimals or negative numbers; huge ones become MAX_VALUE). */
    static Integer parse(String text) {
        String trimmed = text.trim();
        if (!trimmed.matches("\\d+")) {
            return null;
        }
        return trimmed.length() > 9 ? Integer.MAX_VALUE : Integer.parseInt(trimmed);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1 || !(sender instanceof Player player) || !sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        Settings config = settings.get();
        int available = Math.min(hearts.hearts(player) - config.minHearts(), config.withdraw().maxPerCommand());
        List<String> options = new ArrayList<>();
        for (int i = 1; i <= available; i++) {
            if (String.valueOf(i).startsWith(args[0])) {
                options.add(String.valueOf(i));
            }
        }
        if (available > 0 && "all".startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
            options.add("all");
        }
        return options;
    }

    private void send(CommandSender sender, String template, Object... pairs) {
        sender.sendMessage(Text.color(settings.get().messages().get("prefix")) + Text.format(template, pairs));
    }
}
