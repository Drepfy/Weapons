package io.github.drepfy.vigil.check.combat;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.model.AngleMath;
import io.github.drepfy.vigil.model.Box;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Kill aura, detected two ways:
 * <ol>
 *   <li><b>Hitbox</b>: a vanilla client attacks whatever its crosshair points at. The
 *   attack is sent before the rotation of the same tick, so it is judged one tick
 *   later against that rotation (and, leniently, every rotation in between): the look
 *   ray from the exact eye position must pass through the target's lag-compensated
 *   hitbox, grown by {@code hitbox-expand}. Auras that hit without looking fail.</li>
 *   <li><b>Multi-target</b>: the crosshair target is chosen once per client tick, so
 *   two different entities attacked within one client tick is impossible (needs
 *   Paper's client tick events; skipped elsewhere).</li>
 * </ol>
 */
public final class KillAuraCheck {

    private static final CheckType TYPE = CheckType.KILLAURA;
    private static final int MAX_PENDING = 512;
    private static final long CLIENT_TICK_FRESHNESS_MS = 1000;
    /** Each this many degrees of miss adds 1 to a flag's weight. */
    private static final double DEGREES_PER_EXTRA_WEIGHT = 30.0;

    private record Pending(UUID attacker, AttackSnapshot hit) {
    }

    private final CheckContext ctx;
    private final List<Pending> pending = new ArrayList<>();

    public KillAuraCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onAttack(Player attacker, PlayerData data, AttackSnapshot hit, int targetId, long now) {
        if (!ctx.isActive(attacker, data, TYPE, now) || ctx.recentlyRelocated(data, now)) {
            return;
        }
        if (ctx.settings().general().useClientTickEvents() && now - data.lastClientTickMs < CLIENT_TICK_FRESHNESS_MS) {
            if (data.lastAttackClientTick == data.clientTick && data.lastAttackTargetId != targetId) {
                violation(attacker, data, now, 1.5, "attacked two different entities within one tick", 1.0);
            }
            data.lastAttackClientTick = data.clientTick;
            data.lastAttackTargetId = targetId;
        }
        if (hit.distance() < ctx.settings(TYPE).num("min-distance")) {
            return;
        }
        if (pending.size() < MAX_PENDING) {
            pending.add(new Pending(attacker.getUniqueId(), hit));
        }
    }

    /** Called once per tick, after the attack tick's rotation has arrived. */
    public void processPending(long now) {
        if (pending.isEmpty()) {
            return;
        }
        List<Pending> batch = new ArrayList<>(pending);
        pending.clear();
        for (Pending entry : batch) {
            Player attacker = Bukkit.getPlayer(entry.attacker());
            if (attacker == null) {
                continue;
            }
            PlayerData data = ctx.players().peek(attacker.getUniqueId());
            if (data == null) {
                continue;
            }
            ctx.run(TYPE, now, () -> evaluate(attacker, data, entry.hit(), now));
        }
    }

    private void evaluate(Player attacker, PlayerData data, AttackSnapshot hit, long now) {
        if (!ctx.isActive(attacker, data, TYPE, now)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        Location location = attacker.getLocation();
        double expand = settings.num("hitbox-expand");
        List<Box> boxes = new ArrayList<>();
        for (Box box : hit.tolerantBoxes()) {
            boxes.add(box.expand(expand));
        }
        double angle = 180.0;
        for (double eyeY : hit.eyeYs()) {
            angle = Math.min(angle, AngleMath.minAngleOverArc(hit.eyeX(), eyeY, hit.eyeZ(),
                    hit.yaw(), hit.pitch(), location.getYaw(), location.getPitch(), boxes));
            if (angle <= 0.0) {
                break;
            }
        }
        if (angle <= 0.0) {
            data.buffer(TYPE).reduce(0.25);
            return;
        }
        double missed = angle;
        ctx.debug(attacker, TYPE, () -> "look ray missed the target by " + Text.num(missed) + " degrees");
        // Missing the target by a wide angle (hitting someone behind you) is never aim or lag.
        violation(attacker, data, now, 1.0, "hit a target it was not looking at (off by " + Text.num(missed)
                + " degrees, " + Text.num(hit.distance()) + " blocks away)", 1.0 + missed / DEGREES_PER_EXTRA_WEIGHT);
    }

    /**
     * @param weight   how much this adds to the suspicion buffer
     * @param severity how much the flag counts towards a ban (1-3)
     */
    private void violation(Player attacker, PlayerData data, long now, double weight, String detail,
                           double severity) {
        if (!ctx.canFlag(attacker, data, now)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        double buffer = data.buffer(TYPE).add(weight, now, 0.05);
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        data.buffer(TYPE).reset();
        ctx.flag(attacker, data, TYPE, detail, severity);
    }

    public void clear() {
        pending.clear();
    }
}
