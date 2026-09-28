package dev.drepfy.moderation.command;

import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.config.Messages;
import dev.drepfy.moderation.service.Placeholders;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

abstract class BaseCommand implements TabExecutor {

    protected final ModerationPlugin plugin;
    private final String usage;

    BaseCommand(ModerationPlugin plugin, String usage) {
        this.plugin = plugin;
        this.usage = usage;
    }

    protected Messages messages() {
        return plugin.messages();
    }

    protected boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        messages().send(sender, "general.no-permission");
        return false;
    }

    protected void sendUsage(CommandSender sender, String label) {
        messages().send(sender, "general.usage", Placeholders.text("usage", "/" + label + " " + usage));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return List.of();
    }

    /** Names of online players the sender can see. */
    protected static List<String> playerNames(CommandSender sender, String prefix) {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!(sender instanceof Player viewer) || viewer.canSee(player)) {
                names.add(player.getName());
            }
        }
        return filter(names, prefix);
    }

    protected static List<String> filter(Collection<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
