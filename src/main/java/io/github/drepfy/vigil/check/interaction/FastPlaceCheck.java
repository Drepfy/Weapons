package io.github.drepfy.vigil.check.interaction;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;

/**
 * Placing blocks faster than a vanilla client can (holding right click places about 5
 * blocks per second, very fast clicking about 15-20).
 */
public final class FastPlaceCheck {

    private static final CheckType TYPE = CheckType.FASTPLACE;
    private static final long FLAG_INTERVAL_MS = 1000;

    private final CheckContext ctx;

    public FastPlaceCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onPlace(Player player, PlayerData data, BlockPlaceEvent event, long now) {
        if (!ctx.isActive(player, data, TYPE, now)) {
            return;
        }
        int perSecond = data.blockPlaces.record(now);
        CheckSettings settings = ctx.settings(TYPE);
        double max = settings.num("max-per-second");
        if (perSecond <= max) {
            return;
        }
        if (ctx.mitigationAllowed(settings)) {
            event.setCancelled(true);
        }
        if (now - data.lastFastPlaceFlagMs < FLAG_INTERVAL_MS || !ctx.canFlag(player, data, now)) {
            return;
        }
        data.lastFastPlaceFlagMs = now;
        double buffer = data.buffer(TYPE).add(1.0, now, 0.1);
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        data.buffer(TYPE).reset();
        ctx.flag(player, data, TYPE, "placed " + perSecond + " blocks in one second (max " + (int) max + ")");
    }
}
