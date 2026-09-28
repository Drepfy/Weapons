package com.drepfy.staffvanish.listener;

import com.drepfy.staffvanish.FallProtection;
import com.drepfy.staffvanish.VanishModule;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;

/**
 * Keeps vanished players able to fly. Switching to survival or adventure, respawning and (with some world
 * management plugins) changing worlds reset flight, so it's given back a tick later.
 */
public final class FlightListener implements Listener {

    private final VanishModule module;
    private final Plugin plugin;
    private final FallProtection fallProtection;

    public FlightListener(VanishModule module, Plugin plugin, FallProtection fallProtection) {
        this.module = module;
        this.plugin = plugin;
        this.fallProtection = fallProtection;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        reapplyNextTick(event.getPlayer(), event.getPlayer().isFlying());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        reapplyNextTick(event.getPlayer(), false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        reapplyNextTick(event.getPlayer(), event.getPlayer().isFlying());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onFall(EntityDamageEvent event) {
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL
                && event.getEntity() instanceof Player player
                && fallProtection.consume(player)) {
            event.setCancelled(true);
        }
    }

    private void reapplyNextTick(Player player, boolean wasFlying) {
        if (!module.manager().isVanished(player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                module.manager().reapplyFlight(player, wasFlying);
            }
        });
    }
}
