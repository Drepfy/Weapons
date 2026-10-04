package io.github.drepfy.legendary.guard;

import io.github.drepfy.legendary.LegendaryPlugin;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Allay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.Locale;

/**
 * Keeps legendaries out of everything that is not the holder's own inventory: chests, ender
 * chests, barrels, shulker boxes, hoppers, droppers, dispensers, crafters, furnaces, anvils,
 * crafting grids, horse and minecart inventories, plugin menus, bundles, item frames, armour
 * stands, allays, decorated pots and shelves. Mobs and hoppers cannot pick them up (see
 * {@link Tracker}).
 */
public final class StorageGuard implements Listener {

    private final LegendaryPlugin plugin;

    public StorageGuard(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private boolean legendary(ItemStack item) {
        return plugin.items().isLegendary(item);
    }

    private static boolean isBundle(ItemStack item) {
        return item != null && item.getType().name().endsWith("BUNDLE");
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ItemStack cursor = event.getCursor();
        ItemStack current = event.getCurrentItem();
        // Bundles take items from the slot they are clicked on, and the other way round.
        if ((legendary(cursor) && isBundle(current)) || (isBundle(cursor) && legendary(current))) {
            deny(event, player, "bundle-blocked");
            return;
        }
        if (event.getAction() == InventoryAction.CLONE_STACK && legendary(current)) {
            event.setCancelled(true); // Creative middle-click would make a copy.
            return;
        }
        Inventory clicked = event.getClickedInventory();
        if (clicked == null) {
            return;
        }
        InventoryView view = event.getView();
        if (!isOwn(player, clicked)) {
            if (movesInto(event, player)) {
                deny(event, player, "storage-blocked");
            }
        } else if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY && legendary(current)
                && Tracker.isForeign(player, view.getTopInventory())) {
            deny(event, player, "storage-blocked"); // Shift-click into the open container.
        }
    }

    /**
     * The player's own inventory (hotbar, main, armour, offhand). Everything else is off limits,
     * the 2x2 crafting grid included: two swords there repair into a plain one.
     */
    static boolean isOwn(Player player, Inventory inventory) {
        return inventory instanceof PlayerInventory own && player.equals(own.getHolder());
    }

    /** Whether this click on a container slot would put a legendary into it. */
    private boolean movesInto(InventoryClickEvent event, Player player) {
        return switch (event.getAction()) {
            case PLACE_ALL, PLACE_ONE, PLACE_SOME, SWAP_WITH_CURSOR -> legendary(event.getCursor());
            case HOTBAR_SWAP, HOTBAR_MOVE_AND_READD -> legendary(swapped(event, player));
            default -> event.getClick() == ClickType.SWAP_OFFHAND || event.getClick() == ClickType.NUMBER_KEY
                    ? legendary(swapped(event, player)) : false;
        };
    }

    /** The item a number key or the offhand key would swap into the clicked slot. */
    private static ItemStack swapped(InventoryClickEvent event, Player player) {
        PlayerInventory inventory = player.getInventory();
        int button = event.getHotbarButton();
        if (event.getClick() == ClickType.SWAP_OFFHAND || button < 0 || button == 40) {
            return inventory.getItemInOffHand();
        }
        return inventory.getItem(button);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !legendary(event.getOldCursor())) {
            return;
        }
        InventoryView view = event.getView();
        for (int raw : event.getRawSlots()) {
            if (!isOwn(player, view.getInventory(raw))) {
                event.setCancelled(true);
                plugin.notice(player, "storage-blocked");
                return;
            }
        }
    }

    private void deny(InventoryClickEvent event, Player player, String message) {
        event.setCancelled(true);
        plugin.notice(player, message);
    }

    /** Hoppers moving items between containers (in case one ever got in). */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(InventoryMoveItemEvent event) {
        if (legendary(event.getItem())) {
            event.setCancelled(true);
        }
    }

    /** Netherite swords and axes can be repaired by combining two: that would destroy a legendary. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (legendary(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (legendary(item)) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Item frames and allays take the held item; so would an armour stand. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityInteract(PlayerInteractEntityEvent event) {
        Entity target = event.getRightClicked();
        if (!(target instanceof ItemFrame) && !(target instanceof Allay)
                && !(target instanceof LivingEntity && target.getType().name().equals("MANNEQUIN"))) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItem(event.getHand());
        if (legendary(hand)) {
            event.setCancelled(true);
            plugin.notice(player, "placing-blocked");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        if (legendary(event.getPlayerItem())) {
            event.setCancelled(true);
            plugin.notice(event.getPlayer(), "placing-blocked");
        }
    }

    /**
     * Decorated pots take the held item. Shelves (1.21.9+) take it too, and sneak-clicking one
     * swaps the whole hotbar into it.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        Block block = event.getClickedBlock();
        String type = block.getType().name();
        boolean pot = block.getType() == Material.DECORATED_POT;
        boolean shelf = type.endsWith("_SHELF");
        if (!pot && !shelf) {
            return;
        }
        Player player = event.getPlayer();
        ItemStack hand = event.getHand() == EquipmentSlot.OFF_HAND ? player.getInventory().getItemInOffHand()
                : player.getInventory().getItemInMainHand();
        boolean risky = legendary(hand);
        if (shelf && !risky) {
            for (int slot = 0; slot < 9 && !risky; slot++) {
                risky = legendary(player.getInventory().getItem(slot));
            }
            risky |= legendary(player.getInventory().getItemInOffHand());
        }
        if (risky) {
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            plugin.notice(player, "placing-blocked");
        }
    }

    /** Selling or listing a legendary through a command (shops, auction houses). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String command = event.getMessage().toLowerCase(Locale.ROOT).trim();
        if (command.startsWith("/")) {
            command = command.substring(1);
        }
        int colon = command.indexOf(':');
        int space = command.indexOf(' ');
        if (colon >= 0 && (space < 0 || colon < space)) {
            command = command.substring(colon + 1); // essentials:sell → sell
        }
        for (String blocked : plugin.settings().blockedCommands()) {
            if ((command.equals(blocked) || command.startsWith(blocked + " "))
                    && plugin.tracker().carriesAny(event.getPlayer())) {
                event.setCancelled(true);
                plugin.notice(event.getPlayer(), "command-blocked");
                return;
            }
        }
    }

}
