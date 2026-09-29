package io.github.drepfy.vigil.check.movement;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.env.WorldProbe;
import io.github.drepfy.vigil.model.Physics;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

/**
 * Anti-knockback. When the server knocks a player who stands on open ground upwards,
 * a vanilla client must rise: nothing can stop the vertical part of knockback except
 * a ceiling, liquids, cobwebs, ladders and similar blocks (all of which exempt the
 * check). The player has {@code ping + window-extra-ms} of client activity to rise at
 * least {@code min-ratio} of the height the knockback would carry them.
 */
public final class VelocityCheck {

    private static final CheckType TYPE = CheckType.VELOCITY;
    /** Knockback weaker than this (vertical blocks/tick) is ignored. */
    private static final double MIN_VERTICAL = 0.15;
    /** Blocks above the head that must be free. */
    private static final double HEADROOM = 1.5;

    private final CheckContext ctx;

    public VelocityCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    /** Called for every velocity the server sends to the player (after other plugins changed it). */
    public void onVelocity(Player player, PlayerData data, Vector velocity, long now) {
        if (data.velocityRequiredRise > 0.0) {
            // Still judging an earlier knockback: in a combo every hit would otherwise
            // restart the wait and no verdict would ever be reached.
            return;
        }
        if (velocity.getY() < MIN_VERTICAL || !ctx.isActive(player, data, TYPE, now)
                || ctx.isMovementExemptState(player, data, now) || ctx.recentlyRelocated(data, now)
                || now - data.lastVehicleMs < 1000 || now - data.lastGlideMs < 1000 || now - data.lastRiptideMs < 1000
                || now - data.lastFlyingMs < 1000) {
            return;
        }
        ServerCompat compat = ctx.compat();
        if (compat.hasEffect(player, ServerCompat.Effect.LEVITATION)
                || compat.hasEffect(player, ServerCompat.Effect.SLOW_FALLING)
                || compat.attributeOr(player, ServerCompat.Attr.GRAVITY, Physics.GRAVITY) < 0.075) {
            return;
        }
        Location location = player.getLocation();
        World world = location.getWorld();
        WorldProbe probe = ctx.probe();
        double width = player.getWidth();
        double height = player.getHeight();
        int flags = probe.scan(world, location.getX(), location.getY(), location.getZ(), width, height, 0.0, 0.05);
        if ((flags & WorldProbe.SUPPORT) == 0 || (flags & (WorldProbe.LIQUID | WorldProbe.CLIMBABLE
                | WorldProbe.SLOWING | WorldProbe.BOUNCY | WorldProbe.PISTON | WorldProbe.UNKNOWN)) != 0) {
            return;
        }
        // The scan's fast path only answers "standing on a full block"; water, cobwebs and
        // ladders around the body damp knockback too.
        if (probe.bodyFlags(world, location.getX(), location.getY(), location.getZ(), width, height) != 0) {
            return;
        }
        if (probe.hasBlockAbove(world, location.getX(), location.getY() + height, location.getZ(), width, HEADROOM)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        double apex = Physics.jumpApex(velocity.getY());
        data.velocityStartY = location.getY();
        data.velocityMaxY = location.getY();
        data.velocityRequiredRise = apex * settings.num("min-ratio");
        data.velocityActiveMs = 0.0;
        data.velocityWindowMs = ctx.recentPing(player, data, now) + settings.num("window-extra-ms");
    }

    /** Called every server tick with the client time that passed. */
    public void onSample(Player player, PlayerData data, double y, double activeMs, long now) {
        if (data.velocityRequiredRise <= 0.0) {
            return;
        }
        data.velocityMaxY = Math.max(data.velocityMaxY, y);
        data.velocityActiveMs += activeMs;
        double rise = data.velocityMaxY - data.velocityStartY;
        if (rise >= data.velocityRequiredRise) {
            data.velocityRequiredRise = 0.0;
            data.buffer(TYPE).reduce(0.5);
            return;
        }
        if (data.velocityActiveMs < data.velocityWindowMs) {
            return;
        }
        double required = data.velocityRequiredRise;
        data.velocityRequiredRise = 0.0;
        Location location = player.getLocation();
        if (!ctx.isActive(player, data, TYPE, now) || ctx.isMovementExemptState(player, data, now)
                || ctx.recentlyRelocated(data, now) || now - data.lastLiquidMs < 1000 || now - data.lastClimbMs < 1000
                || now - data.lastSlowingMs < 1000 || !ctx.canFlag(player, data, now)
                || ctx.isDisturbed(location, now) || ctx.nearPlatformEntity(player)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        double buffer = data.buffer(TYPE).add(1.0, now, 0.05);
        ctx.debug(player, TYPE, () -> "rose " + Text.num(rise) + " of " + Text.num(required) + ", buffer "
                + Text.num(buffer));
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        data.buffer(TYPE).reset();
        ctx.flag(player, data, TYPE, "ignored knockback: rose " + Text.num(rise) + " blocks, at least "
                + Text.num(required) + " expected");
    }
}
