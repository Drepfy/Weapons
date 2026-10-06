package io.github.drepfy.legendary;

import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.registry.WeaponRecord;
import io.github.drepfy.legendary.registry.WeaponRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BossBar;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.LivingEntityMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The legendary weapons on a simulated server. */
class LegendaryTest {

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private ServerMock server;
    private WorldMock world;
    private LegendaryPlugin plugin;
    private int players;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        for (int x = -24; x <= 24; x++) {
            for (int z = -24; z <= 24; z++) {
                world.getBlockAt(x, 63, z).setType(Material.STONE);
            }
        }
        plugin = MockBukkit.load(LegendaryPlugin.class);
        plugin.setClock(clock::get);
        // A real server fires the damage event inside damage(); the simulated one only in simulateDamage().
        plugin.hits().setDamager((target, amount, attacker) -> ((LivingEntityMock) target).simulateDamage(amount, attacker));
        tick(1);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    /** A player standing on the stone floor, facing south (+z). */
    private PlayerMock player(String name, double x, double z) {
        // Each player gets their own address (the simulated ones are random and can collide).
        PlayerMock player = new PlayerMock(server, name);
        player.setAddress(new java.net.InetSocketAddress("10.7." + (++players) + ".1", 25565));
        server.addPlayer(player);
        player.teleport(new Location(world, x + 0.5, 64, z + 0.5, 0f, 0f));
        player.setHealth(20.0);
        chat(player);
        return player;
    }

    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            clock.addAndGet(50);
            server.getScheduler().performTicks(1);
        }
    }

    private void command(String line) {
        server.dispatchCommand(server.getConsoleSender(), line);
    }

    /** Gives the weapon with /legendary give and holds it (the returned item is the one in hand). */
    private ItemStack give(PlayerMock player, WeaponType type) {
        command("legendary give " + player.getName() + " " + type.key());
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            WeaponItems.Tag tag = plugin.items().read(item);
            if (tag != null && tag.type() == type) {
                if (slot < 9) {
                    player.getInventory().setHeldItemSlot(slot);
                } else {
                    player.getInventory().setItem(slot, null);
                    player.getInventory().setItemInMainHand(item);
                }
                return player.getInventory().getItemInMainHand();
            }
        }
        throw new AssertionError(player.getName() + " did not get " + type.key());
    }

    private UUID id(ItemStack item) {
        return plugin.items().read(item).id();
    }

    private WeaponRecord record(ItemStack item) {
        return plugin.registry().get(id(item));
    }

    /** F (swap hands): the default key for a weapon's first ability. */
    private PlayerSwapHandItemsEvent useKey(PlayerMock player) {
        PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(player,
                player.getInventory().getItemInOffHand(), player.getInventory().getItemInMainHand());
        server.getPluginManager().callEvent(event);
        return event;
    }

    /** A left-click (the arm swings). */
    private void swing(PlayerMock player) {
        server.getPluginManager().callEvent(new PlayerAnimationEvent(player, PlayerAnimationType.ARM_SWING));
    }

    /** Shift + F: the second ability. */
    private PlayerSwapHandItemsEvent sneakUseKey(PlayerMock player) {
        player.setSneaking(true);
        try {
            return useKey(player);
        } finally {
            player.setSneaking(false);
        }
    }

    private void controls(String controls) {
        plugin.getConfig().set("controls", controls);
        plugin.saveConfig();
        assertEquals(List.of(), plugin.reload());
    }

    private PlayerInteractEvent rightClick(PlayerMock player) {
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                player.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private PlayerInteractEvent sneakRightClick(PlayerMock player) {
        player.setSneaking(true);
        try {
            return rightClick(player);
        } finally {
            player.setSneaking(false);
        }
    }

    /** A sword or axe hit (as the server reports it). */
    private EntityDamageByEntityEvent melee(PlayerMock attacker, PlayerMock target, double damage) {
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(attacker, target,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build(), damage);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private static List<String> chat(PlayerMock player) {
        List<String> lines = new ArrayList<>();
        String line;
        while ((line = player.nextMessage()) != null) {
            lines.add(ChatColor.stripColor(line));
        }
        return lines;
    }

    /** What was shown above the hotbar since the last call. */
    private static List<String> actionBars(PlayerMock player) {
        List<String> bars = new ArrayList<>();
        Component bar;
        while ((bar = player.nextActionBar()) != null) {
            bars.add(PlainTextComponentSerializer.plainText().serialize(bar));
        }
        return bars;
    }

    /** Back to the start, facing south (+z); a pitch of 15 looks at the ground about 6 blocks ahead. */
    private void stand(PlayerMock player, double x, double z, float pitch) {
        player.teleport(new Location(world, x + 0.5, 64, z + 0.5, 0f, pitch), PlayerTeleportEvent.TeleportCause.COMMAND);
    }

    private long cooldown(ItemStack weapon, Ability ability) {
        return plugin.abilities().cooldowns().remaining(id(weapon), ability, plugin.tick());
    }

    /**
     * An item property from 1.21.2+ (item model, tooltip style), or null where the simulated server
     * does not have it yet (a real one does).
     */
    private static <T> T modern(java.util.function.Supplier<T> getter) {
        try {
            return getter.get();
        } catch (org.mockbukkit.mockbukkit.exception.UnimplementedOperationException e) {
            return null;
        }
    }

    private static void assertModern(String expected, java.util.function.Supplier<?> getter) {
        Object value = modern(getter);
        if (value != null) {
            assertEquals(expected, String.valueOf(value));
        }
    }

    private static String title(BossBar bar) {
        return ChatColor.stripColor(bar.getTitle());
    }

    private static boolean has(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    private int count(Player player, WeaponType type) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            WeaponItems.Tag tag = plugin.items().read(item);
            if (tag != null && tag.type() == type) {
                count++;
            }
        }
        return count;
    }

    private void protect(Player protectedPlayer) {
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGH)
            public void onDamage(EntityDamageByEntityEvent event) {
                if (event.getEntity().equals(protectedPlayer)) {
                    event.setCancelled(true); // Like a no-PvP region.
                }
            }
        }, plugin);
    }

    /** The player's own inventory screen: the 2x2 crafting grid on top (the simulated one cannot map slots). */
    private InventoryView ownView(PlayerMock player) {
        Inventory grid = new org.mockbukkit.mockbukkit.inventory.InventoryMock(player, 5, InventoryType.CRAFTING);
        return new org.mockbukkit.mockbukkit.inventory.SimpleInventoryViewMock(player, grid, player.getInventory(),
                InventoryType.CRAFTING) {
            @Override
            public int convertSlot(int raw) {
                int top = getTopInventory().getSize();
                return raw < top ? raw : raw - top;
            }

            @Override
            public Inventory getInventory(int raw) {
                return raw < 0 ? null : raw < getTopInventory().getSize() ? getTopInventory() : getBottomInventory();
            }
        };
    }

    /** The raw slot of the bottom inventory that shows this item. */
    private static int rawSlotOf(InventoryView view, ItemStack item) {
        for (int raw = view.getTopInventory().getSize(); raw < view.countSlots(); raw++) {
            if (item.equals(view.getItem(raw))) {
                return raw;
            }
        }
        throw new AssertionError("not in the view");
    }

    private InventoryClickEvent click(InventoryView view, int rawSlot, ClickType click, InventoryAction action) {
        InventoryClickEvent event = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, rawSlot, click, action);
        server.getPluginManager().callEvent(event);
        return event;
    }

    // ---- the weapons ------------------------------------------------------------------------------------

    @Test
    void fiveNetheriteWeaponsWithTheirEnchantmentsNamesLoreAndIds() {
        PlayerMock steve = player("Steve", 0, 0);
        List<UUID> ids = new ArrayList<>();
        for (WeaponType type : WeaponType.values()) {
            ItemStack item = give(steve, type);
            boolean axe = type == WeaponType.GRAVEBREAKER || type == WeaponType.STARFORGED;
            assertEquals(axe ? Material.NETHERITE_AXE : Material.NETHERITE_SWORD, item.getType());
            ItemMeta meta = item.getItemMeta();
            Map<String, Integer> enchantments = new HashMap<>();
            meta.getEnchants().forEach((enchantment, level) -> enchantments.put(enchantment.getKey().getKey(), level));
            assertEquals(axe ? Map.of("sharpness", 7, "efficiency", 5, "fortune", 3)
                    : Map.of("sharpness", 7, "fire_aspect", 2, "looting", 3, "sweeping_edge", 3), enchantments, type.key());
            assertTrue(meta.isUnbreakable());
            assertTrue(meta.hasCustomModelData(), "older clients still get the model");
            // The 1.21 look: its own model and tooltip frame from the pack, always shimmering.
            assertModern("legendary:" + type.key(), meta::getItemModel);
            assertModern("legendary:" + type.key(), meta::getTooltipStyle);
            assertTrue(meta.hasEnchantmentGlintOverride() && meta.getEnchantmentGlintOverride(),
                    "the shimmer is always on, like on the Heart items");
            assertFalse(meta.hasItemFlag(ItemFlag.HIDE_ENCHANTS), "the game lists them under the name, like on any item");
            assertFalse(meta.hasItemFlag(ItemFlag.HIDE_UNBREAKABLE));
            String name = ChatColor.stripColor(meta.getDisplayName());
            assertEquals(type.key(), name.toLowerCase(), "named " + name);
            String lore = ChatColor.stripColor(String.join("\n", meta.getLore()));
            for (Ability ability : type.abilities()) {
                assertTrue(lore.contains(plugin.settings().ability(ability).name()), type + " lore names " + ability);
            }
            assertFalse(lore.contains("Sharpness"), "not twice: " + lore);
            // The real attack damage, Sharpness VII included (vanilla would say 8 and 10).
            assertTrue(lore.endsWith("When in Main Hand:\n " + (axe ? "14 Attack Damage\n 1 Attack Speed"
                    : "12 Attack Damage\n 1.6 Attack Speed")), lore);
            assertTrue(meta.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES), "instead of vanilla's lines");
            assertFalse(lore.contains(WeaponItems.shortId(id(item))), "no tracking number in the lore");
            assertFalse(lore.contains("LEGENDARY"), "no LEGENDARY line at the bottom");
            assertEquals(WeaponRecord.State.HELD, record(item).state());
            assertEquals(steve.getUniqueId(), record(item).holder());
            ids.add(id(item));
        }
        assertEquals(5, ids.stream().distinct().count(), "every weapon has its own id");
        // A plain netherite sword with the same name is not a legendary.
        ItemStack fake = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = fake.getItemMeta();
        meta.setDisplayName("Kurogane");
        fake.setItemMeta(meta);
        assertFalse(plugin.items().isLegendary(fake));
    }

    @Test
    void onlyOneOfEachUntilRemoved() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 3, 0);
        give(steve, WeaponType.KUROGANE);
        command("legendary give Alex kurogane");
        assertEquals(0, count(alex, WeaponType.KUROGANE), "a second Kurogane is refused");
        command("legendary remove Steve kurogane");
        assertEquals(0, count(steve, WeaponType.KUROGANE));
        command("legendary give Alex kurogane");
        assertEquals(1, count(alex, WeaponType.KUROGANE));
    }

    @Test
    void cannotBeCrafted() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        InventoryView view = ownView(steve);
        steve.setItemOnCursor(sword.clone());
        steve.getInventory().setItemInMainHand(null);
        assertTrue(click(view, 1, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled(),
                "not even into the 2x2 crafting grid (two swords repair into a plain one)");
    }

    // ---- storage ----------------------------------------------------------------------------------------

    @Test
    void neverIntoAnyContainer() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.SUGARCRASH);
        for (InventoryType type : List.of(InventoryType.CHEST, InventoryType.BARREL, InventoryType.SHULKER_BOX,
                InventoryType.HOPPER, InventoryType.DROPPER, InventoryType.DISPENSER, InventoryType.FURNACE,
                InventoryType.ANVIL, InventoryType.WORKBENCH)) {
            Inventory container = server.createInventory(null, type);
            InventoryView view = steve.openInventory(container);
            // Placing it from the cursor.
            steve.setItemOnCursor(sword);
            steve.getInventory().setItemInMainHand(null);
            assertTrue(click(view, 0, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled(), type + ": place");
            // Shift-clicking it from the inventory.
            steve.setItemOnCursor(null);
            steve.getInventory().setItem(9, sword);
            int raw = rawSlotOf(view, sword);
            assertTrue(click(view, raw, ClickType.SHIFT_LEFT, InventoryAction.MOVE_TO_OTHER_INVENTORY).isCancelled(),
                    type + ": shift-click");
            steve.getInventory().setItem(9, null);
            // A number key over the container slot.
            steve.getInventory().setItem(0, sword);
            InventoryClickEvent swap = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 0,
                    ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 0);
            server.getPluginManager().callEvent(swap);
            assertTrue(swap.isCancelled(), type + ": number key");
            // Dragging over it.
            Map<Integer, ItemStack> slots = new HashMap<>();
            slots.put(0, sword);
            InventoryDragEvent drag = new InventoryDragEvent(view, null, sword, false, slots);
            server.getPluginManager().callEvent(drag);
            assertTrue(drag.isCancelled(), type + ": drag");
            steve.closeInventory();
            steve.getInventory().setItemInMainHand(sword);
            steve.getInventory().setItem(0, sword);
        }
        assertTrue(has(chat(steve), "can't go in containers"));
        // The ender chest too.
        InventoryView ender = steve.openInventory(steve.getEnderChest());
        steve.setItemOnCursor(sword);
        assertTrue(click(ender, 0, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled(), "ender chest");
    }

    @Test
    void movingItAroundYourOwnInventoryIsFine() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.RIFTBLADE);
        InventoryView view = ownView(steve);
        steve.getInventory().setItemInMainHand(null);
        steve.setItemOnCursor(sword);
        int raw = view.getTopInventory().getSize() + 9;
        assertFalse(click(view, raw, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled());
    }

    @Test
    void notInBundlesFramesOrStands() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        InventoryView view = ownView(steve);
        int raw = view.getTopInventory().getSize() + 9;
        steve.setItemOnCursor(new ItemStack(Material.BUNDLE));
        steve.getInventory().setItem(9, sword);
        assertTrue(click(view, raw, ClickType.RIGHT, InventoryAction.PICKUP_HALF).isCancelled(), "bundle on the sword");
        steve.setItemOnCursor(sword);
        steve.getInventory().setItem(9, new ItemStack(Material.BUNDLE));
        assertTrue(click(view, raw, ClickType.LEFT, InventoryAction.SWAP_WITH_CURSOR).isCancelled(), "sword on a bundle");
        steve.setItemOnCursor(null);
        steve.getInventory().setItemInMainHand(sword);

        org.bukkit.entity.ArmorStand stand = (org.bukkit.entity.ArmorStand) world.spawnEntity(
                new Location(world, 1, 64, 1), EntityType.ARMOR_STAND);
        org.bukkit.event.player.PlayerArmorStandManipulateEvent manipulate =
                new org.bukkit.event.player.PlayerArmorStandManipulateEvent(steve, stand, sword,
                        new ItemStack(Material.AIR), EquipmentSlot.HAND, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(manipulate);
        assertTrue(manipulate.isCancelled(), "armour stand");

        // A decorated pot would take the held item.
        world.getBlockAt(2, 64, 2).setType(Material.DECORATED_POT);
        PlayerInteractEvent pot = new PlayerInteractEvent(steve, Action.RIGHT_CLICK_BLOCK, sword,
                world.getBlockAt(2, 64, 2), BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(pot);
        assertEquals(org.bukkit.event.Event.Result.DENY, pot.useInteractedBlock(), "decorated pot");
    }

    @Test
    void hoppersAndMobsCannotPickItUp() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        steve.getInventory().setItemInMainHand(null);
        Item dropped = world.dropItem(new Location(world, 0.5, 64, 0.5), sword);
        InventoryPickupItemEvent hopper = new InventoryPickupItemEvent(server.createInventory(null, InventoryType.HOPPER),
                dropped);
        server.getPluginManager().callEvent(hopper);
        assertTrue(hopper.isCancelled(), "hopper");
        Zombie zombie = (Zombie) world.spawnEntity(new Location(world, 0.5, 64, 0.5), EntityType.ZOMBIE);
        EntityPickupItemEvent mob = new EntityPickupItemEvent(zombie, dropped, 0);
        server.getPluginManager().callEvent(mob);
        assertTrue(mob.isCancelled(), "zombie");
        ItemDespawnEvent despawn = new ItemDespawnEvent(dropped, dropped.getLocation());
        server.getPluginManager().callEvent(despawn);
        assertTrue(despawn.isCancelled(), "never despawns");
    }

    @Test
    void foundInAContainerItIsGivenBack() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.STARFORGED);
        steve.getInventory().setItemInMainHand(null);
        Inventory chest = server.createInventory(null, InventoryType.CHEST);
        chest.setItem(4, sword); // Put there before the plugin was installed.
        steve.openInventory(chest);
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryOpenEvent(steve.getOpenInventory()));
        assertNull(chest.getItem(4));
        assertEquals(1, count(steve, WeaponType.STARFORGED));
        // And from the ender chest when joining.
        steve.closeInventory();
        steve.getInventory().clear();
        steve.getEnderChest().setItem(0, sword);
        steve.disconnect();
        steve.reconnect();
        tick(2);
        assertNull(steve.getEnderChest().getItem(0));
        assertEquals(1, count(steve, WeaponType.STARFORGED));
    }

    @Test
    void sellCommandsAreRefusedForTheLegendaryOnly() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack axe = give(steve, WeaponType.GRAVEBREAKER);
        for (String line : List.of("/sell hand", "/ah sell 1k", "/essentials:sellhand", "/auction sell 10m")) {
            PlayerCommandPreprocessEvent held = new PlayerCommandPreprocessEvent(steve, line);
            server.getPluginManager().callEvent(held);
            assertTrue(held.isCancelled(), line + " with the legendary in hand");
        }
        // Holding something else: the auction house works for the rest of the inventory.
        steve.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 16));
        steve.getInventory().setItem(8, axe);
        PlayerCommandPreprocessEvent diamonds = new PlayerCommandPreprocessEvent(steve, "/ah sell 1k");
        server.getPluginManager().callEvent(diamonds);
        assertFalse(diamonds.isCancelled(), "selling diamonds while owning a legendary");
        PlayerCommandPreprocessEvent all = new PlayerCommandPreprocessEvent(steve, "/sellall");
        server.getPluginManager().callEvent(all);
        assertTrue(all.isCancelled(), "a whole-inventory sell is refused while carrying one");
        PlayerCommandPreprocessEvent spawn = new PlayerCommandPreprocessEvent(steve, "/spawn");
        server.getPluginManager().callEvent(spawn);
        assertFalse(spawn.isCancelled(), "other commands work");
    }

    // ---- duplicates and tracking -----------------------------------------------------------------------

    @Test
    void aCopyInSomeoneElsesHandsIsDeleted() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 5, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        alex.getInventory().addItem(sword.clone()); // A dupe glitch.
        plugin.tracker().scan();
        assertEquals(1, count(steve, WeaponType.KUROGANE), "the original stays");
        assertEquals(0, count(alex, WeaponType.KUROGANE), "the copy is gone");
        assertTrue(has(chat(alex), "can't be duplicated"));
        // Two in one inventory.
        steve.getInventory().addItem(sword.clone());
        plugin.tracker().scan();
        assertEquals(1, count(steve, WeaponType.KUROGANE));
    }

    @Test
    void aCopyCannotUseAbilities() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 5, 0);
        ItemStack sword = give(steve, WeaponType.SUGARCRASH);
        alex.getInventory().setItemInMainHand(sword.clone());
        useKey(alex);
        tick(12);
        assertEquals(0.0, alex.getVelocity().length(), 1.0E-9, "no dash");
        assertFalse(alex.hasPotionEffect(PotionEffectType.SPEED));
        assertEquals(0, count(alex, WeaponType.SUGARCRASH));
    }

    @Test
    void creativeMiddleClickCannotCopyIt() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        steve.getInventory().setItem(9, sword);
        InventoryView view = ownView(steve);
        InventoryClickEvent clone = click(view, view.getTopInventory().getSize() + 9, ClickType.MIDDLE,
                InventoryAction.CLONE_STACK);
        assertTrue(clone.isCancelled());
    }

    @Test
    void removedFromAnOfflinePlayerItDisappearsWhenTheyJoin() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.RIFTBLADE);
        steve.disconnect();
        command("legendary remove Steve riftblade");
        steve.reconnect();
        tick(2);
        assertEquals(0, count(steve, WeaponType.RIFTBLADE));
        assertTrue(has(chat(steve), "taken away by staff"));
        command("legendary give Steve riftblade");
        assertEquals(1, count(steve, WeaponType.RIFTBLADE), "and a new one can be given");
    }

    @Test
    void droppedAndPickedUpItIsFollowed() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 3, 0);
        ItemStack sword = give(steve, WeaponType.GRAVEBREAKER);
        steve.getInventory().setItemInMainHand(null);
        Item dropped = world.dropItem(steve.getLocation(), sword);
        server.getPluginManager().callEvent(new PlayerDropItemEvent(steve, dropped));
        assertEquals(WeaponRecord.State.GROUND, record(sword).state());
        assertTrue(dropped.isInvulnerable(), "lava, fire and explosions cannot destroy it");
        assertTrue(dropped.isGlowing());
        EntityPickupItemEvent pickup = new EntityPickupItemEvent(alex, dropped, 0);
        server.getPluginManager().callEvent(pickup);
        assertFalse(pickup.isCancelled());
        assertEquals(WeaponRecord.State.HELD, record(sword).state());
        assertEquals(alex.getUniqueId(), record(sword).holder());
        assertEquals("Steve", record(sword).previousHolderName());
    }

    @Test
    void altAccountsCannotPassItOn() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alt = player("SteveAlt", 2, 0);
        PlayerMock bob = player("Bob", 4, 0);
        plugin.registry().recordIp(steve.getUniqueId(), "81.2.69.160");
        plugin.registry().recordIp(alt.getUniqueId(), "81.2.69.160");
        plugin.registry().recordIp(bob.getUniqueId(), "175.16.199.1");
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        steve.getInventory().setItemInMainHand(null);
        Item dropped = world.dropItem(steve.getLocation(), sword);
        server.getPluginManager().callEvent(new PlayerDropItemEvent(steve, dropped));
        EntityPickupItemEvent byAlt = new EntityPickupItemEvent(alt, dropped, 0);
        server.getPluginManager().callEvent(byAlt);
        assertTrue(byAlt.isCancelled(), "same IP: refused");
        assertTrue(has(chat(alt), "can't move between your own accounts"));
        EntityPickupItemEvent byBob = new EntityPickupItemEvent(bob, dropped, 0);
        server.getPluginManager().callEvent(byBob);
        assertFalse(byBob.isCancelled(), "anyone else can take it");
    }

    @Test
    void dropsOnDeathEvenWithKeepInventoryAndNeverIntoGraves() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.STARFORGED);
        List<ItemStack> grave = new ArrayList<>();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST)
            public void onDeath(PlayerDeathEvent event) {
                grave.addAll(event.getDrops()); // A grave plugin taking the drops...
                for (ItemStack item : event.getEntity().getInventory().getContents()) {
                    if (item != null) {
                        grave.add(item); // ...or reading the inventory itself.
                    }
                }
            }
        }, plugin);
        List<ItemStack> drops = new ArrayList<>();
        for (ItemStack item : steve.getInventory().getContents()) {
            if (item != null) {
                drops.add(item);
            }
        }
        PlayerDeathEvent death = new PlayerDeathEvent(steve, DamageSource.builder(DamageType.GENERIC).build(), drops,
                0, "Steve died");
        death.setKeepInventory(true);
        server.getPluginManager().callEvent(death);
        assertTrue(grave.stream().noneMatch(plugin.items()::isLegendary), "graves never get it");
        assertEquals(0, count(steve, WeaponType.STARFORGED), "not kept, even with keepInventory");
        assertEquals(WeaponRecord.State.GROUND, record(sword).state());
        Item onGround = world.getEntitiesByClass(Item.class).stream()
                .filter(item -> plugin.items().isLegendary(item.getItemStack())).findFirst().orElse(null);
        assertNotNull(onGround, "it lies where Steve died");
        assertTrue(onGround.isInvulnerable());
    }

    @Test
    void staffLookingIntoSomeonesInventoryDoNotTakeTheirWeapon() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock admin = player("Admin", 3, 3);
        give(steve, WeaponType.KUROGANE);
        admin.openInventory(steve.getInventory()); // /invsee
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryOpenEvent(admin.getOpenInventory()));
        plugin.tracker().scan();
        assertEquals(1, count(steve, WeaponType.KUROGANE));
        assertEquals(0, count(admin, WeaponType.KUROGANE));
    }

    @Test
    void aWeaponThatVanishesStaysWithItsHolderUnlessMarkLostIsOn() {
        PlayerMock admin = player("Admin", 5, 5);
        admin.setOp(true);
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack old = give(steve, WeaponType.SUGARCRASH);
        UUID oldId = id(old);
        chat(admin);
        steve.getInventory().clear(); // /clear, or a plugin deleting it
        tick(10 * 20);
        assertEquals(WeaponRecord.State.HELD, plugin.registry().get(oldId).state(), "not marked lost by default");
        assertFalse(has(chat(admin), "marked as lost"), "and no alert");

        plugin.getConfig().set("mark-lost", true);
        plugin.saveConfig();
        assertEquals(List.of(), plugin.reload());
        tick(60);
        assertEquals(WeaponRecord.State.HELD, plugin.registry().get(oldId).state(), "not after a moment (creative cursor)");
        tick(6 * 20);
        assertEquals(WeaponRecord.State.LOST, plugin.registry().get(oldId).state());
        assertTrue(has(chat(admin), "marked as lost"), "staff are told");
        command("legendary give Steve sugarcrash");
        assertEquals(1, count(steve, WeaponType.SUGARCRASH), "a lost weapon can be given out again");
        assertEquals(WeaponRecord.State.REMOVED, plugin.registry().get(oldId).state(), "the old one is retired");
        // If the old copy ever turns up, it is deleted rather than becoming a second one.
        PlayerMock alex = player("Alex", 3, 0);
        alex.getInventory().addItem(old);
        plugin.tracker().scan();
        assertEquals(0, count(alex, WeaponType.SUGARCRASH));
    }

    @Test
    void aDeathThatIsCancelledGivesTheWeaponBack() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack axe = give(steve, WeaponType.GRAVEBREAKER);
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGH)
            public void onDeath(PlayerDeathEvent event) {
                event.setCancelled(true); // A plugin saving the player.
            }
        }, plugin);
        PlayerDeathEvent death = new PlayerDeathEvent(steve, DamageSource.builder(DamageType.GENERIC).build(),
                new ArrayList<>(List.of(axe)), 0, "Steve died");
        server.getPluginManager().callEvent(death);
        assertEquals(1, count(steve, WeaponType.GRAVEBREAKER));
        assertEquals(WeaponRecord.State.HELD, record(axe).state());
    }

    @Test
    void theRegistryIsKeptAcrossRestarts() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        plugin.registry().close(0);
        WeaponRegistry reloaded = new WeaponRegistry(plugin.getLogger(), plugin.getDataFolder().toPath(), clock::get);
        WeaponRecord record = reloaded.get(id(sword));
        assertNotNull(record);
        assertEquals(WeaponType.KUROGANE, record.type());
        assertEquals(WeaponRecord.State.HELD, record.state());
        assertEquals("Steve", record.holderName());
        reloaded.close(0);
    }

    // ---- controls ---------------------------------------------------------------------------------------

    @Test
    void fUsesTheAbilitiesAndKeepsTheWeaponInHand() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack scythe = give(steve, WeaponType.SUGARCRASH);
        rightClick(steve);
        assertEquals(0, cooldown(scythe, Ability.CANDY_HOOK), "right-click no longer uses abilities");
        PlayerSwapHandItemsEvent f = useKey(steve);
        assertTrue(f.isCancelled(), "the weapon is not swapped into the offhand");
        assertTrue(cooldown(scythe, Ability.CANDY_HOOK) > 0, "F: Candy Hook");
        assertEquals(scythe, steve.getInventory().getItemInMainHand());
        sneakUseKey(steve);
        assertTrue(plugin.abilities().active(steve, plugin.items().read(scythe), Ability.CANDY_BARRAGE, plugin.tick()) > 0,
                "Shift + F: Candy Barrage");
        String lore = ChatColor.stripColor(String.join("\n", scythe.getItemMeta().getLore()));
        assertTrue(lore.contains("F » Candy Hook") && lore.contains("Shift + F » Candy Barrage"), lore);
        assertTrue(lore.contains("Passive » Sugar High"), lore);
        // A legendary in the offhand swaps back to the main hand as usual.
        steve.getInventory().setItemInMainHand(new ItemStack(Material.BREAD));
        steve.getInventory().setItemInOffHand(scythe);
        assertFalse(useKey(steve).isCancelled());
    }

    @Test
    void rightClickControlsStillWork() {
        controls("right-click");
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack scythe = give(steve, WeaponType.SUGARCRASH);
        assertFalse(useKey(steve).isCancelled(), "F swaps hands as usual");
        assertEquals(0, cooldown(scythe, Ability.CANDY_HOOK));
        rightClick(steve);
        assertTrue(cooldown(scythe, Ability.CANDY_HOOK) > 0, "right-click: Candy Hook");
        String lore = ChatColor.stripColor(String.join("\n", steve.getInventory().getItemInMainHand().getItemMeta().getLore()));
        assertTrue(lore.contains("Right-click » Candy Hook") && lore.contains("Sneak + right-click » Candy Barrage"), lore);

        controls("both");
        PlayerMock alex = player("Alex", 9, 0);
        ItemStack blade = give(alex, WeaponType.RIFTBLADE);
        assertTrue(useKey(alex).isCancelled(), "both: F works too");
        assertTrue(cooldown(blade, Ability.VOID_REND) > 0, "Void Rend was used");
    }

    @Test
    void eatingFromTheOffhandDoesNotWasteTheAbility() {
        controls("right-click");
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack scythe = give(steve, WeaponType.SUGARCRASH);
        steve.getInventory().setItemInOffHand(new ItemStack(Material.GOLDEN_APPLE));
        rightClick(steve);
        assertEquals(0, cooldown(scythe, Ability.CANDY_HOOK), "the golden apple is eaten instead");
        steve.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        tick(5);
        rightClick(steve);
        assertTrue(cooldown(scythe, Ability.CANDY_HOOK) > 0, "with a shield the ability still works");
        tick(5);
        rightClick(steve); // Blocking while it recharges.
        tick(2);
        assertEquals(BarColor.PINK, plugin.hud().bars(steve).get(Ability.CANDY_HOOK).getColor(), "no nagging while blocking");
    }

    // ---- Kurogane ---------------------------------------------------------------------------------------

    @Test
    void crimsonFlashHasTwoChargesThenItsCooldown() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 4);
        PlayerMock behind = player("Behind", 0, -3);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        useKey(steve);
        assertEquals(8.5, steve.getLocation().getZ(), 1.0E-6, "8 blocks ahead, through Alex");
        assertEquals(0, cooldown(sword, Ability.CRIMSON_FLASH), "a second dash is ready");
        tick(4);
        assertEquals(20.0, alex.getHealth(), "the cut lands a moment later");
        tick(6);
        assertEquals(14.0, alex.getHealth(), 1.0E-6, "6 damage");
        useKey(steve);
        assertEquals(16.5, steve.getLocation().getZ(), 1.0E-6, "the second dash");
        assertTrue(cooldown(sword, Ability.CRIMSON_FLASH) > 0, "both used: now the cooldown");
        useKey(steve);
        assertEquals(16.5, steve.getLocation().getZ(), 1.0E-6, "no third");
        tick(65);
        assertEquals(11.0, alex.getHealth(), 1.0E-6, "then 1 a second for 3 seconds of bleeding");
        assertEquals(20.0, behind.getHealth(), "nothing behind");
        tick(16 * 20);
        stand(steve, 0, 0, 0f);
        useKey(steve);
        assertEquals(8.5, steve.getLocation().getZ(), 1.0E-6, "ready again after 16s");
    }

    @Test
    void crimsonFlashsSecondChargeRunsOut() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        useKey(steve);
        tick(2);
        assertTrue(plugin.hud().bars(steve).get(Ability.CRIMSON_FLASH).getProgress() < 1.0, "the bar shows the window");
        tick(3 * 20);
        assertTrue(cooldown(sword, Ability.CRIMSON_FLASH) > 0, "not used in 3s: the cooldown starts");
        useKey(steve);
        assertEquals(8.5, steve.getLocation().getZ(), 1.0E-6);
    }

    @Test
    void crimsonFlashStopsAtWalls() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        world.getBlockAt(0, 64, 1).setType(Material.STONE);
        useKey(steve);
        assertEquals(0.5, steve.getLocation().getZ(), 1.0E-6, "a wall right in front: nothing happens");
        assertEquals(0, cooldown(sword, Ability.CRIMSON_FLASH), "and nothing is spent");
        tick(2 * 20);
        assertEquals(0, cooldown(sword, Ability.CRIMSON_FLASH));
        world.getBlockAt(0, 64, 1).setType(Material.AIR);
        world.getBlockAt(0, 65, 4).setType(Material.STONE); // Head height is enough.
        useKey(steve);
        assertEquals(3.75, steve.getLocation().getZ(), 1.0E-6, "stops in front of the wall, never in it");
    }

    @Test
    void iaidoBlocksTheNextHitAndCountersFromBehind() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 3);
        alex.teleport(new Location(world, 0.5, 64, 3.5, 180f, 0f)); // Facing Steve.
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        steve.setHealth(10.0);
        sneakUseKey(steve);
        assertTrue(steve.hasPotionEffect(PotionEffectType.SLOWNESS), "holding the stance");
        assertTrue(cooldown(sword, Ability.IAIDO) > 0);
        tick(5);
        EntityDamageByEntityEvent hit = melee(alex, steve, 6.0);
        assertTrue(hit.isCancelled(), "blocked");
        assertEquals(4.8, steve.getLocation().getZ(), 1.0E-6, "now behind Alex");
        assertEquals(8.0, alex.getHealth(), 1.0E-6, "a 12 damage counter");
        assertEquals(14.0, steve.getHealth(), 1.0E-6, "and two hearts back");
        assertFalse(steve.hasPotionEffect(PotionEffectType.SLOWNESS));
        assertFalse(melee(alex, steve, 6.0).isCancelled(), "only the first hit");
        tick(25);
        assertTrue(alex.getHealth() < 8.0, "bleeding");
    }

    @Test
    void iaidoReleasesACrescentWhenNothingComes() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 4);
        PlayerMock far = player("Far", 0, 12);
        give(steve, WeaponType.KUROGANE);
        sneakUseKey(steve);
        tick(20);
        assertEquals(20.0, alex.getHealth());
        tick(15);
        assertEquals(12.0, alex.getHealth(), 1.0E-6, "the crescent: 8 damage");
        assertEquals(20.0, far.getHealth(), "6 blocks long");
    }

    @Test
    void crimsonEdgeEveryThirdHitCutsDeep() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        PlayerMock bob = player("Bob", 2, 0);
        give(steve, WeaponType.KUROGANE);
        assertEquals(5.0, melee(steve, alex, 5.0).getDamage(), 1.0E-6);
        assertEquals(5.0, melee(steve, alex, 5.0).getDamage(), 1.0E-6, "spam clicks do not count");
        tick(12);
        assertEquals(5.0, melee(steve, alex, 5.0).getDamage(), 1.0E-6);
        tick(12);
        assertEquals(9.0, melee(steve, alex, 5.0).getDamage(), 1.0E-6, "the 3rd: +4");
        tick(25);
        assertTrue(alex.getHealth() < 20.0, "and bleeding");
        tick(12);
        melee(steve, alex, 5.0);
        tick(12);
        melee(steve, alex, 5.0);
        tick(12);
        assertEquals(5.0, melee(steve, bob, 5.0).getDamage(), 1.0E-6, "switching target starts again");
    }

    // ---- Sugarcrash -------------------------------------------------------------------------------------

    @Test
    void candyHookYanksAPlayerAndStunsThem() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 8);
        ItemStack scythe = give(steve, WeaponType.SUGARCRASH);
        useKey(steve);
        assertTrue(cooldown(scythe, Ability.CANDY_HOOK) > 0);
        tick(2);
        assertEquals(20.0, alex.getHealth(), "the hook takes a moment to fly");
        tick(5);
        assertEquals(14.0, alex.getHealth(), 1.0E-6, "caught: 3 hearts");
        assertTrue(alex.getVelocity().getZ() < -0.5 && alex.getVelocity().getY() > 0.2, "yanked towards Steve");
        tick(8);
        assertTrue(alex.hasPotionEffect(PotionEffectType.SLOWNESS), "stunned");
        assertEquals(6, alex.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier());
    }

    @Test
    void candyHookGrapplesToWalls() {
        PlayerMock steve = player("Steve", 0, 0);
        for (int x = -2; x <= 2; x++) {
            for (int y = 64; y <= 68; y++) {
                world.getBlockAt(x, y, 12).setType(Material.STONE);
            }
        }
        give(steve, WeaponType.SUGARCRASH);
        useKey(steve);
        tick(9);
        assertTrue(steve.getVelocity().getZ() > 0.8, "pulled to the wall");
        EntityDamageEvent fall = new EntityDamageEvent(steve, EntityDamageEvent.DamageCause.FALL,
                DamageSource.builder(DamageType.FALL).build(), 6.0);
        server.getPluginManager().callEvent(fall);
        assertTrue(fall.isCancelled(), "no fall damage after");
    }

    @Test
    void candyBarrageFloatsCanesThatFireOnEachSwing() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 10);
        ItemStack scythe = give(steve, WeaponType.SUGARCRASH);
        int before = plugin.visuals().count();
        sneakUseKey(steve);
        assertEquals(before + 5, plugin.visuals().count(), "five candy canes over Steve's head");
        assertEquals(0, cooldown(scythe, Ability.CANDY_BARRAGE), "no cooldown while they float");
        tick(4);
        assertEquals(20.0, alex.getHealth(), "nothing until Steve swings");

        swing(steve);
        tick(6);
        assertEquals(17.0, alex.getHealth(), 1.0E-6, "a cane: 1.5 hearts");
        swing(steve);
        swing(steve); // Too soon after the last: not fired.
        tick(6);
        assertEquals(14.0, alex.getHealth(), 1.0E-6, "one cane per swing");
        sneakUseKey(steve); // Shift + F again fires one too.
        tick(6);
        assertEquals(11.0, alex.getHealth(), 1.0E-6);

        // With something else in hand, a swing fires nothing, but the bar still counts down.
        steve.getInventory().setHeldItemSlot(8);
        swing(steve);
        tick(6);
        assertEquals(11.0, alex.getHealth(), 1.0E-6);
        assertNotNull(plugin.hud().bars(steve).get(Ability.CANDY_BARRAGE), "the bar stays after switching");
        steve.getInventory().setHeldItemSlot(0);
        swing(steve);
        tick(6);
        swing(steve);
        tick(6);
        assertEquals(5.0, alex.getHealth(), 1.0E-6, "five canes, 7.5 hearts in all");
        assertTrue(cooldown(scythe, Ability.CANDY_BARRAGE) > 21 * 20, "all fired: the cooldown starts");
        swing(steve);
        tick(6);
        assertEquals(5.0, alex.getHealth(), 1.0E-6, "none left");
        tick(10); // The last hit's candy burst fades.
        assertEquals(before, plugin.visuals().count(), "all the canes are gone");
    }

    @Test
    void candyBarrageCanesFadeAfterAWhile() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack scythe = give(steve, WeaponType.SUGARCRASH);
        int before = plugin.visuals().count();
        sneakUseKey(steve);
        tick(7 * 20);
        assertEquals(0, cooldown(scythe, Ability.CANDY_BARRAGE));
        assertEquals(before + 5, plugin.visuals().count());
        tick(30);
        assertTrue(cooldown(scythe, Ability.CANDY_BARRAGE) > 21 * 20, "8s later the rest fade and the cooldown starts");
        assertEquals(before, plugin.visuals().count());
        sneakUseKey(steve);
        assertEquals(before, plugin.visuals().count(), "not again until it recharges");
    }

    @Test
    void sugarHighBuildsUpThenCrashes() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        PlayerMock bob = player("Bob", 2, 2);
        give(steve, WeaponType.SUGARCRASH);
        for (int i = 0; i < 5; i++) {
            assertEquals(5.0, melee(steve, alex, 5.0).getDamage(), 1.0E-6, "building sugar");
            melee(steve, alex, 5.0); // A spam click: not counted.
            tick(12);
        }
        // (The simulated server keeps each effect added; a real one upgrades Speed I to Speed II.)
        assertTrue(steve.getActivePotionEffects().stream().anyMatch(effect -> effect.getType().equals(PotionEffectType.SPEED)
                && effect.getAmplifier() == 1), "Speed II with the sugar");
        assertEquals(9.0, melee(steve, alex, 5.0).getDamage(), 1.0E-6, "full: the Sugar Crash (+4)");
        assertEquals(14.0, bob.getHealth(), 1.0E-6, "the candy blast hits who is near");
        assertTrue(bob.getVelocity().length() > 0.5);
        tick(12);
        assertEquals(5.0, melee(steve, alex, 5.0).getDamage(), 1.0E-6, "and starts again");
    }

    // ---- Riftblade --------------------------------------------------------------------------------------

    @Test
    void voidRendPullsInThenSnapsShutAndLifts() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 2, 5);
        PlayerMock far = player("Far", 0, 14);
        give(steve, WeaponType.RIFTBLADE);
        useKey(steve);
        tick(2);
        assertEquals(20.0, alex.getHealth(), "the rift pulls first");
        assertTrue(alex.getVelocity().getX() < 0, "towards the rift");
        tick(25);
        assertEquals(10.0, alex.getHealth(), 1.0E-6, "then snaps shut: 5 hearts");
        assertTrue(alex.hasPotionEffect(PotionEffectType.DARKNESS));
        assertTrue(alex.hasPotionEffect(PotionEffectType.LEVITATION), "lifted helplessly");
        assertEquals(20.0, far.getHealth());
        assertFalse(far.hasPotionEffect(PotionEffectType.DARKNESS));
    }

    @Test
    void riftSwapTradesPlacesThenReturnsToTheEcho() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 6);
        ItemStack blade = give(steve, WeaponType.RIFTBLADE);
        sneakUseKey(steve);
        assertEquals(6.5, steve.getLocation().getZ(), 1.0E-6);
        assertEquals(0.5, alex.getLocation().getZ(), 1.0E-6);
        assertEquals(14.0, alex.getHealth(), 1.0E-6);
        assertTrue(alex.hasPotionEffect(PotionEffectType.NAUSEA), "dizzy from the void");
        assertEquals(0, cooldown(blade, Ability.RIFT_SWAP), "the echo is open");
        steve.teleport(new Location(world, 4.5, 64, 9.5), PlayerTeleportEvent.TeleportCause.COMMAND); // Ran off.
        tick(20);
        sneakUseKey(steve);
        assertEquals(0.5, steve.getLocation().getX(), 1.0E-6, "back where the swap began");
        assertEquals(0.5, steve.getLocation().getZ(), 1.0E-6);
        assertTrue(cooldown(blade, Ability.RIFT_SWAP) > 0);
        sneakUseKey(steve);
        assertEquals(0.5, steve.getLocation().getZ(), 1.0E-6, "once");
    }

    @Test
    void riftSwapBlinksWhenNobodyIsInSightAndCanBeRefused() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack blade = give(steve, WeaponType.RIFTBLADE);
        // A safe zone or region plugin refusing the teleport: nothing happens, nothing is spent.
        Listener refuse = new Listener() {
            @EventHandler
            public void onTeleport(PlayerTeleportEvent event) {
                if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) {
                    event.setCancelled(true);
                }
            }
        };
        server.getPluginManager().registerEvents(refuse, plugin);
        sneakUseKey(steve);
        assertEquals(0.5, steve.getLocation().getZ(), 1.0E-6, "refused");
        tick(5 * 20);
        assertEquals(0, cooldown(blade, Ability.RIFT_SWAP), "no echo, no cooldown");
        HandlerList.unregisterAll(refuse);
        sneakUseKey(steve);
        assertEquals(10.5, steve.getLocation().getZ(), 1.0E-6, "blinks 10 blocks ahead");
        tick(5 * 20);
        assertTrue(cooldown(blade, Ability.RIFT_SWAP) > 0, "the echo faded: the cooldown runs");
        sneakUseKey(steve);
        assertEquals(10.5, steve.getLocation().getZ(), 1.0E-6, "too late to go back");
    }

    @Test
    void phaseShiftLetsAHitPassThrough() {
        plugin.getConfig().set("weapons.riftblade.abilities.phase-shift.chance", 1.0);
        plugin.saveConfig();
        assertEquals(List.of(), plugin.reload());
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        ItemStack blade = give(steve, WeaponType.RIFTBLADE);
        EntityDamageByEntityEvent hit = melee(alex, steve, 6.0);
        assertTrue(hit.isCancelled(), "it passed through");
        assertEquals(3.0, Math.abs(steve.getLocation().getX() - 0.5), 1.0E-6, "slipped 3 blocks aside");
        assertEquals(0.5, steve.getLocation().getZ(), 1.0E-6);
        assertTrue(cooldown(blade, Ability.PHASE_SHIFT) > 0);
        assertFalse(melee(alex, steve, 6.0).isCancelled(), "at most once every 10 seconds");
        tick(10 * 20);
        assertTrue(melee(alex, steve, 6.0).isCancelled());
        steve.getInventory().setHeldItemSlot(8);
        tick(10 * 20);
        assertFalse(melee(alex, steve, 6.0).isCancelled(), "only while holding the Riftblade");
    }

    // ---- Gravebreaker -----------------------------------------------------------------------------------

    @Test
    void executionersLeapSlamsDownWithoutFallDamage() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 3);
        PlayerMock far = player("Far", 0, 12);
        give(steve, WeaponType.GRAVEBREAKER);
        useKey(steve);
        assertEquals(1.1, steve.getVelocity().getY(), 1.0E-6, "up...");
        assertTrue(steve.getVelocity().getZ() > 0.5, "...and forward");
        tick(10);
        assertEquals(20.0, alex.getHealth(), "nothing until the slam");
        EntityDamageEvent fall = new EntityDamageEvent(steve, EntityDamageEvent.DamageCause.FALL,
                DamageSource.builder(DamageType.FALL).build(), 9.0);
        server.getPluginManager().callEvent(fall);
        assertTrue(fall.isCancelled(), "no fall damage from the leap");
        steve.setVelocity(new Vector()); // Landed.
        tick(1);
        assertEquals(20.0 - 8.4, alex.getHealth(), 1.0E-6, "3 of 5 blocks out: between 12 at the centre and 6 at the edge");
        assertEquals(0.7, alex.getVelocity().getY(), 1.0E-6, "thrown up");
        assertTrue(alex.getVelocity().getZ() > 0.3, "and away");
        assertTrue(alex.hasPotionEffect(PotionEffectType.SLOWNESS));
        assertEquals(20.0, far.getHealth());
        for (int z = -3; z <= 6; z++) {
            assertEquals(Material.STONE, world.getBlockAt(0, 63, z).getType(), "no block is changed");
        }
    }

    @Test
    void executionersLeapDivesWhenPressedAgain() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 3);
        PlayerMock edge = player("Edge", 6, 0);
        ItemStack axe = give(steve, WeaponType.GRAVEBREAKER);
        useKey(steve);
        tick(5);
        steve.teleport(new Location(world, 0.5, 64, 0.5, 0f, 40f), PlayerTeleportEvent.TeleportCause.COMMAND);
        steve.setVelocity(new Vector(0, 0.4, 0.5)); // Still in the air.
        useKey(steve);
        assertTrue(steve.getVelocity().getY() <= -0.6, "plunging down");
        assertTrue(steve.getVelocity().getZ() > 0.3, "at where Steve looks");
        tick(1);
        double normal = 20.0 - 8.4;
        assertTrue(alex.getHealth() < normal - 2, "a bigger slam: " + alex.getHealth());
        assertTrue(edge.getHealth() < 20.0, "and a wider one (6 blocks out)");
        useKey(steve);
        assertTrue(cooldown(axe, Ability.EXECUTIONERS_LEAP) > 0, "landed: back to the cooldown");
    }

    @Test
    void graveRiseLaunchesAndWeighsDownThenTheTombBursts() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 6);
        PlayerMock aside = player("Aside", 4, 6);
        PlayerMock bob = player("Bob", 2, 12);
        give(steve, WeaponType.GRAVEBREAKER);
        sneakUseKey(steve);
        tick(2);
        assertEquals(20.0, alex.getHealth(), "the stones rise one after another");
        tick(6);
        assertEquals(12.0, alex.getHealth(), 1.0E-6, "4 hearts");
        assertEquals(0.9, alex.getVelocity().getY(), 1.0E-6, "launched");
        assertEquals(2, alex.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier(), "Slowness III");
        assertTrue(alex.hasPotionEffect(PotionEffectType.MINING_FATIGUE));
        assertEquals(20.0, bob.getHealth());
        tick(20);
        assertEquals(14.0, bob.getHealth(), 1.0E-6, "the tomb at the end bursts: 3 hearts");
        assertEquals(12.0, alex.getHealth(), 1.0E-6, "hit once");
        assertEquals(20.0, aside.getHealth(), "only on the line");
        for (int z = 0; z <= 13; z++) {
            assertEquals(Material.STONE, world.getBlockAt(0, 63, z).getType(), "no block is changed");
            assertEquals(Material.AIR, world.getBlockAt(0, 64, z).getType());
        }
    }

    @Test
    void lastRitesFinishesWeakPlayersAndKillsResetTheLeap() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        ItemStack axe = give(steve, WeaponType.GRAVEBREAKER);
        assertEquals(8.0, melee(steve, alex, 8.0).getDamage(), 1.0E-6, "full health: a normal hit");
        alex.setHealth(6.0);
        assertEquals(10.0, melee(steve, alex, 8.0).getDamage(), 1.0E-6, "under 40%: +25%");
        useKey(steve);
        assertTrue(cooldown(axe, Ability.EXECUTIONERS_LEAP) > 0);
        alex.setKiller(steve);
        server.getPluginManager().callEvent(new PlayerDeathEvent(alex, DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(steve).withDirectEntity(steve).build(), new ArrayList<>(), 0, "Alex was slain"));
        assertEquals(0, cooldown(axe, Ability.EXECUTIONERS_LEAP), "the kill made the leap ready");
    }

    // ---- Starforged -------------------------------------------------------------------------------------

    @Test
    void starfallWarnsThenRainsStars() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 6);
        PlayerMock outside = player("Outside", 0, 16);
        ItemStack axe = give(steve, WeaponType.STARFORGED);
        stand(steve, 0, 0, 15f);
        useKey(steve);
        tick(15);
        assertEquals(20.0, alex.getHealth(), "a warning first: time to get out");
        tick(35);
        assertTrue(alex.getHealth() < 20.0, "then the stars land");
        assertTrue(alex.getHealth() >= 20.0 - 3 * 4.0 - 1.0E-6, "at most 3 stars hit one player");
        assertEquals(0.6, alex.getVelocity().getY(), 1.0E-6, "launched");
        assertEquals(20.0, outside.getHealth());
        assertTrue(cooldown(axe, Ability.SINGULARITY) > 0, "no black hole while stars fall");
    }

    @Test
    void starfallsCircleFollowsYourAim() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock target = player("Target", 6, 0);
        PlayerMock first = player("First", 0, 6);
        give(steve, WeaponType.STARFORGED);
        stand(steve, 0, 0, 15f);
        useKey(steve);
        tick(1);
        // Turned to the right (east): the circle glides over to Target.
        steve.teleport(new Location(world, 0.5, 64, 0.5, -90f, 15f), PlayerTeleportEvent.TeleportCause.COMMAND);
        tick(60);
        assertTrue(target.getHealth() < 20.0, "the stars followed");
        assertEquals(20.0, first.getHealth(), "nothing left where it opened");
    }

    @Test
    void starfallNeedsSomethingToAimAt() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack axe = give(steve, WeaponType.STARFORGED);
        stand(steve, 0, 0, -30f); // At the sky.
        actionBars(steve);
        useKey(steve);
        assertEquals(0, cooldown(axe, Ability.STARFALL), "nothing spent");
        assertTrue(actionBars(steve).stream().anyMatch(bar -> bar.contains("Nothing to aim at")));
    }

    @Test
    void singularityPullsThenExplodes() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 3, 6);
        ItemStack axe = give(steve, WeaponType.STARFORGED);
        stand(steve, 0, 0, 15f);
        sneakUseKey(steve);
        tick(2);
        assertEquals(19.0, alex.getHealth(), 1.0E-6, "caught: a little damage");
        assertTrue(alex.getVelocity().getX() < 0, "pulled towards the centre");
        // No stars can be called into it while it is open.
        WeaponItems.Tag tag = plugin.items().read(axe);
        useKey(steve);
        assertEquals(0, plugin.abilities().active(steve, tag, Ability.STARFALL, plugin.tick()), "Starfall is locked");
        tick(70);
        assertEquals(9.0, alex.getHealth(), 1.0E-6, "then the nova: 5 hearts");
        assertTrue(alex.getVelocity().getX() > 0.5, "throws everyone out");
        assertEquals(1, alex.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier(), "and slows them");
    }

    @Test
    void starstruckEveryFourthHitCallsAStar() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.STARFORGED);
        for (int i = 0; i < 4; i++) {
            melee(steve, alex, 5.0);
            tick(12);
        }
        // The swings themselves are not applied here: only the star hurts.
        assertEquals(14.0, alex.getHealth(), 1.0E-6, "a star fell on the 4th hit");
        assertEquals(0.5, alex.getVelocity().getY(), 1.0E-6, "and launched them");
        melee(steve, alex, 5.0);
        tick(12);
        assertEquals(14.0, alex.getHealth(), 1.0E-6, "the count starts again");
    }

    // ---- fairness ---------------------------------------------------------------------------------------

    @Test
    void protectedPlayersAreNeverHurtPulledOrSwapped() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock safe = player("Safe", 0, 3);
        protect(safe);
        give(steve, WeaponType.KUROGANE);
        useKey(steve); // Crimson Flash through them.
        tick(80);
        assertEquals(20.0, safe.getHealth(), "not cut, no bleeding");

        give(steve, WeaponType.SUGARCRASH);
        stand(steve, 0, 0, 0f);
        useKey(steve); // Candy Hook.
        tick(20);
        assertEquals(0.0, safe.getVelocity().length(), 1.0E-9, "not yanked");
        assertFalse(safe.hasPotionEffect(PotionEffectType.SLOWNESS), "not stunned");

        give(steve, WeaponType.RIFTBLADE);
        stand(steve, 0, 0, 0f);
        sneakUseKey(steve); // Rift Swap.
        assertEquals(0.5, steve.getLocation().getZ(), 1.0E-6, "no swapping with a protected player");
        assertEquals(3.5, safe.getLocation().getZ(), 1.0E-6);
        assertTrue(actionBars(steve).stream().anyMatch(bar -> bar.contains("can't take you there")));
        useKey(steve); // Void Rend.
        tick(30);
        assertEquals(20.0, safe.getHealth());
        assertEquals(0.0, safe.getVelocity().length(), 1.0E-9, "not pulled");
        assertFalse(safe.hasPotionEffect(PotionEffectType.DARKNESS));
        assertFalse(safe.hasPotionEffect(PotionEffectType.LEVITATION));

        give(steve, WeaponType.STARFORGED);
        stand(steve, 0, 0, 15f);
        sneakUseKey(steve); // Singularity.
        tick(70);
        assertEquals(20.0, safe.getHealth());
        assertEquals(0.0, safe.getVelocity().length(), 1.0E-9, "not dragged into the black hole");
        assertFalse(safe.hasPotionEffect(PotionEffectType.SLOWNESS));
    }

    @Test
    void countersAndDodgesIgnoreProtectionChecks() {
        plugin.getConfig().set("weapons.riftblade.abilities.phase-shift.chance", 0); // No lucky dodge of the counter.
        plugin.saveConfig();
        assertEquals(List.of(), plugin.reload());
        PlayerMock steve = player("Steve", 2, 5);
        PlayerMock alex = player("Alex", 0, 0);
        give(steve, WeaponType.KUROGANE);
        give(alex, WeaponType.RIFTBLADE);
        sneakUseKey(steve); // Iaido's stance.
        useKey(alex); // Void Rend asks protection plugins before it pulls Steve: that is no attack.
        tick(2);
        assertEquals(2.5, steve.getLocation().getX(), 1.0E-6, "no counter");
        assertTrue(steve.getVelocity().getX() < 0, "pulled");
        tick(25);
        assertTrue(steve.getLocation().getZ() < 0.5, "the snap is an attack: countered from behind Alex");
        assertTrue(alex.getHealth() < 20.0);
    }

    @Test
    void abilityDamageThatKillsBreaksNothing() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 3);
        PlayerMock bob = player("Bob", 0, 5);
        alex.setHealth(7.0);
        bob.setHealth(7.0);
        give(steve, WeaponType.KUROGANE);
        useKey(steve); // The cut leaves both on half a heart; the bleeding finishes both on the same tick.
        tick(12);
        assertEquals(1.0, alex.getHealth(), 1.0E-6);
        assertEquals(1.0, bob.getHealth(), 1.0E-6);
        tick(25);
        assertTrue(alex.isDead() && bob.isDead(), "both bled out: " + alex.getHealth() + ", " + bob.getHealth());
        tick(4);
        assertEquals(java.util.Set.of(Ability.CRIMSON_FLASH), plugin.hud().bars(steve).keySet(),
                "everything else kept running");
    }

    @Test
    void creativePlayersAndPvpOffAreLeftAlone() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock builder = player("Builder", 0, 3);
        builder.setGameMode(org.bukkit.GameMode.CREATIVE);
        give(steve, WeaponType.SUGARCRASH);
        sneakUseKey(steve);
        tick(90);
        assertEquals(0.0, builder.getVelocity().length(), 1.0E-9);
        builder.setGameMode(org.bukkit.GameMode.SURVIVAL);
        world.setPVP(false);
        tick(30 * 20);
        sneakUseKey(steve);
        tick(90);
        assertEquals(20.0, builder.getHealth(), "PvP off in this world");
        assertEquals(0.0, builder.getVelocity().length(), 1.0E-9);
    }

    // ---- display, commands, config -----------------------------------------------------------------------

    @Test
    void bossBarsShowOnlyWhileAnAbilityRecharges() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.STARFORGED);
        tick(4);
        assertTrue(plugin.hud().bars(steve).isEmpty(), "everything ready: no bars");

        stand(steve, 0, 0, 15f);
        useKey(steve);
        tick(10);
        Map<Ability, BossBar> bars = plugin.hud().bars(steve);
        BossBar starfall = bars.get(Ability.STARFALL);
        assertNotNull(starfall, "used: its bar appears");
        assertTrue(title(starfall).matches("Starfall » \\d\\.\\ds"), "while the stars fall: " + title(starfall));
        assertTrue(starfall.getProgress() > 0.5 && starfall.getProgress() < 1.0, "running down");
        assertEquals(BarColor.BLUE, starfall.getColor(), "the weapon's colour");
        assertTrue(starfall.getPlayers().contains(steve));
        BossBar singularity = bars.get(Ability.SINGULARITY);
        assertNotNull(singularity, "locked while the stars fall, so it recharges too");
        tick(60);
        assertEquals("Starfall » 21s", title(starfall), "then how long until it is ready");
        assertTrue(starfall.getProgress() < 0.5, "filling up again");
        assertTrue(title(singularity).startsWith("Singularity » "), title(singularity));
        tick(12);
        assertNull(plugin.hud().bars(steve).get(Ability.SINGULARITY), "the lockout is over: gone again");
        assertFalse(singularity.getPlayers().contains(steve));

        // Too early: no message, the bar flashes.
        useKey(steve);
        tick(2);
        assertEquals(BarColor.WHITE, starfall.getColor());
        tick(8);
        assertEquals(BarColor.BLUE, starfall.getColor());
        assertFalse(has(chat(steve), "ready in"), "nothing in chat");
        assertEquals(List.of(), actionBars(steve), "the action bar is left to the Combat plugin");
        assertFalse(steve.hasMetadata("vanillasmp:actionbar"));

        // Put away: the recharging one stays while the axe is in the inventory...
        steve.getInventory().setHeldItemSlot(8);
        tick(4);
        assertEquals(java.util.Set.of(Ability.STARFALL), plugin.hud().bars(steve).keySet(), "still counting down");
        assertTrue(starfall.getPlayers().contains(steve));
        steve.getInventory().setHeldItemSlot(0);
        tick(4);
        assertEquals(java.util.Set.of(Ability.STARFALL), plugin.hud().bars(steve).keySet());
        // ...and goes when the axe does.
        ItemStack axe = steve.getInventory().getItemInMainHand();
        steve.getInventory().setItemInMainHand(null);
        tick(12);
        assertTrue(plugin.hud().bars(steve).isEmpty(), "not carried: no bar");
        assertFalse(starfall.getPlayers().contains(steve));
        steve.getInventory().setItemInMainHand(axe);
        tick(4);
        // Ready again: no bar.
        tick(24 * 20);
        assertTrue(plugin.hud().bars(steve).isEmpty(), "ready: the bar is gone");
    }

    @Test
    void readyBarsCanBeTurnedOn() {
        plugin.getConfig().set("display.boss-bars-when-ready", true);
        plugin.saveConfig();
        assertEquals(List.of(), plugin.reload());
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.STARFORGED);
        tick(4);
        Map<Ability, BossBar> bars = plugin.hud().bars(steve);
        assertEquals(java.util.Set.of(Ability.STARFALL, Ability.SINGULARITY), bars.keySet());
        assertEquals("Starfall", title(bars.get(Ability.STARFALL)), "ready: just its name");
        assertEquals(1.0, bars.get(Ability.STARFALL).getProgress(), 1.0E-9);
    }

    @Test
    void abilitiesShowTheirEffectsAndCleanThemUp() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.KUROGANE);
        useKey(steve);
        List<String> models = new ArrayList<>();
        for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
            assertFalse(display.isPersistent(), "never saved with the world");
            ItemMeta meta = display.getItemStack().getItemMeta();
            models.add(String.valueOf(modern(meta::getItemModel)));
        }
        assertTrue(models.size() >= 2, "the streak and the slash: " + models);
        if (!models.contains("null")) {
            assertTrue(models.contains("legendary:fx/crimson_streak") && models.contains("legendary:fx/crimson_slash"),
                    String.valueOf(models));
        }
        assertEquals(models.size(), plugin.visuals().count());
        tick(40);
        assertEquals(0, plugin.visuals().count(), "all gone again");
        assertTrue(world.getEntitiesByClass(ItemDisplay.class).isEmpty());
    }

    @Test
    void listInspectAndReload() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock admin = player("Admin", 5, 5);
        admin.setOp(true);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        chat(admin);
        server.dispatchCommand(admin, "legendary list");
        List<String> list = chat(admin);
        assertTrue(has(list, "Kurogane #" + WeaponItems.shortId(id(sword)) + " held by Steve"), String.join("\n", list));
        assertTrue(has(list, "Riftblade not given out"));
        server.dispatchCommand(admin, "legendary inspect Steve");
        List<String> inspect = chat(admin);
        assertTrue(has(inspect, "Crimson Flash ready"), String.join("\n", inspect));

        PlayerMock player = player("Player", 6, 6);
        server.dispatchCommand(player, "legendary give Player kurogane");
        assertTrue(has(chat(player), "do not have permission"));

        // A new name in config.yml reaches the weapon already out there.
        plugin.getConfig().set("weapons.kurogane.name", "&cBlood Moon");
        plugin.saveConfig();
        server.dispatchCommand(admin, "legendary reload");
        assertTrue(has(chat(admin), "reloaded"));
        assertEquals("Blood Moon", ChatColor.stripColor(steve.getInventory().getItemInMainHand().getItemMeta().getDisplayName()));
    }

    @Test
    void wrongConfigValuesFallBackWithAWarning() {
        plugin.getConfig().set("weapons.kurogane.abilities.crimson-flash.cooldown", "soon");
        plugin.getConfig().set("weapons.starforged.abilities.starfall.warning", "0.1s");
        plugin.getConfig().set("weapons.sugarcrash.boss-bar.color", "rainbow");
        plugin.getConfig().set("hit-mobs", "everything");
        plugin.saveConfig();
        List<String> warnings = plugin.reload();
        assertEquals(4, warnings.size(), String.join("\n", warnings));
        assertEquals(16.0, plugin.settings().ability(Ability.CRIMSON_FLASH).num("cooldown"));
        assertEquals(1.0, plugin.settings().ability(Ability.STARFALL).num("warning"), "never under 0.5s");
        assertEquals(BarColor.PINK, plugin.settings().look(WeaponType.SUGARCRASH).barColor());
        assertEquals(List.of(), freshWarnings(), "the bundled config.yml has no mistakes");
    }

    @Test
    void aConfigFromBefore12IsBroughtUpToDate() {
        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
        config.set("config-version", null);
        config.set("display.action-bar", true);
        config.set("display.ready-sound", true);
        config.set("messages.hud-edge", "&7Edge {stacks}");
        config.set("weapons.kurogane.enchantments", null);
        config.set("weapons.kurogane.enchantments.sharpness", 6);
        config.set("weapons.kurogane.abilities.crescent-draw.cooldown", "8s");
        config.set("weapons.kurogane.item-model", "");
        config.set("weapons.sugarcrash.abilities.sugar-rush", null);
        config.set("weapons.sugarcrash.abilities.sugar-rush.cooldown", "18s");
        config.set("weapons.sugarcrash.abilities.sugar-rush.duration", "6s");
        config.set("weapons.sugarcrash.abilities.sugar-rush.speed-level", 2);
        config.set("weapons.sugarcrash.abilities.sugar-rush.haste-level", 2);
        config.set("weapons.riftblade.enchantments", null);
        config.set("weapons.riftblade.enchantments.sharpness", 9); // This server's own choice.
        plugin.saveConfig();

        assertEquals(List.of(), plugin.reload(), "nothing left to warn about");
        org.bukkit.configuration.file.YamlConfiguration saved = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.File(plugin.getDataFolder(), "config.yml"));
        assertEquals(4, saved.getInt("config-version"));
        assertFalse(saved.isSet("display.action-bar"));
        assertFalse(saved.isSet("display.ready-sound"));
        assertFalse(saved.isSet("messages.hud-edge"));
        assertFalse(saved.isSet("weapons.kurogane.abilities.crescent-draw"));
        assertFalse(saved.isSet("weapons.sugarcrash.abilities.sugar-rush.duration"), "1.1's Sugar Rush settings");
        assertEquals(List.of("sharpness", "fire_aspect", "looting", "sweeping_edge"),
                List.copyOf(saved.getConfigurationSection("weapons.kurogane.enchantments").getKeys(false)));
        assertEquals(7, saved.getInt("weapons.kurogane.enchantments.sharpness"));
        assertEquals(Map.of("sharpness", 9), plugin.settings().look(WeaponType.RIFTBLADE).enchantments(), "kept");
        assertEquals("legendary:kurogane", saved.getString("weapons.kurogane.item-model"));
        assertEquals(22.0, plugin.settings().ability(Ability.CANDY_HOOK).num("range"));
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        assertEquals(7, sword.getItemMeta().getEnchantLevel(io.github.drepfy.legendary.util.Compat.enchantment("sharpness")));
        // Once up to date it is left alone.
        assertEquals(List.of(), plugin.reload());
        assertEquals(Map.of("sharpness", 9), plugin.settings().look(WeaponType.RIFTBLADE).enchantments());
    }

    @Test
    void aConfigFrom12IsBroughtUpToDate() {
        org.bukkit.configuration.file.YamlConfiguration old = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.InputStreamReader(plugin.getResource("previous-text.yml"),
                        java.nio.charset.StandardCharsets.UTF_8));
        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
        config.set("config-version", 2);
        config.set("weapons.kurogane.lore", old.getStringList("v1_2_1.weapons.kurogane.lore"));
        config.set("weapons.kurogane.abilities", null);
        config.set("weapons.kurogane.abilities.crimson-flash.cooldown", "20s"); // 1.2's defaults...
        config.set("weapons.kurogane.abilities.crimson-flash.damage", 7);
        config.set("weapons.kurogane.abilities.crimson-flash.range", 10); // ...and this server's own value.
        config.set("weapons.kurogane.abilities.blood-moon.cooldown", "35s");
        config.set("weapons.sugarcrash.abilities.sugar-rush.cooldown", "18s");
        config.set("weapons.sugarcrash.abilities.candy-cyclone.duration", "3s");
        plugin.saveConfig();

        assertEquals(List.of(), plugin.reload(), "nothing left to warn about");
        org.bukkit.configuration.file.YamlConfiguration saved = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.File(plugin.getDataFolder(), "config.yml"));
        assertEquals(4, saved.getInt("config-version"));
        assertFalse(saved.isSet("weapons.kurogane.abilities.blood-moon"), "replaced by Iaido");
        assertFalse(saved.isSet("weapons.sugarcrash.abilities.sugar-rush"), "replaced by Candy Hook");
        assertEquals("16s", saved.getString("weapons.kurogane.abilities.crimson-flash.cooldown"), "the new default");
        assertEquals(6, saved.getInt("weapons.kurogane.abilities.crimson-flash.damage"));
        assertEquals(10, saved.getInt("weapons.kurogane.abilities.crimson-flash.range"), "own value kept");
        assertFalse(saved.isSet("weapons.sugarcrash.abilities.candy-cyclone"), "replaced by Candy Barrage");
        assertEquals(12, saved.getStringList("weapons.kurogane.lore").size(), "the lore with the passive and attack");
        assertEquals(10.0, plugin.settings().ability(Ability.CRIMSON_FLASH).num("range"));
        assertEquals(12.0, plugin.settings().ability(Ability.IAIDO).num("damage"));
    }

    @Test
    void aConfigFrom13GetsTheShimmerAndTheEnchantmentList() {
        org.bukkit.configuration.file.YamlConfiguration old = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.InputStreamReader(plugin.getResource("previous-text.yml"),
                        java.nio.charset.StandardCharsets.UTF_8));
        for (WeaponType type : WeaponType.values()) {
            plugin.getConfig().set("weapons." + type.key() + ".glint", false);
            plugin.getConfig().set("weapons." + type.key() + ".lore", old.getStringList("v1_3_0.weapons." + type.key() + ".lore"));
        }
        plugin.getConfig().set("weapons.starforged.glint", null);
        plugin.getConfig().set("weapons.starforged.lore", List.of("&bMine", "{enchantments}")); // Their own.
        plugin.saveConfig();
        assertEquals(List.of(), plugin.reload());
        assertTrue(plugin.settings().look(WeaponType.KUROGANE).glint(), "1.3.0's default (no shimmer) is updated");
        assertEquals(12, plugin.settings().look(WeaponType.KUROGANE).lore().size());
        PlayerMock steve = player("Steve", 0, 0);
        ItemMeta sword = give(steve, WeaponType.KUROGANE).getItemMeta();
        assertFalse(sword.hasItemFlag(ItemFlag.HIDE_ENCHANTS));
        assertFalse(String.join("\n", sword.getLore()).contains("Sharpness"));
        // A lore that still lists them itself keeps doing so, in the weapon's style.
        ItemMeta axe = give(steve, WeaponType.STARFORGED).getItemMeta();
        List<String> lore = axe.getLore().stream().map(ChatColor::stripColor).toList();
        assertEquals(List.of("Mine", "Sharpness VII  ✦  Efficiency V", "Fortune III  ✦  Unbreakable"), lore);
        assertTrue(axe.hasItemFlag(ItemFlag.HIDE_ENCHANTS), "not twice");
    }

    @Test
    void oldDefaultTextsAreUpdatedButOwnTextsAreKept() {
        // A config.yml written by 1.0.1: the old long lore and messages.
        org.bukkit.configuration.file.YamlConfiguration old = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.InputStreamReader(plugin.getResource("previous-text.yml"),
                        java.nio.charset.StandardCharsets.UTF_8));
        plugin.getConfig().set("weapons.kurogane.lore", old.getStringList("v1_0_1.weapons.kurogane.lore"));
        plugin.getConfig().set("messages.storage-blocked", old.getString("v1_0_1.messages.storage-blocked"));
        // ...one written by 1.0.2, which said "Right-click" before the keys moved to F...
        plugin.getConfig().set("weapons.sugarcrash.lore", old.getStringList("v1_0_2.weapons.sugarcrash.lore"));
        // ...and one written by 1.1.0, before the abilities changed.
        plugin.getConfig().set("weapons.riftblade.lore", old.getStringList("v1_1_0.weapons.riftblade.lore"));
        plugin.getConfig().set("weapons.riftblade.name", old.getString("v1_1_0.weapons.riftblade.name"));
        // ...and one written by 1.2.0, with LEGENDARY at the bottom.
        plugin.getConfig().set("weapons.gravebreaker.lore", old.getStringList("v1_2_0.weapons.gravebreaker.lore"));
        // But this server wrote its own Starforged lore and alt message.
        plugin.getConfig().set("weapons.starforged.lore", List.of("&bMy own lore"));
        plugin.getConfig().set("messages.alt-blocked", "&cNo alts!");
        plugin.saveConfig();
        assertTrue(old.getStringList("v1_0_1.weapons.kurogane.lore").size() > 12, "the old lore was long");

        plugin.reload();
        List<String> lore = plugin.getConfig().getStringList("weapons.kurogane.lore");
        assertEquals(12, lore.size(), "the new lore");
        assertTrue(String.join("\n", lore).contains("{crimson-flash.name}"));
        assertEquals("&cLegendaries can't go in containers.", plugin.getConfig().getString("messages.storage-blocked"));
        assertTrue(plugin.getConfig().getStringList("weapons.sugarcrash.lore").contains(
                "&#FF7AC3{sneak-key} &8» &f{candy-barrage.name} &8({candy-barrage.cooldown})"));
        assertTrue(plugin.getConfig().getStringList("weapons.riftblade.lore").contains(
                "&#B76BFF{key} &8» &f{void-rend.name} &8({void-rend.cooldown})"));
        assertEquals("<gradient:#E9C6FF:#A855F7>&lRiftblade</gradient>", plugin.getConfig().getString("weapons.riftblade.name"),
                "brighter on the dark tooltip");
        assertEquals(12, plugin.getConfig().getStringList("weapons.gravebreaker.lore").size());
        assertFalse(String.join("\n", plugin.getConfig().getStringList("weapons.gravebreaker.lore")).contains("LEGENDARY"));
        assertEquals(List.of("&bMy own lore"), plugin.getConfig().getStringList("weapons.starforged.lore"));
        assertEquals("&cNo alts!", plugin.getConfig().getString("messages.alt-blocked"));
        // It is saved, so it sticks after the next restart.
        org.bukkit.configuration.file.YamlConfiguration saved = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.File(plugin.getDataFolder(), "config.yml"));
        assertEquals(12, saved.getStringList("weapons.kurogane.lore").size());
        // Weapons already out get the new lore too.
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        List<String> itemLore = sword.getItemMeta().getLore().stream().map(ChatColor::stripColor).toList();
        assertEquals(14, itemLore.size(), String.join("\n", itemLore));
        assertTrue(itemLore.contains("F » Crimson Flash (16s)"), String.join("\n", itemLore));
        assertTrue(itemLore.contains("Passive » Crimson Edge"), String.join("\n", itemLore));
    }


    @Test
    void aConfigFrom133GetsTrueDamageCandyBarrageAndTheAttackLines() {
        org.bukkit.configuration.file.YamlConfiguration old = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.InputStreamReader(plugin.getResource("previous-text.yml"),
                        java.nio.charset.StandardCharsets.UTF_8));
        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
        config.set("config-version", 3);
        for (WeaponType type : WeaponType.values()) {
            config.set("weapons." + type.key() + ".lore", old.getStringList("v1_3_3.weapons." + type.key() + ".lore"));
        }
        config.set("weapons.kurogane.abilities.iaido.damage", 9); // 1.3's default...
        config.set("weapons.riftblade.abilities.void-rend.damage", 8); // ...and this server's own value.
        config.set("weapons.sugarcrash.abilities.candy-cyclone.duration", "4s");
        config.set("sounds.candy-cyclone", List.of("legendary:sugarcrash.cyclone 1 1"));
        plugin.saveConfig();

        assertEquals(List.of(), plugin.reload(), "nothing left to warn about");
        org.bukkit.configuration.file.YamlConfiguration saved = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.File(plugin.getDataFolder(), "config.yml"));
        assertEquals(4, saved.getInt("config-version"));
        assertEquals(12, saved.getInt("weapons.kurogane.abilities.iaido.damage"), "the new default");
        assertEquals(8, saved.getInt("weapons.riftblade.abilities.void-rend.damage"), "own value kept");
        assertFalse(saved.isSet("weapons.sugarcrash.abilities.candy-cyclone"));
        assertFalse(saved.isSet("sounds.candy-cyclone"));
        assertTrue(saved.getStringList("weapons.sugarcrash.lore").contains(
                "&#FF7AC3{sneak-key} &8» &f{candy-barrage.name} &8({candy-barrage.cooldown})"));
        assertTrue(plugin.settings().trueDamage());

        PlayerMock steve = player("Steve", 0, 0);
        ItemMeta axe = give(steve, WeaponType.GRAVEBREAKER).getItemMeta();
        List<String> lore = axe.getLore().stream().map(ChatColor::stripColor).toList();
        assertEquals(List.of("When in Main Hand:", " 14 Attack Damage", " 1 Attack Speed"),
                lore.subList(lore.size() - 3, lore.size()), String.join("\n", lore));
        assertTrue(axe.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES));
    }

    @Test
    void protectionIsMadeUpForSoAbilityDamageIsTrue() {
        PlayerMock alex = player("Alex", 0, 0);
        assertEquals(8.0, io.github.drepfy.legendary.ability.Hits.throughProtection(alex, 8.0), 1.0E-9, "no armour");
        org.bukkit.enchantments.Enchantment protection = io.github.drepfy.legendary.util.Compat.enchantment("protection");
        ItemStack[] armour = {new ItemStack(Material.NETHERITE_BOOTS), new ItemStack(Material.NETHERITE_LEGGINGS),
                new ItemStack(Material.NETHERITE_CHESTPLATE), new ItemStack(Material.NETHERITE_HELMET)};
        for (ItemStack piece : armour) {
            piece.addEnchantment(protection, 4);
        }
        alex.getEquipment().setArmorContents(armour);
        double dealt = io.github.drepfy.legendary.ability.Hits.throughProtection(alex, 8.0);
        assertEquals(8.0, dealt * (1.0 - 16 / 25.0), 1.0E-9, "Protection IV x4 takes 64% of magic damage: made up for");
    }

    private List<String> freshWarnings() {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        file.delete();
        plugin.saveDefaultConfig();
        return plugin.reload();
    }
}
