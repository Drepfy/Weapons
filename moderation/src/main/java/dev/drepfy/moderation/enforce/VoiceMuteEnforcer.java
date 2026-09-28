package dev.drepfy.moderation.enforce;

import dev.drepfy.moderation.ModerationPlugin;
import dev.drepfy.moderation.cache.PunishmentCache;
import dev.drepfy.moderation.model.Punishment;
import dev.drepfy.moderation.model.PunishmentType;
import dev.drepfy.moderation.service.PunishmentService;
import dev.drepfy.moderation.voice.VoiceChatHook;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.Optional;
import java.util.UUID;

/**
 * Decides, for every microphone packet, whether the speaker is voice-muted. Runs on the voice chat
 * plugin's network thread, so it only reads the in-memory cache.
 */
public final class VoiceMuteEnforcer implements VoiceChatHook.MicrophoneGate {

    private static final long MIN_NOTICE_COOLDOWN_MILLIS = 1000;

    private final ModerationPlugin plugin;
    private final PunishmentCache cache;
    private final PunishmentService service;

    public VoiceMuteEnforcer(ModerationPlugin plugin, PunishmentCache cache, PunishmentService service) {
        this.plugin = plugin;
        this.cache = cache;
        this.service = service;
    }

    @Override
    public boolean blocks(UUID player) {
        long now = System.currentTimeMillis();
        Optional<Punishment> mute = cache.find(player, PunishmentType.VOICE_MUTE, now);
        if (mute.isEmpty()) {
            return false;
        }
        // Microphone packets arrive ~50 times a second; only tell the player (and the log) now and then.
        long cooldown = Math.max(MIN_NOTICE_COOLDOWN_MILLIS, plugin.settings().voiceNotifyCooldownSeconds() * 1000L);
        if (plugin.cooldowns().tryAcquire(player, "voice", now, cooldown)) {
            Punishment punishment = mute.get();
            plugin.runOnMain(() -> {
                Player online = Bukkit.getPlayer(player);
                if (online != null && plugin.messages().enabled("voicemute.blocked")) {
                    online.sendActionBar(plugin.messages().render("voicemute.blocked", service.placeholders(punishment)));
                }
            });
            if (plugin.settings().logBlockedAttempts()) {
                plugin.audit().record("BLOCKED-VOICE", punishment.targetName() + " (#" + punishment.id() + ") tried to speak");
            }
        }
        return true;
    }
}
