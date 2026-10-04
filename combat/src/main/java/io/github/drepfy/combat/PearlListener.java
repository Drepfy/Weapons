package io.github.drepfy.combat;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;

/**
 * The Ender Pearl cooldown. It belongs to the player on the server, not to the item: switching
 * items, hotbars or hands, reconnecting and commands cannot reset it. A throw on cooldown is
 * refused before the pearl is used, and a pearl that is launched anyway (another plugin,
 * a modified client) is removed at launch.
 */
final class PearlListener implements Listener {

    private final CombatPlugin plugin;

    PearlListener(CombatPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onUse(PlayerInteractEvent event) {
        Action action = event.getAction();
        ItemStack item = event.getItem();
        if ((action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) || item == null
                || item.getType() != Material.ENDER_PEARL) {
            return;
        }
        long left = plugin.pearlCooldown(event.getPlayer());
        if (left > 0) {
            event.setUseItemInHand(Event.Result.DENY);
            plugin.pearlRefused(event.getPlayer(), left);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onLaunch(ProjectileLaunchEvent event) {
        if (!(event.getEntity() instanceof EnderPearl pearl) || !(pearl.getShooter() instanceof Player player)) {
            return;
        }
        long left = plugin.pearlCooldown(player);
        if (left > 0) {
            event.setCancelled(true);
            plugin.pearlRefused(player, left);
            // Make sure the client shows the pearl it kept.
            Bukkit.getScheduler().runTask(plugin, player::updateInventory);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLaunched(ProjectileLaunchEvent event) {
        if (event.getEntity() instanceof EnderPearl pearl && pearl.getShooter() instanceof Player player) {
            plugin.pearlThrown(player);
        }
    }
}
