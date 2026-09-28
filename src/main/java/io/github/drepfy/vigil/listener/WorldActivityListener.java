package io.github.drepfy.vigil.listener;

import io.github.drepfy.vigil.env.DisturbanceRegistry;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.ProjectileHitEvent;

/**
 * Records world activity that can move players without Bukkit telling us
 * (pistons, explosions, wind charges). Recording is a single map write.
 */
public final class WorldActivityListener implements Listener {

    private final DisturbanceRegistry disturbances;

    public WorldActivityListener(DisturbanceRegistry disturbances) {
        this.disturbances = disturbances;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        record(event.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        record(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onProjectileHit(ProjectileHitEvent event) {
        Entity projectile = event.getEntity();
        // Wind charges knock players back without damage or a velocity event.
        if (projectile.getType().name().contains("WIND_CHARGE")) {
            record(projectile.getLocation());
        }
    }

    private void record(Block block) {
        disturbances.record(block.getWorld(), block.getX() + 0.5, block.getZ() + 0.5, Clock.now());
    }

    private void record(Location location) {
        if (location.getWorld() != null) {
            disturbances.record(location.getWorld(), location.getX(), location.getZ(), Clock.now());
        }
    }
}
