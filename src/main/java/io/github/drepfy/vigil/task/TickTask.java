package io.github.drepfy.vigil.task;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.combat.KillAuraCheck;
import io.github.drepfy.vigil.check.combat.NoSwingCheck;
import io.github.drepfy.vigil.check.movement.FlightCheck;
import io.github.drepfy.vigil.check.movement.NoFallCheck;
import io.github.drepfy.vigil.check.movement.NoSlowCheck;
import io.github.drepfy.vigil.check.movement.VelocityCheck;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.env.DisturbanceRegistry;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.Set;

/**
 * Runs once per server tick on the main thread:
 * <ul>
 *   <li>measures TPS and lag spikes,</li>
 *   <li>records every player's hitbox for lag compensation,</li>
 *   <li>drives the flight, velocity and no-slow checks with the client time that passed,</li>
 *   <li>judges pending kill-aura, no-swing and no-fall observations,</li>
 *   <li>refreshes cached bypass permissions and does periodic housekeeping.</li>
 * </ul>
 */
public final class TickTask implements Runnable {

    /** Client tick-end events count only if one arrived this recently. */
    private static final long CLIENT_TICK_FRESHNESS_MS = 2000;
    /** Without tick-end events, one sample never represents more client time than this. */
    private static final double MAX_ACTIVE_PER_SAMPLE_MS = 100.0;
    /** Upper bound for a burst of client ticks processed in one server tick. */
    private static final int MAX_TICKS_PER_SAMPLE = 20;
    private static final long BYPASS_CACHE_MS = 5000;

    /** The checks driven by this task. */
    public record Checks(FlightCheck flight, VelocityCheck velocity, NoSlowCheck noSlow, NoFallCheck noFall,
                         KillAuraCheck killAura, NoSwingCheck noSwing) {
    }

    private final CheckContext ctx;
    private final Checks checks;
    private final DisturbanceRegistry disturbances;
    private final Runnable every5Seconds;
    private final Runnable every5Minutes;
    private final Location reusable = new Location(null, 0, 0, 0);
    private long ticks;

    public TickTask(CheckContext ctx, Checks checks, DisturbanceRegistry disturbances,
                    Runnable every5Seconds, Runnable every5Minutes) {
        this.ctx = ctx;
        this.checks = checks;
        this.disturbances = disturbances;
        this.every5Seconds = every5Seconds;
        this.every5Minutes = every5Minutes;
    }

    @Override
    public void run() {
        long now = Clock.now();
        ticks++;
        ctx.advanceTick();
        Settings settings = ctx.settings();
        ctx.tps().onTick(now, settings.lag().lagSpikeThresholdMs());
        boolean enabled = settings.general().enabled();

        for (Player player : Bukkit.getOnlinePlayers()) {
            PlayerData data = ctx.players().get(player);
            player.getLocation(reusable);
            double x = reusable.getX();
            double y = reusable.getY();
            double z = reusable.getZ();
            data.positions.record(now, x, y, z, player.getWidth(), player.getHeight());
            if (ticks % 20 == 0) {
                ctx.recentPing(player, data, now);
            }
            if (settings.general().bypassPermission() && data.bypassCacheExpired(now, BYPASS_CACHE_MS)) {
                refreshBypass(player, data, now);
            }

            if (enabled) {
                double activeMs = activeClientTime(data, now, settings.general().useClientTickEvents());
                boolean moved = !data.hasSample || x != data.sampleX || y != data.sampleY || z != data.sampleZ;
                ctx.run(CheckType.FLIGHT, now, () -> {
                    if (checks.flight().onSample(player, data, reusable, activeMs, moved, now)) {
                        setBack(player, data, now);
                    }
                });
                ctx.run(CheckType.VELOCITY, now, () -> checks.velocity().onSample(player, data, y, activeMs, now));
                ctx.run(CheckType.NOSLOW, now, () -> checks.noSlow().onSample(player, data, now));
                ctx.run(CheckType.NOFALL, now, () -> checks.noFall().onTick(player, data, now));
            }
            data.sampleX = x;
            data.sampleY = y;
            data.sampleZ = z;
            data.hasSample = true;
            data.lastSampleMs = now;
            data.moveEventsSinceSample = 0;
            data.clientTicksSinceSample = 0;
        }
        reusable.setWorld(null);

        checks.killAura().processPending(now);
        checks.noSwing().processPending(now);

        if (ticks % 100 == 0) {
            disturbances.prune(now, Math.max(settings.lag().disturbanceGraceMs(), 1000));
            ctx.players().pruneQuit(now);
            every5Seconds.run();
        }
        if (ticks % 6000 == 0) {
            every5Minutes.run();
        }
    }

    /**
     * Client time that passed since the previous sample. With Paper's tick-end
     * events this is exact; otherwise it is wall time, but only when movement
     * packets arrived (a client that sends nothing is frozen or lagging, and its
     * airtime must not count).
     */
    private static double activeClientTime(PlayerData data, long now, boolean useClientTicks) {
        if (useClientTicks && now - data.lastClientTickMs < CLIENT_TICK_FRESHNESS_MS) {
            return 50.0 * Math.min(data.clientTicksSinceSample, MAX_TICKS_PER_SAMPLE);
        }
        if (data.moveEventsSinceSample <= 0 || !data.hasSample) {
            return 0.0;
        }
        return Math.min(Math.max(0L, now - data.lastSampleMs), (long) MAX_ACTIVE_PER_SAMPLE_MS);
    }

    /** Cached so the permission lookups stay off the hot path; only used when bypass-permission is on. */
    private static void refreshBypass(Player player, PlayerData data, long now) {
        boolean all = player.hasPermission("vigil.bypass");
        Set<CheckType> perCheck = EnumSet.noneOf(CheckType.class);
        if (!all) {
            for (CheckType type : CheckType.values()) {
                if (player.hasPermission(type.bypassPermission())) {
                    perCheck.add(type);
                }
            }
        }
        data.updateBypass(all, perCheck, now);
    }

    private static void setBack(Player player, PlayerData data, long now) {
        Location safe = data.lastSafeLocation;
        if (safe == null || !safe.getWorld().equals(player.getWorld())
                || safe.distanceSquared(player.getLocation()) > 64 * 64) {
            return;
        }
        data.lastSetbackMs = now;
        Location target = safe.clone();
        Location current = player.getLocation();
        target.setYaw(current.getYaw());
        target.setPitch(current.getPitch());
        data.pendingSetback = target.clone();
        data.pendingSetbackMs = now;
        player.teleport(target);
    }
}
