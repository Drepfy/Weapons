package dev.drepfy.moderation.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import org.bukkit.Server;

/**
 * Enforces voice mutes in Simple Voice Chat by dropping muted players' microphone packets.
 *
 * <p>The handler is registered with the highest possible priority. Simple Voice Chat calls
 * handlers in priority order and stops at the first one that cancels, so a muted player's audio
 * never reaches proximity chat, groups, whispering or any other addon (radios, recorders, ...).
 */
final class SimpleVoiceChatHook implements VoicechatPlugin, VoiceChatHook {

    private final MicrophoneGate gate;
    private volatile boolean enabled = true;
    private volatile boolean listening;

    private SimpleVoiceChatHook(MicrophoneGate gate) {
        this.gate = gate;
    }

    /** Returns the interface type so callers never need this class (or the voice chat API) loaded. */
    static VoiceChatHook register(Server server, MicrophoneGate gate) {
        BukkitVoicechatService service = server.getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            return null;
        }
        SimpleVoiceChatHook hook = new SimpleVoiceChatHook(gate);
        service.registerPlugin(hook);
        return hook;
    }

    @Override
    public String getPluginId() {
        return "staffmoderation";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone, Integer.MAX_VALUE);
        listening = true;
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        if (!enabled) {
            return;
        }
        VoicechatConnection sender = event.getSenderConnection();
        if (sender != null && gate.blocks(sender.getPlayer().getUuid())) {
            event.cancel();
        }
    }

    @Override
    public String describe() {
        return listening ? "Simple Voice Chat (enforcing)" : "Simple Voice Chat (waiting for its voice server to start)";
    }

    @Override
    public void disable() {
        enabled = false;
    }
}
