package io.github.drepfy.vigil.check.movement;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.env.WorldProbe;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

/**
 * NoFall, detected two independent ways:
 * <ol>
 *   <li><b>Ground spoof</b>: the client claims to stand on the ground (a value taken
 *   straight from its movement packet) while the server finds nothing to stand on.
 *   Before flagging, the nearby blocks are re-sent once so a ghost-block desync
 *   ends the suspicion instead of producing a false positive.</li>
 *   <li><b>Missing fall damage</b>: the server watched the player fall from high
 *   enough to get hurt and land on an ordinary block, but no fall damage followed.
 *   Water, ladders, cobwebs, slime, hay, beds, honey, Slow Falling, the fallDamage
 *   game rule, invulnerability, knockback, mace hits and wind charges all cancel it.</li>
 * </ol>
 */
public final class NoFallCheck {

    private static final CheckType TYPE = CheckType.NOFALL;
    private static final long ENVIRONMENT_GRACE_MS = 1000;
    private static final long RESYNC_INTERVAL_MS = 3000;
    /** Blocks beyond the safe fall distance before a missing hurt counts (vanilla hurts after 1). */
    private static final double MIN_EXCESS_FALL = 3.0;
    /** Time to wait for the damage after landing, on top of the ping. */
    private static final long DAMAGE_WAIT_MS = 400;
    /** Other damage this recently can absorb the fall damage (hurt cooldown). */
    private static final long OTHER_DAMAGE_GRACE_MS = 1000;

    private final CheckContext ctx;

    public NoFallCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public boolean onMove(Player player, PlayerData data, Location from, Location to, boolean exemptState, long now) {
        if (!ctx.isActive(player, data, TYPE, now) || exemptState || ctx.inMovementGrace(data, now)) {
            data.buffer(TYPE).reset();
            resetFall(data, to.getY(), now);
            return false;
        }
        if (now - data.lastLiquidMs < ENVIRONMENT_GRACE_MS || now - data.lastClimbMs < ENVIRONMENT_GRACE_MS
                || now - data.lastSlowingMs < ENVIRONMENT_GRACE_MS || now - data.lastBouncyMs < ENVIRONMENT_GRACE_MS) {
            resetFall(data, to.getY(), now);
            return false;
        }
        trackFall(player, data, from, to, now);
        return groundSpoof(player, data, to, now);
    }

    // ---- missing fall damage ------------------------------------------------------------------

    private void trackFall(Player player, PlayerData data, Location from, Location to, long now) {
        double y = to.getY();
        if (Double.isNaN(data.fallPeakY) || y - from.getY() >= 0.0) {
            // Rising or level: no fall in progress (vanilla only counts downward movement).
            resetFall(data, y, now);
            return;
        }
        if (data.fallPeakY - y < MIN_EXCESS_FALL) {
            return;
        }
        ServerCompat compat = ctx.compat();
        if (data.fallPeakY - y - safeFallDistance(player) < MIN_EXCESS_FALL) {
            return;
        }
        if (compat.hasEffect(player, ServerCompat.Effect.SLOW_FALLING)
                || compat.hasEffect(player, ServerCompat.Effect.LEVITATION)) {
            resetFall(data, y, now);
            return;
        }
        World world = to.getWorld();
        int flags = ctx.probe().scan(world, to.getX(), y, to.getZ(), player.getWidth(), player.getHeight(), 0.0, 0.05);
        if ((flags & (WorldProbe.LIQUID | WorldProbe.CLIMBABLE | WorldProbe.SLOWING | WorldProbe.BOUNCY
                | WorldProbe.PISTON | WorldProbe.UNKNOWN)) != 0) {
            resetFall(data, y, now);
            return;
        }
        if ((flags & WorldProbe.SUPPORT) == 0) {
            return;
        }
        // Landed on an ordinary block.
        double fallen = data.fallPeakY - y;
        long fallStart = data.fallPeakMs;
        resetFall(data, y, now);
        if (softLanding(world, to, player.getWidth()) || data.lastFallDamageMs >= fallStart
                || compat.attributeOr(player, ServerCompat.Attr.FALL_DAMAGE_MULTIPLIER, 1.0) < 0.5) {
            return;
        }
        data.pendingFallDistance = fallen;
        data.pendingFallLandingY = y;
        data.pendingFallLandingMs = now;
        data.pendingFallStartMs = fallStart;
    }

    /** Called every tick: decides landings that are still waiting for their fall damage. */
    public void onTick(Player player, PlayerData data, long now) {
        if (data.pendingFallDistance <= 0.0) {
            return;
        }
        int ping = ctx.recentPing(player, data, now);
        if (now - data.pendingFallLandingMs < DAMAGE_WAIT_MS + ping) {
            return;
        }
        double fallen = data.pendingFallDistance;
        data.pendingFallDistance = 0.0;
        if (data.lastFallDamageMs >= data.pendingFallStartMs || !ctx.isActive(player, data, TYPE, now)
                || player.isDead() || player.isInvulnerable() || !fallDamageEnabled(player.getWorld())
                || data.lastDamageMs > data.pendingFallLandingMs - OTHER_DAMAGE_GRACE_MS
                || ctx.compat().amplifier(player, ServerCompat.Effect.RESISTANCE) >= 4
                || player.getLocation().getY() < data.pendingFallLandingY - 0.5) {
            return;
        }
        Location location = player.getLocation();
        if (!ctx.canFlag(player, data, now) || ctx.isDisturbed(location, now) || ctx.nearPlatformEntity(player)
                || ctx.inMovementGrace(data, now)) {
            return;
        }
        // A whole fall without damage is already a clear pattern: no further buffering.
        ctx.flag(player, data, TYPE, "fell " + Text.num(fallen) + " blocks and took no fall damage");
    }

    private double safeFallDistance(Player player) {
        ServerCompat compat = ctx.compat();
        double safe = compat.attributeOr(player, ServerCompat.Attr.SAFE_FALL_DISTANCE, 3.0);
        int jump = compat.amplifier(player, ServerCompat.Effect.JUMP_BOOST);
        return safe + (jump >= 0 ? jump + 1 : 0);
    }

    /** Hay bales cushion falls; checked separately because they are ordinary full blocks otherwise. */
    private boolean softLanding(World world, Location at, double width) {
        double half = width / 2.0;
        int by = WorldProbe.floor(at.getY() - 0.05);
        for (int corner = 0; corner < 4; corner++) {
            int bx = WorldProbe.floor(at.getX() + ((corner & 1) == 0 ? -half : half));
            int bz = WorldProbe.floor(at.getZ() + ((corner & 2) == 0 ? -half : half));
            Material material = ctx.probe().typeAt(world, bx, by, bz);
            if (material == null || material.name().equals("HAY_BLOCK") || material.name().endsWith("_BED")) {
                return true;
            }
        }
        return false;
    }

    private static boolean fallDamageEnabled(World world) {
        try {
            Boolean value = world.getGameRuleValue(GameRule.FALL_DAMAGE);
            return value == null || value;
        } catch (LinkageError | RuntimeException e) {
            return false;
        }
    }

    private static void resetFall(PlayerData data, double y, long now) {
        data.fallPeakY = y;
        data.fallPeakMs = now;
    }

    // ---- ground spoof -------------------------------------------------------------------------

    private boolean groundSpoof(Player player, PlayerData data, Location to, long now) {
        @SuppressWarnings("deprecation")
        boolean claimsGround = player.isOnGround();
        if (!claimsGround) {
            data.buffer(TYPE).decay(now, 0.5);
            return false;
        }
        CheckSettings settings = ctx.settings(TYPE);
        int flags = ctx.probe().scan(to.getWorld(), to.getX(), to.getY(), to.getZ(), player.getWidth(),
                player.getHeight(), settings.num("horizontal-tolerance"), settings.num("vertical-tolerance"));
        if ((flags & WorldProbe.NOT_AIRBORNE) != 0) {
            data.buffer(TYPE).reduce(1.0);
            return false;
        }
        if (!ctx.isChunkSent(player, to)) {
            return false;
        }

        double buffer = data.buffer(TYPE).add(1.0, now, 0.5);
        double threshold = settings.bufferThreshold();
        ctx.debug(player, TYPE, () -> "claims ground with nothing below, buffer=" + Text.num(buffer) + "/"
                + Text.num(threshold));
        if (buffer >= threshold / 2.0 && now - data.lastGroundSpoofResyncMs > RESYNC_INTERVAL_MS) {
            data.lastGroundSpoofResyncMs = now;
            ctx.probe().resyncAround(player, to.getWorld(), to.getX(), to.getY(), to.getZ());
        }
        if (buffer < threshold) {
            return false;
        }
        data.buffer(TYPE).reset();
        if (!ctx.canFlag(player, data, now) || ctx.isDisturbed(to, now) || ctx.nearPlatformEntity(player)) {
            return false;
        }
        ctx.flag(player, data, TYPE, "claimed to be on the ground " + (int) threshold + "x in mid-air");
        return ctx.mitigationAllowed(settings);
    }
}
