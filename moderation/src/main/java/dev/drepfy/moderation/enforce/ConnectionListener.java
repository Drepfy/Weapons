package dev.drepfy.moderation.enforce;

import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.Permissions;
import dev.drepfy.moderation.cache.PunishmentCache;
import dev.drepfy.moderation.config.Messages;
import dev.drepfy.moderation.model.Punishment;
import dev.drepfy.moderation.model.PunishmentType;
import dev.drepfy.moderation.service.Placeholders;
import dev.drepfy.moderation.service.PunishmentService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Enforces bans at login and loads each player's punishments before they can chat or talk.
 */
public final class ConnectionListener implements Listener {

    private record Denial(AsyncPlayerPreLoginEvent.Result result, Component message, String reason) {
    }

    private static final long LOGIN_NOTICE_COOLDOWN_MILLIS = 30_000;

    private final ModerationPlugin plugin;
    private final PunishmentCache cache;
    private final PunishmentService service;
    /** Logins this listener refused, so the HIGHEST handler can undo other plugins re-allowing them. */
    private final Map<UUID, Denial> denials = new ConcurrentHashMap<>();

    public ConnectionListener(ModerationPlugin plugin, PunishmentCache cache, PunishmentService service) {
        this.plugin = plugin;
        this.cache = cache;
        this.service = service;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        UUID id = event.getUniqueId();
        String name = event.getName();
        long token = cache.beginSnapshot();
        long now = System.currentTimeMillis();
        List<Punishment> inForce;
        try {
            inForce = plugin.storage().submit(store -> {
                store.recordLogin(id, name, now);
                return store.findInForce(id, now);
            }).get(plugin.settings().loginTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            refuseOnError(event, e);
            return;
        } catch (ExecutionException | TimeoutException e) {
            refuseOnError(event, e);
            return;
        }
        cache.applySnapshot(id, inForce, token, System.currentTimeMillis());

        Optional<Punishment> ban = cache.find(id, PunishmentType.BAN, now);
        if (ban.isEmpty()) {
            return;
        }
        Punishment punishment = ban.get();
        Component screen = plugin.messages().render("ban.screen", service.placeholders(punishment));
        deny(event, new Denial(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen, "banned (#" + punishment.id() + ")"));
        if (plugin.settings().logBlockedAttempts()) {
            plugin.audit().record("BLOCKED-LOGIN", name + " (" + id + ") is banned (#" + punishment.id() + ")");
        }
        // A banned player hammering reconnect shouldn't flood staff chat; the log still has every attempt.
        if (plugin.cooldowns().tryAcquire(id, "login-attempt", now, LOGIN_NOTICE_COOLDOWN_MILLIS)) {
            plugin.runOnMain(() -> service.notifyStaff("ban.login-attempt", service.placeholders(punishment), null));
        }
    }

    private void refuseOnError(AsyncPlayerPreLoginEvent event, Exception error) {
        String who = event.getName() + " (" + event.getUniqueId() + ")";
        service.fail(null, "loading punishments for " + who + " during login", error);
        if (!plugin.settings().denyLoginOnDatabaseError()) {
            plugin.audit().warn("LOGIN", "Let " + who + " in without checking punishments (deny-login-on-database-error is off)");
            return;
        }
        if (Bukkit.getOfflinePlayer(event.getUniqueId()).isOp()) {
            plugin.audit().warn("LOGIN", "Let operator " + who + " in while the database is unavailable");
            return;
        }
        deny(event, new Denial(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                plugin.messages().render("login.database-error"), "database unavailable"));
    }

    private void deny(AsyncPlayerPreLoginEvent event, Denial denial) {
        event.disallow(denial.result(), denial.message());
        denials.put(event.getUniqueId(), denial);
    }

    /** Re-applies our denial if a plugin listening later (e.g. a whitelist) allowed the login again. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLoginFinal(AsyncPlayerPreLoginEvent event) {
        Denial denial = denials.remove(event.getUniqueId());
        if (denial != null && event.getLoginResult() == AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            event.disallow(denial.result(), denial.message());
            plugin.audit().warn("BYPASS-BLOCKED", "Another plugin allowed " + event.getName()
                    + " to log in although they are " + denial.reason() + "; refused again");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID id = player.getUniqueId();
        long now = System.currentTimeMillis();

        // Covers a ban issued between the login check and now.
        Optional<Punishment> ban = cache.find(id, PunishmentType.BAN, now);
        if (ban.isPresent()) {
            event.joinMessage(null);
            player.kick(plugin.messages().render("ban.screen", service.placeholders(ban.get())));
            plugin.audit().record("BLOCKED-LOGIN", player.getName() + " (" + id + ") was banned while logging in (#" + ban.get().id() + ")");
            return;
        }

        if (!cache.isLoaded(id)) {
            // Only possible when deny-login-on-database-error is off and the database failed at login.
            plugin.whenComplete(plugin.loadPlayers(List.of(id)), (ignored, error) -> {
                if (error == null && player.isOnline()) {
                    cache.find(id, PunishmentType.BAN, System.currentTimeMillis()).ifPresent(late ->
                            player.kick(plugin.messages().render("ban.screen", service.placeholders(late))));
                }
            });
        }
        remindActive(player, now);

        boolean exempt = player.hasPermission(Permissions.EXEMPT);
        plugin.whenComplete(plugin.storage().submit(store -> {
            store.setExempt(id, exempt);
            return store.undeliveredWarnings(id);
        }), (warnings, error) -> {
            if (error != null) {
                service.fail(null, "loading offline warnings for " + player.getName(), error);
                return;
            }
            deliverOfflineWarnings(player, warnings);
        });
    }

    private void remindActive(Player player, long now) {
        Messages messages = plugin.messages();
        cache.find(player.getUniqueId(), PunishmentType.MUTE, now)
                .ifPresent(mute -> messages.send(player, "mute.join-reminder", service.placeholders(mute)));
        cache.find(player.getUniqueId(), PunishmentType.VOICE_MUTE, now)
                .ifPresent(mute -> messages.send(player, "voicemute.join-reminder", service.placeholders(mute)));
    }

    private void deliverOfflineWarnings(Player player, List<Punishment> warnings) {
        if (warnings.isEmpty() || !player.isOnline()) {
            return;
        }
        Messages messages = plugin.messages();
        messages.send(player, "warn.offline-header", Placeholders.text("count", warnings.size()));
        List<Long> ids = new ArrayList<>(warnings.size());
        for (Punishment warning : warnings) {
            messages.send(player, "warn.offline-entry", service.placeholders(warning));
            ids.add(warning.id());
        }
        plugin.storage().submit(store -> {
            store.markNotified(ids);
            return null;
        }).exceptionally(error -> {
            service.fail(null, "marking warnings as delivered for " + player.getName(), error);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        // Cached punishments are kept briefly (see PunishmentCache#evictIdle) so a quick relog can't
        // race past an eviction; only the notification cooldowns are cleared here.
        plugin.cooldowns().clear(event.getPlayer().getUniqueId());
    }
}
