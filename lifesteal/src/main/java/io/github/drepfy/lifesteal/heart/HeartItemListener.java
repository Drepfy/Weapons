package io.github.drepfy.lifesteal.heart;

import io.github.drepfy.lifesteal.config.Settings;
import io.github.drepfy.lifesteal.util.Text;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Cat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Sheep;
import org.bukkit.entity.Wolf;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Using Heart items, and keeping them from acting like red dye.
 *
 * <ul>
 *   <li>Right-click: use one Heart. Sneak + right-click: use as many from the stack as fit
 *   below the maximum.</li>
 *   <li>Right-clicking a chest, door, button... with a Heart uses the block as normal.</li>
 *   <li>Hearts cannot dye signs, sheep, wolf or cat collars, and cannot be used in crafting,
 *   looms or villager trades. Every kind of storage is allowed.</li>
 * </ul>
 */
public final class HeartItemListener implements Listener {

    /** Inventories that would use a Heart up as dye. */
    private static final Set<InventoryType> BLOCKED_INVENTORIES = Set.of(InventoryType.LOOM, InventoryType.MERCHANT);
    /** One click can arrive as two events (block, then air); they are counted once. */
    private static final long DOUBLE_CLICK_MS = 100L;

    private final Supplier<Settings> settings;
    private final HeartItems items;
    private final HeartService hearts;
    private final LongSupplier clock;
    private final Consumer<String> log;
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public HeartItemListener(Supplier<Settings> settings, HeartItems items, HeartService hearts, LongSupplier clock,
                             Consumer<String> log) {
        this.settings = settings;
        this.items = items;
        this.hearts = hearts;
        this.clock = clock;
        this.log = log;
    }

    // ---- using Hearts ------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if ((action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) || !items.isHeart(event.getItem())) {
            return;
        }
        Player player = event.getPlayer();
        // The dye itself is never used (it would dye signs).
        event.setUseItemInHand(Event.Result.DENY);
        if (event.getHand() == EquipmentSlot.OFF_HAND && items.isHeart(player.getInventory().getItemInMainHand())) {
            return; // The main hand's Heart is used.
        }
        Block block = event.getClickedBlock();
        if (action == Action.RIGHT_CLICK_BLOCK && !player.isSneaking() && block != null && block.getType().isInteractable()) {
            return; // Open the chest, door... as normal.
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        long now = clock.getAsLong();
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && now - last >= 0 && now - last < DOUBLE_CLICK_MS) {
            return;
        }
        lastUse.put(player.getUniqueId(), now);
        use(player, event.getHand() == EquipmentSlot.OFF_HAND, player.isSneaking());
    }

    /**
     * Uses Hearts from the player's hand.
     *
     * @param all as many as fit below the maximum (else one)
     * @return how many were used
     */
    public int use(Player player, boolean offHand, boolean all) {
        Settings config = settings.get();
        PlayerInventory inventory = player.getInventory();
        ItemStack stack = offHand ? inventory.getItemInOffHand() : inventory.getItemInMainHand();
        if (!items.isHeart(stack)) {
            return 0;
        }
        int before = hearts.hearts(player);
        int room = config.maxHearts() - before;
        if (room <= 0) {
            send(player, config.messages().get("consume-at-max"), "max", config.maxHearts());
            return 0;
        }
        int used = all ? Math.min(stack.getAmount(), room) : 1;
        int left = stack.getAmount() - used;
        if (left <= 0) {
            stack = null;
        } else {
            stack.setAmount(left);
        }
        if (offHand) {
            inventory.setItemInOffHand(stack);
        } else {
            inventory.setItemInMainHand(stack);
        }
        int after = hearts.set(player, before + used, config.healGainedHearts());
        send(player, config.messages().get("consume"), "count", used, "s", Text.plural(used), "hearts", after);
        try {
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        } catch (RuntimeException | LinkageError ignored) {
            // Sound names differ between versions; it is only a sound.
        }
        log.accept(player.getName() + " used " + used + " Heart item(s): " + before + " -> " + after);
        return used;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    // ---- not red dye ------------------------------------------------------------------------------------------

    /** No dyeing sheep, wolf collars or cat collars. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        Entity entity = event.getRightClicked();
        if (!(entity instanceof Sheep || entity instanceof Wolf || entity instanceof Cat)) {
            return;
        }
        PlayerInventory inventory = event.getPlayer().getInventory();
        ItemStack held = event.getHand() == EquipmentSlot.OFF_HAND ? inventory.getItemInOffHand()
                : inventory.getItemInMainHand();
        if (items.isHeart(held)) {
            event.setCancelled(true);
        }
    }

    /** Hearts are not an ingredient of any recipe (no red wool, banners, fireworks...). */
    @EventHandler(priority = EventPriority.HIGH)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (items.containsHeart(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCraft(CraftItemEvent event) {
        if (items.containsHeart(event.getInventory().getMatrix())) {
            event.setCancelled(true);
        }
    }

    /** Looms and villager trades would use Hearts up as dye. */
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!BLOCKED_INVENTORIES.contains(top.getType())) {
            return;
        }
        boolean intoTop = event.getClickedInventory() != null && event.getClickedInventory().equals(top);
        ItemStack moving = null;
        if (intoTop) {
            if (event.getClick() == ClickType.NUMBER_KEY && event.getHotbarButton() >= 0) {
                moving = event.getWhoClicked().getInventory().getItem(event.getHotbarButton());
            } else if (event.getClick() == ClickType.SWAP_OFFHAND) {
                moving = event.getWhoClicked().getInventory().getItemInOffHand();
            } else {
                moving = event.getCursor();
            }
        } else if (event.isShiftClick()) {
            moving = event.getCurrentItem();
        }
        if (items.isHeart(moving)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!BLOCKED_INVENTORIES.contains(top.getType()) || !items.isHeart(event.getOldCursor())) {
            return;
        }
        for (int slot : event.getRawSlots()) {
            if (slot < top.getSize()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    private void send(Player player, String template, Object... pairs) {
        if (template != null && !template.isEmpty()) {
            player.sendMessage(Text.color(settings.get().messages().get("prefix")) + Text.format(template, pairs));
        }
    }
}
