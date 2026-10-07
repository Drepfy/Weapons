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
        ItemStack sword = give(steve, WeaponType.WYRMFANG);
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
        give(steve, WeaponType.WYRMFANG);
        steve.disconnect();
        command("legendary remove Steve wyrmfang");
        steve.reconnect();
        tick(2);
        assertEquals(0, count(steve, WeaponType.WYRMFANG));
        assertTrue(has(chat(steve), "taken away by staff"));
        command("legendary give Steve wyrmfang");
        assertEquals(1, count(steve, WeaponType.WYRMFANG), "and a new one can be given");
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
        PlayerMock near = player("Near", 1, 0);
        ItemStack scythe = give(steve, WeaponType.SUGARCRASH);
        rightClick(steve);
        assertEquals(0, cooldown(scythe, Ability.CANDY_REAPER), "right-click no longer uses abilities");
        PlayerSwapHandItemsEvent f = useKey(steve);
        assertTrue(f.isCancelled(), "the weapon is not swapped into the offhand");
        assertTrue(cooldown(scythe, Ability.CANDY_REAPER) > 0, "F: Candy Reaper");
        assertEquals(scythe, steve.getInventory().getItemInMainHand());
        sneakUseKey(steve);
        tick(2);
        assertTrue(near.getHealth() < 20.0, "Shift + F: Sugar Rush");
        String lore = ChatColor.stripColor(String.join("\n", scythe.getItemMeta().getLore()));
        assertTrue(lore.contains("F » Candy Reaper (19s)") && lore.contains("Shift + F » Sugar Rush (33s)"), lore);
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
        assertEquals(0, cooldown(scythe, Ability.CANDY_REAPER));
        rightClick(steve);
        assertTrue(cooldown(scythe, Ability.CANDY_REAPER) > 0, "right-click: Candy Reaper");
        String lore = ChatColor.stripColor(String.join("\n", steve.getInventory().getItemInMainHand().getItemMeta().getLore()));
        assertTrue(lore.contains("Right-click » Candy Reaper") && lore.contains("Sneak + right-click » Sugar Rush"), lore);

        controls("both");
        PlayerMock alex = player("Alex", 9, 0);
        ItemStack blade = give(alex, WeaponType.WYRMFANG);
        assertTrue(useKey(alex).isCancelled(), "both: F works too");
        assertTrue(cooldown(blade, Ability.WYRM_LUNGE) > 0, "Wyrm Lunge was used");
    }

    @Test
    void eatingFromTheOffhandDoesNotWasteTheAbility() {
        controls("right-click");
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack scythe = give(steve, WeaponType.SUGARCRASH);
        steve.getInventory().setItemInOffHand(new ItemStack(Material.GOLDEN_APPLE));
        rightClick(steve);
        assertEquals(0, cooldown(scythe, Ability.CANDY_REAPER), "the golden apple is eaten instead");
        steve.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        tick(5);
        rightClick(steve);
        assertTrue(cooldown(scythe, Ability.CANDY_REAPER) > 0, "with a shield the ability still works");
        tick(5);
        rightClick(steve); // Blocking while it recharges.
        tick(2);
        assertEquals(BarColor.PINK, plugin.hud().bars(steve).get(Ability.CANDY_REAPER).getColor(), "no nagging while blocking");
    }

    // ---- Kurogane ---------------------------------------------------------------------------------------

    @Test
    void phantomStepCutsEveryoneItPassesAndLeavesThemBleeding() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 4);
        PlayerMock aside = player("Aside", 3, 5);
        PlayerMock beyond = player("Beyond", 0, 13);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        useKey(steve);
        assertEquals(10.5, steve.getLocation().getZ(), 1.0E-6, "10 blocks ahead, through Alex");
        assertTrue(cooldown(sword, Ability.PHANTOM_STEP) > 0);
        assertEquals(2, steve.getPotionEffect(PotionEffectType.SPEED).getAmplifier(), "Speed III after");
        tick(2);
        assertEquals(20.0, alex.getHealth(), "the cut opens a moment later");
        tick(2);
        assertEquals(12.5, alex.getHealth(), 1.0E-6, "7.5 damage");
        tick(61);
        assertEquals(12.5 - 3 * 1.25, alex.getHealth(), 1.0E-6, "then 1.25 a second for 3 seconds of bleeding");
        assertEquals(20.0, aside.getHealth(), "only who was in the way");
        assertEquals(20.0, beyond.getHealth());
        useKey(steve);
        assertEquals(10.5, steve.getLocation().getZ(), 1.0E-6, "recharging");
        tick(21 * 20);
        stand(steve, 0, 0, 0f);
        useKey(steve);
        assertEquals(10.5, steve.getLocation().getZ(), 1.0E-6, "ready again after 21s (16s + 5s)");
    }

    @Test
    void phantomStepStopsAtWalls() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        world.getBlockAt(0, 64, 1).setType(Material.STONE);
        useKey(steve);
        assertEquals(0.5, steve.getLocation().getZ(), 1.0E-6, "a wall right in front: nothing happens");
        assertEquals(0, cooldown(sword, Ability.PHANTOM_STEP), "and nothing is spent");
        world.getBlockAt(0, 64, 1).setType(Material.AIR);
        world.getBlockAt(0, 65, 4).setType(Material.STONE); // Head height is enough.
        useKey(steve);
        assertEquals(3.75, steve.getLocation().getZ(), 1.0E-6, "stops in front of the wall, never in it");
    }

    @Test
    void crimsonTempestSendsACrescentWithEverySwing() {
        setting("weapons.kurogane.abilities.crimson-hunger.chance", 0); // No lucky bleeding here.
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        PlayerMock bob = player("Bob", 0, 6);
        PlayerMock far = player("Far", 0, 10);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        melee(steve, alex, 5.0);
        assertEquals(20.0, bob.getHealth(), "no crescents before");
        sneakUseKey(steve);
        assertTrue(cooldown(sword, Ability.CRIMSON_TEMPEST) > 0);
        assertEquals(1, steve.getPotionEffect(PotionEffectType.SPEED).getAmplifier(), "Speed II while it lasts");
        // (The swings themselves are not applied here: only the crescents hurt.)
        melee(steve, alex, 5.0);
        assertEquals(15.0, bob.getHealth(), 1.0E-6, "a crescent: 5 to everyone in its path");
        assertEquals(15.0, alex.getHealth(), 1.0E-6);
        assertEquals(20.0, far.getHealth(), "7 blocks long");
        melee(steve, alex, 5.0);
        assertEquals(15.0, bob.getHealth(), 1.0E-6, "at most one every half second");
        tick(10);
        melee(steve, alex, 5.0);
        assertEquals(10.0, bob.getHealth(), 1.0E-6);
        tick(5 * 20);
        melee(steve, alex, 5.0);
        assertEquals(10.0, bob.getHealth(), 1.0E-6, "over after 5 seconds");
    }

    @Test
    void crimsonTempestStopsAtWallsAndACancelledHitDoesNothingLater() {
        setting("weapons.kurogane.abilities.crimson-hunger.chance", 0);
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        PlayerMock hidden = player("Hidden", 0, 6);
        for (int x = -2; x <= 2; x++) {
            for (int y = 64; y <= 66; y++) {
                world.getBlockAt(x, y, 4).setType(Material.STONE);
            }
        }
        give(steve, WeaponType.KUROGANE);
        sneakUseKey(steve);
        melee(steve, alex, 5.0);
        assertEquals(15.0, alex.getHealth(), 1.0E-6);
        assertEquals(20.0, hidden.getHealth(), "the wall stopped the crescent");
        // A hit a protection plugin cancels never lands...
        Listener refuse = new Listener() {
            @EventHandler(priority = EventPriority.HIGHEST)
            public void onDamage(EntityDamageByEntityEvent event) {
                event.setCancelled(true);
            }
        };
        server.getPluginManager().registerEvents(refuse, plugin);
        tick(10);
        assertTrue(melee(steve, alex, 5.0).isCancelled());
        HandlerList.unregisterAll(refuse);
        // ...and what it would have done does not happen on a later hit either.
        for (int x = -2; x <= 2; x++) {
            for (int y = 64; y <= 66; y++) {
                world.getBlockAt(x, y, 4).setType(Material.AIR);
            }
        }
        tick(5 * 20);
        melee(steve, alex, 5.0);
        assertEquals(15.0, alex.getHealth(), 1.0E-6, "the tempest is over: no crescent");
        assertEquals(20.0, hidden.getHealth());
    }

    @Test
    void crimsonHungerBleedsAndFeedsOnBleeding() {
        setting("weapons.kurogane.abilities.crimson-hunger.chance", 1.0);
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.KUROGANE);
        steve.setHealth(10.0);
        assertEquals(6.25, melee(steve, alex, 5.0).getDamage(), 1.0E-6, "melee-damage: 1.25x");
        assertEquals(10.0, steve.getHealth(), 1.0E-6, "not bleeding yet: nothing to feed on");
        tick(21);
        assertEquals(18.75, alex.getHealth(), 1.0E-6, "bleeding");
        melee(steve, alex, 5.0);
        assertEquals(11.0, steve.getHealth(), 1.0E-6, "a bleeding target heals half a heart");

        setting("weapons.kurogane.abilities.crimson-hunger.chance", 0);
        PlayerMock bob = player("Bob", 2, 0);
        melee(steve, bob, 5.0);
        tick(45);
        assertEquals(20.0, bob.getHealth(), "chance 0: never");
    }

    // ---- Sugarcrash -------------------------------------------------------------------------------------

    @Test
    void candyReaperCutsOnTheWayOutAndDragsThemBackOnTheReturn() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 8);
        PlayerMock far = player("Far", 0, 22);
        ItemStack scythe = give(steve, WeaponType.SUGARCRASH);
        useKey(steve);
        assertTrue(cooldown(scythe, Ability.CANDY_REAPER) > 0);
        assertEquals(1, plugin.visuals().count(), "the thrown scythe");
        tick(3);
        assertEquals(20.0, alex.getHealth(), "it takes a moment to fly");
        tick(4);
        assertEquals(13.75, alex.getHealth(), 1.0E-6, "cut on the way out: 6.25");
        assertEquals(0.0, alex.getVelocity().length(), 1.0E-9, "not pulled going out");
        tick(15);
        assertEquals(7.5, alex.getHealth(), 1.0E-6, "and again on the way back");
        assertTrue(alex.getVelocity().getZ() < -0.5, "dragged towards Steve");
        assertEquals(20.0, far.getHealth(), "18 blocks out at most");
        tick(20);
        assertEquals(0, plugin.visuals().count(), "caught again");
    }

    @Test
    void candyReaperTurnsBackAtWalls() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock hidden = player("Hidden", 0, 9);
        for (int x = -2; x <= 2; x++) {
            for (int y = 64; y <= 68; y++) {
                world.getBlockAt(x, y, 6).setType(Material.STONE);
            }
        }
        give(steve, WeaponType.SUGARCRASH);
        useKey(steve);
        tick(40);
        assertEquals(20.0, hidden.getHealth(), "the wall stopped it");
        assertEquals(0, plugin.visuals().count(), "and it came back");
    }

    @Test
    void sugarRushRamsEveryoneOnceDeflectsArrowsThenBursts() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 1);
        PlayerMock bob = player("Bob", 3, 0);
        PlayerMock far = player("Far", 9, 0);
        ItemStack scythe = give(steve, WeaponType.SUGARCRASH);
        sneakUseKey(steve);
        assertTrue(cooldown(scythe, Ability.SUGAR_RUSH) > 0);
        tick(1);
        assertEquals(0.9, steve.getVelocity().getZ(), 1.0E-6, "carried where Steve looks");
        assertEquals(13.75, alex.getHealth(), 1.0E-6, "rammed: 6.25");
        assertTrue(alex.getVelocity().getY() > 0.3, "and thrown aside");
        tick(5);
        assertEquals(13.75, alex.getHealth(), 1.0E-6, "once");
        assertEquals(20.0, bob.getHealth(), "not in the way");
        org.bukkit.entity.Arrow arrow = world.spawn(new Location(world, 5.5, 65, 0.5), org.bukkit.entity.Arrow.class);
        arrow.setShooter(far);
        arrow.setVelocity(new Vector(-2, 0, 0));
        EntityDamageByEntityEvent shot = new EntityDamageByEntityEvent(arrow, steve,
                EntityDamageEvent.DamageCause.PROJECTILE, DamageSource.builder(DamageType.ARROW)
                .withCausingEntity(far).withDirectEntity(arrow).build(), 6.0);
        server.getPluginManager().callEvent(shot);
        assertTrue(shot.isCancelled(), "arrows bounce off");
        assertTrue(arrow.getVelocity().getX() > 0, "back the way it came");
        tick(60);
        assertEquals(15.0, bob.getHealth(), 1.0E-6, "the candy burst at the end: 5");
        assertEquals(8.75, alex.getHealth(), 1.0E-6);
        assertTrue(bob.getVelocity().getX() > 0.5, "thrown out");
        assertEquals(20.0, far.getHealth());
        EntityDamageEvent fall = new EntityDamageEvent(steve, EntityDamageEvent.DamageCause.FALL,
                DamageSource.builder(DamageType.FALL).build(), 6.0);
        server.getPluginManager().callEvent(fall);
        assertTrue(fall.isCancelled(), "no fall damage after");
    }

    @Test
    void sugarRushBurstsEarlyWhenPressedAgain() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock bob = player("Bob", 3, 0);
        give(steve, WeaponType.SUGARCRASH);
        sneakUseKey(steve);
        tick(5);
        assertEquals(20.0, bob.getHealth());
        sneakUseKey(steve);
        assertEquals(15.0, bob.getHealth(), 1.0E-6, "burst now");
        tick(80);
        assertEquals(15.0, bob.getHealth(), 1.0E-6, "and only once");
        sneakUseKey(steve);
        tick(5);
        assertEquals(15.0, bob.getHealth(), 1.0E-6, "then it recharges");
    }

    @Test
    void sugarHighSlowsWhatTheScytheHits() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.SUGARCRASH);
        melee(steve, alex, 5.0);
        org.bukkit.potion.PotionEffect slow = alex.getPotionEffect(PotionEffectType.SLOWNESS);
        assertNotNull(slow);
        assertEquals(0, slow.getAmplifier(), "Slowness I");
        assertEquals(30, slow.getDuration(), "for 1.5 seconds");
    }

    // ---- Wyrmfang ---------------------------------------------------------------------------------------

    @Test
    void wyrmLungeSeizesTheFirstEnemyItReaches() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 1);
        PlayerMock bob = player("Bob", 1, 1);
        ItemStack sword = give(steve, WeaponType.WYRMFANG);
        useKey(steve);
        assertTrue(cooldown(sword, Ability.WYRM_LUNGE) > 0);
        assertEquals(0.45, steve.getVelocity().getY(), 1.0E-6, "a low leap...");
        assertEquals(1.5, steve.getVelocity().getZ(), 1.0E-6, "...forward");
        tick(1);
        assertEquals(10.0, alex.getHealth(), 1.0E-6, "seized: 10 damage");
        assertEquals(0.8, alex.getVelocity().getY(), 1.0E-6, "thrown up");
        assertEquals(2, alex.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier(), "Slowness III");
        assertEquals(20.0, bob.getHealth(), "only the first one");
        EntityDamageEvent fall = new EntityDamageEvent(steve, EntityDamageEvent.DamageCause.FALL,
                DamageSource.builder(DamageType.FALL).build(), 6.0);
        server.getPluginManager().callEvent(fall);
        assertTrue(fall.isCancelled(), "no fall damage from the leap");
    }

    @Test
    void wyrmLungeIsOverWhenItLands() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.WYRMFANG);
        useKey(steve);
        tick(6);
        steve.setVelocity(new Vector()); // Landed.
        tick(1);
        PlayerMock alex = player("Alex", 0, 1);
        tick(5);
        assertEquals(20.0, alex.getHealth(), "nobody is seized after landing");
    }

    @Test
    void dragonsBreathBurnsAndPoisonsEveryoneInTheCone() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 4);
        PlayerMock aside = player("Aside", 4, 2);
        PlayerMock behind = player("Behind", 0, -3);
        PlayerMock far = player("Far", 0, 11);
        PlayerMock hidden = player("Hidden", 2, 5);
        for (int x = 1; x <= 2; x++) {
            for (int y = 64; y <= 65; y++) {
                world.getBlockAt(x, y, 3).setType(Material.STONE);
            }
        }
        ItemStack sword = give(steve, WeaponType.WYRMFANG);
        sneakUseKey(steve);
        assertTrue(cooldown(sword, Ability.DRAGONS_BREATH) > 0);
        tick(2);
        assertEquals(18.75, alex.getHealth(), 1.0E-6, "1.25 a gust");
        assertEquals(1, alex.getPotionEffect(PotionEffectType.POISON).getAmplifier(), "Poison II");
        tick(45);
        assertEquals(20.0 - 8 * 1.25, alex.getHealth(), 1.0E-6, "a gust every 0.25s for 2 seconds");
        assertEquals(20.0, aside.getHealth(), "outside the cone");
        assertEquals(20.0, behind.getHealth());
        assertEquals(20.0, far.getHealth(), "8 blocks long");
        assertEquals(20.0, hidden.getHealth(), "not through walls");
    }

    @Test
    void venomFangPoisonsWhatTheSwordHits() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.WYRMFANG);
        assertEquals(6.25, melee(steve, alex, 5.0).getDamage(), 1.0E-6);
        org.bukkit.potion.PotionEffect poison = alex.getPotionEffect(PotionEffectType.POISON);
        assertNotNull(poison);
        assertEquals(1, poison.getAmplifier(), "Poison II");
        assertEquals(40, poison.getDuration(), "for 2 seconds");
    }

    @Test
    void aRiftbladeBecomesAWyrmfang() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.WYRMFANG);
        UUID id = id(sword);
        // As 1.6 left it: a Riftblade, in the inventory and in data.yml.
        ItemMeta meta = sword.getItemMeta();
        meta.setDisplayName("Riftblade");
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "weapon"),
                org.bukkit.persistence.PersistentDataType.STRING, "riftblade");
        meta.getPersistentDataContainer().set(new org.bukkit.NamespacedKey(plugin, "look"),
                org.bukkit.persistence.PersistentDataType.STRING, "1.6");
        sword.setItemMeta(meta);
        steve.getInventory().setItemInMainHand(sword);
        assertEquals(WeaponType.WYRMFANG, plugin.items().read(sword).type(), "read as the Wyrmfang");
        tick(40);
        ItemStack held = steve.getInventory().getItemInMainHand();
        assertEquals("Wyrmfang", ChatColor.stripColor(held.getItemMeta().getDisplayName()), "the new name and lore");
        assertEquals("wyrmfang", held.getItemMeta().getPersistentDataContainer().get(
                new org.bukkit.NamespacedKey(plugin, "weapon"), org.bukkit.persistence.PersistentDataType.STRING));
        assertEquals(id, id(held), "the same weapon");

        plugin.registry().close(0);
        java.nio.file.Path data = plugin.getDataFolder().toPath().resolve("data.yml");
        try {
            String yaml = java.nio.file.Files.readString(data);
            assertTrue(yaml.contains("type: wyrmfang"), yaml);
            java.nio.file.Files.writeString(data, yaml.replace("type: wyrmfang", "type: riftblade"));
        } catch (java.io.IOException e) {
            throw new AssertionError(e);
        }
        WeaponRegistry reloaded = new WeaponRegistry(plugin.getLogger(), plugin.getDataFolder().toPath(), clock::get);
        assertEquals(WeaponType.WYRMFANG, reloaded.get(id).type(), "data.yml's Riftblade too");
        reloaded.close(0);
        assertEquals(WeaponType.WYRMFANG, WeaponType.byKey("riftblade"), "/legendary give ... riftblade still works");
    }

    // ---- Gravebreaker -----------------------------------------------------------------------------------

    @Test
    void earthsplitterTearsTheGroundForwardAndThrowsEveryoneOnIt() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 6);
        PlayerMock aside = player("Aside", 3, 6);
        PlayerMock far = player("Far", 0, 19);
        ItemStack axe = give(steve, WeaponType.GRAVEBREAKER);
        useKey(steve);
        assertTrue(cooldown(axe, Ability.EARTHSPLITTER) > 0);
        tick(1);
        assertEquals(20.0, alex.getHealth(), "it runs forward point by point");
        tick(10);
        assertEquals(10.0, alex.getHealth(), 1.0E-6, "10 damage, once");
        assertEquals(1.0, alex.getVelocity().getY(), 1.0E-6, "thrown up");
        assertEquals(1, alex.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier(), "Slowness II");
        assertEquals(20.0, aside.getHealth(), "only on the line");
        assertEquals(20.0, far.getHealth(), "14 blocks long");
        for (int z = 0; z <= 15; z++) {
            assertEquals(Material.STONE, world.getBlockAt(0, 63, z).getType(), "no block is changed");
            assertEquals(Material.AIR, world.getBlockAt(0, 64, z).getType());
        }
    }

    @Test
    void earthsplitterStopsAtWalls() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock behind = player("Behind", 0, 7);
        world.getBlockAt(0, 64, 5).setType(Material.STONE);
        world.getBlockAt(0, 65, 5).setType(Material.STONE);
        give(steve, WeaponType.GRAVEBREAKER);
        useKey(steve);
        tick(20);
        assertEquals(20.0, behind.getHealth(), "the crack ended at the wall");
    }

    @Test
    void ironBastionStoresTheDamageTakenAndReleasesIt() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 3);
        PlayerMock far = player("Far", 0, 9);
        ItemStack axe = give(steve, WeaponType.GRAVEBREAKER);
        sneakUseKey(steve);
        assertTrue(cooldown(axe, Ability.IRON_BASTION) > 0);
        assertEquals(2, steve.getPotionEffect(PotionEffectType.RESISTANCE).getAmplifier(), "Resistance III");
        assertBraced(true, steve);
        melee(alex, steve, 4.0);
        tick(20);
        assertEquals(20.0, alex.getHealth(), "braced: nothing yet");
        tick(65);
        assertEquals(20.0 - 6.25 - 4.0, alex.getHealth(), 1.0E-6, "the release: 6.25 + the 4 taken");
        assertTrue(alex.getVelocity().getZ() > 0.5, "thrown away");
        assertEquals(0.5, alex.getVelocity().getY(), 1.0E-6);
        assertEquals(20.0, far.getHealth(), "6 blocks round");
        assertBraced(false, steve);
    }

    @Test
    void ironBastionReleasesEarlyAndTheStoredDamageIsCapped() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 3);
        give(steve, WeaponType.GRAVEBREAKER);
        sneakUseKey(steve);
        melee(alex, steve, 4.0);
        melee(alex, steve, 5.0);
        tick(10);
        sneakUseKey(steve);
        assertEquals(20.0 - 6.25 - 6.25, alex.getHealth(), 1.0E-6, "released now, with at most 6.25 stored");
        assertBraced(false, steve);
        tick(80);
        assertEquals(7.5, alex.getHealth(), 1.0E-6, "only once");
    }

    @Test
    void headsmanHitsWeakPlayersHarderAndKillsHeal() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.GRAVEBREAKER);
        assertEquals(10.0, melee(steve, alex, 8.0).getDamage(), 1.0E-6, "full health: 1.25x like every legendary hit");
        alex.setHealth(6.0);
        assertEquals(13.0, melee(steve, alex, 8.0).getDamage(), 1.0E-6, "under 40%: +30% more");
        alex.setKiller(steve);
        server.getPluginManager().callEvent(new PlayerDeathEvent(alex, DamageSource.builder(DamageType.PLAYER_ATTACK)
                .withCausingEntity(steve).withDirectEntity(steve).build(), new ArrayList<>(), 0, "Alex was slain"));
        assertEquals(1, steve.getPotionEffect(PotionEffectType.REGENERATION).getAmplifier(), "a kill: Regeneration II");
    }

    // ---- Starforged -------------------------------------------------------------------------------------

    @Test
    void starLancePiercesTheFirstEnemyAndPinsThem() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 10);
        PlayerMock bob = player("Bob", 0, 14);
        ItemStack axe = give(steve, WeaponType.STARFORGED);
        alex.setVelocity(new Vector(0.4, 0.3, 0.4));
        useKey(steve);
        assertTrue(cooldown(axe, Ability.STAR_LANCE) > 0);
        tick(2);
        assertEquals(20.0, alex.getHealth(), "it flies");
        tick(3);
        assertEquals(8.75, alex.getHealth(), 1.0E-6, "11.25 damage");
        assertEquals(3, alex.getPotionEffect(PotionEffectType.SLOWNESS).getAmplifier(), "Slowness IV");
        assertTrue(alex.hasPotionEffect(PotionEffectType.GLOWING), "lit up for everyone");
        assertEquals(0.0, alex.getVelocity().length(), 1.0E-9, "pinned");
        tick(10);
        assertEquals(20.0, bob.getHealth(), "only the first enemy");
    }

    @Test
    void starLanceBurstsOnWalls() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock hidden = player("Hidden", 0, 8);
        world.getBlockAt(0, 65, 5).setType(Material.STONE);
        give(steve, WeaponType.STARFORGED);
        useKey(steve);
        tick(10);
        assertEquals(20.0, hidden.getHealth(), "the wall stopped it");
    }

    @Test
    void starLanceGoesNoFurtherThanItsRange() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock far = player("Far", 0, 40);
        give(steve, WeaponType.STARFORGED);
        useKey(steve);
        tick(30);
        assertEquals(20.0, far.getHealth(), "30 blocks at most");
        assertEquals(0, plugin.visuals().count(), "and the star is gone");
    }

    @Test
    void celestialPrisonTrapsEveryoneNearThenCollapses() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 3);
        PlayerMock out = player("Out", 0, 12);
        ItemStack axe = give(steve, WeaponType.STARFORGED);
        sneakUseKey(steve);
        assertTrue(cooldown(axe, Ability.CELESTIAL_PRISON) > 0);
        tick(2);
        assertEquals(0.0, alex.getVelocity().length(), 1.0E-9, "inside: left alone");
        alex.teleport(new Location(world, 0.5, 64, 6.3), PlayerTeleportEvent.TeleportCause.COMMAND); // Walked to the edge.
        tick(1);
        assertTrue(alex.getVelocity().getZ() < -0.4, "pushed back in");
        PlayerTeleportEvent pearl = new PlayerTeleportEvent(alex, alex.getLocation(),
                new Location(world, 0.5, 64, 16.5), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        server.getPluginManager().callEvent(pearl);
        assertTrue(pearl.isCancelled(), "no pearling out");
        PlayerTeleportEvent inside = new PlayerTeleportEvent(alex, alex.getLocation(),
                new Location(world, 0.5, 64, 2.5), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        server.getPluginManager().callEvent(inside);
        assertFalse(inside.isCancelled(), "pearling inside is fine");
        PlayerTeleportEvent free = new PlayerTeleportEvent(out, out.getLocation(),
                new Location(world, 0.5, 64, 20.5), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        server.getPluginManager().callEvent(free);
        assertFalse(free.isCancelled(), "nobody else is held");
        alex.setVelocity(new Vector());
        assertEquals(20.0, alex.getHealth(), "no damage while it holds");
        tick(64);
        assertEquals(7.5, alex.getHealth(), 1.0E-6, "the stars collapse: 12.5");
        assertEquals(0.7, alex.getVelocity().getY(), 1.0E-6, "thrown up");
        assertEquals(20.0, out.getHealth());
        tick(5);
        PlayerTeleportEvent after = new PlayerTeleportEvent(alex, alex.getLocation(),
                new Location(world, 0.5, 64, 16.5), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        server.getPluginManager().callEvent(after);
        assertFalse(after.isCancelled(), "free again");
    }

    @Test
    void starlightHitsHarderInTheAir() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.STARFORGED);
        alex.setOnGround(true);
        assertEquals(10.0, melee(steve, alex, 8.0).getDamage(), 1.0E-6, "on the ground: 1.25x like every legendary hit");
        alex.setOnGround(false);
        assertEquals(13.0, melee(steve, alex, 8.0).getDamage(), 1.0E-6, "in the air: +30% more");
    }

    // ---- fairness ---------------------------------------------------------------------------------------

    @Test
    void protectedPlayersAreNeverHurtPulledOrTrapped() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock safe = player("Safe", 0, 1);
        protect(safe);
        give(steve, WeaponType.KUROGANE);
        useKey(steve); // Phantom Step through them.
        tick(80);
        assertEquals(20.0, safe.getHealth(), "not cut, no bleeding");

        give(steve, WeaponType.SUGARCRASH);
        stand(steve, 0, 0, 0f);
        useKey(steve); // Candy Reaper.
        tick(40);
        sneakUseKey(steve); // Sugar Rush.
        tick(70);
        assertEquals(20.0, safe.getHealth());
        assertEquals(0.0, safe.getVelocity().length(), 1.0E-9, "not dragged, rammed or blown away");

        give(steve, WeaponType.WYRMFANG);
        PlayerMock alex = player("Alex", 1, 1);
        stand(steve, 0, 0, 0f);
        useKey(steve); // Wyrm Lunge.
        tick(1);
        assertEquals(20.0, safe.getHealth());
        assertEquals(10.0, alex.getHealth(), 1.0E-6, "the lunge went past Safe to Alex");
        sneakUseKey(steve); // Dragon's Breath.
        tick(45);
        assertEquals(20.0, safe.getHealth());
        assertFalse(safe.hasPotionEffect(PotionEffectType.POISON));
        assertFalse(safe.hasPotionEffect(PotionEffectType.SLOWNESS));
        alex.teleport(new Location(world, 9.5, 64, 9.5));

        give(steve, WeaponType.GRAVEBREAKER);
        stand(steve, 0, 0, 0f);
        useKey(steve); // Earthsplitter.
        tick(20);
        sneakUseKey(steve); // Iron Bastion.
        tick(85);
        assertEquals(20.0, safe.getHealth());
        assertEquals(0.0, safe.getVelocity().length(), 1.0E-9, "not launched or thrown");

        give(steve, WeaponType.STARFORGED);
        stand(steve, 0, 0, 0f);
        useKey(steve); // Star Lance.
        tick(20);
        assertFalse(safe.hasPotionEffect(PotionEffectType.GLOWING));
        sneakUseKey(steve); // Celestial Prison.
        safe.teleport(new Location(world, 0.5, 64, 6.3), PlayerTeleportEvent.TeleportCause.COMMAND);
        tick(2);
        assertEquals(0.0, safe.getVelocity().length(), 1.0E-9, "not held in");
        PlayerTeleportEvent pearl = new PlayerTeleportEvent(safe, safe.getLocation(),
                new Location(world, 0.5, 64, 16.5), PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        server.getPluginManager().callEvent(pearl);
        assertFalse(pearl.isCancelled(), "free to pearl away");
        tick(70);
        assertEquals(20.0, safe.getHealth());
    }

    @Test
    void protectionChecksAreNotCountedAsHits() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 3);
        give(steve, WeaponType.GRAVEBREAKER);
        give(alex, WeaponType.STARFORGED);
        sneakUseKey(steve); // Iron Bastion stores the damage Steve takes...
        sneakUseKey(alex); // ...and Celestial Prison asks protection plugins before it traps Steve: no damage.
        tick(10);
        sneakUseKey(steve); // Released.
        assertEquals(20.0 - 6.25, alex.getHealth(), 1.0E-6, "nothing was stored");
    }

    @Test
    void abilityDamageThatKillsBreaksNothing() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 3);
        PlayerMock bob = player("Bob", 0, 5);
        alex.setHealth(8.5);
        bob.setHealth(8.5);
        give(steve, WeaponType.KUROGANE);
        useKey(steve); // The cut leaves both on half a heart; the bleeding finishes both on the same tick.
        tick(12);
        assertEquals(1.0, alex.getHealth(), 1.0E-6);
        assertEquals(1.0, bob.getHealth(), 1.0E-6);
        tick(25);
        assertTrue(alex.isDead() && bob.isDead(), "both bled out: " + alex.getHealth() + ", " + bob.getHealth());
        tick(4);
        assertEquals(java.util.Set.of(Ability.PHANTOM_STEP), plugin.hud().bars(steve).keySet(),
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
        tick(33 * 20);
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

        sneakUseKey(steve);
        tick(10);
        Map<Ability, BossBar> bars = plugin.hud().bars(steve);
        BossBar prison = bars.get(Ability.CELESTIAL_PRISON);
        assertNotNull(prison, "used: its bar appears");
        assertTrue(title(prison).matches("Celestial Prison » \\d\\.\\ds"), "while it holds: " + title(prison));
        assertTrue(prison.getProgress() > 0.5 && prison.getProgress() < 1.0, "running down");
        assertEquals(BarColor.BLUE, prison.getColor(), "the weapon's colour");
        assertTrue(prison.getPlayers().contains(steve));
        assertEquals(java.util.Set.of(Ability.CELESTIAL_PRISON), bars.keySet(), "Star Lance is still ready");
        tick(60);
        assertEquals("Celestial Prison » 37s", title(prison), "then how long until it is ready (35s + 5s)");
        assertTrue(prison.getProgress() < 0.5, "filling up again");

        // Too early: no message, the bar flashes.
        sneakUseKey(steve);
        tick(2);
        assertEquals(BarColor.WHITE, prison.getColor());
        tick(8);
        assertEquals(BarColor.BLUE, prison.getColor());
        assertFalse(has(chat(steve), "ready in"), "nothing in chat");
        assertEquals(List.of(), actionBars(steve), "the action bar is left to the Combat plugin");
        assertFalse(steve.hasMetadata("vanillasmp:actionbar"));

        // Put away: the recharging one stays while the axe is in the inventory...
        steve.getInventory().setHeldItemSlot(8);
        tick(4);
        assertEquals(java.util.Set.of(Ability.CELESTIAL_PRISON), plugin.hud().bars(steve).keySet(), "still counting down");
        assertTrue(prison.getPlayers().contains(steve));
        steve.getInventory().setHeldItemSlot(0);
        tick(4);
        assertEquals(java.util.Set.of(Ability.CELESTIAL_PRISON), plugin.hud().bars(steve).keySet());
        // ...and goes when the axe does.
        ItemStack axe = steve.getInventory().getItemInMainHand();
        steve.getInventory().setItemInMainHand(null);
        tick(12);
        assertTrue(plugin.hud().bars(steve).isEmpty(), "not carried: no bar");
        assertFalse(prison.getPlayers().contains(steve));
        steve.getInventory().setItemInMainHand(axe);
        tick(4);
        // Ready again: no bar.
        tick(35 * 20);
        assertTrue(plugin.hud().bars(steve).isEmpty(), "ready: the bar is gone");
    }

    @Test
    void readyBarsCanBeTurnedOn() {
        setting("display.boss-bars-when-ready", true);
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.STARFORGED);
        tick(4);
        Map<Ability, BossBar> bars = plugin.hud().bars(steve);
        assertEquals(java.util.Set.of(Ability.STAR_LANCE, Ability.CELESTIAL_PRISON), bars.keySet());
        assertEquals("Star Lance", title(bars.get(Ability.STAR_LANCE)), "ready: just its name");
        assertEquals(1.0, bars.get(Ability.STAR_LANCE).getProgress(), 1.0E-9);
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
    void sugarcrashGivesSpeedWhileHeld() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.SUGARCRASH);
        tick(6);
        org.bukkit.potion.PotionEffect speed = steve.getPotionEffect(PotionEffectType.SPEED);
        assertNotNull(speed, "Speed I while held");
        assertEquals(0, speed.getAmplifier());
        assertTrue(speed.isAmbient() && !speed.hasParticles(), "quiet, like a beacon's");
        steve.getInventory().setHeldItemSlot(8);
        tick(6);
        assertFalse(steve.hasPotionEffect(PotionEffectType.SPEED), "gone when put away");
        plugin.getConfig().set("weapons.sugarcrash.abilities.sugar-high.speed-level", 0);
        plugin.saveConfig();
        assertEquals(List.of(), plugin.reload());
        steve.getInventory().setHeldItemSlot(0);
        tick(6);
        assertFalse(steve.hasPotionEffect(PotionEffectType.SPEED), "speed-level 0: none");
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
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        chat(admin);
        server.dispatchCommand(admin, "legendary list");
        List<String> list = chat(admin);
        assertTrue(has(list, "Kurogane #" + WeaponItems.shortId(id(sword)) + " held by Steve"), String.join("\n", list));
        assertTrue(has(list, "Wyrmfang not given out"));
        server.dispatchCommand(admin, "legendary inspect Steve");
        List<String> inspect = chat(admin);
        assertTrue(has(inspect, "Phantom Step ready"), String.join("\n", inspect));

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
        plugin.getConfig().set("weapons.kurogane.abilities.phantom-step.cooldown", "soon");
        plugin.getConfig().set("weapons.starforged.abilities.celestial-prison.duration", "0.1s");
        plugin.getConfig().set("weapons.sugarcrash.boss-bar.color", "rainbow");
        plugin.getConfig().set("hit-mobs", "everything");
        plugin.getConfig().set("melee-damage", 50);
        plugin.saveConfig();
        List<String> warnings = plugin.reload();
        assertEquals(5, warnings.size(), String.join("\n", warnings));
        assertEquals(21.0, plugin.settings().ability(Ability.PHANTOM_STEP).num("cooldown"));
        assertEquals(3.0, plugin.settings().ability(Ability.CELESTIAL_PRISON).num("duration"), "never under 0.5s");
        assertEquals(1.25, plugin.settings().meleeDamage(), "at most 10x");
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
        // 1.1's Sugar Rush: the name is back in 2.0, but for another ability.
        config.set("weapons.sugarcrash.abilities.sugar-rush", null);
        config.set("weapons.sugarcrash.abilities.sugar-rush.cooldown", "18s");
        config.set("weapons.sugarcrash.abilities.sugar-rush.duration", "6s");
        config.set("weapons.sugarcrash.abilities.sugar-rush.speed-level", 2);
        config.set("weapons.sugarcrash.abilities.sugar-rush.haste-level", 2);
        config.set("weapons.starforged.enchantments", null);
        config.set("weapons.starforged.enchantments.sharpness", 9); // This server's own choice.
        plugin.saveConfig();

        assertEquals(List.of(), plugin.reload(), "nothing left to warn about");
        org.bukkit.configuration.file.YamlConfiguration saved = savedConfig();
        assertEquals(6, saved.getInt("config-version"));
        assertFalse(saved.isSet("display.action-bar"));
        assertFalse(saved.isSet("display.ready-sound"));
        assertFalse(saved.isSet("messages.hud-edge"));
        assertFalse(saved.isSet("weapons.kurogane.abilities.crescent-draw"));
        assertEquals("3s", saved.getString("weapons.sugarcrash.abilities.sugar-rush.duration"), "2.0's Sugar Rush");
        assertFalse(saved.isSet("weapons.sugarcrash.abilities.sugar-rush.haste-level"));
        assertEquals(List.of("sharpness", "fire_aspect", "looting", "sweeping_edge"),
                List.copyOf(saved.getConfigurationSection("weapons.kurogane.enchantments").getKeys(false)));
        assertEquals(7, saved.getInt("weapons.kurogane.enchantments.sharpness"));
        assertEquals(Map.of("sharpness", 9), plugin.settings().look(WeaponType.STARFORGED).enchantments(), "kept");
        assertEquals("legendary:kurogane", saved.getString("weapons.kurogane.item-model"));
        assertEquals(33.0, plugin.settings().ability(Ability.SUGAR_RUSH).num("cooldown"));
        assertEquals(3.0, plugin.settings().ability(Ability.SUGAR_RUSH).num("duration"));
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        assertEquals(7, sword.getItemMeta().getEnchantLevel(io.github.drepfy.legendary.util.Compat.enchantment("sharpness")));
        // Once up to date it is left alone.
        assertEquals(List.of(), plugin.reload());
        assertEquals(Map.of("sharpness", 9), plugin.settings().look(WeaponType.STARFORGED).enchantments());
    }

    @Test
    void aConfigFrom16GetsTheNewAbilitiesTheWyrmfangAndFewerSounds() {
        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
        config.set("config-version", 5);
        config.set("melee-damage", null);
        // 1.6's Kurogane, with this server's own name and cooldown.
        config.set("weapons.kurogane.name", "&cBlood Moon");
        config.set("weapons.kurogane.lore", List.of("&8Legendary Katana", "",
                "&#FF4D6APassive &8» &f{crimson-edge.name}", "&7Every 3rd hit in a row cuts deep and bleeds.", "",
                "&#FF4D6A{key} &8» &f{crimson-flash.name} &8({crimson-flash.cooldown})", "{attack}"));
        config.set("weapons.kurogane.abilities", null);
        config.set("weapons.kurogane.abilities.crimson-flash.cooldown", "10s");
        config.set("weapons.kurogane.abilities.iaido.cooldown", "22s");
        config.set("weapons.kurogane.abilities.crimson-edge.hits", 3);
        // The Riftblade, Starfall and its sounds and messages.
        config.set("weapons.riftblade.name", "<gradient:#E9C6FF:#A855F7>&lRiftblade</gradient>");
        config.set("weapons.riftblade.custom-model-data", 1003);
        config.set("weapons.riftblade.abilities.void-rend.cooldown", "20s");
        config.set("weapons.wyrmfang", null);
        config.set("weapons.starforged.abilities", null);
        config.set("weapons.starforged.abilities.starfall.cooldown", "24s");
        config.set("weapons.starforged.lore", List.of("&bMy own lore", "{attack}")); // Their own, without placeholders.
        config.set("sounds", null);
        config.set("sounds.crimson-flash", List.of("entity.player.attack.sweep 1 0.8"));
        config.set("sounds.riftblade-equip", List.of());
        config.set("sounds.received", List.of("block.bell.use 1 1")); // Their own.
        config.set("messages.swap-refused", "&cThe rift can't take you there.");
        config.set("messages.no-target", "&7Nothing to aim at within {range} blocks.");
        plugin.saveConfig();

        assertEquals(List.of(), plugin.reload(), "nothing left to warn about");
        org.bukkit.configuration.file.YamlConfiguration saved = savedConfig();
        org.bukkit.configuration.file.YamlConfiguration fresh = bundledConfig();
        assertEquals(6, saved.getInt("config-version"));
        assertEquals(1.25, saved.getDouble("melee-damage"), "the new setting is written in");
        assertFalse(saved.isSet("weapons.riftblade"), "the Riftblade is gone");
        assertEquals(fresh.getConfigurationSection("weapons.wyrmfang").getValues(true).toString(),
                saved.getConfigurationSection("weapons.wyrmfang").getValues(true).toString(), "the Wyrmfang took its place");
        assertEquals(List.of("phantom-step", "crimson-tempest", "crimson-hunger"),
                List.copyOf(saved.getConfigurationSection("weapons.kurogane.abilities").getKeys(false)), "the 2.0 abilities");
        assertEquals("21s", saved.getString("weapons.kurogane.abilities.phantom-step.cooldown"));
        assertEquals(List.of("star-lance", "celestial-prison", "starlight"),
                List.copyOf(saved.getConfigurationSection("weapons.starforged.abilities").getKeys(false)));
        assertEquals("&cBlood Moon", saved.getString("weapons.kurogane.name"), "own name kept");
        assertEquals(fresh.getStringList("weapons.kurogane.lore"), saved.getStringList("weapons.kurogane.lore"),
                "the lore named the old abilities: the new one");
        assertEquals(List.of("&bMy own lore", "{attack}"), saved.getStringList("weapons.starforged.lore"), "own lore kept");
        assertFalse(saved.isSet("sounds.crimson-flash"));
        assertFalse(saved.isSet("sounds.riftblade-equip"));
        assertEquals(List.of("block.bell.use 1 1"), saved.getStringList("sounds.received"), "own sound kept");
        for (String key : fresh.getConfigurationSection("sounds").getKeys(false)) {
            if (!key.equals("received")) {
                assertEquals(fresh.getStringList("sounds." + key), saved.getStringList("sounds." + key), "sounds." + key);
            }
        }
        assertFalse(saved.isSet("messages.swap-refused"));
        assertFalse(saved.isSet("messages.no-target"));
        String text = savedText();
        assertTrue(text.contains("# Sword and axe hits with a legendary do this many times"), "explained like a fresh one");
        assertTrue(text.contains("# How far you vanish"), "the new abilities too");
        assertFalse(text.contains("Crimson Flash's second dash"), "no old explanations");
        assertEquals(1.25, plugin.settings().meleeDamage());
        assertEquals(21.0, plugin.settings().ability(Ability.PHANTOM_STEP).num("cooldown"));
        // The weapons out there follow.
        PlayerMock steve = player("Steve", 0, 0);
        List<String> lore = give(steve, WeaponType.KUROGANE).getItemMeta().getLore().stream().map(ChatColor::stripColor).toList();
        assertTrue(lore.contains("F » Phantom Step (21s)"), String.join("\n", lore));
        assertTrue(lore.contains(" 15 Attack Damage"), String.join("\n", lore));
        // Once up to date it is left alone.
        assertEquals(List.of(), plugin.reload());
        assertEquals("&cBlood Moon", plugin.getConfig().getString("weapons.kurogane.name"));
    }

    @Test
    void aConfigFrom13GetsTheShimmerAndTheEnchantmentList() {
        org.bukkit.configuration.file.YamlConfiguration old = previousTexts();
        for (WeaponType type : WeaponType.values()) {
            plugin.getConfig().set("weapons." + type.key() + ".glint", false);
            if (old.isSet("v1_3_0.weapons." + type.key() + ".lore")) {
                plugin.getConfig().set("weapons." + type.key() + ".lore", old.getStringList("v1_3_0.weapons." + type.key() + ".lore"));
            }
        }
        plugin.getConfig().set("weapons.starforged.glint", null);
        plugin.getConfig().set("weapons.starforged.lore", List.of("&bMine", "{enchantments}")); // Their own.
        plugin.saveConfig();
        assertEquals(List.of(), plugin.reload());
        assertTrue(plugin.settings().look(WeaponType.KUROGANE).glint(), "1.3.0's default (no shimmer) is updated");
        assertEquals(bundledConfig().getStringList("weapons.kurogane.lore"), plugin.settings().look(WeaponType.KUROGANE).lore());
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
        org.bukkit.configuration.file.YamlConfiguration old = previousTexts();
        plugin.getConfig().set("weapons.kurogane.lore", old.getStringList("v1_0_1.weapons.kurogane.lore"));
        plugin.getConfig().set("messages.storage-blocked", old.getString("v1_0_1.messages.storage-blocked"));
        // ...one written by 1.0.2, which said "Right-click" before the keys moved to F...
        plugin.getConfig().set("weapons.sugarcrash.lore", old.getStringList("v1_0_2.weapons.sugarcrash.lore"));
        // ...and one written by 1.2.0, with LEGENDARY at the bottom.
        plugin.getConfig().set("weapons.gravebreaker.lore", old.getStringList("v1_2_0.weapons.gravebreaker.lore"));
        // But this server wrote its own Starforged lore and alt message.
        plugin.getConfig().set("weapons.starforged.lore", List.of("&bMy own lore"));
        plugin.getConfig().set("messages.alt-blocked", "&cNo alts!");
        plugin.saveConfig();
        assertTrue(old.getStringList("v1_0_1.weapons.kurogane.lore").size() > 15, "the old lore was long");

        plugin.reload();
        org.bukkit.configuration.file.YamlConfiguration fresh = bundledConfig();
        List<String> lore = plugin.getConfig().getStringList("weapons.kurogane.lore");
        assertEquals(fresh.getStringList("weapons.kurogane.lore"), lore, "the new lore");
        assertTrue(String.join("\n", lore).contains("{phantom-step.name}"));
        assertEquals("&cLegendaries can't go in containers.", plugin.getConfig().getString("messages.storage-blocked"));
        assertTrue(plugin.getConfig().getStringList("weapons.sugarcrash.lore").contains(
                "&#FF7AC3{sneak-key} &8» &f{sugar-rush.name} &8({sugar-rush.cooldown})"));
        assertEquals(fresh.getStringList("weapons.gravebreaker.lore"), plugin.getConfig().getStringList("weapons.gravebreaker.lore"));
        assertFalse(String.join("\n", plugin.getConfig().getStringList("weapons.gravebreaker.lore")).contains("LEGENDARY"));
        assertEquals(List.of("&bMy own lore"), plugin.getConfig().getStringList("weapons.starforged.lore"));
        assertEquals("&cNo alts!", plugin.getConfig().getString("messages.alt-blocked"));
        // It is saved, so it sticks after the next restart.
        assertEquals(lore, savedConfig().getStringList("weapons.kurogane.lore"));
        // Weapons already out get the new lore too.
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        List<String> itemLore = sword.getItemMeta().getLore().stream().map(ChatColor::stripColor).toList();
        assertEquals(lore.size() - 1 + 3, itemLore.size(), String.join("\n", itemLore));
        assertTrue(itemLore.contains("F » Phantom Step (21s)"), String.join("\n", itemLore));
        assertTrue(itemLore.contains("Shift + F » Crimson Tempest (27s)"), String.join("\n", itemLore));
        assertTrue(itemLore.contains("Passive » Crimson Hunger"), String.join("\n", itemLore));
        assertTrue(itemLore.contains("For 5s, every swing sends out"), "settings fill in: " + String.join("\n", itemLore));
    }

    @Test
    void aConfigFrom133GetsTheAttackLinesAndFewerSounds() {
        org.bukkit.configuration.file.YamlConfiguration old = previousTexts();
        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
        config.set("config-version", 3);
        for (WeaponType type : WeaponType.values()) {
            if (old.isSet("v1_3_3.weapons." + type.key() + ".lore")) {
                config.set("weapons." + type.key() + ".lore", old.getStringList("v1_3_3.weapons." + type.key() + ".lore"));
            }
        }
        config.set("weapons.kurogane.abilities.iaido.damage", 9);
        config.set("sounds.candy-cyclone", List.of("legendary:sugarcrash.cyclone 1 1", "entity.breeze.wind_burst 0.2 1.3"));
        config.set("sounds.kurogane-hit", List.of("legendary:kurogane.hit 0.9 1"));
        plugin.saveConfig();

        assertEquals(List.of(), plugin.reload(), "nothing left to warn about");
        org.bukkit.configuration.file.YamlConfiguration saved = savedConfig();
        assertEquals(6, saved.getInt("config-version"));
        assertFalse(saved.isSet("weapons.kurogane.abilities.iaido"));
        assertFalse(saved.isSet("sounds.candy-cyclone"), "the old pack sounds are gone");
        assertFalse(saved.isSet("sounds.kurogane-hit"));
        assertTrue(saved.getStringList("weapons.sugarcrash.lore").contains(
                "&#FF7AC3{sneak-key} &8» &f{sugar-rush.name} &8({sugar-rush.cooldown})"));
        assertTrue(plugin.settings().trueDamage());

        PlayerMock steve = player("Steve", 0, 0);
        ItemMeta axe = give(steve, WeaponType.GRAVEBREAKER).getItemMeta();
        List<String> lore = axe.getLore().stream().map(ChatColor::stripColor).toList();
        assertEquals(List.of("When in Main Hand:", " 17.5 Attack Damage", " 1 Attack Speed"),
                lore.subList(lore.size() - 3, lore.size()), String.join("\n", lore));
        assertTrue(axe.hasItemFlag(ItemFlag.HIDE_ATTRIBUTES));
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
    void aConfigFrom150LosesCandyBarrageAndItsLayeredSounds() {
        org.bukkit.configuration.file.YamlConfiguration old = previousTexts();
        org.bukkit.configuration.file.FileConfiguration config = plugin.getConfig();
        config.set("config-version", 4);
        config.set("weapons.sugarcrash.lore", old.getStringList("v1_5_0.weapons.sugarcrash.lore"));
        config.set("weapons.sugarcrash.abilities", null);
        config.set("weapons.sugarcrash.abilities.candy-barrage.cooldown", "22s");
        config.set("weapons.sugarcrash.abilities.candy-barrage.canes", 5);
        for (String key : old.getConfigurationSection("v1_5_0.sounds").getKeys(false)) {
            config.set("sounds." + key, old.getStringList("v1_5_0.sounds." + key));
        }
        config.set("sounds.candy-barrage", List.of("block.amethyst_cluster.place 1 1.4"));
        plugin.saveConfig();

        assertEquals(List.of(), plugin.reload(), "nothing left to warn about");
        org.bukkit.configuration.file.YamlConfiguration saved = savedConfig();
        assertEquals(6, saved.getInt("config-version"));
        assertFalse(saved.isSet("weapons.sugarcrash.abilities.candy-barrage"), "Candy Barrage is gone");
        assertFalse(saved.isSet("sounds.candy-barrage"));
        assertEquals(33.0, plugin.settings().ability(Ability.SUGAR_RUSH).num("cooldown"));
        assertTrue(saved.getStringList("weapons.sugarcrash.lore").contains("&7Charge where you look, ramming everyone."));
        org.bukkit.configuration.file.YamlConfiguration fresh = bundledConfig();
        assertEquals(fresh.getConfigurationSection("sounds").getKeys(false), saved.getConfigurationSection("sounds").getKeys(false));
        for (String key : fresh.getConfigurationSection("sounds").getKeys(false)) {
            assertEquals(fresh.getStringList("sounds." + key), saved.getStringList("sounds." + key), "sounds." + key);
        }
    }

    @Test
    void legendaryHitsDoMeleeDamageTimesTheirNormalDamage() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.WYRMFANG);
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
