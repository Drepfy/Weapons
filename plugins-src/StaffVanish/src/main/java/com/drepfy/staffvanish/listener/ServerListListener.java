package com.drepfy.staffvanish.listener;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.drepfy.staffvanish.VanishManager;
import com.drepfy.staffvanish.VanishModule;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;

/** Leaves vanished players out of the multiplayer server list. Runs off the main thread. */
public final class ServerListListener implements Listener {

    private final VanishModule module;

    public ServerListListener(VanishModule module) {
        this.module = module;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPing(PaperServerListPingEvent event) {
        if (!module.settings().hideFromServerList() || module.storage().entries().isEmpty()) {
            return;
        }
        VanishManager vanish = module.manager();
        event.setNumPlayers(Math.max(0, event.getNumPlayers() - vanish.vanishedOnline().size()));
        // The sample comes from a status snapshot that can be a few seconds old, so match against everyone who is
        // vanished, including players who have just left.
        event.getListedPlayers().removeIf(player -> vanish.isMarkedVanished(player.id()));
    }
}
