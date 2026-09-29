package com.drepfy.staffvanish.selector;

import com.drepfy.staffvanish.Messages;
import com.drepfy.staffvanish.PlayerInfo;
import com.drepfy.staffvanish.VanishModule;
import com.drepfy.staffvanish.VanishSettings;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jspecify.annotations.Nullable;

/**
 * The selector menu: one head per player the staff member can teleport to, with page controls on the bottom row.
 */
public final class SelectorMenu implements InventoryHolder {

    static final int PREVIOUS_SLOT = 0;
    static final int CLOSE_SLOT = 4;
    static final int NEXT_SLOT = 8;

    private final VanishModule module;
    private final UUID viewer;
    private final Inventory inventory;
    private final int pageSize;
    private final Map<Integer, UUID> targets = new HashMap<>();
    private int page;
    private int pageCount = 1;

    public SelectorMenu(VanishModule module, Player viewer) {
        VanishSettings.Menu settings = module.settings().selector().menu();
        this.module = module;
        this.viewer = viewer.getUniqueId();
        this.pageSize = (settings.rows() - 1) * 9;
        this.inventory = Bukkit.createInventory(this, settings.rows() * 9, module.messages().parse(settings.title()));
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void open(Player player) {
        render();
        player.openInventory(inventory);
    }

    /** Fills the current page, picking up players who joined, left or moved since it was last drawn. */
    public void render() {
        Player owner = Bukkit.getPlayer(viewer);
        if (owner == null) {
            return;
        }
        VanishSettings.Menu settings = module.settings().selector().menu();
        Messages messages = module.messages();
        List<Player> players = module.teleporter().targets(owner);
        pageCount = Math.max(1, (players.size() + pageSize - 1) / pageSize);
        page = Math.clamp(page, 0, pageCount - 1);

        inventory.clear();
        targets.clear();
        int first = page * pageSize;
        for (int slot = 0; slot < pageSize && first + slot < players.size(); slot++) {
            Player target = players.get(first + slot);
            inventory.setItem(slot, head(target, settings, messages));
            targets.put(slot, target.getUniqueId());
        }

        TagResolver pageInfo = TagResolver.resolver(
                Placeholder.unparsed("page", Integer.toString(page + 1)),
                Placeholder.unparsed("pages", Integer.toString(pageCount)));
        if (page > 0) {
            inventory.setItem(pageSize + PREVIOUS_SLOT, button(Material.ARROW, settings.previousPage(), null));
        }
        inventory.setItem(pageSize + CLOSE_SLOT,
                button(Material.BARRIER, settings.close(), messages.parseItemText(settings.pageInfo(), pageInfo)));
        if (page < pageCount - 1) {
            inventory.setItem(pageSize + NEXT_SLOT, button(Material.ARROW, settings.nextPage(), null));
        }
    }

    /**
     * Handles a click on a slot of this menu. Must run outside the click event, since it may close the inventory.
     */
    public void click(Player player, int slot) {
        if (slot < pageSize) {
            UUID targetId = targets.get(slot);
            Player target = targetId != null ? Bukkit.getPlayer(targetId) : null;
            if (target == null) {
                render();
                return;
            }
            player.closeInventory();
            module.teleporter().teleport(player, target);
            return;
        }
        switch (slot - pageSize) {
            case PREVIOUS_SLOT -> {
                if (page > 0) {
                    page--;
                    render();
                }
            }
            case NEXT_SLOT -> {
                if (page < pageCount - 1) {
                    page++;
                    render();
                }
            }
            case CLOSE_SLOT -> player.closeInventory();
            default -> {
            }
        }
    }

    public int page() {
        return page;
    }

    public @Nullable UUID targetAt(int slot) {
        return targets.get(slot);
    }

    private ItemStack head(Player target, VanishSettings.Menu settings, Messages messages) {
        boolean vanished = module.manager().isVanished(target);
        TagResolver placeholders = TagResolver.resolver(
                Placeholder.unparsed("player", target.getName()),
                Placeholder.component("vanished", vanished ? messages.parse(settings.vanishedTag()) : Component.empty()),
                PlayerInfo.placeholders(target));
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        head.editMeta(SkullMeta.class, meta -> {
            meta.setPlayerProfile(target.getPlayerProfile());
            meta.customName(messages.parseItemText(settings.headName(), placeholders));
            meta.lore(settings.headLore().stream().map(line -> messages.parseItemText(line, placeholders)).toList());
        });
        return head;
    }

    private ItemStack button(Material material, String name, @Nullable Component lore) {
        ItemStack button = new ItemStack(material);
        button.editMeta(meta -> {
            meta.customName(module.messages().parseItemText(name));
            if (lore != null) {
                meta.lore(List.of(lore));
            }
        });
        return button;
    }
}
