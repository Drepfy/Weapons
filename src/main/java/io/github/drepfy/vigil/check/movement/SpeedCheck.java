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
 * Horizontal speed. The player's travelled distance is compared with the maximum
 * legitimate speed over roughly the last second of real time (see
 * {@link io.github.drepfy.vigil.model.SpeedBudget}). The limit starts at vanilla
 * sprint-jumping speed and is raised by speed effects/attributes and by recently
 * touched surfaces that allow faster movement (ice, slime, soul speed blocks, a
 * ceiling that enables head-hitter jumps), then multiplied by the leniency.
 */
public final class SpeedCheck {

    private static final CheckType TYPE = CheckType.SPEED;
    /** Liquids change movement completely; skip this long after touching one. */
    private static final long LIQUID_GRACE_MS = 1000;

    private final CheckContext ctx;

    public SpeedCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    /**
     * @return true when the check asks the listener to set the player back
     */
    public boolean onMove(Player player, PlayerData data, Location from, Location to, boolean exemptState, long now) {
        if (!ctx.isActive(player, data, TYPE, now)) {
            data.speed.restart(now);
            return false;
        }
        if (exemptState || ctx.inMovementGrace(data, now) || now - data.lastLiquidMs < LIQUID_GRACE_MS) {
            data.speed.restart(now);
            return false;
        }
        CheckSettings settings = ctx.settings(TYPE);
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double distance = Math.sqrt(dx * dx + dz * dz);

        double limit = limitPerTick(player, data, settings, now);
        double capacity = limit * settings.num("burst-ticks") + settings.num("extra-blocks");
        double deficit = data.speed.consume(now, distance, limit, capacity);

        if (ctx.isDebugging(player)) {
            ctx.debug(player, TYPE, () -> "dist=" + Text.num(distance) + " limit=" + Text.num(limit)
                    + "/t balance=" + Text.num(data.speed.balance()) + "/" + Text.num(capacity)
                    + " bonus=" + Text.num(data.speed.bonus()));
        }
        if (deficit <= 0.0) {
            return false;
        }

        // The budget is exhausted: only now run the more expensive/uncertain exemptions.
        if (!ctx.canFlag(player, data, now) || ctx.isDisturbed(to, now)) {
            data.speed.refill(capacity);
            return false;
        }

        double averageSpeed = data.positions.averageHorizontalSpeed(now - 1000);
        data.speed.refill(capacity);
        double buffer = data.buffer(TYPE).add(1.0, now, 0.05);
        if (buffer < settings.bufferThreshold()) {
            return false;
        }
        data.buffer(TYPE).reset();

        String detail = "over by " + Text.num(deficit) + " blocks, avg " + Text.num(averageSpeed * 20.0)
                + " m/s, limit " + Text.num(limit * 20.0) + " m/s";
        ctx.flag(player, data, TYPE, detail);
        return ctx.mitigationAllowed(settings);
    }

    /** Maximum legitimate horizontal distance per 50 ms for this player right now. */
    double limitPerTick(Player player, PlayerData data, CheckSettings settings, long now) {
        long memory = settings.millis("surface-memory-ms");
        double surface = 1.0;
        if (now - data.lastIceMs < memory) {
            surface = Math.max(surface, settings.num("ice-multiplier"));
        }
        if (now - data.lastSlimeMs < memory) {
            surface = Math.max(surface, settings.num("slime-multiplier"));
        }
        if (now - data.lastSoulMs < memory) {
            surface = Math.max(surface, settings.num("soul-speed-multiplier"));
        }
        if (now - data.lastCeilingMs < memory) {
            surface = Math.max(surface, settings.num("ceiling-multiplier"));
        }
        return Physics.SPRINT_JUMP_SPEED * surface * speedFactor(player) * settings.num("leniency");
    }

    /**
     * How much faster than a default player this player may move, from the movement
     * speed attribute (includes potions and item modifiers on modern servers), the
     * Speed effect (for servers without the attribute) and the Bukkit walk speed.
     */
    private double speedFactor(Player player) {
        ServerCompat compat = ctx.compat();
        double factor = 1.0;
        double attribute = compat.attribute(player, ServerCompat.Attr.MOVEMENT_SPEED);
        if (!Double.isNaN(attribute) && attribute > 0) {
            double base = player.isSprinting() ? Physics.DEFAULT_MOVEMENT_SPEED * 1.3 : Physics.DEFAULT_MOVEMENT_SPEED;
            factor = Math.max(factor, attribute / base);
        }
        int speedAmplifier = compat.amplifier(player, ServerCompat.Effect.SPEED);
        if (speedAmplifier >= 0) {
            factor = Math.max(factor, 1.0 + Physics.SPEED_POTION_PER_LEVEL * (speedAmplifier + 1));
        }
        factor = Math.max(factor, player.getWalkSpeed() / Physics.DEFAULT_WALK_SPEED);
        return factor;
    }
}
