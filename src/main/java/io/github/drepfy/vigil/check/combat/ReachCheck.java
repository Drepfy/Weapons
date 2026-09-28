package io.github.drepfy.vigil.check.combat;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.model.Physics;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

/**
 * Melee reach, measured like vanilla (attacker eye to the closest point of the
 * target's hitbox) but against the target's lag-compensated positions. Hits beyond
 * range + leniency add suspicion; hits beyond range + cancel-leniency are physically
 * impossible even with lag and are cancelled when mitigation is on.
 */
public final class ReachCheck {

    private static final CheckType TYPE = CheckType.REACH;

    private final CheckContext ctx;

    public ReachCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onAttack(Player attacker, PlayerData data, AttackSnapshot hit, Cancellable event, long now) {
        if (!ctx.isActive(attacker, data, TYPE, now) || !ctx.canFlag(attacker, data, now)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        double fallback = attacker.getGameMode() == GameMode.CREATIVE ? 5.0 : Physics.DEFAULT_ENTITY_RANGE;
        double range = ctx.compat().attributeOr(attacker, ServerCompat.Attr.ENTITY_INTERACTION_RANGE, fallback);
        double distance = hit.distance();

        ctx.debug(attacker, TYPE, () -> "distance=" + Text.num(distance) + " range=" + Text.num(range)
                + " window=" + hit.windowMs() + "ms candidates=" + hit.targetBoxes().size());

        if (distance > range + settings.num("cancel-leniency") && ctx.mitigationAllowed(settings)) {
            event.setCancelled(true);
        }
        if (distance <= range + settings.num("leniency")) {
            data.buffer(TYPE).reduce(0.25);
            return;
        }
        double buffer = data.buffer(TYPE).add(1.0, now, 0.02);
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        data.buffer(TYPE).reset();
        ctx.flag(attacker, data, TYPE, "hit from " + Text.num(distance) + " blocks (range " + Text.num(range)
                + ", " + (hit.playerTarget() ? "player" : "mob") + ", lag window " + hit.windowMs() + "ms)");
    }
}
