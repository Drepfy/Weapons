package io.github.drepfy.vigil.check.combat;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import org.bukkit.entity.Player;

/**
 * Clicking faster than a human can. Counts left-click arm swings per second, leaving
 * out swings caused by mining (the arm swings every tick while a block is being
 * broken), placing blocks, using items and dropping items.
 */
public final class AutoClickerCheck {

    private static final CheckType TYPE = CheckType.AUTOCLICKER;
    /** Swings this soon after a block click, placement, item use or drop are not clicks. */
    private static final long OTHER_CAUSE_MS = 150;
    /** A dig without a matching break/abort ends after this long. */
    private static final long MAX_DIG_MS = 30_000;
    private static final long SAMPLE_INTERVAL_MS = 1000;

    private final CheckContext ctx;

    public AutoClickerCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onSwing(Player player, PlayerData data, long now) {
        data.lastSwingMs = now;
        if (data.diggingSinceMs >= 0 && now - data.diggingSinceMs < MAX_DIG_MS) {
            return;
        }
        if (now - data.lastNonAttackSwingCauseMs < OTHER_CAUSE_MS || !ctx.isActive(player, data, TYPE, now)) {
            return;
        }
        int cps = data.swings.record(now);
        CheckSettings settings = ctx.settings(TYPE);
        double max = settings.num("max-cps");
        if (cps <= max || now - data.lastAutoClickerSampleMs < SAMPLE_INTERVAL_MS) {
            return;
        }
        data.lastAutoClickerSampleMs = now;
        if (!ctx.canFlag(player, data, now)) {
            return;
        }
        double buffer = data.buffer(TYPE).add(1.0, now, 0.1);
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        data.buffer(TYPE).reset();
        ctx.flag(player, data, TYPE, "clicked " + cps + " times in one second (max " + (int) max + ")");
    }
}
