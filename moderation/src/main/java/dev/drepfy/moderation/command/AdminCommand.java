package dev.drepfy.moderation.command;

import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.Permissions;
import dev.drepfy.moderation.service.Placeholders;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Locale;

/**
 * {@code /moderation reload|info}.
 */
public final class AdminCommand extends BaseCommand {

    public AdminCommand(ModerationPlugin plugin) {
        super(plugin, "<reload|info>");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!require(sender, Permissions.ADMIN)) {
            return true;
        }
        String action = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : "";
        switch (action) {
            case "reload" -> plugin.reload(sender);
            case "info" -> messages().send(sender, "admin.info",
                    Placeholders.text("version", plugin.getPluginMeta().getVersion()),
                    Placeholders.text("storage", plugin.storage().describe()),
                    Placeholders.text("voice", plugin.voiceChatStatus()),
                    Placeholders.text("cached", plugin.cache().size()));
            default -> sendUsage(sender, label);
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 && sender.hasPermission(Permissions.ADMIN)
                ? filter(List.of("reload", "info"), args[0])
                : List.of();
    }
}
