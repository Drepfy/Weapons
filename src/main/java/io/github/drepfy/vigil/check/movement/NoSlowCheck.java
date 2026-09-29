package io.github.drepfy.vigil.check.movement;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.model.Physics;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.entity.Player;

/**
 * Moving at full speed while using an item (eating, drinking, blocking with a shield,
 * drawing a bow...). Vanilla multiplies movement input by 0.2 while an item is in
 * use, which caps ground speed at about 0.06 blocks per tick; the default limit is
 * twice that. Only a window that lies completely inside the item use (after a grace
 * period for momentum and shifted back by the ping) is measured, so starting or
 * stopping to eat never counts.
 */
public final class NoSlowCheck {

    private static final CheckType TYPE = CheckType.NOSLOW;
    /** Length of the measured window. */
    private static final long WINDOW_MS = 500;
    /** Minimum time between two measurements. */
    private static final long SAMPLE_INTERVAL_MS = 250;
    private static final long ENVIRONMENT_GRACE_MS = 1500;

    private final CheckContext ctx;

    public NoSlowCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    /** Called every server tick. */
    public void onSample(Player player, PlayerData data, long now) {
        boolean using;
        try {
            using = player.isHandRaised();
        } catch (LinkageError | RuntimeException e) {
            using = false;
        }
        if (!using) {
            data.itemUseSinceMs = -1;
            return;
        }
        if (data.itemUseSinceMs < 0) {
            data.itemUseSinceMs = now;
            return;
        }
        if (now - data.lastNoSlowSampleMs < SAMPLE_INTERVAL_MS || !ctx.isActive(player, data, TYPE, now)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        long lag = ctx.recentPing(player, data, now) + 100L;
        long windowEnd = now - lag;
        long windowStart = windowEnd - WINDOW_MS;
        if (windowStart < data.itemUseSinceMs + settings.millis("grace-ms")) {
            return;
        }
        data.lastNoSlowSampleMs = now;
        if (ctx.isMovementExemptState(player, data, now) || ctx.inMovementGrace(data, now)
                || now - data.lastLiquidMs < ENVIRONMENT_GRACE_MS || now - data.lastIceMs < ENVIRONMENT_GRACE_MS
                || now - data.lastSlimeMs < ENVIRONMENT_GRACE_MS || now - data.lastBouncyMs < ENVIRONMENT_GRACE_MS
                || now - data.lastSoulMs < ENVIRONMENT_GRACE_MS || now - data.lastClimbMs < ENVIRONMENT_GRACE_MS) {
            return;
        }
        double average = data.positions.averageHorizontalSpeed(windowStart, windowEnd);
        if (Double.isNaN(average)) {
            return;
        }
        double limit = settings.num("max-speed") * speedFactor(player);
        if (average <= limit) {
            data.buffer(TYPE).reduce(0.25);
            return;
        }
        if (!ctx.canFlag(player, data, now) || ctx.isDisturbed(player.getLocation(), now)
                || ctx.nearPlatformEntity(player)) {
            return;
        }
        double buffer = data.buffer(TYPE).add(1.0, now, 0.1);
        ctx.debug(player, TYPE, () -> "speed " + Text.num(average) + "/t while using an item, limit "
                + Text.num(limit) + ", buffer " + Text.num(buffer));
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        data.buffer(TYPE).reset();
        ctx.flag(player, data, TYPE, "moved " + Text.num(average * 20.0) + " m/s while using an item (max "
                + Text.num(limit * 20.0) + ")");
    }

    private double speedFactor(Player player) {
        ServerCompat compat = ctx.compat();
        double factor = 1.0;
        double attribute = compat.attribute(player, ServerCompat.Attr.MOVEMENT_SPEED);
        if (!Double.isNaN(attribute) && attribute > 0) {
            factor = Math.max(factor, attribute / Physics.DEFAULT_MOVEMENT_SPEED);
        }
        int amplifier = compat.amplifier(player, ServerCompat.Effect.SPEED);
        if (amplifier >= 0) {
            factor = Math.max(factor, 1.0 + Physics.SPEED_POTION_PER_LEVEL * (amplifier + 1));
        }
        return Math.max(factor, player.getWalkSpeed() / Physics.DEFAULT_WALK_SPEED);
    }
}
