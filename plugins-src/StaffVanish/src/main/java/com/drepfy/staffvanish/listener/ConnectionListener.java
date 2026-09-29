package com.drepfy.staffvanish.listener;

import com.drepfy.staffvanish.VanishModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Keeps vanish state across reconnects and hides vanished players' join and quit messages. */
public final class ConnectionListener implements Listener {

    private final VanishModule module;

    public ConnectionListener(VanishModule module) {
        this.module = module;
    }

    // LOWEST so other plugins' join listeners already see the player as vanished. Paper announces the player in tab
    // lists and starts tracking their entity only after this event, so hiding them here doesn't leak anything.
    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        switch (module.manager().handleJoin(player)) {
            case VANISHED -> {
                module.messages().send(player, "rejoined-vanished");
                module.manager().notifyStaff("staff-joined", player, null);
            }
            case PERMISSION_REVOKED -> module.messages().send(player, "permission-revoked");
            case VISIBLE -> {
            }
        }
    }

    // HIGHEST so plugins that customise join messages at a lower priority can't bring it back.
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoinMessage(PlayerJoinEvent event) {
        if (module.manager().isVanished(event.getPlayer())) {
            event.joinMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuitMessage(PlayerQuitEvent event) {
        if (module.manager().isVanished(event.getPlayer())) {
            event.quitMessage(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        module.teleporter().forget(player.getUniqueId());
        if (module.manager().handleQuit(player)) {
            module.manager().notifyStaff("staff-left", player, null);
        }
    }
}
