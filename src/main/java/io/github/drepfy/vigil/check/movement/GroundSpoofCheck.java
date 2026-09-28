package io.github.drepfy.vigil.check.movement;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.env.WorldProbe;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * The client claims to be on the ground while the server sees nothing to stand on
 * (NoFall-style cheats). The "on ground" value comes straight from the client's
 * movement packet, so it is compared with a server-side collision scan that is
 * lenient on purpose (half a block below, a third of a block to each side).
 *
 * <p>Before flagging, the blocks around the player are re-sent once: if the client
 * was standing on a desynchronised "ghost block", it disappears and the player
 * falls, which ends the suspicion instead of producing a false positive.
 */
public final class GroundSpoofCheck {

    private static final CheckType TYPE = CheckType.GROUND_SPOOF;
    private static final long ENVIRONMENT_GRACE_MS = 1000;
    private static final long RESYNC_INTERVAL_MS = 3000;

    private final CheckContext ctx;

    public GroundSpoofCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public boolean onMove(Player player, PlayerData data, Location from, Location to, boolean exemptState, long now) {
        if (!ctx.isActive(player, data, TYPE, now) || exemptState || ctx.inMovementGrace(data, now)) {
            data.buffer(TYPE).reset();
            return false;
        }
        @SuppressWarnings("deprecation")
        boolean claimsGround = player.isOnGround();
        if (!claimsGround) {
            data.buffer(TYPE).decay(now, 0.5);
            return false;
        }
        if (now - data.lastLiquidMs < ENVIRONMENT_GRACE_MS || now - data.lastClimbMs < ENVIRONMENT_GRACE_MS
                || now - data.lastSlowingMs < ENVIRONMENT_GRACE_MS) {
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
        ctx.flag(player, data, TYPE, "claimed ground " + (int) threshold + "x with no block within "
                + Text.num(settings.num("vertical-tolerance")) + " below");
        return ctx.mitigationAllowed(settings);
    }
}
