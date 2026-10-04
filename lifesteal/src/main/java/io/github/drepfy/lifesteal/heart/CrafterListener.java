package io.github.drepfy.lifesteal.heart;

import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.CrafterCraftEvent;

/** The crafter block (1.21+) must not use Hearts as dye either. Only registered where it exists. */
public final class CrafterListener implements Listener {

    private final HeartItems items;

    public CrafterListener(HeartItems items) {
        this.items = items;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCrafterCraft(CrafterCraftEvent event) {
        BlockState state = event.getBlock().getState();
        if (state instanceof Container container && items.containsHeart(container.getInventory().getContents())) {
            event.setCancelled(true);
        }
    }
}
