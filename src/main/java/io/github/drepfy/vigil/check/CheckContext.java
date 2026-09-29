package io.github.drepfy.vigil.check;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.data.PlayerDataManager;
import io.github.drepfy.vigil.env.DisturbanceRegistry;
import io.github.drepfy.vigil.env.WorldProbe;
import io.github.drepfy.vigil.model.Physics;
import io.github.drepfy.vigil.task.TpsMonitor;
import io.github.drepfy.vigil.util.Glob;
import io.github.drepfy.vigil.violation.DebugService;
import io.github.drepfy.vigil.violation.ViolationService;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.EnumMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shared services and the exemption logic every check relies on. Main thread only.
 */
public final class CheckContext {

    /** Errors within this window count towards the circuit breaker. */
    private static final long ERROR_WINDOW_MS = 60_000;
    private static final int MAX_ERRORS_PER_WINDOW = 20;

    private final Supplier<Settings> settings;
    private final Logger logger;
    private final ServerCompat compat;
    private final WorldProbe probe;
    private final DisturbanceRegistry disturbances;
    private final TpsMonitor tps;
    private final ViolationService violations;
    private final DebugService debug;
    private final PlayerDataManager players;
    private final Map<CheckType, int[]> errorCounts = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Long> errorWindowStart = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Boolean> brokenChecks = new EnumMap<>(CheckType.class);
    private long tick;

    public CheckContext(Supplier<Settings> settings, Logger logger, ServerCompat compat, WorldProbe probe,
                        DisturbanceRegistry disturbances, TpsMonitor tps, ViolationService violations,
                        DebugService debug, PlayerDataManager players) {
        this.settings = settings;
        this.logger = logger;
        this.compat = compat;
        this.probe = probe;
        this.disturbances = disturbances;
        this.tps = tps;
        this.violations = violations;
        this.debug = debug;
        this.players = players;
    }

    public Settings settings() {
        return settings.get();
    }

    public CheckSettings settings(CheckType type) {
        return settings.get().check(type);
    }

    public ServerCompat compat() {
        return compat;
    }

    public WorldProbe probe() {
        return probe;
    }

    public TpsMonitor tps() {
        return tps;
    }

    public PlayerDataManager players() {
        return players;
    }

    /** Server ticks since the plugin started (advanced by the tick task). */
    public long currentTick() {
        return tick;
    }

    public void advanceTick() {
        tick++;
    }

    // ---- activation ----------------------------------------------------------------------

    /**
     * Whether a check should run for this player at all (configuration, bypass
     * permissions, manual exemptions, disabled worlds, creative/spectator).
     */
    public boolean isActive(Player player, PlayerData data, CheckType type, long nowMs) {
        Settings config = settings.get();
        if (!config.general().enabled() || !config.check(type).enabled() || brokenChecks.containsKey(type)) {
            return false;
        }
        if ((config.general().bypassPermission() && data.bypasses(type)) || data.isManuallyExempt(type, nowMs)) {
            return false;
        }
        if (config.general().exemptCreativeAndSpectator()) {
            GameMode mode = player.getGameMode();
            if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
                return false;
            }
        }
        World world = player.getWorld();
        return config.general().disabledWorlds().isEmpty() || !config.general().disabledWorlds().contains(world.getName());
    }

    /**
     * Lag protection: whether a flag may be recorded right now. Never true while the
     * server is lagging, just recovered from a spike, runs a modified tick rate, or
     * while the player's recent ping is above the limit.
     */
    public boolean canFlag(Player player, PlayerData data, long nowMs) {
        Settings.Lag lag = settings.get().lag();
        if (tps.tps() < lag.minTps() || tps.recentlySpiked(nowMs, lag.lagSpikeGraceMs())) {
            return false;
        }
        if (!compat.isTickRateNormal()) {
            return false;
        }
        return recentPing(player, data, nowMs) <= lag.maxPingMs();
    }

    /** Highest ping seen over the last ~10 seconds (the current value if no history). */
    public int recentPing(Player player, PlayerData data, long nowMs) {
        int current = compat.ping(player);
        if (current < 0) {
            // Unknown ping: assume a moderate value so lag compensation stays generous.
            current = 150;
        }
        data.ping.record(nowMs, current);
        return (int) Math.max(current, data.ping.max(nowMs, current));
    }

    /** Player states in which movement physics do not apply. Also refreshes the related timestamps. */
    public boolean isMovementExemptState(Player player, PlayerData data, long nowMs) {
        boolean exempt = false;
        if (player.isFlying() || player.getAllowFlight()) {
            data.lastFlyingMs = nowMs;
            exempt = true;
        }
        if (player.isInsideVehicle()) {
            data.lastVehicleMs = nowMs;
            exempt = true;
        }
        if (player.isGliding()) {
            data.lastGlideMs = nowMs;
            exempt = true;
        }
        if (player.isRiptiding()) {
            data.lastRiptideMs = nowMs;
            exempt = true;
        }
        if (player.isDead() || player.isSleeping()) {
            exempt = true;
        }
        return exempt;
    }

    /** Grace windows after events that legitimately change a player's movement. */
    public boolean inMovementGrace(PlayerData data, long nowMs) {
        Settings.Lag lag = settings.get().lag();
        return nowMs - data.joinMs < lag.joinGraceMs()
                || nowMs - data.lastTeleportMs < lag.teleportGraceMs()
                || nowMs - data.lastRespawnMs < lag.respawnGraceMs()
                || nowMs - data.lastWorldChangeMs < lag.worldChangeGraceMs()
                || nowMs - data.lastGamemodeChangeMs < lag.gamemodeChangeGraceMs()
                || nowMs - data.lastVelocityMs < lag.velocityGraceMs()
                || nowMs - data.lastImpulseMs < lag.velocityGraceMs()
                || nowMs - data.lastVehicleMs < lag.vehicleExitGraceMs()
                || nowMs - data.lastFlyingMs < lag.vehicleExitGraceMs()
                || nowMs - data.lastGlideMs < lag.elytraGraceMs()
                || nowMs - data.lastRiptideMs < lag.riptideGraceMs();
    }

    /**
     * Grace after the server moved the player (join, teleport, respawn, world change): the
     * client may still act from its previous position for a moment.
     */
    public boolean recentlyRelocated(PlayerData data, long nowMs) {
        Settings.Lag lag = settings.get().lag();
        return nowMs - data.joinMs < lag.joinGraceMs()
                || nowMs - data.lastTeleportMs < lag.teleportGraceMs()
                || nowMs - data.lastRespawnMs < lag.respawnGraceMs()
                || nowMs - data.lastWorldChangeMs < lag.worldChangeGraceMs();
    }

    /** Whether pistons, explosions or wind charges were active near the location recently. */
    public boolean isDisturbed(Location location, long nowMs) {
        Settings.Lag lag = settings.get().lag();
        World world = location.getWorld();
        return world != null && disturbances.isNear(world, location.getX(), location.getZ(),
                lag.disturbanceRadius(), nowMs, lag.disturbanceGraceMs());
    }

    /** Whether an entity a player can stand on (boat, shulker, happy ghast...) is close by. */
    public boolean nearPlatformEntity(Player player) {
        var names = settings.get().general().platformEntities();
        if (names.isEmpty()) {
            return false;
        }
        for (Entity entity : player.getNearbyEntities(2.0, 3.0, 2.0)) {
            if (Glob.containsAny(names, entity.getType().name())) {
                return true;
            }
        }
        return false;
    }

    /** Paper only: false when the client does not have the chunk it is in (it then hovers/sinks slowly). */
    public boolean isChunkSent(Player player, Location location) {
        return compat.isChunkSent(player, WorldProbe.floor(location.getX()) >> 4, WorldProbe.floor(location.getZ()) >> 4);
    }

    /** Eye heights that may apply this tick (the pose can change within a tick). */
    public double[] eyeHeights(Player player) {
        double current = player.getEyeHeight();
        return new double[] {current, Physics.STANDING_EYE_HEIGHT, Physics.SNEAKING_EYE_HEIGHT};
    }

    public boolean mitigationAllowed(CheckSettings check) {
        return check.mitigate() && !settings.get().general().passiveMode();
    }

    // ---- output --------------------------------------------------------------------------

    public double flag(Player player, PlayerData data, CheckType type, String detail) {
        return violations.flag(player, data, type, detail);
    }

    public void debug(Player player, CheckType type, Supplier<String> message) {
        debug.debug(player, type, message);
    }

    public boolean isDebugging(Player player) {
        return debug.isWatched(player.getUniqueId());
    }

    // ---- circuit breaker -----------------------------------------------------------------

    /**
     * Runs check code. An exception never propagates into the event pipeline; a check
     * that keeps failing is disabled until the next reload instead of spamming the log.
     */
    public void run(CheckType type, long nowMs, Runnable body) {
        if (brokenChecks.containsKey(type)) {
            return;
        }
        try {
            body.run();
        } catch (Throwable t) {
            recordError(type, nowMs, t);
        }
    }

    private void recordError(CheckType type, long nowMs, Throwable error) {
        Long start = errorWindowStart.get(type);
        int[] count = errorCounts.computeIfAbsent(type, key -> new int[1]);
        if (start == null || nowMs - start > ERROR_WINDOW_MS) {
            errorWindowStart.put(type, nowMs);
            count[0] = 0;
        }
        count[0]++;
        if (count[0] == 1) {
            logger.log(Level.WARNING, "Check " + type.displayName() + " threw an exception (the event was not affected)",
                    error);
        }
        if (count[0] >= MAX_ERRORS_PER_WINDOW) {
            brokenChecks.put(type, Boolean.TRUE);
            logger.severe("Check " + type.displayName() + " failed " + count[0]
                    + " times within a minute and was disabled until the next /ac reload.");
        }
    }

    public boolean isBroken(CheckType type) {
        return brokenChecks.containsKey(type);
    }

    /** Clears the circuit breaker (on reload). */
    public void resetBreakers() {
        brokenChecks.clear();
        errorCounts.clear();
        errorWindowStart.clear();
    }
}
