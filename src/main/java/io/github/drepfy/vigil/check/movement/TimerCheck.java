package io.github.drepfy.vigil.check.movement;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.entity.Player;

/**
 * Clients simulating more ticks than real time allows ("timer"). Fed either with
 * Paper's client tick-end events (exact) or, when those are unavailable, with
 * movement events (a legitimate client produces at most one per client tick).
 */
public final class TimerCheck {

    private static final CheckType TYPE = CheckType.TIMER;

    private final CheckContext ctx;

    public TimerCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onClientTick(Player player, PlayerData data, boolean exemptState, long now) {
        if (!ctx.isActive(player, data, TYPE, now) || exemptState || ctx.inMovementGrace(data, now)
                || !ctx.compat().isTickRateNormal()) {
            data.timer.restart(now);
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        double debt = data.timer.onClientTick(now, settings.num("max-credit-ms"),
                settings.millis("stall-forgiveness-ms"), settings.num("max-debt-ms"));
        if (debt <= 0.0) {
            return;
        }
        if (!ctx.canFlag(player, data, now)) {
            return;
        }
        double buffer = data.buffer(TYPE).add(1.0, now, 0.02);
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        data.buffer(TYPE).reset();
        ctx.flag(player, data, TYPE, "client ran " + Text.num(debt) + "ms ahead of real time");
    }
}
