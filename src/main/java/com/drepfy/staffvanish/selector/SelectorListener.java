package com.drepfy.staffvanish.selector;

import com.drepfy.staffvanish.Messages;
import com.drepfy.staffvanish.PlayerInfo;
import com.drepfy.staffvanish.StaffTeleporter;
import com.drepfy.staffvanish.VanishModule;
import com.drepfy.staffvanish.VanishSettings;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BundleMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.jspecify.annotations.Nullable;

/** Makes the staff selector work, runs its menu, and keeps it from leaving its owner's inventory. */
public final class SelectorListener implements Listener {

    /**
     * One click can reach the server as several interactions (right-clicking a player also sends a right-click on the
     * air), so repeats within this window are ignored.
     */
    private static final long CLICK_COOLDOWN_MILLIS = 150;

    private final VanishModule module;
    private final Plugin plugin;
    private final Map<UUID, Long> lastClick = new HashMap<>();

    public SelectorListener(VanishModule module, Plugin plugin) {
        this.module = module;
        this.plugin = plugin;
    }

    // --- Using the selector ---

    // Not ignoreCancelled: clicks on the air arrive already cancelled.
    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        if (event.getAction() == Action.PHYSICAL || !holdsSelector(player)) {
            return;
        }
        // The selector (and whatever is in the off hand) never breaks, places or uses anything.
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() != EquipmentSlot.HAND || !takeClick(player)) {
            return;
        }
        VanishSettings.Actions actions = module.settings().selector().actions();
        boolean sneaking = player.isSneaking();
        SelectorAction action = event.getAction().isLeftClick()
                ? (sneaking ? actions.sneakLeftClick() : actions.leftClick())
                : (sneaking ? actions.sneakRightClick() : actions.rightClick());
        perform(player, action, null);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Player player = event.getPlayer();
        if (!holdsSelector(player)) {
            return;
        }
        event.setCancelled(true);
        if (event.getHand() == EquipmentSlot.HAND && event.getRightClicked() instanceof Player target
                && takeClick(player)) {
            perform(player, module.settings().selector().actions().clickPlayer(), target);
        }
    }

    /** Armor stands and other precise interactions. */
    @EventHandler(priority = EventPriority.LOW)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (holdsSelector(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Hitting something with the selector would reveal the vanished player. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && holdsSelector(player)) {
            event.setCancelled(true);
        }
    }

    private void perform(Player player, SelectorAction action, @Nullable Player clicked) {
        if (action == SelectorAction.NONE || !module.teleporter().canTeleport(player)) {
            return;
        }
        StaffTeleporter teleporter = module.teleporter();
        switch (action) {
            case OPEN_MENU -> openMenu(player);
            case NEXT_PLAYER -> teleportTo(player, teleporter.cycle(player, 1));
            case PREVIOUS_PLAYER -> teleportTo(player, teleporter.cycle(player, -1));
            case RANDOM_PLAYER -> teleportTo(player, teleporter.random(player));
            case INSPECT -> inspect(player, clicked != null ? clicked : lookedAt(player));
            case NONE -> {
            }
        }
    }

    private void openMenu(Player player) {
        if (module.teleporter().targets(player).isEmpty()) {
            module.messages().send(player, "no-players");
            return;
        }
        new SelectorMenu(module, player).open(player);
    }

    private void teleportTo(Player player, @Nullable Player target) {
        if (target == null) {
            module.messages().send(player, "no-players");
        } else {
            module.teleporter().teleport(player, target);
        }
    }

    private void inspect(Player staff, @Nullable Player target) {
        Messages messages = module.messages();
        if (target == null) {
            messages.send(staff, "no-target");
            return;
        }
        Component button = messages.get("inspect-teleport-button");
        Component vanishedTag = module.manager().isVanished(target)
                ? messages.parse(module.settings().selector().menu().vanishedTag())
                : Component.empty();
        messages.send(staff, "inspect",
                Placeholder.unparsed("target", target.getName()),
                Placeholder.component("vanished", vanishedTag),
                Placeholder.component("teleport", button == null
                        ? Component.empty()
                        : button.clickEvent(ClickEvent.runCommand("/vanish tp " + target.getName()))),
                PlayerInfo.placeholders(target));
    }

    /** The player the staff member is looking at, if any, without seeing through blocks. */
    private @Nullable Player lookedAt(Player staff) {
        Location eye = staff.getEyeLocation();
        RayTraceResult result = staff.getWorld().rayTrace(eye, eye.getDirection(),
                module.settings().selector().inspectRange(), FluidCollisionMode.NEVER, true, 0.2,
                entity -> entity instanceof Player player && !player.equals(staff)
                        && module.manager().canSee(staff, player));
        return result != null && result.getHitEntity() instanceof Player player ? player : null;
    }

    private boolean takeClick(Player player) {
        long now = System.currentTimeMillis();
        Long previous = lastClick.put(player.getUniqueId(), now);
        return previous == null || now - previous >= CLICK_COOLDOWN_MILLIS;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastClick.remove(event.getPlayer().getUniqueId());
    }

    // --- The menu ---

    @EventHandler(priority = EventPriority.LOW)
    public void onMenuClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof SelectorMenu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= top.getSize()) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT && click != ClickType.RIGHT
                && click != ClickType.SHIFT_LEFT && click != ClickType.SHIFT_RIGHT) {
            return;
        }
        int slot = event.getRawSlot();
        // Closing or changing inventories inside a click event isn't safe, so act on the next tick.
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                menu.click(player, slot);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onMenuDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof SelectorMenu) {
            event.setCancelled(true);
        }
    }

    // --- Keeping the selector where it belongs ---

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (selector().isSelector(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** Last line of defence: a selector never exists as an item in the world. */
    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        if (selector().isSelector(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        event.getDrops().removeIf(selector()::isSelector);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!module.manager().isVanished(player)) {
            return;
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && module.manager().isVanished(player)) {
                module.manager().equipSelector(player);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (selector().isSelector(event.getMainHandItem()) || selector().isSelector(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (selector().isSelector(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    /** A Breeze Rod is a crafting ingredient (wind charges), so the selector must never be crafted with. */
    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        for (ItemStack item : event.getInventory().getMatrix()) {
            if (selector().isSelector(item)) {
                event.getInventory().setResult(null);
                return;
            }
        }
    }

    /**
     * The selector may be moved around its owner's inventory, but not into another inventory, the crafting grid,
     * the off hand or a bundle.
     */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        ClickType click = event.getClick();
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        boolean involved = selector().isSelector(current) || selector().isSelector(cursor)
                || (click == ClickType.NUMBER_KEY
                        && selector().isSelector(player.getInventory().getItem(event.getHotbarButton())))
                || (click == ClickType.SWAP_OFFHAND
                        && selector().isSelector(player.getInventory().getItemInOffHand()));
        if (!involved) {
            return;
        }
        InventoryType top = event.getView().getTopInventory().getType();
        boolean ownInventoryOnly = top == InventoryType.CRAFTING || top == InventoryType.CREATIVE;
        InventoryType.SlotType slotType = event.getSlotType();
        if (!ownInventoryOnly
                || slotType == InventoryType.SlotType.CRAFTING
                || slotType == InventoryType.SlotType.RESULT
                || click == ClickType.SWAP_OFFHAND
                || isBundle(current)
                || isBundle(cursor)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!selector().isSelector(event.getOldCursor())) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        for (int slot : event.getRawSlots()) {
            if (slot < topSize) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private boolean holdsSelector(Player player) {
        return selector().isSelector(player.getInventory().getItemInMainHand());
    }

    private SelectorItem selector() {
        return module.selectorItem();
    }

    private static boolean isBundle(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getItemMeta() instanceof BundleMeta;
    }
}
