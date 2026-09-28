package dev.drepfy.moderation.command;

import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.Permissions;
import dev.drepfy.moderation.config.Messages;
import dev.drepfy.moderation.model.Punishment;
import dev.drepfy.moderation.model.PunishmentType;
import dev.drepfy.moderation.service.Placeholders;
import dev.drepfy.moderation.service.TargetResolver.Target;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /history [player] [page]} and {@code /warnings [player]}. Players can view their own record
 * with {@code moderation.history.self}; viewing others needs {@code moderation.history}.
 */
public final class HistoryCommand extends BaseCommand {

    public enum Mode { HISTORY, WARNINGS }

    private record Page(int number, int pages, int total, List<Punishment> entries) {
    }

    private final Mode mode;

    public HistoryCommand(ModerationPlugin plugin, Mode mode) {
        super(plugin, mode == Mode.HISTORY ? "[player] [page]" : "[player]");
        this.mode = mode;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Player self = sender instanceof Player player ? player : null;
        if (args.length == 0 && self == null) {
            sendUsage(sender, label);
            return true;
        }
        String input = args.length == 0 ? self.getName() : args[0];
        boolean viewingSelf = self != null
                && (input.equalsIgnoreCase(self.getName()) || input.equalsIgnoreCase(self.getUniqueId().toString()));
        boolean allowed = sender.hasPermission(Permissions.HISTORY)
                || (viewingSelf && sender.hasPermission(Permissions.HISTORY_SELF));
        if (!allowed) {
            messages().send(sender, "general.no-permission");
            return true;
        }

        int page = 1;
        if (mode == Mode.HISTORY && args.length >= 2) {
            try {
                page = Integer.parseInt(args[1]);
            } catch (NumberFormatException e) {
                page = 0;
            }
            if (page < 1) {
                sendUsage(sender, label);
                return true;
            }
        }

        CompletableFuture<Optional<Target>> target = viewingSelf
                ? CompletableFuture.completedFuture(Optional.of(new Target(self.getUniqueId(), self.getName(), false)))
                : plugin.targets().resolve(input);
        int requestedPage = page;
        plugin.whenComplete(target, (found, error) -> {
            if (error != null) {
                plugin.service().fail(sender, "looking up " + input, error);
            } else if (found.isEmpty()) {
                messages().send(sender, "general.player-not-found", Placeholders.text("input", input));
            } else if (mode == Mode.HISTORY) {
                showHistory(sender, found.get(), requestedPage);
            } else {
                showWarnings(sender, found.get());
            }
        });
        return true;
    }

    private void showHistory(CommandSender sender, Target target, int requestedPage) {
        int size = plugin.settings().historyPageSize();
        plugin.whenComplete(plugin.storage().submit(store -> {
            int total = store.countHistory(target.uniqueId());
            int pages = Math.max(1, (total + size - 1) / size);
            int number = Math.min(requestedPage, pages);
            return new Page(number, pages, total, store.history(target.uniqueId(), (number - 1) * size, size));
        }), (page, error) -> {
            if (error != null) {
                plugin.service().fail(sender, "loading the history of " + target.name(), error);
                return;
            }
            Messages messages = messages();
            TagResolver player = Placeholders.text("player", target.name());
            if (page.total() == 0) {
                messages.send(sender, "history.empty", player);
                return;
            }
            messages.send(sender, "history.header", player,
                    Placeholders.text("page", page.number()),
                    Placeholders.text("pages", page.pages()),
                    Placeholders.text("total", page.total()));
            for (Punishment punishment : page.entries()) {
                messages.send(sender, "history.entry", plugin.service().placeholders(punishment));
            }
            if (page.number() < page.pages()) {
                messages.send(sender, "history.next-page", player, Placeholders.text("next", page.number() + 1));
            }
        });
    }

    private void showWarnings(CommandSender sender, Target target) {
        long now = System.currentTimeMillis();
        plugin.whenComplete(plugin.storage().submit(store -> store.findInForce(target.uniqueId(), now).stream()
                .filter(p -> p.type() == PunishmentType.WARN)
                .toList()), (warnings, error) -> {
            if (error != null) {
                plugin.service().fail(sender, "loading the warnings of " + target.name(), error);
                return;
            }
            Messages messages = messages();
            TagResolver player = Placeholders.text("player", target.name());
            if (warnings.isEmpty()) {
                messages.send(sender, "warnings.empty", player);
                return;
            }
            messages.send(sender, "warnings.header", player, Placeholders.text("count", warnings.size()));
            for (Punishment warning : warnings) {
                messages.send(sender, "warnings.entry", plugin.service().placeholders(warning));
            }
        });
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && sender.hasPermission(Permissions.HISTORY)) {
            return playerNames(sender, args[0]);
        }
        return List.of();
    }
}
