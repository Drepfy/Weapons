package dev.drepfy.moderation.command;

import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.Permissions;
import dev.drepfy.moderation.model.PunishmentType;
import dev.drepfy.moderation.service.Placeholders;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.List;

/**
 * {@code /unwarn <id> [reason] [-s]}: warning IDs are shown by {@code /warnings} and {@code /history}.
 */
public final class UnwarnCommand extends BaseCommand {

    public UnwarnCommand(ModerationPlugin plugin) {
        super(plugin, "<id> [reason] [-s]");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!require(sender, Permissions.lift(PunishmentType.WARN))) {
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        String input = args[0].startsWith("#") ? args[0].substring(1) : args[0];
        long id;
        try {
            id = Long.parseLong(input);
        } catch (NumberFormatException e) {
            id = -1;
        }
        if (id <= 0) {
            messages().send(sender, "general.invalid-id", Placeholders.text("input", args[0]));
            return true;
        }
        PunishmentArguments parsed = PunishmentArguments.parse(Arrays.asList(args).subList(1, args.length), false);
        if (parsed.silent() && !sender.hasPermission(Permissions.SILENT)) {
            messages().send(sender, "general.silent-no-permission");
            return true;
        }
        String reason = parsed.reason().isEmpty() ? messages().plain("general.default-reason") : parsed.reason();
        plugin.service().liftWarning(sender, id, reason, parsed.silent());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 2 && sender.hasPermission(Permissions.SILENT)) {
            return filter(List.of("-s"), args[args.length - 1]);
        }
        return List.of();
    }
}
