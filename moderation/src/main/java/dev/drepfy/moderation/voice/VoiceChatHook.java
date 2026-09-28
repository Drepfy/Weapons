package dev.drepfy.moderation.voice;

import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Connection to a voice chat plugin that lets voice mutes be enforced.
 */
public interface VoiceChatHook {

    /** Decides whether a player's microphone audio must be dropped. Called off the main thread. */
    @FunctionalInterface
    interface MicrophoneGate {
        boolean blocks(UUID player);
    }

    /** Human-readable status for /moderation info. */
    String describe();

    /** Stops enforcing (the voice chat plugin offers no way to unregister). */
    void disable();

    /**
     * Hooks into Simple Voice Chat if it is installed.
     *
     * @return the hook, or {@code null} if no supported voice chat plugin is available
     */
    static VoiceChatHook register(Plugin plugin, MicrophoneGate gate) {
        if (plugin.getServer().getPluginManager().getPlugin("voicechat") == null) {
            return null;
        }
        try {
            // SimpleVoiceChatHook is only loaded here, so the plugin works without Simple Voice Chat.
            VoiceChatHook hook = SimpleVoiceChatHook.register(plugin.getServer(), gate);
            if (hook == null) {
                plugin.getLogger().severe("Simple Voice Chat is installed but its API service is missing; "
                        + "voice mutes will NOT be enforced");
            }
            return hook;
        } catch (LinkageError | RuntimeException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not hook into Simple Voice Chat; voice mutes will NOT be enforced", e);
            return null;
        }
    }
}
