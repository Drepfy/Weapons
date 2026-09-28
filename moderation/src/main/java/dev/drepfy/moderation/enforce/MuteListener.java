package dev.drepfy.moderation.enforce;

import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.cache.PunishmentCache;
import dev.drepfy.moderation.model.Punishment;
import dev.drepfy.moderation.model.PunishmentType;
import dev.drepfy.moderation.service.Placeholders;
import dev.drepfy.moderation.service.PunishmentService;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerEditBookEvent;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Stops muted players from reaching others through chat, private-message style commands, signs and
 * books.
 *
 * <p>Each event is cancelled at {@link EventPriority#LOWEST}, so plugins that respect cancellation
 * (chat formatters, Discord relays, loggers) never see the message. A second handler at
 * {@link EventPriority#HIGHEST} cancels it again if another plugin un-cancelled it in between.
 */
public final class MuteListener implements Listener {

    private static final long CHAT_NOTICE_COOLDOWN_MILLIS = 1000;

    private final ModerationPlugin plugin;
    private final PunishmentCache cache;
    private final PunishmentService service;
    private volatile CommandMatcher commands;

    public MuteListener(ModerationPlugin plugin, PunishmentCache cache, PunishmentService service) {
        this.plugin = plugin;
        this.cache = cache;
        this.service = service;
        reconfigure();
    }

    /** Picks up a changed blocked-commands list after a reload. */
    public void reconfigure() {
        this.commands = new CommandMatcher(plugin.settings().blockedCommands());
    }

    Optional<Punishment> activeMute(Player player) {
        return cache.find(player.getUniqueId(), PunishmentType.MUTE, System.currentTimeMillis());
    }

    // ------------------------------------------------------------------------------------- chat

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Optional<Punishment> mute = activeMute(event.getPlayer());
        if (mute.isPresent()) {
            // If it's already cancelled, the legacy-chat handler blocked it and told the player.
            blockChat(event.getPlayer(), mute.get(), event, PlainTextComponentSerializer.plainText().serialize(event.message()));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChatFinal(AsyncChatEvent event) {
        reassert(event, event.getPlayer(), "chat");
    }

    /**
     * Cancels a chat event from a muted player, notifying and logging only if nothing else had
     * cancelled it yet (so the legacy and modern chat events don't report the same message twice).
     */
    void blockChat(Player player, Punishment mute, Cancellable event, String message) {
        boolean alreadyCancelled = event.isCancelled();
        event.setCancelled(true);
        if (alreadyCancelled) {
            return;
        }
        notifyBlocked(player, "chat", "mute.blocked", service.placeholders(mute));
        logBlocked("BLOCKED-CHAT", player, mute, message);
    }

    // --------------------------------------------------------------------------------- commands

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        Optional<Punishment> mute = activeMute(player);
        if (mute.isEmpty()) {
            return;
        }
        Optional<String> blocked = commands.match(event.getMessage(), MuteListener::commandNames);
        if (blocked.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        notifyBlocked(player, "command", "mute.blocked-command",
                TagResolver.resolver(service.placeholders(mute.get()), Placeholders.text("command", blocked.get())));
        logBlocked("BLOCKED-COMMAND", player, mute.get(), event.getMessage());
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandFinal(PlayerCommandPreprocessEvent event) {
        if (!event.isCancelled() && activeMute(event.getPlayer()).isPresent()
                && commands.match(event.getMessage(), MuteListener::commandNames).isPresent()) {
            reassert(event, event.getPlayer(), "command " + event.getMessage());
        }
    }

    /** The real name and label of the command a typed label runs, so aliases can't dodge the block list. */
    private static Collection<String> commandNames(String label) {
        Command command = Bukkit.getCommandMap().getCommand(label);
        return command == null ? List.of() : List.of(command.getName(), command.getLabel());
    }

    // ---------------------------------------------------------------------------- signs & books

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSign(SignChangeEvent event) {
        if (!plugin.settings().blockSigns() || !hasText(event.lines())) {
            return;
        }
        activeMute(event.getPlayer()).ifPresent(mute -> {
            event.setCancelled(true);
            notifyBlocked(event.getPlayer(), "sign", "mute.blocked-sign", service.placeholders(mute));
            logBlocked("BLOCKED-SIGN", event.getPlayer(), mute, plainLines(event.lines()));
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSignFinal(SignChangeEvent event) {
        if (plugin.settings().blockSigns() && hasText(event.lines())) {
            reassert(event, event.getPlayer(), "sign");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBook(PlayerEditBookEvent event) {
        if (!plugin.settings().blockBooks()) {
            return;
        }
        activeMute(event.getPlayer()).ifPresent(mute -> {
            event.setCancelled(true);
            notifyBlocked(event.getPlayer(), "book", "mute.blocked-book", service.placeholders(mute));
            logBlocked("BLOCKED-BOOK", event.getPlayer(), mute, event.isSigning() ? "signing a book" : "editing a book");
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBookFinal(PlayerEditBookEvent event) {
        if (plugin.settings().blockBooks()) {
            reassert(event, event.getPlayer(), "book");
        }
    }

    // ---------------------------------------------------------------------------------- helpers

    /** Cancels an event again if another plugin un-cancelled it for a muted player. */
    void reassert(Cancellable event, Player player, String what) {
        if (event.isCancelled()) {
            return;
        }
        Optional<Punishment> mute = activeMute(player);
        if (mute.isPresent()) {
            event.setCancelled(true);
            plugin.audit().warn("BYPASS-BLOCKED", "Another plugin un-cancelled " + what + " from muted player "
                    + player.getName() + " (#" + mute.get().id() + "); cancelled it again");
        }
    }

    private void notifyBlocked(Player player, String channel, String key, TagResolver placeholders) {
        if (plugin.cooldowns().tryAcquire(player.getUniqueId(), "mute-" + channel, System.currentTimeMillis(), CHAT_NOTICE_COOLDOWN_MILLIS)) {
            plugin.messages().send(player, key, placeholders);
        }
    }

    private void logBlocked(String category, Player player, Punishment mute, String content) {
        if (plugin.settings().logBlockedAttempts()) {
            plugin.audit().record(category, player.getName() + " (#" + mute.id() + "): " + content);
        }
    }

    private static boolean hasText(List<Component> lines) {
        return !plainLines(lines).isBlank();
    }

    private static String plainLines(List<Component> lines) {
        StringBuilder text = new StringBuilder();
        for (Component line : lines) {
            if (line != null) {
                text.append(PlainTextComponentSerializer.plainText().serialize(line)).append(' ');
            }
        }
        return text.toString().strip();
    }
}
