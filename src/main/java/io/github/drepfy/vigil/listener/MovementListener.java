package io.github.drepfy.vigil.listener;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.movement.GroundSpoofCheck;
import io.github.drepfy.vigil.check.movement.SpeedCheck;
import io.github.drepfy.vigil.check.movement.TimerCheck;
import io.github.drepfy.vigil.check.movement.VerticalCheck;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.env.BlockTraits;
import io.github.drepfy.vigil.env.WorldProbe;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Feeds movement packets to the movement checks. Runs at HIGH priority so an
 * optional setback can still change the event; moves cancelled by other plugins
 * are ignored (the player is teleported back, which resets state).
 */
public final class MovementListener implements Listener {

    /** How often the "last safe location" used for setbacks is refreshed. */
    private static final long SAFE_LOCATION_INTERVAL_MS = 250;
    /** Setbacks never teleport further than this. */
    private static final double MAX_SETBACK_DISTANCE = 64.0;

    private final CheckContext ctx;
    private final Logger logger;
    private final SpeedCheck speed;
    private final VerticalCheck vertical;
    private final GroundSpoofCheck groundSpoof;
    private final TimerCheck timer;
    private boolean environmentErrorLogged;

    public MovementListener(CheckContext ctx, Logger logger, SpeedCheck speed, VerticalCheck vertical,
                            GroundSpoofCheck groundSpoof, TimerCheck timer) {
        this.ctx = ctx;
        this.logger = logger;
        this.speed = speed;
        this.vertical = vertical;
        this.groundSpoof = groundSpoof;
        this.timer = timer;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return;
        }
        Player player = event.getPlayer();
        long now = Clock.now();
        PlayerData data = ctx.players().get(player);
        data.moveEventsSinceSample++;
        data.lastMoveEventMs = now;

        Settings settings = ctx.settings();
        if (!settings.general().enabled()) {
            return;
        }
        boolean exemptState = ctx.isMovementExemptState(player, data, now);
        if (ctx.isActive(player, data, CheckType.SPEED, now) || ctx.isActive(player, data, CheckType.VERTICAL, now)
                || ctx.isActive(player, data, CheckType.GROUND_SPOOF, now)) {
            sampleEnvironment(player, data, to, now);
        }

        if (!usesClientTicks(data, now)) {
            ctx.run(CheckType.TIMER, now, () -> timer.onClientTick(player, data, exemptState, now));
        }
        boolean[] setback = new boolean[1];
        ctx.run(CheckType.SPEED, now,
                () -> setback[0] |= speed.onMove(player, data, from, to, exemptState, now));
        ctx.run(CheckType.VERTICAL, now,
                () -> setback[0] |= vertical.onMove(player, data, from, to, exemptState, now));
        ctx.run(CheckType.GROUND_SPOOF, now,
                () -> setback[0] |= groundSpoof.onMove(player, data, from, to, exemptState, now));

        if (setback[0]) {
            applySetback(event, data, now);
        } else if (anyMovementMitigation(settings)) {
            updateSafeLocation(player, data, to, exemptState, now);
        }
    }

    /** Whether client tick-end events currently drive the timer for this player. */
    private boolean usesClientTicks(PlayerData data, long now) {
        return ctx.settings().general().useClientTickEvents() && now - data.lastClientTickMs < 2000;
    }

    /** Remembers surfaces and fluids for the checks' leniency (a handful of block lookups). */
    private void sampleEnvironment(Player player, PlayerData data, Location to, long now) {
        try {
            World world = to.getWorld();
            WorldProbe probe = ctx.probe();
            double width = player.getWidth();
            double height = player.getHeight();
            int surface = probe.surfaceTraits(world, to.getX(), to.getY(), to.getZ(), width);
            if ((surface & BlockTraits.ICE) != 0) {
                data.lastIceMs = now;
            }
            if ((surface & BlockTraits.SLIME) != 0) {
                data.lastSlimeMs = now;
            }
            if ((surface & BlockTraits.SOUL) != 0) {
                data.lastSoulMs = now;
            }
            if ((surface & BlockTraits.BOUNCY) != 0) {
                data.lastBouncyMs = now;
            }
            if (probe.hasCeiling(world, to.getX(), to.getY(), to.getZ(), width, height)) {
                data.lastCeilingMs = now;
            }
            int body = probe.bodyFlags(world, to.getX(), to.getY(), to.getZ(), width, height);
            if ((body & WorldProbe.LIQUID) != 0 || player.isInWater()) {
                data.lastLiquidMs = now;
            }
            if ((body & WorldProbe.CLIMBABLE) != 0) {
                data.lastClimbMs = now;
            }
            if ((body & (WorldProbe.SLOWING | WorldProbe.UNKNOWN)) != 0) {
                data.lastSlowingMs = now;
            }
        } catch (RuntimeException e) {
            // Never let sampling break movement; be lenient instead.
            data.lastSlowingMs = now;
            data.lastLiquidMs = now;
            if (!environmentErrorLogged) {
                environmentErrorLogged = true;
                logger.log(Level.WARNING, "Environment sampling failed (movement checks stay lenient)", e);
            }
        }
    }

    private static boolean anyMovementMitigation(Settings settings) {
        if (settings.general().passiveMode()) {
            return false;
        }
        return settings.check(CheckType.SPEED).mitigate() || settings.check(CheckType.FLIGHT).mitigate()
                || settings.check(CheckType.VERTICAL).mitigate() || settings.check(CheckType.GROUND_SPOOF).mitigate();
    }

    private void updateSafeLocation(Player player, PlayerData data, Location to, boolean exemptState, long now) {
        if (exemptState || now - data.lastSafeUpdateMs < SAFE_LOCATION_INTERVAL_MS) {
            return;
        }
        data.lastSafeUpdateMs = now;
        int flags = ctx.probe().scan(to.getWorld(), to.getX(), to.getY(), to.getZ(), player.getWidth(),
                player.getHeight(), 0.0, 0.1);
        if ((flags & WorldProbe.SUPPORT) != 0 && (flags & WorldProbe.UNKNOWN) == 0) {
            data.lastSafeLocation = to.clone();
        }
    }

    private void applySetback(PlayerMoveEvent event, PlayerData data, long now) {
        data.lastSetbackMs = now;
        Location safe = data.lastSafeLocation;
        Location from = event.getFrom();
        if (safe != null && from.getWorld().equals(safe.getWorld())
                && safe.distanceSquared(from) <= MAX_SETBACK_DISTANCE * MAX_SETBACK_DISTANCE) {
            Location target = safe.clone();
            target.setYaw(event.getTo().getYaw());
            target.setPitch(event.getTo().getPitch());
            event.setTo(target);
        } else {
            event.setCancelled(true);
        }
    }
}
