package com.drepfy.staffvanish.staff;

import com.drepfy.staffvanish.VanishModule;
import com.drepfy.staffvanish.VanishPermissions;
import com.drepfy.staffvanish.staff.StaffPreferences.Option;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code /vanish settings}: a menu of switches for vanish itself and each staff member's preferences. */
public final class StaffPanel implements Listener {

    private static final int VANISH_SLOT = 13;
    private static final Map<Integer, Option> OPTION_SLOTS = Map.of(
            20, Option.NIGHT_VISION,
            21, Option.SILENT_CONTAINERS,
            22, Option.ITEM_PICKUP,
            23, Option.BOSS_BAR,
            24, Option.GLOW);

    private static final class View implements InventoryHolder {
        private @Nullable Inventory inventory;

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Plugin plugin;
    private final VanishModule module;

    public StaffPanel(Plugin plugin, VanishModule module) {
        this.plugin = plugin;
        this.module = module;
    }

    public void open(Player player) {
        View view = new View();
        Component title = module.messages().get("settings-title");
        Inventory inventory = Bukkit.createInventory(view, 36,
                title != null ? title : Component.text("Vanish settings"));
        view.inventory = inventory;
        draw(player, inventory);
        player.openInventory(inventory);
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder(false) instanceof View)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getRawSlot() >= top.getSize()) {
            return;
        }
        int slot = event.getRawSlot();
        // Changing vanish state inside a click event isn't safe, so act on the next tick.
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.getOpenInventory().getTopInventory() != top) {
                return;
            }
            if (slot == VANISH_SLOT) {
                player.performCommand("vanish");
            } else {
                Option option = OPTION_SLOTS.get(slot);
                if (option == null || !available(option)) {
                    return;
                }
                module.preferences().toggle(player, option);
                module.effects().refresh(player);
            }
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
            draw(player, top);
        });
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof View) {
            event.setCancelled(true);
        }
    }

    private boolean available(Option option) {
        return option != Option.SILENT_CONTAINERS || module.settings().staffTools().silentContainers();
    }

    private void draw(Player player, Inventory inventory) {
        ItemStack filler = item(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of(), false);
        for (int i = 0; i < inventory.getSize(); i++) {
            inventory.setItem(i, filler);
        }
        boolean vanished = module.manager().isVanished(player);
        inventory.setItem(VANISH_SLOT, item(vanished ? Material.ENDER_EYE : Material.ENDER_PEARL,
                title("Vanish", vanished),
                List.of(status(vanished),
                        gray("Level: " + module.manager().level(player)),
                        Component.empty(),
                        hint(vanished ? "Click to become visible" : "Click to vanish")),
                vanished));

        describe(inventory, Option.NIGHT_VISION, Material.GOLDEN_CARROT, player,
                "See clearly in the dark while vanished.");
        describe(inventory, Option.SILENT_CONTAINERS, Material.CHEST, player,
                "Open chests without animation or sound.");
        describe(inventory, Option.ITEM_PICKUP, Material.HOPPER, player,
                "Pick up items while vanished.");
        describe(inventory, Option.BOSS_BAR, Material.DRAGON_HEAD, player,
                "Show the vanish boss bar.");
        describe(inventory, Option.GLOW, Material.GLOWSTONE_DUST, player,
                "Glow for other staff while vanished.");

        if (player.hasPermission(VanishPermissions.TELEPORT)) {
            inventory.setItem(31, item(module.settings().selector().material(), Component.text("Vanish Stick",
                    NamedTextColor.AQUA, TextDecoration.BOLD), List.of(gray("Use /vanish stick to get it back.")),
                    false));
        }
    }

    private void describe(Inventory inventory, Option option, Material material, Player player, String description) {
        int slot = OPTION_SLOTS.entrySet().stream().filter(e -> e.getValue() == option)
                .map(Map.Entry::getKey).findFirst().orElseThrow();
        String name = switch (option) {
            case NIGHT_VISION -> "Night Vision";
            case SILENT_CONTAINERS -> "Silent Chests";
            case ITEM_PICKUP -> "Item Pickup";
            case BOSS_BAR -> "Boss Bar";
            case GLOW -> "Glow";
        };
        if (!available(option)) {
            inventory.setItem(slot, item(Material.BARRIER, Component.text(name, NamedTextColor.DARK_GRAY),
                    List.of(gray("Turned off in the config.")), false));
            return;
        }
        boolean on = module.preferences().get(player, option);
        List<Component> lore = new ArrayList<>();
        lore.add(status(on));
        lore.add(gray(description));
        lore.add(Component.empty());
        lore.add(hint("Click to turn " + (on ? "off" : "on")));
        inventory.setItem(slot, item(material, title(name, on), lore, on));
    }

    private static Component title(String name, boolean on) {
        return Component.text(name, on ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD);
    }

    private static Component status(boolean on) {
        return gray("Status: ").append(on
                ? Component.text("ON", NamedTextColor.GREEN, TextDecoration.BOLD)
                : Component.text("OFF", NamedTextColor.RED, TextDecoration.BOLD));
    }

    private static Component gray(String text) {
        return Component.text(text, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false);
    }

    private static Component hint(String text) {
        return Component.text("» " + text, NamedTextColor.AQUA).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material material, Component name, List<Component> lore, boolean glint) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(name.decoration(TextDecoration.ITALIC, false));
        meta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
        if (glint) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }
}
