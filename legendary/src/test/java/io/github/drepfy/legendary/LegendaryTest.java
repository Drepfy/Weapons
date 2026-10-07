package io.github.drepfy.legendary;

import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.registry.WeaponRecord;
import io.github.drepfy.legendary.registry.WeaponRegistry;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent;
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

    /** Changes one setting in config.yml and reloads (it must be a valid one). */
    private void setting(String path, Object value) {
        plugin.getConfig().set(path, value);
        plugin.saveConfig();
        assertEquals(List.of(), plugin.reload());
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

    /** A sword or axe hit on a mob. */
    private EntityDamageByEntityEvent melee(PlayerMock attacker, org.bukkit.entity.LivingEntity target) {
        EntityDamageByEntityEvent event = new EntityDamageByEntityEvent(attacker, target,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(attacker).withDirectEntity(attacker).build(), SWORD);
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

    /**
     * Whether Iron Bastion's full knockback resistance is on, where the simulated server has the
     * attribute (it does not have it yet; a real one does).
     */
    private static void assertBraced(boolean expected, Player player) {
        Boolean braced = modern(() -> {
            org.bukkit.attribute.AttributeInstance resistance = player.getAttribute(org.bukkit.attribute.Attribute.KNOCKBACK_RESISTANCE);
            return resistance != null && resistance.getModifiers().stream()
                    .anyMatch(modifier -> modifier.getKey().getKey().equals("iron_bastion") && modifier.getAmount() == 1.0);
        });
        if (braced != null) {
            assertEquals(expected, braced, expected ? "no knockback while braced" : "back to normal");
        }
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
    void fourNetheriteWeaponsWithTheirEnchantmentsNamesLoreAndIds() {
        PlayerMock steve = player("Steve", 0, 0);
        List<UUID> ids = new ArrayList<>();
        for (WeaponType type : WeaponType.values()) {
            ItemStack item = give(steve, type);
            boolean axe = type == WeaponType.CRUSH;
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
            assertEquals(type.key(), name.toLowerCase().replace(" ", ""), "named " + name);
            String lore = ChatColor.stripColor(String.join("\n", meta.getLore()));
            for (Ability ability : type.abilities()) {
                assertTrue(lore.contains(plugin.settings().ability(ability).name()), type + " lore names " + ability);
            }
            assertFalse(lore.contains("Sharpness"), "not twice: " + lore);
            // The real attack damage: Sharpness VII included (vanilla would say 8 and 10), and 1.25x
            // for being a legendary (melee-damage).
            assertTrue(lore.endsWith("When in Main Hand:\n " + (axe ? "17.5 Attack Damage\n 1 Attack Speed"
                    : "15 Attack Damage\n 1.6 Attack Speed")), lore);
            assertTrue(meta.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES), "instead of vanilla's lines");
            assertFalse(lore.contains(WeaponItems.shortId(id(item))), "no tracking number in the lore");
            assertFalse(lore.contains("LEGENDARY"), "no LEGENDARY line at the bottom");
            assertEquals(WeaponRecord.State.HELD, record(item).state());
            assertEquals(steve.getUniqueId(), record(item).holder());
            ids.add(id(item));
        }
        assertEquals(4, ids.stream().distinct().count(), "every weapon has its own id");
        // A plain netherite sword with the same name is not a legendary.
        ItemStack fake = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = fake.getItemMeta();
        meta.setDisplayName("Katana");
        fake.setItemMeta(meta);
        assertFalse(plugin.items().isLegendary(fake));
    }

    @Test
    void onlyOneOfEachUntilRemoved() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 3, 0);
        give(steve, WeaponType.KATANA);
        command("legendary give Alex katana");
        assertEquals(0, count(alex, WeaponType.KATANA), "a second Katana is refused");
        command("legendary remove Steve katana");
        assertEquals(0, count(steve, WeaponType.KATANA));
        command("legendary give Alex katana");
        assertEquals(1, count(alex, WeaponType.KATANA));
    }

    @Test
    void cannotBeCrafted() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KATANA);
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
        ItemStack sword = give(steve, WeaponType.CANDY_CANE);
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
        ItemStack sword = give(steve, WeaponType.REAPER);
        InventoryView view = ownView(steve);
        steve.getInventory().setItemInMainHand(null);
        steve.setItemOnCursor(sword);
        int raw = view.getTopInventory().getSize() + 9;
        assertFalse(click(view, raw, ClickType.LEFT, InventoryAction.PLACE_ALL).isCancelled());
    }

    @Test
    void notInBundlesFramesOrStands() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KATANA);
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
        ItemStack sword = give(steve, WeaponType.KATANA);
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
        ItemStack sword = give(steve, WeaponType.CRUSH);
        steve.getInventory().setItemInMainHand(null);
        Inventory chest = server.createInventory(null, InventoryType.CHEST);
        chest.setItem(4, sword); // Put there before the plugin was installed.
        steve.openInventory(chest);
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryOpenEvent(steve.getOpenInventory()));
        assertNull(chest.getItem(4));
        assertEquals(1, count(steve, WeaponType.CRUSH));
        // And from the ender chest when joining.
        steve.closeInventory();
        steve.getInventory().clear();
        steve.getEnderChest().setItem(0, sword);
        steve.disconnect();
        steve.reconnect();
        tick(2);
        assertNull(steve.getEnderChest().getItem(0));
        assertEquals(1, count(steve, WeaponType.CRUSH));
    }

    @Test
    void sellCommandsAreRefusedForTheLegendaryOnly() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack axe = give(steve, WeaponType.CRUSH);
        for (String line : List.of("/sell hand", "/ah sell 1k", "/essentials:sellhand", "/auction sell 10m",
                "/auction list 5k", "/auctionhouse list 2m", "/AH LIST 1k")) {
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
        ItemStack sword = give(steve, WeaponType.KATANA);
        alex.getInventory().addItem(sword.clone()); // A dupe glitch.
        plugin.tracker().scan();
        assertEquals(1, count(steve, WeaponType.KATANA), "the original stays");
        assertEquals(0, count(alex, WeaponType.KATANA), "the copy is gone");
        assertTrue(has(chat(alex), "can't be duplicated"));
        // Two in one inventory.
        steve.getInventory().addItem(sword.clone());
        plugin.tracker().scan();
        assertEquals(1, count(steve, WeaponType.KATANA));
    }

    @Test
    void aCopyCannotUseAbilities() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 5, 0);
        ItemStack sword = give(steve, WeaponType.CANDY_CANE);
        alex.getInventory().setItemInMainHand(sword.clone());
        sneakUseKey(alex);
        tick(2);
        assertEquals(0, plugin.visuals().count(), "no traps");
        assertEquals(0, count(alex, WeaponType.CANDY_CANE));
    }

    @Test
    void creativeMiddleClickCannotCopyIt() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KATANA);
        steve.getInventory().setItem(9, sword);
        InventoryView view = ownView(steve);
        InventoryClickEvent clone = click(view, view.getTopInventory().getSize() + 9, ClickType.MIDDLE,
                InventoryAction.CLONE_STACK);
        assertTrue(clone.isCancelled());
    }

    @Test
    void removedFromAnOfflinePlayerItDisappearsWhenTheyJoin() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.REAPER);
        steve.disconnect();
        command("legendary remove Steve reaper");
        steve.reconnect();
        tick(2);
        assertEquals(0, count(steve, WeaponType.REAPER));
        assertTrue(has(chat(steve), "taken away by staff"));
        command("legendary give Steve reaper");
        assertEquals(1, count(steve, WeaponType.REAPER), "and a new one can be given");
    }

    @Test
    void droppedAndPickedUpItIsFollowed() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 3, 0);
        ItemStack sword = give(steve, WeaponType.CRUSH);
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
        ItemStack sword = give(steve, WeaponType.KATANA);
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
        ItemStack sword = give(steve, WeaponType.CRUSH);
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
        assertEquals(0, count(steve, WeaponType.CRUSH), "not kept, even with keepInventory");
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
        give(steve, WeaponType.KATANA);
        admin.openInventory(steve.getInventory()); // /invsee
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.InventoryOpenEvent(admin.getOpenInventory()));
        plugin.tracker().scan();
        assertEquals(1, count(steve, WeaponType.KATANA));
        assertEquals(0, count(admin, WeaponType.KATANA));
    }

    @Test
    void aWeaponThatVanishesStaysWithItsHolderUnlessMarkLostIsOn() {
        PlayerMock admin = player("Admin", 5, 5);
        admin.setOp(true);
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack old = give(steve, WeaponType.CANDY_CANE);
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
        command("legendary give Steve candycane");
        assertEquals(1, count(steve, WeaponType.CANDY_CANE), "a lost weapon can be given out again");
        assertEquals(WeaponRecord.State.REMOVED, plugin.registry().get(oldId).state(), "the old one is retired");
        // If the old copy ever turns up, it is deleted rather than becoming a second one.
        PlayerMock alex = player("Alex", 3, 0);
        alex.getInventory().addItem(old);
        plugin.tracker().scan();
        assertEquals(0, count(alex, WeaponType.CANDY_CANE));
    }

    @Test
    void aDeathThatIsCancelledGivesTheWeaponBack() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack axe = give(steve, WeaponType.CRUSH);
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.HIGH)
            public void onDeath(PlayerDeathEvent event) {
                event.setCancelled(true); // A plugin saving the player.
            }
        }, plugin);
        PlayerDeathEvent death = new PlayerDeathEvent(steve, DamageSource.builder(DamageType.GENERIC).build(),
                new ArrayList<>(List.of(axe)), 0, "Steve died");
        server.getPluginManager().callEvent(death);
        assertEquals(1, count(steve, WeaponType.CRUSH));
        assertEquals(WeaponRecord.State.HELD, record(axe).state());
    }

    @Test
    void theRegistryIsKeptAcrossRestarts() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KATANA);
        plugin.registry().close(0);
        WeaponRegistry reloaded = new WeaponRegistry(plugin.getLogger(), plugin.getDataFolder().toPath(), clock::get);
        WeaponRecord record = reloaded.get(id(sword));
        assertNotNull(record);
        assertEquals(WeaponType.KATANA, record.type());
        assertEquals(WeaponRecord.State.HELD, record.state());
        assertEquals("Steve", record.holderName());
        reloaded.close(0);
    }

    // ---- controls ---------------------------------------------------------------------------------------

    /** A fully charged sword hit with Sharpness VII (axes: AXE); weaker hits do less. */
    private static final double SWORD = 12.0;
    private static final double AXE = 14.0;

    @Test
    void shiftFUsesTheAbilityAndPlainFSwapsHands() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KATANA);
        assertFalse(useKey(steve).isCancelled(), "plain F swaps hands as usual");
        assertEquals(0, cooldown(sword, Ability.DRAW));
        steve.getInventory().setItemInMainHand(sword);
        steve.getInventory().setItemInOffHand(null);
        PlayerSwapHandItemsEvent shiftF = sneakUseKey(steve);
        assertTrue(shiftF.isCancelled(), "the weapon is not swapped into the offhand");
        assertTrue(cooldown(sword, Ability.DRAW) > 0, "Shift + F: Draw");
        assertEquals(sword, steve.getInventory().getItemInMainHand());
        rightClick(steve);
        sneakRightClick(steve);
        String lore = ChatColor.stripColor(String.join("\n", sword.getItemMeta().getLore()));
        assertTrue(lore.contains("Shift + F » Draw (25s)"), lore);
        assertTrue(lore.contains("Passive » Bleed"), lore);
        // A legendary in the offhand swaps back to the main hand as usual.
        steve.getInventory().setItemInMainHand(new ItemStack(Material.BREAD));
        steve.getInventory().setItemInOffHand(sword);
        assertFalse(sneakUseKey(steve).isCancelled());
    }

    @Test
    void rightClickControlsStillWork() {
        controls("right-click");
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KATANA);
        assertFalse(sneakUseKey(steve).isCancelled(), "F swaps hands as usual");
        steve.getInventory().setItemInMainHand(sword);
        steve.getInventory().setItemInOffHand(null);
        rightClick(steve);
        assertEquals(0, cooldown(sword, Ability.DRAW), "a plain right-click does nothing");
        sneakRightClick(steve);
        assertTrue(cooldown(sword, Ability.DRAW) > 0, "sneak + right-click: Draw");
        String lore = ChatColor.stripColor(String.join("\n", steve.getInventory().getItemInMainHand().getItemMeta().getLore()));
        assertTrue(lore.contains("Sneak + right-click » Draw"), lore);

        controls("both");
        PlayerMock alex = player("Alex", 9, 0);
        ItemStack blade = give(alex, WeaponType.REAPER);
        assertTrue(sneakUseKey(alex).isCancelled(), "both: Shift + F works too");
        assertTrue(cooldown(blade, Ability.REAP) > 0, "Reap was used");
    }

    @Test
    void eatingFromTheOffhandDoesNotWasteTheAbility() {
        controls("right-click");
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack cane = give(steve, WeaponType.CANDY_CANE);
        steve.getInventory().setItemInOffHand(new ItemStack(Material.GOLDEN_APPLE));
        sneakRightClick(steve);
        assertEquals(0, cooldown(cane, Ability.SUGAR_TRAP), "the golden apple is eaten instead");
        steve.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        tick(5);
        sneakRightClick(steve);
        assertTrue(cooldown(cane, Ability.SUGAR_TRAP) > 0, "with a shield the ability still works");
        tick(5);
        sneakRightClick(steve); // Blocking while it recharges.
        tick(2);
        assertEquals(BarColor.PINK, plugin.hud().bars(steve).get(Ability.SUGAR_TRAP).getColor(), "no nagging while blocking");
    }

    // ---- Katana -----------------------------------------------------------------------------------------

    @Test
    void bleedHurtsOverTimeStartsOverWhenHitAgainAndNeverStacks() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.KATANA);
        // (The sword hits themselves are not applied here: only the bleeding hurts.)
        melee(steve, alex, 5.0);
        tick(20);
        assertEquals(19.0, alex.getHealth(), 1.0E-6, "half a heart a second");
        tick(20);
        assertEquals(18.0, alex.getHealth(), 1.0E-6);
        melee(steve, alex, 5.0);
        melee(steve, alex, 5.0);
        tick(20);
        assertEquals(17.0, alex.getHealth(), 1.0E-6, "hit again: still half a heart a second, never more");
        tick(60);
        assertEquals(14.0, alex.getHealth(), 1.0E-6, "and 4 seconds from the last hit");
        tick(40);
        assertEquals(14.0, alex.getHealth(), 1.0E-6, "then it stops");
    }

    @Test
    void drawCutsTheNextFullStrengthHitForAShareOfTheirHealth() {
        setting("weapons.katana.abilities.bleed.damage", 0); // Only Draw here.
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        ItemStack sword = give(steve, WeaponType.KATANA);
        sneakUseKey(steve);
        assertTrue(cooldown(sword, Ability.DRAW) > 0);
        WeaponItems.Tag tag = plugin.items().read(sword);
        assertTrue(plugin.abilities().active(steve, tag, Ability.DRAW, plugin.tick()) > 0, "waiting for the hit");
        melee(steve, alex, 5.0);
        tick(1);
        assertEquals(20.0, alex.getHealth(), "a weak (spam) hit does not use it");
        Zombie zombie = world.spawn(new Location(world, 2.5, 64, 2.5), Zombie.class);
        melee(steve, zombie);
        tick(1);
        assertTrue(plugin.abilities().active(steve, tag, Ability.DRAW, plugin.tick()) > 0, "nor a hit on a mob");
        melee(steve, alex, SWORD);
        tick(1);
        assertEquals(14.0, alex.getHealth(), 1.0E-6, "30% of 10 hearts: 3 hearts");
        assertEquals(0, plugin.abilities().active(steve, tag, Ability.DRAW, plugin.tick()), "used up");
        melee(steve, alex, SWORD);
        tick(1);
        assertEquals(14.0, alex.getHealth(), 1.0E-6, "once");
    }

    @Test
    void drawDoesLessToLowPlayersAndRunsOut() {
        setting("weapons.katana.abilities.bleed.damage", 0);
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.KATANA);
        alex.setHealth(4.0);
        sneakUseKey(steve);
        melee(steve, alex, SWORD);
        tick(1);
        assertEquals(2.0, alex.getHealth(), 1.0E-6, "30% of 2 hearts is less than the minimum: 1 heart");
        tick(25 * 20);
        alex.setHealth(20.0);
        sneakUseKey(steve);
        tick(4 * 20 + 1);
        melee(steve, alex, SWORD);
        tick(1);
        assertEquals(20.0, alex.getHealth(), "4 seconds to land it");
    }

    // ---- Candy Cane -------------------------------------------------------------------------------------

    @Test
    void stickySweetSlowsButCannotKeepThemSlowed() {
        setting("weapons.candycane.abilities.sticky-sweet.chance", 1.0);
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.CANDY_CANE);
        melee(steve, alex, 5.0);
        org.bukkit.potion.PotionEffect slow = alex.getPotionEffect(PotionEffectType.SLOWNESS);
        assertNotNull(slow, "stuck");
        assertEquals(0, slow.getAmplifier(), "Slowness I");
        assertEquals(30, slow.getDuration(), "for 1.5 seconds");
        alex.removePotionEffect(PotionEffectType.SLOWNESS);
        tick(20);
        melee(steve, alex, 5.0);
        assertFalse(alex.hasPotionEffect(PotionEffectType.SLOWNESS), "not again within 3 seconds");
        tick(41);
        melee(steve, alex, 5.0);
        assertTrue(alex.hasPotionEffect(PotionEffectType.SLOWNESS), "then it may stick again");

        setting("weapons.candycane.abilities.sticky-sweet.chance", 0);
        PlayerMock bob = player("Bob", 2, 0);
        melee(steve, bob, 5.0);
        assertFalse(bob.hasPotionEffect(PotionEffectType.SLOWNESS), "chance 0: never");
    }

    /** Where Sugar Trap number i lands for a player at block (0, 0) facing south. */
    private Location trapSpot(int i) {
        Vector way = new Vector(0, 0, 1).rotateAroundY(Math.toRadians(72.0 * i)).multiply(2.5);
        return new Location(world, 0.5 + way.getX(), 64, 0.5 + way.getZ());
    }

    @Test
    void sugarTrapsCatchEnemiesOnceAndNeverTheirOwner() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 9, 9);
        PlayerMock bob = player("Bob", -9, 9);
        ItemStack cane = give(steve, WeaponType.CANDY_CANE);
        sneakUseKey(steve);
        assertTrue(cooldown(cane, Ability.SUGAR_TRAP) > 0);
        assertEquals(5, plugin.visuals().count(), "five traps");
        tick(10);
        alex.teleport(trapSpot(0));
        tick(1);
        assertEquals(0, alex.getPotionEffect(PotionEffectType.POISON).getAmplifier(), "Poison I");
        assertEquals(80, alex.getPotionEffect(PotionEffectType.POISON).getDuration(), "for 4 seconds");
        assertEquals(80, alex.getPotionEffect(PotionEffectType.NAUSEA).getDuration());
        assertEquals(20, alex.getPotionEffect(PotionEffectType.SLOWNESS).getDuration(), "and Slowness I for 1 second");
        alex.getActivePotionEffects().forEach(effect -> alex.removePotionEffect(effect.getType()));
        tick(5);
        assertFalse(alex.hasPotionEffect(PotionEffectType.POISON), "that trap is gone");
        steve.teleport(trapSpot(1));
        tick(2);
        assertFalse(steve.hasPotionEffect(PotionEffectType.POISON), "the owner is never caught");
        bob.teleport(trapSpot(2));
        tick(1);
        assertTrue(bob.hasPotionEffect(PotionEffectType.POISON), "the others are still there");
        tick(8 * 20);
        alex.teleport(trapSpot(3));
        tick(2);
        assertFalse(alex.hasPotionEffect(PotionEffectType.POISON), "gone after 8 seconds");
        assertEquals(0, plugin.visuals().count());
    }

    @Test
    void sugarTrapsNeedGroundAndNothingIsSpentWithout() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack cane = give(steve, WeaponType.CANDY_CANE);
        steve.teleport(new Location(world, 0.5, 120, 0.5)); // High in the air.
        chat(steve);
        sneakUseKey(steve);
        assertEquals(0, cooldown(cane, Ability.SUGAR_TRAP), "nothing spent");
        assertTrue(has(chat(steve), "no room"));
        assertEquals(0, plugin.visuals().count());
        // Walls round three sides: only the traps with room are set.
        steve.teleport(new Location(world, 0.5, 64, 0.5));
        for (int x = -4; x <= 4; x++) {
            for (int y = 64; y <= 66; y++) {
                world.getBlockAt(x, y, -1).setType(Material.STONE);
            }
        }
        sneakUseKey(steve);
        assertTrue(cooldown(cane, Ability.SUGAR_TRAP) > 0);
        int traps = plugin.visuals().count();
        assertTrue(traps >= 1 && traps < 5, "only where there is room: " + traps);
    }

    // ---- Crush ------------------------------------------------------------------------------------------

    /** The knockback the game gives after a hit, as Paper reports it to plugins. */
    private EntityPushedByEntityAttackEvent knockback(PlayerMock attacker, PlayerMock target, Vector push) {
        EntityPushedByEntityAttackEvent event = new EntityPushedByEntityAttackEvent(target,
                EntityKnockbackEvent.Cause.ENTITY_ATTACK, attacker, push);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private void lift(PlayerMock player, double blocks) {
        Location at = player.getLocation();
        player.teleport(new Location(world, at.getX(), 64 + blocks, at.getZ()));
    }

    @Test
    void heavyKnocksFurtherAndKnocksPlayersInTheAirDown() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.CRUSH);
        melee(steve, alex, AXE);
        Vector pushed = knockback(steve, alex, new Vector(0, 0.36, 0.4)).getKnockback();
        assertEquals(0.52, pushed.getZ(), 1.0E-6, "30% further");
        assertEquals(0.36, pushed.getY(), 1.0E-6, "not higher");
        tick(1);
        assertEquals(0.4, knockback(steve, alex, new Vector(0, 0.36, 0.4)).getKnockback().getZ(), 1.0E-6,
                "only right after an axe hit");

        lift(alex, 3);
        melee(steve, alex, 5.0);
        tick(1);
        assertEquals(20.0, alex.getHealth(), "a weak hit in the air: nothing more");
        melee(steve, alex, AXE);
        tick(1);
        assertEquals(18.5, alex.getHealth(), 1.0E-6, "a full-strength hit: 0.75 hearts more");
        assertEquals(-0.8, alex.getVelocity().getY(), 1.0E-6, "knocked down");
    }

    @Test
    void crushSlamsHarderTheHigherTheyWere() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        ItemStack axe = give(steve, WeaponType.CRUSH);
        sneakUseKey(steve);
        assertTrue(cooldown(axe, Ability.CRUSH) > 0);
        WeaponItems.Tag tag = plugin.items().read(axe);
        melee(steve, alex, 5.0);
        tick(1);
        assertTrue(plugin.abilities().active(steve, tag, Ability.CRUSH, plugin.tick()) > 0, "a weak hit does not use it");
        lift(alex, 4);
        melee(steve, alex, AXE);
        tick(1);
        assertEquals(13.0, alex.getHealth(), 1.0E-6, "0.5 + 0.75 a block for 4 blocks: 3.5 hearts");
        assertEquals(-1.6, alex.getVelocity().getY(), 1.0E-6, "slammed down");
        assertEquals(0, plugin.abilities().active(steve, tag, Ability.CRUSH, plugin.tick()), "used up");
        int effects = plugin.visuals().count();
        lift(alex, 0); // Landed.
        tick(1);
        assertTrue(plugin.visuals().count() > effects, "the ground cracks where they land");
        for (int z = 0; z <= 4; z++) {
            assertEquals(Material.STONE, world.getBlockAt(0, 63, z).getType(), "no block is changed");
            assertEquals(Material.AIR, world.getBlockAt(0, 64, z).getType());
        }
    }

    @Test
    void crushIsCappedAndSmallOnTheGround() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.CRUSH);
        sneakUseKey(steve);
        lift(alex, 12);
        melee(steve, alex, AXE);
        tick(1);
        assertEquals(12.0, alex.getHealth(), 1.0E-6, "at most 4 hearts");
        tick(28 * 20);
        alex.setHealth(20.0);
        lift(alex, 0);
        sneakUseKey(steve);
        melee(steve, alex, AXE);
        tick(1);
        assertEquals(19.0, alex.getHealth(), 1.0E-6, "on the ground: half a heart");
        tick(28 * 20);
        sneakUseKey(steve);
        tick(10 * 20 + 1);
        alex.setHealth(20.0);
        lift(alex, 4);
        melee(steve, alex, AXE);
        tick(1);
        assertEquals(18.5, alex.getHealth(), 1.0E-6, "after 10 seconds it is gone (only Heavy's knock-down)");
    }

    // ---- Reaper -----------------------------------------------------------------------------------------

    @Test
    void executionHitsLowPlayersHarder() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.REAPER);
        melee(steve, alex, 5.0);
        tick(1);
        assertEquals(20.0, alex.getHealth(), "above 6 hearts: nothing more");
        alex.setHealth(11.0);
        melee(steve, alex, 5.0);
        tick(1);
        assertEquals(9.5, alex.getHealth(), 1.0E-6, "below 6 hearts: 0.75 hearts more, even on a weak hit");
    }

    @Test
    void reapWaitsForALowTargetThenReapsThem() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        ItemStack blade = give(steve, WeaponType.REAPER);
        WeaponItems.Tag tag = plugin.items().read(blade);
        sneakUseKey(steve);
        assertTrue(cooldown(blade, Ability.REAP) > 0);
        melee(steve, alex, SWORD);
        tick(1);
        assertEquals(20.0, alex.getHealth(), "above 8 hearts: not used up");
        assertTrue(plugin.abilities().active(steve, tag, Ability.REAP, plugin.tick()) > 0);
        alex.setHealth(15.0);
        melee(steve, alex, 5.0);
        tick(1);
        assertEquals(15.0, alex.getHealth(), "not on a weak hit");
        melee(steve, alex, SWORD);
        tick(1);
        assertEquals(8.0, alex.getHealth(), 1.0E-6, "below 8 hearts: reaped for 3.5 hearts");
        assertEquals(0, plugin.abilities().active(steve, tag, Ability.REAP, plugin.tick()), "used up");
        melee(steve, alex, SWORD);
        tick(1);
        assertEquals(6.5, alex.getHealth(), 1.0E-6, "after that only Execution");
    }

    @Test
    void reapRunsOutAndAddsToExecution() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.REAPER);
        alex.setHealth(10.0);
        sneakUseKey(steve);
        melee(steve, alex, SWORD);
        tick(1);
        assertEquals(10.0 - 1.5 - 7.0, alex.getHealth(), 1.0E-6, "Execution and Reap together");
        tick(30 * 20);
        alex.setHealth(14.0);
        sneakUseKey(steve);
        tick(8 * 20 + 1);
        melee(steve, alex, SWORD);
        tick(1);
        assertEquals(14.0, alex.getHealth(), 1.0E-6, "8 seconds to land it");
    }

    // ---- old weapons -------------------------------------------------------------------------------------

    @Test
    void theOldWeaponsBecomeTheNewOnes() {
        assertEquals(WeaponType.KATANA, WeaponType.byKey("kurogane"));
        assertEquals(WeaponType.CANDY_CANE, WeaponType.byKey("sugarcrash"));
        assertEquals(WeaponType.CRUSH, WeaponType.byKey("gravebreaker"));
        assertEquals(WeaponType.REAPER, WeaponType.byKey("wyrmfang"));
        assertEquals(WeaponType.REAPER, WeaponType.byKey("riftblade"));
        assertEquals(WeaponType.CANDY_CANE, WeaponType.byKey("Candy-Cane"));
        assertNull(WeaponType.byKey("starforged"));

        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KATANA);
        UUID id = id(sword);
        // As 2.0 left it: a Kurogane, in the inventory and in data.yml.
        ItemMeta meta = sword.getItemMeta();
        meta.setDisplayName("Kurogane");
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "weapon"),
                org.bukkit.persistence.PersistentDataType.STRING, "kurogane");
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "look"),
                org.bukkit.persistence.PersistentDataType.STRING, "2.0");
        sword.setItemMeta(meta);
        steve.getInventory().setItemInMainHand(sword);
        tick(40);
        ItemStack held = steve.getInventory().getItemInMainHand();
        assertEquals("Katana", ChatColor.stripColor(held.getItemMeta().getDisplayName()), "the new name and lore");
        assertEquals("katana", held.getItemMeta().getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(plugin, "weapon"), org.bukkit.persistence.PersistentDataType.STRING));
        assertEquals(id, id(held), "the same weapon");

        plugin.registry().close(0);
        java.nio.file.Path data = plugin.getDataFolder().toPath().resolve("data.yml");
        try {
            String yaml = java.nio.file.Files.readString(data);
            java.nio.file.Files.writeString(data, yaml.replace("type: katana", "type: kurogane"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        WeaponRegistry reloaded = new WeaponRegistry(plugin.getLogger(), plugin.getDataFolder().toPath(), clock::get);
        assertEquals(WeaponType.KATANA, reloaded.get(id).type(), "data.yml's Kurogane too");
        reloaded.close(0);
    }

    @Test
    void aStarforgedNoLongerExistsAndIsTakenAway() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack old = new ItemStack(Material.NETHERITE_AXE);
        ItemMeta meta = old.getItemMeta();
        meta.setDisplayName("Starforged");
        meta.addEnchant(io.github.drepfy.legendary.util.Compat.enchantment("sharpness"), 7, true);
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "weapon"),
                org.bukkit.persistence.PersistentDataType.STRING, "starforged");
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "id"),
                org.bukkit.persistence.PersistentDataType.STRING, UUID.randomUUID().toString());
        old.setItemMeta(meta);
        steve.getInventory().setItem(4, old);
        chat(steve);
        tick(40);
        assertNull(steve.getInventory().getItem(4), "taken away");
        assertTrue(has(chat(steve), "no longer exists"));
        command("legendary give Steve starforged");
        assertEquals(0, count(steve, WeaponType.CRUSH), "and it cannot be given");
    }

    // ---- fairness ---------------------------------------------------------------------------------------

    @Test
    void protectedPlayersAreNeverHurtTrappedOrSlowed() {
        setting("weapons.candycane.abilities.sticky-sweet.chance", 1.0);
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock safe = player("Safe", 0, 2);
        protect(safe);
        ItemStack sword = give(steve, WeaponType.KATANA);
        sneakUseKey(steve);
        melee(steve, safe, SWORD);
        tick(60);
        assertEquals(20.0, safe.getHealth(), "no cut, no bleeding");
        assertTrue(plugin.abilities().active(steve, plugin.items().read(sword), Ability.DRAW, plugin.tick()) > 0,
                "a hit that did not land does not use Draw up");

        give(steve, WeaponType.CANDY_CANE);
        melee(steve, safe, SWORD);
        assertFalse(safe.hasPotionEffect(PotionEffectType.SLOWNESS));
        stand(steve, 0, 0, 0f);
        sneakUseKey(steve);
        safe.teleport(trapSpot(0));
        tick(2);
        assertFalse(safe.hasPotionEffect(PotionEffectType.POISON), "not caught by a trap");

        give(steve, WeaponType.CRUSH);
        sneakUseKey(steve);
        lift(safe, 3);
        melee(steve, safe, AXE);
        tick(2);
        assertEquals(20.0, safe.getHealth());
        assertEquals(0.0, safe.getVelocity().length(), 1.0E-9, "not slammed");

        give(steve, WeaponType.REAPER);
        safe.setHealth(10.0);
        sneakUseKey(steve);
        melee(steve, safe, SWORD);
        tick(2);
        assertEquals(10.0, safe.getHealth(), "not reaped");
    }

    @Test
    void creativePlayersMobsAndPvpOffAreLeftAlone() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock builder = player("Builder", 9, 9);
        builder.setGameMode(org.bukkit.GameMode.CREATIVE);
        give(steve, WeaponType.CANDY_CANE);
        sneakUseKey(steve);
        builder.teleport(trapSpot(0));
        tick(2);
        assertFalse(builder.hasPotionEffect(PotionEffectType.POISON), "creative");
        builder.setGameMode(org.bukkit.GameMode.SURVIVAL);
        world.setPVP(false);
        builder.teleport(trapSpot(1));
        tick(2);
        assertFalse(builder.hasPotionEffect(PotionEffectType.POISON), "PvP off in this world");
        give(steve, WeaponType.KATANA);
        melee(steve, builder, SWORD);
        tick(40);
        assertEquals(20.0, builder.getHealth(), "no bleeding with PvP off");
        world.setPVP(true);
        Zombie zombie = world.spawn(new Location(world, 3.5, 64, 3.5), Zombie.class);
        double health = zombie.getHealth();
        melee(steve, zombie);
        tick(40);
        assertEquals(health, zombie.getHealth(), 1.0E-9, "mobs never bleed: the weapons are for PvP");
    }

    @Test
    void bleedingToDeathBreaksNothing() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        PlayerMock bob = player("Bob", 2, 0);
        ItemStack sword = give(steve, WeaponType.KATANA);
        melee(steve, alex, 5.0);
        melee(steve, bob, 5.0);
        alex.setHealth(1.0);
        bob.setHealth(1.0);
        tick(21);
        assertTrue(alex.isDead() && bob.isDead(), "both bled out on the same tick");
        tick(4);
        sneakUseKey(steve);
        assertTrue(cooldown(sword, Ability.DRAW) > 0, "everything kept running");
    }

    // ---- display, commands, config -----------------------------------------------------------------------

    @Test
    void bossBarsShowOnlyWhileAnAbilityWaitsOrRecharges() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.KATANA);
        tick(4);
        assertTrue(plugin.hud().bars(steve).isEmpty(), "everything ready: no bars");

        sneakUseKey(steve);
        tick(10);
        Map<Ability, BossBar> bars = plugin.hud().bars(steve);
        BossBar draw = bars.get(Ability.DRAW);
        assertNotNull(draw, "used: its bar appears");
        assertTrue(title(draw).matches("Draw » \\d\\.\\ds"), "while it waits for the hit: " + title(draw));
        assertTrue(draw.getProgress() > 0.5 && draw.getProgress() < 1.0, "running down");
        assertEquals(BarColor.RED, draw.getColor(), "the weapon's colour");
        assertTrue(draw.getPlayers().contains(steve));
        tick(80);
        assertEquals("Draw » 21s", title(draw), "then how long until it is ready");
        assertTrue(draw.getProgress() < 0.5, "filling up again");

        // Too early: no message, the bar flashes.
        sneakUseKey(steve);
        tick(2);
        assertEquals(BarColor.WHITE, draw.getColor());
        tick(8);
        assertEquals(BarColor.RED, draw.getColor());
        assertFalse(has(chat(steve), "ready in"), "nothing in chat");
        assertEquals(List.of(), actionBars(steve), "the action bar is left to the Combat plugin");

        // Put away: the bar stays while the sword is in the inventory...
        steve.getInventory().setHeldItemSlot(8);
        tick(4);
        assertEquals(java.util.Set.of(Ability.DRAW), plugin.hud().bars(steve).keySet(), "still counting down");
        steve.getInventory().setHeldItemSlot(0);
        // ...and goes when the sword does.
        ItemStack sword = steve.getInventory().getItemInMainHand();
        steve.getInventory().setItemInMainHand(null);
        tick(12);
        assertTrue(plugin.hud().bars(steve).isEmpty(), "not carried: no bar");
        assertFalse(draw.getPlayers().contains(steve));
        steve.getInventory().setItemInMainHand(sword);
        tick(25 * 20);
        assertTrue(plugin.hud().bars(steve).isEmpty(), "ready: the bar is gone");
    }

    @Test
    void readyBarsCanBeTurnedOn() {
        setting("display.boss-bars-when-ready", true);
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.CRUSH);
        tick(4);
        Map<Ability, BossBar> bars = plugin.hud().bars(steve);
        assertEquals(java.util.Set.of(Ability.CRUSH), bars.keySet());
        assertEquals("Crush", title(bars.get(Ability.CRUSH)), "ready: just its name");
        assertEquals(1.0, bars.get(Ability.CRUSH).getProgress(), 1.0E-9);
    }

    @Test
    void abilitiesShowTheirEffectsAndCleanThemUp() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.REAPER);
        sneakUseKey(steve);
        List<String> models = new ArrayList<>();
        for (ItemDisplay display : world.getEntitiesByClass(ItemDisplay.class)) {
            assertFalse(display.isPersistent(), "never saved with the world");
            ItemMeta meta = display.getItemStack().getItemMeta();
            models.add(String.valueOf(modern(meta::getItemModel)));
        }
        assertEquals(1, models.size(), "the ring of souls: " + models);
        if (!models.contains("null")) {
            assertEquals(List.of("legendary:fx/soul_ring"), models);
        }
        tick(8 * 20 + 20);
        assertEquals(0, plugin.visuals().count(), "all gone again");
        assertTrue(world.getEntitiesByClass(ItemDisplay.class).isEmpty());
    }

    @Test
    void everySoundIsARealMinecraftSound() {
        for (Map.Entry<String, List<io.github.drepfy.legendary.config.Settings.SoundSpec>> entry
                : plugin.settings().sounds().entrySet()) {
            for (io.github.drepfy.legendary.config.Settings.SoundSpec spec : entry.getValue()) {
                String key = spec.key();
                assertFalse(key.contains(":") && !key.startsWith("minecraft:"), entry.getKey() + ": a vanilla sound, " + key);
                String field = key.replace("minecraft:", "").replace('.', '_').toUpperCase(java.util.Locale.ROOT);
                try {
                    org.bukkit.Sound.class.getField(field);
                } catch (NoSuchFieldException e) {
                    throw new AssertionError(entry.getKey() + ": no such sound " + key);
                }
            }
        }
    }

    @Test
    void listInspectAndReload() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock admin = player("Admin", 5, 5);
        admin.setOp(true);
        ItemStack sword = give(steve, WeaponType.KATANA);
        chat(admin);
        server.dispatchCommand(admin, "legendary list");
        List<String> list = chat(admin);
        assertTrue(has(list, "Katana #" + WeaponItems.shortId(id(sword)) + " held by Steve"), String.join("\n", list));
        assertTrue(has(list, "Candy Cane not given out"), String.join("\n", list));
        server.dispatchCommand(admin, "legendary inspect Steve");
        List<String> inspect = chat(admin);
        assertTrue(has(inspect, "Draw ready"), String.join("\n", inspect));

        PlayerMock player = player("Player", 6, 6);
        server.dispatchCommand(player, "legendary give Player katana");
        assertTrue(has(chat(player), "do not have permission"));

        // A new name in config.yml reaches the weapon already out there.
        plugin.getConfig().set("weapons.katana.name", "&cBlood Moon");
        plugin.saveConfig();
        server.dispatchCommand(admin, "legendary reload");
        assertTrue(has(chat(admin), "reloaded"));
        assertEquals("Blood Moon", ChatColor.stripColor(steve.getInventory().getItemInMainHand().getItemMeta().getDisplayName()));
    }

    @Test
    void wrongConfigValuesFallBackWithAWarning() {
        plugin.getConfig().set("weapons.katana.abilities.draw.cooldown", "soon");
        plugin.getConfig().set("weapons.candycane.abilities.sugar-trap.lifetime", "0.1s");
        plugin.getConfig().set("weapons.candycane.boss-bar.color", "rainbow");
        plugin.getConfig().set("melee-damage", 50);
        plugin.saveConfig();
        List<String> warnings = plugin.reload();
        assertEquals(4, warnings.size(), String.join("\n", warnings));
        assertEquals(25.0, plugin.settings().ability(Ability.DRAW).num("cooldown"));
        assertEquals(8.0, plugin.settings().ability(Ability.SUGAR_TRAP).num("lifetime"), "never under 1s");
        assertEquals(BarColor.PINK, plugin.settings().look(WeaponType.CANDY_CANE).barColor());
        assertEquals(1.25, plugin.settings().meleeDamage(), "at most 10x");
        assertEquals(List.of(), freshWarnings(), "the bundled config.yml has no mistakes");
    }

    @Test
    void aConfigFrom20GetsTheFourNewWeapons() {
        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
        config.set("config-version", 6);
        config.set("display.action-bar", true);
        config.set("hit-mobs", "hostile");
        config.set("full-strength-hits", null);
        config.set("weapons", null);
        config.set("weapons.kurogane.name", "&cBlood Moon");
        config.set("weapons.kurogane.abilities.phantom-step.cooldown", "21s");
        config.set("weapons.wyrmfang.custom-model-data", 1003);
        config.set("weapons.starforged.abilities.star-lance.cooldown", "29s");
        config.set("sounds", null);
        config.set("sounds.phantom-step", List.of("entity.player.attack.sweep 1 0.7"));
        config.set("sounds.received", List.of("block.bell.use 1 1")); // Their own.
        config.set("messages.no-target", "&7Nothing to aim at within {range} blocks.");
        config.set("messages.alt-blocked", "&cNo alts!"); // Their own.
        plugin.saveConfig();

        assertEquals(List.of(), plugin.reload(), "nothing left to warn about");
        org.bukkit.configuration.file.YamlConfiguration saved = savedConfig();
        org.bukkit.configuration.file.YamlConfiguration fresh = bundledConfig();
        assertEquals(7, saved.getInt("config-version"));
        assertFalse(saved.isSet("display.action-bar"));
        assertFalse(saved.isSet("hit-mobs"), "the weapons only affect players now");
        assertTrue(saved.getBoolean("full-strength-hits"), "the new setting is written in");
        assertEquals(List.of("katana", "candycane", "crush", "reaper"),
                List.copyOf(saved.getConfigurationSection("weapons").getKeys(false)), "the four new weapons");
        assertEquals(fresh.getConfigurationSection("weapons").getValues(true).toString(),
                saved.getConfigurationSection("weapons").getValues(true).toString());
        assertFalse(saved.isSet("sounds.phantom-step"));
        assertEquals(List.of("block.bell.use 1 1"), saved.getStringList("sounds.received"), "own sound kept");
        for (String key : fresh.getConfigurationSection("sounds").getKeys(false)) {
            if (!key.equals("received")) {
                assertEquals(fresh.getStringList("sounds." + key), saved.getStringList("sounds." + key), "sounds." + key);
            }
        }
        assertFalse(saved.isSet("messages.no-target"));
        assertEquals("&cNo alts!", saved.getString("messages.alt-blocked"), "own message kept");
        String text = savedText();
        assertTrue(text.contains("# Draw, Crush and Reap only go off on a full-strength hit"), "explained like a fresh one");
        assertTrue(text.contains("# Passive: a hit on a player makes them bleed"), "the new abilities too");
        PlayerMock steve = player("Steve", 0, 0);
        List<String> lore = give(steve, WeaponType.KATANA).getItemMeta().getLore().stream().map(ChatColor::stripColor).toList();
        assertTrue(lore.contains("Shift + F » Draw (25s)"), String.join("\n", lore));
        assertTrue(lore.contains("Hits make players bleed for 4s."), String.join("\n", lore));
        assertTrue(lore.contains(" 15 Attack Damage"), String.join("\n", lore));
        // Once up to date it is left alone.
        assertEquals(List.of(), plugin.reload());
    }

    @Test
    void oldDefaultMessagesAreUpdatedButOwnOnesAreKept() {
        org.bukkit.configuration.file.YamlConfiguration old = previousTexts();
        plugin.getConfig().set("messages.storage-blocked", old.getString("v1_0_1.messages.storage-blocked"));
        plugin.getConfig().set("messages.alt-blocked", "&cNo alts!");
        plugin.saveConfig();
        plugin.reload();
        assertEquals("&cLegendaries can't go in containers.", plugin.getConfig().getString("messages.storage-blocked"));
        assertEquals("&cNo alts!", plugin.getConfig().getString("messages.alt-blocked"));
        assertEquals("&cLegendaries can't go in containers.", savedConfig().getString("messages.storage-blocked"),
                "saved, so it sticks after the next restart");
    }

    @Test
    void anOldBlockedCommandListGetsTheAuctionListCommands() {
        plugin.getConfig().set("blocked-commands",
                List.of("ah sell", "ah list", "auction sell", "auctionhouse sell", "sell", "sellhand", "sellall"));
        plugin.saveConfig();
        assertEquals(List.of(), plugin.reload());
        assertTrue(plugin.settings().blockedCommands().containsAll(List.of("auction list", "auctionhouse list")),
                "the unchanged old list is brought up to date: " + plugin.settings().blockedCommands());
        // A list the owner changed is kept as it is.
        plugin.getConfig().set("blocked-commands", List.of("ah sell", "shop sell"));
        plugin.saveConfig();
        plugin.reload();
        assertEquals(List.of("ah sell", "shop sell"), plugin.settings().blockedCommands());
    }

    @Test
    void legendaryHitsDoMeleeDamageTimesTheirNormalDamage() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.REAPER);
        assertEquals(15.0, melee(steve, alex, 12.0).getDamage(), 1.0E-6, "1.25x by default");
        steve.getInventory().setHeldItemSlot(8);
        assertEquals(12.0, melee(steve, alex, 12.0).getDamage(), 1.0E-6, "other weapons are not changed");
        steve.getInventory().setHeldItemSlot(0);
        setting("melee-damage", 1.0);
        assertEquals(12.0, melee(steve, alex, 12.0).getDamage(), 1.0E-6, "1 = like any netherite sword");
        List<String> lore = steve.getInventory().getItemInMainHand().getItemMeta().getLore().stream()
                .map(ChatColor::stripColor).toList();
        assertTrue(lore.contains(" 12 Attack Damage"), "the lore follows: " + String.join("\n", lore));
    }

    @Test
    void fullStrengthHitsCanBeTurnedOff() {
        setting("full-strength-hits", false);
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.REAPER);
        alex.setHealth(15.0);
        sneakUseKey(steve);
        melee(steve, alex, 3.0);
        tick(1);
        assertEquals(8.0, alex.getHealth(), 1.0E-6, "any hit reaps");
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

    private org.bukkit.configuration.file.YamlConfiguration previousTexts() {
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                plugin.getResource("previous-text.yml"), java.nio.charset.StandardCharsets.UTF_8));
    }

    private org.bukkit.configuration.file.YamlConfiguration bundledConfig() {
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                plugin.getResource("config.yml"), java.nio.charset.StandardCharsets.UTF_8));
    }

    private org.bukkit.configuration.file.YamlConfiguration savedConfig() {
        return org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                new java.io.File(plugin.getDataFolder(), "config.yml"));
    }

    private String savedText() {
        try {
            return java.nio.file.Files.readString(new java.io.File(plugin.getDataFolder(), "config.yml").toPath());
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
    }

    private List<String> freshWarnings() {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        file.delete();
        plugin.saveDefaultConfig();
        return plugin.reload();
    }
}
