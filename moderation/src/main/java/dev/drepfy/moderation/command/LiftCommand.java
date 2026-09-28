package dev.drepfy.moderation.command;

import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.Permissions;
import dev.drepfy.moderation.model.PunishmentType;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.Arrays;
import java.util.List;

/**
 * {@code /unban}, {@code /unmute} and {@code /unvoicemute}.
 */
public final class LiftCommand extends BaseCommand {

    private final PunishmentType type;

    public LiftCommand(ModerationPlugin plugin, PunishmentType type) {
        super(plugin, "<player> [reason] [-s]");
        this.type = type;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!require(sender, Permissions.lift(type))) {
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender, label);
            return true;
        }
        PunishmentArguments parsed = PunishmentArguments.parse(Arrays.asList(args).subList(1, args.length), false);
        if (parsed.silent() && !sender.hasPermission(Permissions.SILENT)) {
            messages().send(sender, "general.silent-no-permission");
            return true;
        }
        String reason = parsed.reason().isEmpty() ? messages().plain("general.default-reason") : parsed.reason();
        plugin.service().lift(sender, type, args[0], reason, parsed.silent());
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission(Permissions.lift(type))) {
            return List.of();
        }
        if (args.length == 1) {
            return playerNames(sender, args[0]);
        }
        return sender.hasPermission(Permissions.SILENT) ? filter(List.of("-s"), args[args.length - 1]) : List.of();
    }
}
