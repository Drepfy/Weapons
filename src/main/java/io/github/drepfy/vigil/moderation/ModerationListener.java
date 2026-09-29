package io.github.drepfy.vigil.moderation;

import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;

import java.util.Locale;
import java.util.function.Supplier;

/**
 * Enforces bans at login (always) and mutes in chat and private-message commands.
 * Login and chat events run off the server thread; the lookups are thread safe.
 */
public final class ModerationListener implements Listener {

    private final Supplier<Settings> settings;
    private final ModerationService service;

    public ModerationListener(Supplier<Settings> settings, ModerationService service) {
        this.settings = settings;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        // Always enforced: automatic bans are stored here even when the commands are disabled.
        Punishment ban = service.activeBan(event.getUniqueId());
        if (ban != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen("ban-screen", ban));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (!settings.get().moderation().enabled()) {
            return;
        }
        Punishment mute = service.activeMute(event.getPlayer().getUniqueId());
        if (mute != null) {
            event.setCancelled(true);
            notifyMuted(event.getPlayer(), mute);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Settings.Moderation moderation = settings.get().moderation();
        if (!moderation.enabled()) {
            return;
        }
        Punishment mute = service.activeMute(event.getPlayer().getUniqueId());
        if (mute == null) {
            return;
        }
        String label = commandLabel(event.getMessage());
        if (moderation.mutedBlockedCommands().contains(label)) {
            event.setCancelled(true);
            notifyMuted(event.getPlayer(), mute);
        }
    }

    /** {@code "/minecraft:msg Bob hi"} → {@code "msg"}. */
    static String commandLabel(String message) {
        String text = message.startsWith("/") ? message.substring(1) : message;
        int space = text.indexOf(' ');
        String label = (space >= 0 ? text.substring(0, space) : text).toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        return colon >= 0 ? label.substring(colon + 1) : label;
    }

    private void notifyMuted(Player player, Punishment mute) {
        Settings.Messages messages = settings.get().messages();
        player.sendMessage(Text.color(messages.get("prefix") + fill(messages.get("muted-chat"), mute)));
    }

    /** Multi-line kick/ban screen for a punishment. */
    public String screen(String key, Punishment punishment) {
        return Text.color(fill(settings.get().messages().get(key), punishment));
    }

    /** Replaces the punishment placeholders in a message. */
    public String fill(String template, Punishment punishment) {
        Settings.Messages messages = settings.get().messages();
        String permanent = messages.get("permanent");
        String expires = punishment.isPermanent() ? permanent
                : Durations.format(punishment.expiresEpochMs() - System.currentTimeMillis(), permanent);
        return Text.replace(template,
                "player", punishment.name(),
                "staff", punishment.staff(),
                "reason", punishment.reason(),
                "duration", Durations.format(punishment.durationMs(), permanent),
                "expires", expires,
                "id", punishment.id());
    }
}
