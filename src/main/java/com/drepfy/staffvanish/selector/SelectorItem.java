package com.drepfy.staffvanish.selector;

import com.drepfy.staffvanish.Messages;
import com.drepfy.staffvanish.VanishModule;
import com.drepfy.staffvanish.VanishSettings;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Creates and recognises the staff selector. Selectors are recognised by a persistent data tag rather than their
 * look, so they keep working when the configured item changes.
 */
public final class SelectorItem {

    private final NamespacedKey key;
    private final VanishModule module;

    public SelectorItem(Plugin plugin, VanishModule module) {
        this.key = new NamespacedKey(plugin, "staff_selector");
        this.module = module;
    }

    public ItemStack create() {
        VanishSettings.Selector settings = module.settings().selector();
        Messages messages = module.messages();
        ItemStack item = new ItemStack(settings.material());
        item.editMeta(meta -> {
            meta.customName(messages.parseItemText(settings.name()));
            meta.lore(settings.lore().stream().map(messages::parseItemText).toList());
            if (settings.glint()) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (settings.itemModel() != null) {
                meta.setItemModel(settings.itemModel());
            }
            meta.setMaxStackSize(1);
            meta.getPersistentDataContainer().set(key, PersistentDataType.BOOLEAN, true);
        });
        return item;
    }

    public boolean isSelector(@Nullable ItemStack item) {
        return item != null && !item.isEmpty() && item.getPersistentDataContainer().has(key);
    }

    /**
     * Makes sure the player has exactly one up-to-date selector, preferably in the configured hotbar slot.
     *
     * @return {@code false} if their inventory is full
     */
    public boolean give(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack selector = create();
        int existing = -1;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isSelector(inventory.getItem(slot))) {
                if (existing == -1) {
                    existing = slot;
                } else {
                    inventory.setItem(slot, null);
                }
            }
        }
        if (existing != -1) {
            inventory.setItem(existing, selector);
            return true;
        }

        int preferred = module.settings().selector().slot();
        if (preferred >= 0 && isEmpty(inventory.getItem(preferred))) {
            inventory.setItem(preferred, selector);
            return true;
        }
        int free = inventory.firstEmpty();
        if (free == -1) {
            return false;
        }
        inventory.setItem(free, selector);
        return true;
    }

    /**
     * Removes every selector the player has, including one on their cursor. (Selectors can't be put in the crafting
     * grid, see {@link SelectorListener}.)
     */
    public void strip(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (isSelector(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }
        if (isSelector(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
    }

    private static boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.isEmpty();
    }
}
