package com.drepfy.staffvanish.staff;

import com.drepfy.staffvanish.VanishModule;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * {@code /invsee} and {@code /endersee}: a live, read-only look at another player's inventory or ender chest.
 * Read-only on purpose, so a slip of the mouse can never move, delete or duplicate a player's items.
 */
public final class InventorySpy implements Listener {

    public enum Kind { INVENTORY, ENDER_CHEST }

    /** Marks a spy view and remembers whose items it shows. */
    public static final class View implements InventoryHolder {
        private final UUID target;
        private final Kind kind;
        private @Nullable Inventory inventory;

        private View(UUID target, Kind kind) {
            this.target = target;
            this.kind = kind;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private static final int ARMOR_ROW = 36;

    private final VanishModule module;

    public InventorySpy(Plugin plugin, VanishModule module) {
        this.module = module;
        Bukkit.getScheduler().runTaskTimer(plugin, this::refreshOpenViews, 10L, 10L);
    }

    public void open(Player staff, Player target, Kind kind) {
        View view = new View(target.getUniqueId(), kind);
        String key = kind == Kind.INVENTORY ? "invsee-title" : "endersee-title";
        Component title = module.messages().get(key, Placeholder.unparsed("target", target.getName()));
        if (title == null) {
            title = Component.text(target.getName());
        }
        Inventory inventory = Bukkit.createInventory(view, kind == Kind.INVENTORY ? 45 : 27, title);
        view.inventory = inventory;
        fill(inventory, target, kind);
        staff.openInventory(inventory);
    }

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
        for (Player staff : Bukkit.getOnlinePlayers()) {
            Inventory top = staff.getOpenInventory().getTopInventory();
            if (top == null || !(top.getHolder(false) instanceof View view)) {
                continue;
            }
            Player target = Bukkit.getPlayer(view.target);
            if (target == null) {
                staff.closeInventory();
                module.messages().send(staff, "spy-target-left");
                continue;
            }
            fill(top, target, view.kind);
        }
    }

    private void fill(Inventory inventory, Player target, Kind kind) {
        if (kind == Kind.ENDER_CHEST) {
            ItemStack[] items = target.getEnderChest().getContents();
            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, i < items.length && items[i] != null ? items[i].clone() : null);
            }
            return;
        }
        PlayerInventory source = target.getInventory();
        // Laid out like the player's own screen: main inventory on top, hotbar below it.
        for (int i = 0; i < 27; i++) {
            inventory.setItem(i, copy(source.getItem(9 + i)));
        }
        for (int i = 0; i < 9; i++) {
            inventory.setItem(27 + i, copy(source.getItem(i)));
        }
        inventory.setItem(ARMOR_ROW, copy(source.getHelmet()));
        inventory.setItem(ARMOR_ROW + 1, copy(source.getChestplate()));
        inventory.setItem(ARMOR_ROW + 2, copy(source.getLeggings()));
        inventory.setItem(ARMOR_ROW + 3, copy(source.getBoots()));
        inventory.setItem(ARMOR_ROW + 4, copy(source.getItemInOffHand()));
        ItemStack filler = named(Material.GRAY_STAINED_GLASS_PANE, Component.empty(), List.of());
        for (int slot = ARMOR_ROW + 5; slot < 44; slot++) {
            inventory.setItem(slot, filler);
        }
        inventory.setItem(44, info(target));
    }

    private static ItemStack info(Player target) {
        AttributeInstance maxHealth = target.getAttribute(Attribute.MAX_HEALTH);
        double max = maxHealth != null ? maxHealth.getValue() : 20;
        return named(Material.PAPER, Component.text(target.getName(), NamedTextColor.AQUA, TextDecoration.BOLD), List.of(
                line("Health", Math.round(target.getHealth()) + " / " + Math.round(max)),
                line("Food", target.getFoodLevel() + " / 20"),
                line("Level", Integer.toString(target.getLevel())),
                line("Game mode", target.getGameMode().name().toLowerCase()),
                line("World", target.getWorld().getName())));
    }

    private static Component line(String label, String value) {
        return Component.text(label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, NamedTextColor.WHITE))
                .decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack named(Material material, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static @Nullable ItemStack copy(@Nullable ItemStack item) {
        return item == null || item.isEmpty() ? null : item.clone();
    }
}
