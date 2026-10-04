package io.github.drepfy.combat;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.ArrayList;

/**
 * Movement in combat: no elytra gliding and no riptide near the players you fight, and no
 * way into a safe zone (walking, flying, riding, Ender Pearls, chorus fruit, portals or
 * any teleport).
 */
final class MovementListener implements Listener {

    private final CombatPlugin plugin;

    MovementListener(CombatPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- elytra ---------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.isGliding() && event.getEntity() instanceof Player player
                && plugin.movementBlocked(player, plugin.settings().elytra())) {
            event.setCancelled(true);
            plugin.refuse(player, "elytra-blocked");
        }
    }

    // ---- riptide ----------------------------------------------------------------------------------------

    /** Riptide starts when the trident is charged: refused before that. */
    @EventHandler(priority = EventPriority.LOW)
    public void onTrident(PlayerInteractEvent event) {
        Action action = event.getAction();
        if ((action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) && isRiptide(event.getItem())
                && plugin.movementBlocked(event.getPlayer(), plugin.settings().riptide())) {
            event.setUseItemInHand(Event.Result.DENY);
            plugin.refuse(event.getPlayer(), "riptide-blocked");
        }
    }

    /** The game does not let plugins cancel riptide itself; if one gets through anyway, it is stopped. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent event) {
        Player player = event.getPlayer();
        if (plugin.movementBlocked(player, plugin.settings().riptide())) {
            plugin.refuse(player, "riptide-blocked");
            org.bukkit.Bukkit.getScheduler().runTask(plugin, () -> player.setVelocity(new Vector()));
        }
    }

    static boolean isRiptide(ItemStack item) {
        return item != null && item.getType() == Material.TRIDENT && item.getEnchantmentLevel(Enchantment.RIPTIDE) > 0;
    }

    // ---- safe zones ---------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || !plugin.isInCombat(event.getPlayer())) {
            return;
        }
        SafeZone zone = plugin.zones().at(to);
        if (zone != null && !zone.contains(event.getFrom())) {
            event.setCancelled(true);
            pushOut(event.getPlayer(), zone);
            plugin.refuseZone(event.getPlayer(), zone);
        }
    }

    /** Ender Pearls, chorus fruit, portals, /spawn, /tpa... into a safe zone. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Location to = event.getTo();
        if (to == null || !plugin.isInCombat(event.getPlayer())) {
            return;
        }
        SafeZone zone = plugin.zones().at(to);
        if (zone != null && !zone.contains(event.getFrom())) {
            event.setCancelled(true);
            plugin.refuseZone(event.getPlayer(), zone);
        }
    }

    /** Boats, horses, minecarts... : the rider is taken off outside the zone. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onVehicle(VehicleMoveEvent event) {
        SafeZone zone = plugin.zones().at(event.getTo());
        if (zone == null || zone.contains(event.getFrom())) {
            return;
        }
        for (Entity passenger : new ArrayList<>(event.getVehicle().getPassengers())) {
            if (passenger instanceof Player player && plugin.isInCombat(player)) {
                event.getVehicle().removePassenger(player);
                Location back = event.getFrom().clone();
                back.setYaw(player.getLocation().getYaw());
                back.setPitch(player.getLocation().getPitch());
                player.teleport(back);
                pushOut(player, zone);
                plugin.refuseZone(player, zone);
            }
        }
    }

    /** A small push away from the zone so the player is not stuck against it. */
    private static void pushOut(Player player, SafeZone zone) {
        Location at = player.getLocation();
        Vector away = new Vector(at.getX() - zone.centerX(), 0, at.getZ() - zone.centerZ());
        if (away.lengthSquared() < 1.0E-6) {
            return;
        }
        player.setVelocity(away.normalize().multiply(0.6).setY(0.25));
    }
}
