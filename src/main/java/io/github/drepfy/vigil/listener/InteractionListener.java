package io.github.drepfy.vigil.listener;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.interaction.BlockReachCheck;
import io.github.drepfy.vigil.check.interaction.ChestAuraCheck;
import io.github.drepfy.vigil.check.interaction.FastPlaceCheck;
import io.github.drepfy.vigil.check.interaction.InteractCheck;
import io.github.drepfy.vigil.check.interaction.NukerCheck;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Block clicks, placements and breaks. The checks only see actions that came straight
 * from the client; clicks already denied by another plugin (e.g. protection) are
 * ignored. The state used to tell clicks from other arm swings is tracked for every
 * action, cancelled or not.
 */
public final class InteractionListener implements Listener {

    private final CheckContext ctx;
    private final BlockReachCheck blockReach;
    private final ChestAuraCheck chestAura;
    private final InteractCheck interact;
    private final FastPlaceCheck fastPlace;
    private final NukerCheck nuker;

    public InteractionListener(CheckContext ctx, BlockReachCheck blockReach, ChestAuraCheck chestAura,
                               InteractCheck interact, FastPlaceCheck fastPlace, NukerCheck nuker) {
        this.ctx = ctx;
        this.blockReach = blockReach;
        this.chestAura = chestAura;
        this.interact = interact;
        this.fastPlace = fastPlace;
        this.nuker = nuker;
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
        ctx.run(CheckType.BLOCKREACH, now, () -> blockReach.onInteract(player, data, event, now));
        if (action == Action.RIGHT_CLICK_BLOCK) {
            ctx.run(CheckType.CHESTAURA, now, () -> chestAura.onInteract(player, data, event, now));
        } else {
            ctx.run(CheckType.NUKER, now, () -> nuker.onBlockClick(player, data, event, now));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (!ctx.settings().general().enabled()) {
            return;
        }
        Player player = event.getPlayer();
        long now = Clock.now();
        PlayerData data = ctx.players().get(player);
        ctx.run(CheckType.INTERACT, now, () -> interact.onPlace(player, data, event, now));
        if (!event.isCancelled()) {
            ctx.run(CheckType.FASTPLACE, now, () -> fastPlace.onPlace(player, data, event, now));
        }
    }

    // ---- swing causes (all actions, cancelled or not) --------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void trackInteract(PlayerInteractEvent event) {
        long now = Clock.now();
        PlayerData data = ctx.players().get(event.getPlayer());
        switch (event.getAction()) {
            case LEFT_CLICK_BLOCK -> data.diggingSinceMs = now;
            case RIGHT_CLICK_BLOCK, RIGHT_CLICK_AIR -> data.lastNonAttackSwingCauseMs = now;
            default -> {
                // Left clicks in the air are the clicks being counted.
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void trackPlace(BlockPlaceEvent event) {
        ctx.players().get(event.getPlayer()).lastNonAttackSwingCauseMs = Clock.now();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void trackBreak(BlockBreakEvent event) {
        endDigging(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void trackDrop(PlayerDropItemEvent event) {
        ctx.players().get(event.getPlayer()).lastNonAttackSwingCauseMs = Clock.now();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void trackEntityInteract(PlayerInteractEntityEvent event) {
        ctx.players().get(event.getPlayer()).lastNonAttackSwingCauseMs = Clock.now();
    }

    /** The player finished or stopped breaking a block (break event or Paper's abort event). */
    public void endDigging(Player player) {
        PlayerData data = ctx.players().get(player);
        data.diggingSinceMs = -1;
        // The swing that belongs to the final hit can arrive right after the break.
        data.lastNonAttackSwingCauseMs = Clock.now();
    }
}
