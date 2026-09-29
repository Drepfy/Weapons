package io.github.drepfy.vigil.listener;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.movement.VelocityCheck;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.util.Vector;

import java.util.function.Consumer;

/**
 * Tracks everything that legitimately changes how a player moves, so the checks
 * can grant grace periods and extra allowance instead of flagging.
 */
public final class LifecycleListener implements Listener {

    /** Horizontal speed assumed for knockback whose vector is unknown (blocks/tick). */
    private static final double UNKNOWN_KNOCKBACK = 0.6;
    private static final double EXPLOSION_KNOCKBACK = 2.5;
    private static final long BONUS_DURATION_MS = 3000;

    private final CheckContext ctx;
    private final VelocityCheck velocityCheck;
    private final Consumer<Player> onJoin;
    private final Consumer<Player> onQuit;

    public LifecycleListener(CheckContext ctx, VelocityCheck velocityCheck, Consumer<Player> onJoin,
                             Consumer<Player> onQuit) {
        this.ctx = ctx;
        this.velocityCheck = velocityCheck;
        this.onJoin = onJoin;
        this.onQuit = onQuit;
    }

    /**
     * Records an external push: suspends the flight ascend rule until the player
     * falls, opens the velocity grace window and grants extra speed allowance.
     */
    public void impulse(Player player, PlayerData data, double horizontalPerTick, long now) {
        data.lastImpulseMs = now;
        data.flight.impulse(player.getLocation().getY());
        double credit = ctx.settings(CheckType.SPEED).num("velocity-credit");
        data.speed.grantBonus(Math.max(0.0, horizontalPerTick) * credit + 2.0, now, BONUS_DURATION_MS);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        onJoin.accept(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        onQuit.accept(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        long now = Clock.now();
        PlayerData data = ctx.players().get(event.getPlayer());
        data.lastTeleportMs = now;
        resetMovement(data, event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        long now = Clock.now();
        PlayerData data = ctx.players().get(event.getPlayer());
        data.lastRespawnMs = now;
        resetMovement(data, event.getRespawnLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        long now = Clock.now();
        PlayerData data = ctx.players().get(event.getPlayer());
        data.lastWorldChangeMs = now;
        // Coordinates from another world are meaningless for lag compensation.
        data.positions.clear();
        resetMovement(data, event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        long now = Clock.now();
        PlayerData data = ctx.players().get(event.getPlayer());
        data.lastGamemodeChangeMs = now;
        data.invalidateBypass();
        resetMovement(data, event.getPlayer().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        long now = Clock.now();
        Player player = event.getPlayer();
        PlayerData data = ctx.players().get(player);
        data.lastVelocityMs = now;
        Vector velocity = event.getVelocity();
        double horizontal = Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
        impulse(player, data, horizontal, now);
        if (ctx.settings().general().enabled()) {
            ctx.run(CheckType.VELOCITY, now, () -> velocityCheck.onVelocity(player, data, velocity, now));
        }
    }

    /**
     * Knockback from hits arrives as a velocity event, so damage itself grants no grace;
     * only explosions push players without one. Cancelled damage counts too: a fall whose
     * damage another plugin cancelled was still a legitimate fall.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        long now = Clock.now();
        PlayerData data = ctx.players().get(player);
        EntityDamageEvent.DamageCause cause = event.getCause();
        if (cause == EntityDamageEvent.DamageCause.FALL) {
            data.lastFallDamageMs = now;
            return;
        }
        if (!event.isCancelled()) {
            data.lastDamageMs = now;
        }
        if (cause == EntityDamageEvent.DamageCause.BLOCK_EXPLOSION
                || cause == EntityDamageEvent.DamageCause.ENTITY_EXPLOSION) {
            impulse(player, data, EXPLOSION_KNOCKBACK, now);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            ctx.players().get(player).lastGlideMs = Clock.now();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent event) {
        long now = Clock.now();
        PlayerData data = ctx.players().get(event.getPlayer());
        data.lastRiptideMs = now;
        impulse(event.getPlayer(), data, EXPLOSION_KNOCKBACK, now);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        ctx.players().get(event.getPlayer()).lastFlyingMs = Clock.now();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            ctx.players().get(player).lastVehicleMs = Clock.now();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            long now = Clock.now();
            PlayerData data = ctx.players().get(player);
            data.lastVehicleMs = now;
            data.flight.impulse(player.getLocation().getY());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        Entity caught = event.getCaught();
        if (event.getState() == PlayerFishEvent.State.CAUGHT_ENTITY && caught instanceof Player hooked) {
            long now = Clock.now();
            impulse(hooked, ctx.players().get(hooked), UNKNOWN_KNOCKBACK * 2, now);
        }
    }

    private static void resetMovement(PlayerData data, Location to) {
        data.resetMovementState();
        data.lastSafeLocation = null;
        data.flight.reset(to != null ? to.getY() : 0.0, true);
    }
}
