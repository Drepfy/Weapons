package io.github.drepfy.vigil.check.interaction;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Starting to break more blocks per second than a vanilla client can (nuker / mass
 * block breaking). Only block clicks sent by the client are counted, so vein-miner and
 * tree-feller plugins never count.
 */
public final class NukerCheck {

    private static final CheckType TYPE = CheckType.NUKER;
    private static final long FLAG_INTERVAL_MS = 1000;

    private final CheckContext ctx;

    public NukerCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onBlockClick(Player player, PlayerData data, PlayerInteractEvent event, long now) {
        if (!ctx.isActive(player, data, TYPE, now)) {
            return;
        }
        int perSecond = data.blockStarts.record(now);
        CheckSettings settings = ctx.settings(TYPE);
        double max = settings.num("max-per-second");
        if (perSecond <= max) {
            return;
        }
        if (ctx.mitigationAllowed(settings)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setCancelled(true);
        }
        if (now - data.lastNukerFlagMs < FLAG_INTERVAL_MS || !ctx.canFlag(player, data, now)) {
            return;
        }
        data.lastNukerFlagMs = now;
        double buffer = data.buffer(TYPE).add(1.0, now, 0.1);
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        data.buffer(TYPE).reset();
        ctx.flag(player, data, TYPE, "started breaking " + perSecond + " blocks in one second (max "
                + (int) max + ")");
    }
}
