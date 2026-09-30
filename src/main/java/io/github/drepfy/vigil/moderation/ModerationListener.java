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

    /** Staff name of automatic anti-cheat bans (they get their own ban screen). */
    public static final String ANTI_CHEAT_STAFF = "Anti-Cheat";

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
        if (ban == null) {
            return;
        }
        // Refused first, so a problem with the screen text can never let a banned player in.
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "You are banned from this server.");
        try {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, banScreen(ban));
        } catch (RuntimeException e) {
            // Keep the plain message.
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

    /** The ban screen that fits the ban: anti-cheat, permanent or temporary. */
    public String banScreen(Punishment ban) {
        String key = ANTI_CHEAT_STAFF.equals(ban.staff()) ? "ban-screen-anticheat"
                : ban.isPermanent() ? "ban-screen-permanent" : "ban-screen";
        return screen(key, ban);
    }

    /**
     * Replaces the punishment placeholders in a message: {@code {player} {staff} {reason}
     * {duration} {expires} {expires-date} {date} {id} {offence} {appeal}}.
     */
    public String fill(String template, Punishment punishment) {
        Settings config = settings.get();
        Settings.Messages messages = config.messages();
        String permanent = messages.get("permanent");
        long now = System.currentTimeMillis();
        // Rounded up so a fresh 7 day ban reads "7 days", not "6 days 23 hours".
        long left = Math.max(0L, punishment.expiresEpochMs() - now);
        String expires = punishment.isPermanent() ? permanent
                : Durations.format((left + 999) / 1000 * 1000, permanent);
        java.time.format.DateTimeFormatter dates = dateFormatter(config.moderation().dateFormat());
        String expiresDate = punishment.isPermanent() ? messages.get("never")
                : dates.format(java.time.Instant.ofEpochMilli(punishment.expiresEpochMs()));
        int offence = 1 + previousOffences(config, punishment);
        return Text.replace(template,
                "player", punishment.name(),
                "staff", punishment.staff(),
                "reason", punishment.reason(),
                "duration", Durations.format(punishment.durationMs(), permanent),
                "expires-date", expiresDate,
                "expires", expires,
                "date", dates.format(java.time.Instant.ofEpochMilli(punishment.createdEpochMs())),
                "id", punishment.id(),
                "offence", ReasonPreset.ordinal(offence),
                "appeal", config.moderation().appeal());
    }

    /**
     * Offences before this one, counted the same way the length was chosen: automatic bans
     * on any earlier automatic ban, staff punishments per preset reason.
     */
    private int previousOffences(Settings config, Punishment punishment) {
        if (ANTI_CHEAT_STAFF.equals(punishment.staff()) && punishment.type() == PunishmentType.BAN) {
            return service.previousAutoBans(punishment.uuid(), ANTI_CHEAT_STAFF, punishment.id());
        }
        ReasonPreset preset = ReasonPreset.matching(config.moderation().reasons(punishment.type()), punishment.reason());
        if (preset != null) {
            return service.previousOffences(punishment.uuid(), punishment.type(), preset::matches, punishment.id());
        }
        return service.previousOffences(punishment.uuid(), punishment.type(), punishment.reason(), punishment.id());
    }

    /** Pattern and formatter together, so login and chat threads always see a matching pair. */
    private record CachedFormat(String pattern, java.time.format.DateTimeFormatter formatter) {
    }

    private volatile CachedFormat cachedFormat;

    private java.time.format.DateTimeFormatter dateFormatter(String pattern) {
        CachedFormat cached = cachedFormat;
        if (cached == null || !cached.pattern().equals(pattern)) {
            cached = new CachedFormat(pattern, java.time.format.DateTimeFormatter.ofPattern(pattern,
                    java.util.Locale.ENGLISH).withZone(java.time.ZoneId.systemDefault()));
            cachedFormat = cached;
        }
        return cached.formatter();
    }
}
