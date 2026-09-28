package dev.drepfy.moderation.enforce;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;

/**
 * Cancels the legacy chat event for muted players. Paper fires it before {@code AsyncChatEvent},
 * and many chat relays (Discord bridges, older chat plugins) only listen to it, so without this a
 * muted player's messages could still be forwarded.
 */
@SuppressWarnings("deprecation")
public final class LegacyChatListener implements Listener {

    private final MuteListener mutes;

    public LegacyChatListener(MuteListener mutes) {
        this.mutes = mutes;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        mutes.activeMute(event.getPlayer())
                .ifPresent(mute -> mutes.blockChat(event.getPlayer(), mute, event, event.getMessage()));
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onChatFinal(AsyncPlayerChatEvent event) {
        mutes.reassert(event, event.getPlayer(), "legacy chat");
    }
}
