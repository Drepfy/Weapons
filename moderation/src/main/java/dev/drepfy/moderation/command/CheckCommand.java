package dev.drepfy.moderation.command;

import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.Permissions;
import dev.drepfy.moderation.config.Messages;
import dev.drepfy.moderation.model.PlayerRecord;
import dev.drepfy.moderation.model.Punishment;
import dev.drepfy.moderation.model.PunishmentType;
import dev.drepfy.moderation.service.Placeholders;
import dev.drepfy.moderation.service.TargetResolver.Target;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;

import java.util.List;
import java.util.Optional;

/**
 * {@code /check <player>}: what a player is currently punished with, read fresh from the database.
 */
public final class CheckCommand extends BaseCommand {

    private record Status(Optional<PlayerRecord> record, List<Punishment> inForce, int total) {
    }

    public CheckCommand(ModerationPlugin plugin) {
        super(plugin, "<player>");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!require(sender, Permissions.CHECK)) {
            return true;
        }
        if (args.length != 1) {
            sendUsage(sender, label);
            return true;
        }
        plugin.whenComplete(plugin.targets().resolve(args[0]), (found, error) -> {
            if (error != null) {
                plugin.service().fail(sender, "looking up " + args[0], error);
            } else if (found.isEmpty()) {
                messages().send(sender, "general.player-not-found", Placeholders.text("input", args[0]));
            } else {
                show(sender, found.get());
            }
        });
        return true;
    }

    private void show(CommandSender sender, Target target) {
        long now = System.currentTimeMillis();
        plugin.whenComplete(plugin.storage().submit(store -> new Status(
                store.findPlayer(target.uniqueId()),
                store.findInForce(target.uniqueId(), now),
                store.countHistory(target.uniqueId()))), (status, error) -> {
            if (error != null) {
                plugin.service().fail(sender, "checking " + target.name(), error);
                return;
            }
            Messages messages = messages();
            long warnings = status.inForce().stream().filter(p -> p.type() == PunishmentType.WARN).count();
            Component lastSeen;
            if (Bukkit.getPlayer(target.uniqueId()) != null) {
                lastSeen = messages.render("check.online");
            } else if (status.record().isPresent()) {
                lastSeen = Component.text(Placeholders.date(status.record().get().lastSeen(), plugin.settings().dateFormat()));
            } else {
                lastSeen = messages.render("check.unknown");
            }
            messages.send(sender, "check.lines",
                    Placeholders.text("player", target.name()),
                    Placeholders.text("uuid", target.uniqueId()),
                    Placeholder.component("ban", state(status, PunishmentType.BAN, now)),
                    Placeholder.component("mute", state(status, PunishmentType.MUTE, now)),
                    Placeholder.component("voicemute", state(status, PunishmentType.VOICE_MUTE, now)),
                    Placeholders.text("warnings", warnings),
                    Placeholders.text("total", status.total()),
                    Placeholder.component("last_seen", lastSeen));
        });
    }

    private Component state(Status status, PunishmentType type, long now) {
        return Punishment.strongest(status.inForce(), type, now)
                .map(punishment -> messages().render("check.active", plugin.service().placeholders(punishment)))
                .orElseGet(() -> messages().render("check.inactive"));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        return args.length == 1 && sender.hasPermission(Permissions.CHECK) ? playerNames(sender, args[0]) : List.of();
    }
}
