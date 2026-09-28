package io.github.drepfy.vigil.check.combat;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.model.AngleMath;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Hitting a target that is far outside the attacker's view (primitive kill-aura).
 *
 * <p>The client sends an attack before the rotation of the same tick, so the
 * rotation the server knows at attack time can be one tick old. A suspicious hit is
 * therefore re-evaluated on the next server tick against the whole rotation arc
 * between the old and the new rotation; only if the target was outside the view
 * along the entire arc does the hit count as suspicious.
 */
public final class HitAngleCheck {

    private static final CheckType TYPE = CheckType.HIT_ANGLE;
    private static final int MAX_PENDING = 512;

    private record Pending(UUID attacker, AttackSnapshot hit) {
    }

    private final CheckContext ctx;
    private final List<Pending> pending = new ArrayList<>();

    public HitAngleCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onAttack(Player attacker, PlayerData data, AttackSnapshot hit, long now) {
        if (!ctx.isActive(attacker, data, TYPE, now)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        if (hit.distance() < settings.num("min-distance")) {
            return;
        }
        double angle = arcAngle(hit, hit.yaw(), hit.pitch());
        if (angle <= settings.num("max-angle")) {
            data.buffer(TYPE).reduce(0.5);
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
        if (!ctx.isActive(attacker, data, TYPE, now) || !ctx.canFlag(attacker, data, now)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        Location location = attacker.getLocation();
        double angle = arcAngle(hit, location.getYaw(), location.getPitch());
        ctx.debug(attacker, TYPE, () -> "angle over rotation arc=" + Text.num(angle) + " max="
                + Text.num(settings.num("max-angle")));
        if (angle <= settings.num("max-angle")) {
            data.buffer(TYPE).reduce(0.5);
            return;
        }
        double buffer = data.buffer(TYPE).add(1.0, now, 0.02);
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        data.buffer(TYPE).reset();
        ctx.flag(attacker, data, TYPE, "hit a target " + Text.num(angle) + " degrees outside the view at "
                + Text.num(hit.distance()) + " blocks");
    }

    private static double arcAngle(AttackSnapshot hit, double yaw2, double pitch2) {
        double best = 180.0;
        for (double eyeY : hit.eyeYs()) {
            best = Math.min(best, AngleMath.minAngleOverArc(hit.eyeX(), eyeY, hit.eyeZ(),
                    hit.yaw(), hit.pitch(), yaw2, pitch2, hit.tolerantBoxes()));
            if (best <= 0.0) {
                break;
            }
        }
        return best;
    }

    public void clear() {
        pending.clear();
    }
}
