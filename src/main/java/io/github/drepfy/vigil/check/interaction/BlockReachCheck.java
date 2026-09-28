package io.github.drepfy.vigil.check.interaction;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.model.Box;
import io.github.drepfy.vigil.model.Physics;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Breaking or using blocks from too far away. Evaluated on the packet-driven
 * {@link PlayerInteractEvent} (start of digging / right click), so blocks broken by
 * other plugins (vein miners, tree fellers) can never be mistaken for reach.
 */
public final class BlockReachCheck {

    private static final CheckType TYPE = CheckType.BLOCK_REACH;

    private final CheckContext ctx;

    public BlockReachCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onInteract(Player player, PlayerData data, PlayerInteractEvent event, long now) {
        Block block = event.getClickedBlock();
        if (block == null || !ctx.isActive(player, data, TYPE, now) || !ctx.canFlag(player, data, now)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        double fallback = player.getGameMode() == GameMode.CREATIVE ? 5.0 : Physics.DEFAULT_BLOCK_RANGE;
        double range = ctx.compat().attributeOr(player, ServerCompat.Attr.BLOCK_INTERACTION_RANGE, fallback);

        Location location = player.getLocation();
        Box box = Box.ofBlock(block.getX(), block.getY(), block.getZ());
        double distance = Double.MAX_VALUE;
        for (double height : ctx.eyeHeights(player)) {
            distance = Math.min(distance, box.distanceTo(location.getX(), location.getY() + height, location.getZ()));
        }
        double measured = distance;
        ctx.debug(player, TYPE, () -> "distance=" + Text.num(measured) + " range=" + Text.num(range));

        if (distance > range + settings.num("cancel-leniency") && ctx.mitigationAllowed(settings)) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
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
        ctx.flag(player, data, TYPE, event.getAction().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                + " " + block.getType().name() + " from " + Text.num(distance) + " blocks (range " + Text.num(range) + ")");
    }
}
