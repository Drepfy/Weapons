package io.github.drepfy.lifesteal.command;

import io.github.drepfy.lifesteal.config.Settings;
import io.github.drepfy.lifesteal.data.LifestealStore;
import io.github.drepfy.lifesteal.heart.HeartService;
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
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** {@code /hearts}, {@code /hearts <player>}, {@code /hearts top}. */
public final class HeartsCommand implements CommandExecutor, TabCompleter {

    private static final int TOP = 10;

    private final Supplier<Settings> settings;
    private final LifestealStore store;
    private final HeartService hearts;

    public HeartsCommand(Supplier<Settings> settings, LifestealStore store, HeartService hearts) {
        this.settings = settings;
        this.store = store;
        this.hearts = hearts;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Settings.Messages messages = settings.get().messages();
        if (!sender.hasPermission("lifesteal.hearts")) {
            send(sender, messages.get("no-permission"));
            return true;
        }
        if (args.length == 0) {
            if (sender instanceof Player player) {
                send(player, messages.get("hearts-self"), "hearts", hearts.hearts(player));
            } else {
                send(sender, "&cUsage: /" + label + " <player> | top");
            }
            return true;
        }
        if (args[0].equalsIgnoreCase("top")) {
            List<Map.Entry<UUID, LifestealStore.Entry>> ranking = store.ranking();
            send(sender, messages.get("top-header"));
            int rank = 0;
            for (Map.Entry<UUID, LifestealStore.Entry> entry : ranking.subList(0, Math.min(TOP, ranking.size()))) {
                rank++;
                sender.sendMessage(Text.format(messages.get("top-entry"), "rank", rank,
                        "player", entry.getValue().name(), "hearts", entry.getValue().hearts()));
            }
            return true;
        }
        if (!sender.hasPermission("lifesteal.hearts.others")) {
            send(sender, messages.get("no-permission"));
            return true;
        }
        Target target = Target.find(store, args[0]);
        if (target == null) {
            send(sender, messages.get("player-not-found"), "player", args[0]);
            return true;
        }
        int count = target.online() != null ? hearts.hearts(target.online()) : hearts.hearts(target.uuid());
        send(sender, messages.get("hearts-other"), "player", target.name(), "hearts", count);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        options.add("top");
        if (sender.hasPermission("lifesteal.hearts.others")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                options.add(player.getName());
            }
        }
        String prefix = args[0].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(prefix));
        return options;
    }

    private void send(CommandSender sender, String template, Object... pairs) {
        sender.sendMessage(Text.color(settings.get().messages().get("prefix")) + Text.format(template, pairs));
    }
}
