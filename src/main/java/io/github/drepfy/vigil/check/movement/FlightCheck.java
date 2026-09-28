package io.github.drepfy.vigil.check.movement;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.env.WorldProbe;
import io.github.drepfy.vigil.model.FlightTracker;
import io.github.drepfy.vigil.model.Physics;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * Hovering, gliding and flying up without support. Runs once per server tick from
 * the sampler with the amount of client time that passed (see {@link FlightTracker}).
 *
 * <p>A verdict is never flagged immediately. The first one re-sends the surrounding
 * blocks (fixing any ghost-block desync) and starts a confirmation period; only a
 * second verdict after {@code confirm-ms} of continued airtime, without touching
 * anything in between, becomes a flag.
 */
public final class FlightCheck {

    private static final CheckType TYPE = CheckType.FLIGHT;
    /** Horizontal support tolerance beyond the hitbox. */
    private static final double SUPPORT_HORIZONTAL = 0.3;
    /** Vertical support tolerance below the feet. */
    private static final double SUPPORT_DOWN = 0.6;
    /** Gravity attribute below this is considered modified (default 0.08). */
    private static final double MIN_NORMAL_GRAVITY = 0.075;
    /** Blocks can change around a player who does not move; rescan at least this often. */
    private static final long SURROUNDINGS_MAX_AGE_MS = 1000;

    private final CheckContext ctx;

    public FlightCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    /**
     * @param location current player location (reused object, do not keep)
     * @param activeMs client time since the previous sample (0 if the client sent nothing)
     * @param positionChanged whether the position differs from the previous sample
     * @return true when the player should be set back
     */
    public boolean onSample(Player player, PlayerData data, Location location, double activeMs,
                            boolean positionChanged, long now) {
        double y = location.getY();
        if (!ctx.isActive(player, data, TYPE, now)) {
            reset(data, y);
            return false;
        }
        if (ctx.isMovementExemptState(player, data, now) || ctx.inMovementGrace(data, now)) {
            reset(data, y);
            return false;
        }

        World world = location.getWorld();
        int flags;
        if (!positionChanged && data.surroundingsValid && now - data.surroundingsMs < SURROUNDINGS_MAX_AGE_MS) {
            flags = data.cachedSurroundings;
        } else {
            flags = ctx.probe().scan(world, location.getX(), y, location.getZ(), player.getWidth(), player.getHeight(),
                    SUPPORT_HORIZONTAL, SUPPORT_DOWN);
            data.cachedSurroundings = flags;
            data.surroundingsValid = true;
            data.surroundingsMs = now;
        }
        boolean supported = (flags & WorldProbe.NOT_AIRBORNE) != 0;
        boolean bouncy = (flags & WorldProbe.BOUNCY) != 0;
        if ((flags & WorldProbe.LIQUID) != 0) {
            data.lastLiquidMs = now;
        }
        if ((flags & WorldProbe.CLIMBABLE) != 0) {
            data.lastClimbMs = now;
        }
        if ((flags & WorldProbe.SLOWING) != 0) {
            data.lastSlowingMs = now;
        }
        if (bouncy) {
            data.lastBouncyMs = now;
        }

        CheckSettings settings = ctx.settings(TYPE);
        FlightTracker.Params params = params(settings);
        if (supported) {
            // The common case: no potion or attribute lookups needed.
            data.flight.sample(y, true, bouncy, activeMs, 0.0, params);
            clearSuspicion(data);
            return false;
        }

        ServerCompat compat = ctx.compat();
        if (compat.hasEffect(player, ServerCompat.Effect.LEVITATION)
                || compat.hasEffect(player, ServerCompat.Effect.SLOW_FALLING)
                || compat.attributeOr(player, ServerCompat.Attr.GRAVITY, Physics.GRAVITY) < MIN_NORMAL_GRAVITY) {
            reset(data, y);
            return false;
        }
        double apex = Physics.jumpApex(Physics.jumpVelocity(
                compat.attributeOr(player, ServerCompat.Attr.JUMP_STRENGTH, Physics.DEFAULT_JUMP_STRENGTH),
                compat.amplifier(player, ServerCompat.Effect.JUMP_BOOST)));
        FlightTracker.Verdict verdict = data.flight.sample(y, false, bouncy, activeMs, apex, params);
        if (data.flightSuspectSinceMs >= 0) {
            data.flightConfirmActiveMs += activeMs;
        }
        if (verdict == FlightTracker.Verdict.NONE) {
            // Give up on a suspicion that was not confirmed within a generous period.
            if (data.flightSuspectSinceMs >= 0
                    && data.flightConfirmActiveMs > settings.num("confirm-ms") + 3 * settings.num("hover-window-ms")) {
                clearSuspicion(data);
            }
            return false;
        }

        String detail = verdict == FlightTracker.Verdict.HOVER
                ? "descended " + Text.num(data.flight.lastMeasured()) + " blocks in "
                        + Text.num(settings.num("hover-window-ms") / 1000.0) + "s of airtime (min "
                        + Text.num(data.flight.lastAllowed()) + ")"
                : "rose " + Text.num(data.flight.lastMeasured()) + " blocks above takeoff (max "
                        + Text.num(data.flight.lastAllowed()) + ")";
        ctx.debug(player, TYPE, () -> verdict + ": " + detail + " confirm=" + Text.num(data.flightConfirmActiveMs));

        if (data.flightSuspectSinceMs < 0) {
            // First verdict: remove any ghost blocks and wait for confirmation.
            data.flightSuspectSinceMs = now;
            data.flightConfirmActiveMs = 0;
            data.flightSuspectDetail = detail;
            ctx.probe().resyncAround(player, world, location.getX(), y, location.getZ());
            return false;
        }
        if (data.flightConfirmActiveMs < settings.num("confirm-ms")) {
            return false;
        }

        clearSuspicion(data);
        if (!ctx.canFlag(player, data, now) || ctx.isDisturbed(location, now) || !ctx.isChunkSent(player, location)
                || ctx.nearPlatformEntity(player)) {
            data.flight.impulse(y);
            return false;
        }
        double buffer = data.buffer(TYPE).add(1.0, now, 0.05);
        if (buffer < settings.bufferThreshold()) {
            return false;
        }
        data.buffer(TYPE).reset();
        ctx.flag(player, data, TYPE, detail);
        return ctx.mitigationAllowed(settings);
    }

    private CheckSettings paramsSource;
    private FlightTracker.Params cachedParams;

    /** Parameters for the current settings snapshot, rebuilt only after a reload. */
    private FlightTracker.Params params(CheckSettings settings) {
        if (settings != paramsSource) {
            cachedParams = new FlightTracker.Params(settings.num("hover-window-ms"), settings.num("min-descent"),
                    settings.num("ascend-tolerance"), settings.num("impulse-max-rise-ms"));
            paramsSource = settings;
        }
        return cachedParams;
    }

    private static void reset(PlayerData data, double y) {
        data.flight.reset(y, true);
        clearSuspicion(data);
    }

    private static void clearSuspicion(PlayerData data) {
        data.flightSuspectSinceMs = -1;
        data.flightConfirmActiveMs = 0;
    }
}
