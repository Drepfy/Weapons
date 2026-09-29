package com.drepfy.staffvanish;

import com.drepfy.staffvanish.staff.InventorySpy;
import com.drepfy.staffvanish.staff.StaffPreferences.Option;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaffToolsTest extends VanishTestBase {

    @Test
    void juniorStaffCannotSeeHigherLevelVanishedStaff() {
        TestPlayer owner = staff("Owner");
        owner.addAttachment(plugin, "staffvanish.level.5", true);
        TestPlayer mod = staff("Mod");
        owner.recalculatePermissions();

        vanish.vanish(owner, owner);
        vanish.vanish(mod, mod);

        assertEquals(5, vanish.level(owner));
        assertEquals(0, vanish.level(mod));
        assertFalse(vanish.canSee(mod, owner), "a level 0 moderator must not see a level 5 owner");
        assertFalse(mod.canSee(owner));
        assertTrue(vanish.canSee(owner, mod), "the owner still sees the moderator");
        assertTrue(owner.canSee(mod));
    }

    @Test
    void levelsCanBeTurnedOff() {
        plugin.getConfig().set("staff-tools.vanish-levels", false);
        plugin.saveConfig();
        module.reload();
        TestPlayer owner = staff("Owner");
        owner.addAttachment(plugin, "staffvanish.level.5", true);
        TestPlayer mod = staff("Mod");
        vanish.vanish(owner, owner);
        assertTrue(vanish.canSee(mod, owner));
    }

    @Test
    void nightVisionIsGivenWhileVanishedAndTakenAwayAfter() {
        TestPlayer mod = staff("Mod");
        vanish.vanish(mod, mod);
        assertTrue(mod.hasPotionEffect(PotionEffectType.NIGHT_VISION));

        vanish.unvanish(mod, mod);
        assertFalse(mod.hasPotionEffect(PotionEffectType.NIGHT_VISION));
    }

    @Test
    void nightVisionFromElsewhereIsNeverRemoved() {
        TestPlayer mod = staff("Mod");
        mod.addPotionEffect(new PotionEffect(PotionEffectType.NIGHT_VISION, 20 * 60, 0));
        vanish.vanish(mod, mod);
        vanish.unvanish(mod, mod);
        assertTrue(mod.hasPotionEffect(PotionEffectType.NIGHT_VISION), "a potion the player drank must survive");
    }

    @Test
    void preferencesAreRememberedAndApplied() {
        TestPlayer mod = staff("Mod");
        assertTrue(module.preferences().get(mod, Option.NIGHT_VISION));
        assertFalse(module.preferences().toggle(mod, Option.NIGHT_VISION));
        vanish.vanish(mod, mod);
        assertFalse(mod.hasPotionEffect(PotionEffectType.NIGHT_VISION));

        module.preferences().toggle(mod, Option.GLOW);
        module.effects().refresh(mod);
        assertTrue(mod.isGlowing());
        vanish.unvanish(mod, mod);
        assertFalse(mod.isGlowing());
    }

    @Test
    void invseeShowsALiveReadOnlyCopy() {
        TestPlayer mod = staff("Mod");
        TestPlayer player = regular("Steve");
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 5));
        player.getInventory().setItem(9, new ItemStack(Material.DIRT, 1));

        module.spy().open(mod, player, InventorySpy.Kind.INVENTORY);
        Inventory top = mod.getOpenInventory().getTopInventory();
        assertInstanceOf(InventorySpy.View.class, top.getHolder());
        assertEquals(Material.DIRT, top.getItem(0).getType(), "main inventory first");
        assertEquals(Material.DIAMOND, top.getItem(27).getType(), "hotbar in the fourth row");

        player.getInventory().setItem(0, new ItemStack(Material.EMERALD, 1));
        server.getScheduler().performTicks(10);
        assertEquals(Material.EMERALD, top.getItem(27).getType(), "the view follows changes");

        InventoryView view = mod.getOpenInventory();
        InventoryClickEvent click = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 27,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(click);
        assertTrue(click.isCancelled(), "items can't be taken");
        assertEquals(Material.EMERALD, player.getInventory().getItem(0).getType());
    }

    @Test
    void invseeCommandRespectsVanish() {
        TestPlayer owner = staff("Owner");
        owner.addAttachment(plugin, "staffvanish.level.5", true);
        TestPlayer mod = staff("Mod");
        vanish.vanish(owner, owner);
        messages(mod);

        mod.performCommand("invsee Owner");
        Inventory top = mod.getOpenInventory().getTopInventory();
        assertFalse(top != null && top.getHolder() instanceof InventorySpy.View,
                "can't spy on someone you can't see");
        assertTrue(messages(mod).stream().anyMatch(line -> line.contains("No online player")));
    }

    @Test
    void settingsMenuOpens() {
        TestPlayer mod = staff("Mod");
        mod.performCommand("vanish settings");
        Inventory top = mod.getOpenInventory().getTopInventory();
        assertNotNull(top);
        assertEquals(36, top.getSize());
    }
}
