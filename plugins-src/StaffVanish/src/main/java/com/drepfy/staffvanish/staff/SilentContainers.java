package com.drepfy.staffvanish.staff;

import com.drepfy.staffvanish.VanishModule;
import com.drepfy.staffvanish.staff.StaffPreferences.Option;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.Set;

/**
 * Vanished staff open containers silently: no lid animation, no sound, no sculk vibration and no "someone is using
 * this" state for players nearby. They get a live, read-only copy of the contents, so nothing can be duplicated or
 * lost. Sneaking with an item in hand is left alone so blocks can still be placed against containers.
 */
public final class SilentContainers implements Listener {

    private static final Set<InventoryType> SIZED_TYPES =
            Set.of(InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX);
    private static final Set<InventoryType> FIXED_TYPES = Set.of(InventoryType.HOPPER, InventoryType.DISPENSER,
            InventoryType.DROPPER, InventoryType.FURNACE, InventoryType.BLAST_FURNACE, InventoryType.SMOKER,
            InventoryType.BREWING);

    /** Marks a silent copy; remembers which block it mirrors. */
    public static final class View implements InventoryHolder {
        private final Location location;
        private @Nullable Inventory inventory;

        private View(Location location) {
            this.location = location;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final VanishModule module;

    public SilentContainers(Plugin plugin, VanishModule module) {
        this.module = module;
        // Keeps open copies in sync with the real container.
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenViews, 10L, 10L);
    }

    // LOW: before protection plugins and anti-cheat look at the (now cancelled) click.
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Block block = event.getClickedBlock();
        if (block == null || !module.settings().staffTools().silentContainers()
                || !module.manager().isVanished(player)
                || !module.preferences().get(player, Option.SILENT_CONTAINERS)
                || module.selectorItem().isSelector(player.getInventory().getItemInMainHand())) {
            return;
        }
        if (player.isSneaking() && !player.getInventory().getItemInMainHand().isEmpty()) {
            return;
        }

        if (block.getType() == Material.ENDER_CHEST) {
            deny(event);
            // Their own ender chest, opened without the block animating.
            player.openInventory(player.getEnderChest());
            return;
        }

        BlockState state = block.getState(false);
        if (!(state instanceof Container container)) {
            return;
        }
        Inventory real = container.getInventory();
        InventoryType type = real.getType();
        View view = new View(block.getLocation());
        Component title = module.messages().get("silent-container-title",
                Placeholder.unparsed("block", prettyName(block.getType())));
        if (title == null) {
            title = Component.text(prettyName(block.getType()));
        }
        Inventory copy;
        if (SIZED_TYPES.contains(type)) {
            copy = Bukkit.createInventory(view, real.getSize(), title);
        } else if (FIXED_TYPES.contains(type)) {
            copy = Bukkit.createInventory(view, type, title);
        } else {
            return;
        }
        view.inventory = copy;
        copyContents(real, copy);
        deny(event);
        player.openInventory(copy);
        Component notice = module.messages().get("silent-container-opened");
        if (notice != null) {
            player.sendActionBar(notice);
        }
    }

    /** Read-only: nothing can be taken out or put in. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof View) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof View) {
            event.setCancelled(true);
        }
    }

    private void refreshOpenViews() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = player.getOpenInventory().getTopInventory();
            if (top == null || !(top.getHolder(false) instanceof View view)) {
                continue;
            }
            Location location = view.location;
            if (!location.isChunkLoaded()
                    || !(location.getBlock().getState(false) instanceof Container container)
                    || container.getInventory().getSize() != top.getSize()) {
                player.closeInventory();
                continue;
            }
            copyContents(container.getInventory(), top);
        }
    }

    private static void copyContents(Inventory from, Inventory to) {
        ItemStack[] items = from.getContents();
        ItemStack[] copy = new ItemStack[to.getSize()];
        for (int i = 0; i < copy.length && i < items.length; i++) {
            copy[i] = items[i] == null ? null : items[i].clone();
        }
        to.setContents(copy);
    }

    private static void deny(PlayerInteractEvent event) {
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        event.setCancelled(true);
    }

    static String prettyName(Material material) {
        String[] words = material.name().toLowerCase(Locale.ROOT).split("_");
        StringBuilder name = new StringBuilder();
        for (String word : words) {
            if (!name.isEmpty()) {
                name.append(' ');
            }
            name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return name.toString();
    }
}
