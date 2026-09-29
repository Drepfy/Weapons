package io.github.drepfy.vigil.check.movement;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.model.Physics;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Location;
import org.bukkit.entity.Player;

/**
 * Impossible upward movement within a single move (step/high-jump cheats).
 *
 * <p>In vanilla, one tick can raise a player by at most the larger of their jump
 * velocity (0.42 + 0.1 per Jump Boost level, or the jump_strength attribute) and
 * their step height (0.6 or the step_height attribute). Bukkit merges moves smaller
 * than 1/16 block into the next event, so that much is added as well.
 */
public final class StepCheck {

    private static final CheckType TYPE = CheckType.STEP;
    private static final long ENVIRONMENT_GRACE_MS = 750;
    private static final long BOUNCE_GRACE_MS = 2500;

    private final CheckContext ctx;

    public StepCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public boolean onMove(Player player, PlayerData data, Location from, Location to, boolean exemptState, long now) {
        if (!ctx.isActive(player, data, TYPE, now) || exemptState || ctx.inMovementGrace(data, now)) {
            return false;
        }
        double dy = to.getY() - from.getY();
        if (dy <= 0.0) {
            return false;
        }
        if (now - data.lastLiquidMs < ENVIRONMENT_GRACE_MS
                || now - data.lastClimbMs < ENVIRONMENT_GRACE_MS
                || now - data.lastSlowingMs < ENVIRONMENT_GRACE_MS
                || now - data.lastBouncyMs < BOUNCE_GRACE_MS) {
            return false;
        }
        // The flight tracker knows about launches (bounces, leaving water/elytra...), but only
        // while the flight check runs for this player; otherwise it is always reset.
        if (ctx.isActive(player, data, CheckType.FLIGHT, now) && data.flight.hasImpulse()) {
            return false;
        }
        ServerCompat compat = ctx.compat();
        if (compat.hasEffect(player, ServerCompat.Effect.LEVITATION)) {
            return false;
        }
        CheckSettings settings = ctx.settings(TYPE);
        double jump = Physics.jumpVelocity(
                compat.attributeOr(player, ServerCompat.Attr.JUMP_STRENGTH, Physics.DEFAULT_JUMP_STRENGTH),
                compat.amplifier(player, ServerCompat.Effect.JUMP_BOOST));
        double step = compat.attributeOr(player, ServerCompat.Attr.STEP_HEIGHT, Physics.DEFAULT_STEP_HEIGHT);
        double max = Physics.maxSingleTickAscent(jump, step) + Physics.MOVE_EVENT_THRESHOLD + settings.num("tolerance");
        if (dy <= max) {
            return false;
        }
        if (!ctx.canFlag(player, data, now) || ctx.isDisturbed(to, now) || ctx.nearPlatformEntity(player)) {
            return false;
        }
        double buffer = data.buffer(TYPE).add(1.0, now, 0.1);
        ctx.debug(player, TYPE, () -> "dy=" + Text.num(dy) + " max=" + Text.num(max) + " buffer=" + Text.num(buffer));
        if (buffer < settings.bufferThreshold()) {
            return false;
        }
        data.buffer(TYPE).reset();
        ctx.flag(player, data, TYPE, "rose " + Text.num(dy) + " blocks in one move (max " + Text.num(max) + ")");
        return ctx.mitigationAllowed(settings);
    }
}
