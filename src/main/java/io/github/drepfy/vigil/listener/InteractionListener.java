package io.github.drepfy.vigil.listener;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.interaction.BlockReachCheck;
import io.github.drepfy.vigil.check.interaction.WallInteractCheck;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Block clicks straight from the client (start of digging, right clicks). Clicks
 * already denied by another plugin (e.g. protection) are ignored.
 */
public final class InteractionListener implements Listener {

    private final CheckContext ctx;
    private final BlockReachCheck blockReach;
    private final WallInteractCheck wallInteract;

    public InteractionListener(CheckContext ctx, BlockReachCheck blockReach, WallInteractCheck wallInteract) {
        this.ctx = ctx;
        this.blockReach = blockReach;
        this.wallInteract = wallInteract;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (action == Action.RIGHT_CLICK_BLOCK && event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getClickedBlock() == null || !ctx.settings().general().enabled()) {
            return;
        }
        Player player = event.getPlayer();
        long now = Clock.now();
        PlayerData data = ctx.players().get(player);
        ctx.run(CheckType.BLOCK_REACH, now, () -> blockReach.onInteract(player, data, event, now));
        if (action == Action.RIGHT_CLICK_BLOCK) {
            ctx.run(CheckType.WALL_INTERACT, now, () -> wallInteract.onInteract(player, data, event, now));
        }
    }
}
