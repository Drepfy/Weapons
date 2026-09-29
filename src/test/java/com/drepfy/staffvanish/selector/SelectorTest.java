package com.drepfy.staffvanish.selector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drepfy.staffvanish.VanishTestBase;
import java.util.Arrays;
import java.util.List;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

class SelectorTest extends VanishTestBase {

    @Test
    void vanishingGivesTheSelectorAndReappearingTakesItAway() {
        TestPlayer mod = staff("Mod");

        vanish.vanish(mod, mod);
        ItemStack selector = mod.getInventory().getItem(8);
        assertTrue(selectors().isSelector(selector));
        assertEquals(Material.BREEZE_ROD, selector.getType());
        assertEquals(MiniMessage.miniMessage().deserialize("<aqua><bold>ᴠᴀɴɪsʜ sᴛɪᴄᴋ")
                        .decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE),
                selector.getItemMeta().customName());

        vanish.unvanish(mod, mod);
        assertFalse(hasSelector(mod));
    }

    @Test
    void selectorGoesToAFreeSlotWhenThePreferredOneIsTaken() {
        TestPlayer mod = staff("Mod");
        mod.getInventory().setItem(8, new ItemStack(Material.STONE));

        vanish.vanish(mod, mod);

        assertEquals(Material.STONE, mod.getInventory().getItem(8).getType());
        assertTrue(selectors().isSelector(mod.getInventory().getItem(0)));
    }

    @Test
    void staffWithoutTeleportPermissionDontGetTheSelector() {
        TestPlayer mod = staff("Mod");
        mod.addAttachment(plugin, "staffvanish.teleport", false);

        vanish.vanish(mod, mod);

        assertFalse(hasSelector(mod));
    }

    @Test
    void leftoverSelectorsAreRemovedWhenAVisiblePlayerJoins() {
        TestPlayer mod = staff("Mod");
        mod.disconnect();
        mod.getInventory().setItem(3, selectors().create());

        mod.reconnect();

        assertFalse(hasSelector(mod));
    }

    @Test
    void rightClickOpensAMenuOfEveryoneElse() {
        TestPlayer mod = vanishedHolding("Mod");
        TestPlayer admin = staff("Admin");
        TestPlayer alice = regular("Alice");

        interact(mod, Action.RIGHT_CLICK_AIR);

        SelectorMenu menu = assertInstanceOf(SelectorMenu.class,
                mod.getOpenInventory().getTopInventory().getHolder(false));
        assertEquals(admin.getUniqueId(), menu.targetAt(0));
        assertEquals(alice.getUniqueId(), menu.targetAt(1));
        assertNull(menu.targetAt(2));
        ItemStack head = menu.getInventory().getItem(0);
        assertNotNull(head);
        assertEquals(Material.PLAYER_HEAD, head.getType());
    }

    @Test
    void sneakRightClickOpensTheHeadMenu() {
        TestPlayer mod = vanishedHolding("Mod");
        regular("Alice");
        mod.setSneaking(true);

        interact(mod, Action.RIGHT_CLICK_AIR);

        assertInstanceOf(SelectorMenu.class, mod.getOpenInventory().getTopInventory().getHolder(false));
    }

    @Test
    void sneakLeftClickTeleportsToARandomPlayer() {
        TestPlayer mod = vanishedHolding("Mod");
        TestPlayer alice = regular("Alice");
        TestPlayer bob = regular("Bob");
        alice.teleport(new Location(world, 10, 70, 10));
        bob.teleport(new Location(world, -10, 70, -10));
        mod.setSneaking(true);

        interact(mod, Action.LEFT_CLICK_AIR);

        assertTrue(mod.getLocation().equals(alice.getLocation()) || mod.getLocation().equals(bob.getLocation()),
                "teleported to one of the other players");
    }

    @Test
    void clickingAHeadTeleportsToThatPlayer() {
        TestPlayer mod = vanishedHolding("Mod");
        TestPlayer alice = regular("Alice");
        alice.teleport(new Location(world, 100, 70, -50));
        interact(mod, Action.RIGHT_CLICK_AIR);
        Inventory menu = mod.getOpenInventory().getTopInventory();

        InventoryClickEvent click = new InventoryClickEvent(mod.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, 0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(click);
        assertTrue(click.isCancelled(), "menu items can't be taken");
        server.getScheduler().performOneTick();

        assertEquals(alice.getLocation(), mod.getLocation());
        assertTrue(menu.getViewers().isEmpty(), "the menu closes");
    }

    @Test
    void menuPagesThroughManyPlayers() {
        TestPlayer mod = vanishedHolding("Mod");
        for (int i = 0; i < 50; i++) {
            regular("Player" + i);
        }
        interact(mod, Action.RIGHT_CLICK_AIR);
        SelectorMenu menu = (SelectorMenu) mod.getOpenInventory().getTopInventory().getHolder(false);
        assertNotNull(menu);
        assertNull(menu.getInventory().getItem(45 + SelectorMenu.PREVIOUS_SLOT));

        menu.click(mod, 45 + SelectorMenu.NEXT_SLOT);

        assertEquals(1, menu.page());
        assertNotNull(menu.getInventory().getItem(45 + SelectorMenu.PREVIOUS_SLOT));
        assertNull(menu.getInventory().getItem(45 + SelectorMenu.NEXT_SLOT));
    }

    @Test
    void leftClickTeleportsThroughPlayersInOrder() {
        TestPlayer mod = vanishedHolding("Mod");
        TestPlayer bob = regular("Bob");
        TestPlayer alice = regular("Alice");
        alice.teleport(new Location(world, 10, 70, 10));
        bob.teleport(new Location(world, -10, 70, -10));

        interact(mod, Action.LEFT_CLICK_AIR);
        assertEquals(alice.getLocation(), mod.getLocation());

        assertEquals(bob, module.teleporter().cycle(mod, 1));
        assertEquals(bob, module.teleporter().cycle(mod, -1));
    }

    @Test
    void rightClickingAPlayerInspectsThem() {
        TestPlayer mod = vanishedHolding("Mod");
        TestPlayer alice = regular("Alice");
        messages(mod);

        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(mod, alice, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);

        assertTrue(event.isCancelled());
        assertTrue(messages(mod).getFirst().startsWith("ᴠᴀɴɪʟʟᴀ sᴍᴘ » Inspecting Alice"));
    }

    @Test
    void selectorOnlyWorksWhileVanished() {
        TestPlayer mod = staff("Mod");
        mod.getInventory().setItem(0, selectors().create());
        mod.getInventory().setHeldItemSlot(0);
        messages(mod);

        PlayerInteractEvent event = interact(mod, Action.RIGHT_CLICK_AIR);

        assertEquals(Event.Result.DENY, event.useItemInHand());
        assertEquals(List.of("ᴠᴀɴɪʟʟᴀ sᴍᴘ » You need to be vanished to do that."), messages(mod));
    }

    @Test
    void selectorCannotBeDroppedOrSwappedOrLeftInTheWorld() {
        TestPlayer mod = vanishedHolding("Mod");
        Item item = world.dropItem(mod.getLocation(), new ItemStack(Material.STONE));
        item.setItemStack(selectors().create());

        PlayerDropItemEvent drop = new PlayerDropItemEvent(mod, item);
        ItemSpawnEvent spawn = new ItemSpawnEvent(item);
        PlayerSwapHandItemsEvent swap = new PlayerSwapHandItemsEvent(mod, new ItemStack(Material.AIR),
                mod.getInventory().getItemInMainHand());
        server.getPluginManager().callEvent(drop);
        server.getPluginManager().callEvent(spawn);
        server.getPluginManager().callEvent(swap);

        assertTrue(drop.isCancelled());
        assertTrue(spawn.isCancelled());
        assertTrue(swap.isCancelled());
    }

    @Test
    void selectorCannotBePutInAContainer() {
        TestPlayer mod = vanishedHolding("Mod");
        Inventory chest = server.createInventory(null, 27);
        InventoryView view = mod.openInventory(chest);
        assertNotNull(view);
        view.setCursor(selectors().create());

        InventoryClickEvent click = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PLACE_ALL);
        server.getPluginManager().callEvent(click);

        assertTrue(click.isCancelled());
    }

    @Test
    void reloadingUpdatesSelectorsInPlace() {
        TestPlayer mod = vanishedHolding("Mod");
        plugin.getConfig().set("selector.item.material", "BLAZE_ROD");
        plugin.saveConfig();

        module.reload();

        ItemStack selector = mod.getInventory().getItem(8);
        assertTrue(selectors().isSelector(selector));
        assertEquals(Material.BLAZE_ROD, selector.getType());
        assertEquals(1, Arrays.stream(mod.getInventory().getContents()).filter(selectors()::isSelector).count());
    }

    private PlayerInteractEvent interact(Player player, Action action) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, action, player.getInventory().getItemInMainHand(),
                null, BlockFace.SELF, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event;
    }
}
