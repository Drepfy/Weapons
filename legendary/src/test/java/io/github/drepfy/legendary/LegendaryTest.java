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
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
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
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
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

    /** Gives the weapon with /legendary give and returns the copy in the player's hand. */
    private ItemStack give(PlayerMock player, WeaponType type) {
        command("legendary give " + player.getName() + " " + type.key());
        for (ItemStack item : player.getInventory().getContents()) {
            WeaponItems.Tag tag = plugin.items().read(item);
            if (tag != null && tag.type() == type) {
                player.getInventory().setItemInMainHand(item);
                return item;
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

    private static List<String> bars(PlayerMock player) {
        List<String> bars = new ArrayList<>();
        Component bar;
        while ((bar = player.nextActionBar()) != null) {
            bars.add(PlainTextComponentSerializer.plainText().serialize(bar));
        }
        return bars;
    }

    private static String lastBar(PlayerMock player) {
        List<String> bars = bars(player);
        return bars.isEmpty() ? "" : bars.get(bars.size() - 1);
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
    void fiveNetheriteWeaponsWithSharpnessSixNamesLoreAndIds() {
        PlayerMock steve = player("Steve", 0, 0);
        Enchantment sharpness = io.github.drepfy.legendary.util.Compat.enchantment("sharpness");
        List<UUID> ids = new ArrayList<>();
        for (WeaponType type : WeaponType.values()) {
            ItemStack item = give(steve, type);
            assertEquals(type == WeaponType.GRAVEBREAKER || type == WeaponType.STARFORGED
                    ? Material.NETHERITE_AXE : Material.NETHERITE_SWORD, item.getType());
            ItemMeta meta = item.getItemMeta();
            assertEquals(6, meta.getEnchantLevel(sharpness), type + " has Sharpness VI");
            assertTrue(meta.isUnbreakable());
            assertTrue(meta.hasCustomModelData());
            String name = ChatColor.stripColor(meta.getDisplayName());
            assertEquals(type.key(), name.toLowerCase(), "named " + name);
            String lore = String.join("\n", meta.getLore());
            assertTrue(lore.contains(plugin.settings().ability(type.primary()).name()), type + " lore names its ability");
            assertTrue(lore.contains(WeaponItems.shortId(id(item))), "the lore shows its tracking number");
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
        rightClick(alex);
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
    void aWeaponThatVanishesIsMarkedLostAndCanBeReplaced() {
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack old = give(steve, WeaponType.SUGARCRASH);
        UUID oldId = id(old);
        steve.getInventory().clear(); // /clear, or a plugin deleting it
        tick(60);
        assertEquals(WeaponRecord.State.HELD, plugin.registry().get(oldId).state(), "not after a moment (creative cursor)");
        tick(6 * 20);
        assertEquals(WeaponRecord.State.LOST, plugin.registry().get(oldId).state());
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

    // ---- Kurogane ---------------------------------------------------------------------------------------

    @Test
    void crescentDrawCutsWhatIsInFront() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock front = player("Front", 0, 3);
        PlayerMock behind = player("Behind", 0, -3);
        give(steve, WeaponType.KUROGANE);
        rightClick(steve);
        tick(6);
        assertTrue(front.getHealth() < 20.0, "hit");
        assertTrue(front.getVelocity().getZ() > 0.3, "knocked away");
        assertEquals(20.0, behind.getHealth(), "nothing behind");
        // On cooldown for 8 seconds.
        double health = front.getHealth();
        front.setNoDamageTicks(0);
        rightClick(steve);
        tick(6);
        assertEquals(health, front.getHealth(), "still recharging");
        assertTrue(lastBar(steve).contains("Crescent Draw"));
        tick(8 * 20);
        rightClick(steve);
        tick(6);
        assertTrue(front.getHealth() < health, "ready again after 8s");
    }

    @Test
    void unbrokenEdgeBuildsWithChargedHitsAndFades() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.KUROGANE);
        assertEquals(10.0, melee(steve, alex, 10.0).getDamage(), 1.0E-6, "first hit: no Edge yet");
        tick(12);
        assertEquals(10.5, melee(steve, alex, 10.0).getDamage(), 1.0E-6, "+5% for one stack");
        tick(2);
        assertEquals(11.0, melee(steve, alex, 10.0).getDamage(), 1.0E-6, "two stacks");
        tick(12);
        assertEquals(11.0, melee(steve, alex, 10.0).getDamage(), 1.0E-6, "the spam click before did not build it");
        tick(12);
        melee(steve, alex, 10.0);
        tick(12);
        melee(steve, alex, 10.0);
        tick(12);
        assertEquals(12.0, melee(steve, alex, 10.0).getDamage(), 1.0E-6, "capped at 4 stacks (+20%)");
        tick(4);
        assertTrue(lastBar(steve).contains("Edge"));
        tick(4 * 20);
        assertEquals(10.0, melee(steve, alex, 10.0).getDamage(), 1.0E-6, "faded after 3s without a hit");
        PlayerMock bob = player("Bob", 2, 0);
        tick(12);
        melee(steve, alex, 10.0);
        tick(12);
        assertEquals(10.0, melee(steve, bob, 10.0).getDamage(), 1.0E-6, "a new target starts again");
    }

    // ---- Sugarcrash -------------------------------------------------------------------------------------

    @Test
    void sugarRushThenItsCooldown() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.SUGARCRASH);
        rightClick(steve);
        assertTrue(steve.hasPotionEffect(PotionEffectType.SPEED));
        assertEquals(1, steve.getPotionEffect(PotionEffectType.SPEED).getAmplifier(), "Speed II");
        assertTrue(steve.hasPotionEffect(PotionEffectType.HASTE), "faster attacks");
        tick(4);
        assertTrue(lastBar(steve).contains("Sugar Rush"));
        tick(6 * 20);
        assertTrue(has(chat(steve), "wore off"));
        steve.removePotionEffect(PotionEffectType.SPEED);
        rightClick(steve);
        assertFalse(steve.hasPotionEffect(PotionEffectType.SPEED), "the cooldown runs after it ends");
        tick(18 * 20);
        rightClick(steve);
        assertTrue(steve.hasPotionEffect(PotionEffectType.SPEED), "18s later it works again");
    }

    @Test
    void eatingFromTheOffhandDoesNotWasteTheAbility() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.SUGARCRASH);
        steve.getInventory().setItemInOffHand(new ItemStack(Material.GOLDEN_APPLE));
        rightClick(steve);
        assertFalse(steve.hasPotionEffect(PotionEffectType.SPEED), "the golden apple is eaten instead");
        steve.getInventory().setItemInOffHand(new ItemStack(Material.SHIELD));
        tick(5);
        rightClick(steve);
        assertTrue(steve.hasPotionEffect(PotionEffectType.SPEED), "with a shield the ability still works");
        bars(steve);
        tick(5);
        rightClick(steve); // Blocking while it recharges.
        assertFalse(has(bars(steve), "ready in"), "no nagging while blocking");
    }

    @Test
    void sweetShockKnocksBackAndSlowsWhoIsClose() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock near = player("Near", 3, 0);
        PlayerMock far = player("Far", 12, 0);
        give(steve, WeaponType.SUGARCRASH);
        sneakRightClick(steve);
        assertTrue(near.getHealth() < 20.0);
        assertTrue(near.getVelocity().getX() > 0.5, "thrown away from Steve");
        assertTrue(near.hasPotionEffect(PotionEffectType.SLOWNESS));
        assertEquals(20.0, far.getHealth());
        assertFalse(far.hasPotionEffect(PotionEffectType.SLOWNESS));
    }

    // ---- Riftblade --------------------------------------------------------------------------------------

    @Test
    void riftSlashTravelsAndDistorts() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 7);
        give(steve, WeaponType.RIFTBLADE);
        rightClick(steve);
        tick(2);
        assertEquals(20.0, alex.getHealth(), "the rift takes time to get there (it can be dodged)");
        tick(10);
        assertTrue(alex.getHealth() < 20.0);
        assertTrue(alex.getVelocity().getZ() > 0.3, "thrown back");
        assertTrue(alex.hasPotionEffect(PotionEffectType.NAUSEA), "vision distorted");
    }

    @Test
    void riftRecallReturnsToTheMark() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.RIFTBLADE);
        Location mark = steve.getLocation().clone();
        sneakRightClick(steve);
        steve.teleport(new Location(world, 15.5, 64, 15.5));
        tick(20);
        sneakRightClick(steve);
        assertEquals(mark.getX(), steve.getLocation().getX(), 1.0E-6);
        assertEquals(mark.getZ(), steve.getLocation().getZ(), 1.0E-6);
        // On cooldown now.
        steve.teleport(new Location(world, 15.5, 64, 15.5));
        tick(5);
        sneakRightClick(steve);
        tick(5);
        sneakRightClick(steve);
        assertEquals(15.5, steve.getLocation().getX(), 1.0E-6, "no second recall during the cooldown");
    }

    @Test
    void theRiftMarkFadesAndCanBeRefused() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.RIFTBLADE);
        sneakRightClick(steve);
        tick(11 * 20);
        assertTrue(has(chat(steve), "faded"));
        assertTrue(plugin.abilities().cooldown(plugin.items().read(steve.getInventory().getItemInMainHand()),
                Ability.RIFT_RECALL, plugin.tick()) > 0, "the cooldown starts when it fades");
        // A safe zone or region plugin refusing the teleport keeps the mark.
        tick(23 * 20);
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onTeleport(PlayerTeleportEvent event) {
                if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) {
                    event.setCancelled(true);
                }
            }
        }, plugin);
        sneakRightClick(steve);
        steve.teleport(new Location(world, 10.5, 64, 0.5), PlayerTeleportEvent.TeleportCause.COMMAND);
        tick(5);
        sneakRightClick(steve);
        assertEquals(10.5, steve.getLocation().getX(), 1.0E-6, "refused");
    }

    // ---- Gravebreaker -----------------------------------------------------------------------------------

    @Test
    void earthsplitterThrowsUpAndTiresWithoutBreakingBlocks() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 4);
        give(steve, WeaponType.GRAVEBREAKER);
        rightClick(steve);
        tick(8);
        assertTrue(alex.getHealth() < 20.0);
        assertTrue(alex.getVelocity().getY() >= 0.7, "thrown upwards");
        assertTrue(alex.hasPotionEffect(PotionEffectType.MINING_FATIGUE));
        for (int z = 0; z <= 9; z++) {
            assertEquals(Material.STONE, world.getBlockAt(0, 63, z).getType(), "the ground is untouched");
        }
    }

    @Test
    void executionersMarkMakesTheNextEarthsplitterHarder() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 2);
        give(steve, WeaponType.GRAVEBREAKER);
        melee(steve, alex, 5);
        tick(16);
        melee(steve, alex, 5);
        tick(2);
        melee(steve, alex, 5); // Too fast: does not count.
        tick(16);
        assertFalse(has(chat(alex), "marked"), "two counted hits are not enough");
        melee(steve, alex, 5);
        assertTrue(has(chat(alex), "You are marked"));
        alex.teleport(new Location(world, 0.5, 64, 4.5));
        rightClick(steve);
        tick(8);
        assertEquals(1.2, alex.getVelocity().getY(), 1.0E-6, "0.75 x 1.6 = 1.2 (the cap)");
        // The mark was used up.
        tick(12 * 20);
        alex.setVelocity(new Vector());
        rightClick(steve);
        tick(8);
        assertEquals(0.75, alex.getVelocity().getY(), 1.0E-6);
    }

    // ---- Starforged -------------------------------------------------------------------------------------

    @Test
    void astralImpactWarnsFirstThenStrikes() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 0, 6);
        PlayerMock outside = player("Outside", 0, 14);
        give(steve, WeaponType.STARFORGED);
        // Looking down at the ground about 6 blocks ahead.
        steve.teleport(new Location(world, 0.5, 64, 0.5, 0f, 15f));
        rightClick(steve);
        tick(20);
        assertEquals(20.0, alex.getHealth(), "a warning first: time to get out");
        tick(10);
        assertTrue(alex.getHealth() < 20.0, "then the star lands");
        assertTrue(alex.getVelocity().getY() > 0.5, "launched");
        assertEquals(20.0, outside.getHealth());
    }

    @Test
    void gravityWellPullsThenBursts() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock alex = player("Alex", 4, 7);
        give(steve, WeaponType.STARFORGED);
        steve.teleport(new Location(world, 0.5, 64, 0.5, 0f, 15f));
        sneakRightClick(steve);
        tick(3);
        assertTrue(alex.getHealth() < 20.0, "caught by the field");
        assertTrue(alex.getVelocity().getX() < 0, "pulled towards the centre");
        // No star can be called into the well while it is open.
        tick(10);
        WeaponItems.Tag tag = plugin.items().read(steve.getInventory().getItemInMainHand());
        rightClick(steve);
        assertEquals(0, plugin.abilities().active(steve, tag, Ability.ASTRAL_IMPACT, plugin.tick()),
                "Astral Impact is locked while the well is open");
        assertTrue(has(bars(steve), "Astral Impact ready in"));
        tick(4 * 20 - 10);
        assertTrue(alex.getVelocity().getX() > 0.3, "the burst throws outwards");
    }

    @Test
    void protectedPlayersAreNeverPushedPulledOrSlowed() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock safe = player("Safe", 0, 3);
        protect(safe);
        give(steve, WeaponType.KUROGANE);
        rightClick(steve);
        tick(6);
        assertEquals(20.0, safe.getHealth());
        assertEquals(0.0, safe.getVelocity().length(), 1.0E-9, "not knocked back");
        command("legendary give Steve sugarcrash");
        steve.getInventory().setItemInMainHand(steve.getInventory().getItem(1));
        sneakRightClick(steve);
        assertFalse(safe.hasPotionEffect(PotionEffectType.SLOWNESS), "not slowed");
        assertEquals(0.0, safe.getVelocity().length(), 1.0E-9);
    }

    @Test
    void creativePlayersAndPvpOffAreLeftAlone() {
        PlayerMock steve = player("Steve", 0, 0);
        PlayerMock builder = player("Builder", 0, 3);
        builder.setGameMode(org.bukkit.GameMode.CREATIVE);
        give(steve, WeaponType.KUROGANE);
        rightClick(steve);
        tick(6);
        assertEquals(0.0, builder.getVelocity().length(), 1.0E-9);
        builder.setGameMode(org.bukkit.GameMode.SURVIVAL);
        world.setPVP(false);
        tick(8 * 20);
        rightClick(steve);
        tick(6);
        assertEquals(20.0, builder.getHealth(), "PvP off in this world");
    }

    // ---- display, commands, config -----------------------------------------------------------------------

    @Test
    void theActionBarShowsTheCooldowns() {
        PlayerMock steve = player("Steve", 0, 0);
        give(steve, WeaponType.STARFORGED);
        tick(4);
        String bar = lastBar(steve);
        assertTrue(bar.contains("Astral Impact") && bar.contains("Gravity Well"), bar);
        assertTrue(steve.hasMetadata("vanillasmp:actionbar"), "the Combat plugin knows to stay out of the way");
        steve.getInventory().setItemInMainHand(null);
        tick(4);
        assertFalse(steve.hasMetadata("vanillasmp:actionbar"));
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
        assertTrue(has(inspect, "Crescent Draw ready"), String.join("\n", inspect));

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
        plugin.getConfig().set("weapons.kurogane.abilities.crescent-draw.cooldown", "soon");
        plugin.getConfig().set("weapons.starforged.abilities.astral-impact.warning", "0.1s");
        plugin.getConfig().set("hit-mobs", "everything");
        plugin.saveConfig();
        List<String> warnings = plugin.reload();
        assertEquals(3, warnings.size(), String.join("\n", warnings));
        assertEquals(8.0, plugin.settings().ability(Ability.CRESCENT_DRAW).num("cooldown"));
        assertEquals(1.25, plugin.settings().ability(Ability.ASTRAL_IMPACT).num("warning"), "never under 0.5s");
        assertEquals(List.of(), freshWarnings(), "the bundled config.yml has no mistakes");
    }

    @Test
    void oldDefaultTextsAreUpdatedButOwnTextsAreKept() {
        // A config.yml written by 1.0.1: the old long lore and messages.
        org.bukkit.configuration.file.YamlConfiguration old = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.InputStreamReader(plugin.getResource("previous-text.yml"),
                        java.nio.charset.StandardCharsets.UTF_8));
        plugin.getConfig().set("weapons.kurogane.lore", old.getStringList("weapons.kurogane.lore"));
        plugin.getConfig().set("messages.storage-blocked", old.getString("messages.storage-blocked"));
        // ...but this server wrote its own Starforged lore and alt message.
        plugin.getConfig().set("weapons.starforged.lore", List.of("&bMy own lore"));
        plugin.getConfig().set("messages.alt-blocked", "&cNo alts!");
        plugin.saveConfig();
        assertTrue(old.getStringList("weapons.kurogane.lore").size() > 12, "the old lore was long");

        plugin.reload();
        List<String> lore = plugin.getConfig().getStringList("weapons.kurogane.lore");
        assertEquals(9, lore.size(), "the new, shorter lore");
        assertTrue(String.join("\n", lore).contains("Crescent Draw"));
        assertEquals("&cLegendaries can't go in containers.", plugin.getConfig().getString("messages.storage-blocked"));
        assertEquals(List.of("&bMy own lore"), plugin.getConfig().getStringList("weapons.starforged.lore"));
        assertEquals("&cNo alts!", plugin.getConfig().getString("messages.alt-blocked"));
        // It is saved, so it sticks after the next restart.
        org.bukkit.configuration.file.YamlConfiguration saved = org.bukkit.configuration.file.YamlConfiguration
                .loadConfiguration(new java.io.File(plugin.getDataFolder(), "config.yml"));
        assertEquals(9, saved.getStringList("weapons.kurogane.lore").size());
        // Weapons already out get the new lore too.
        PlayerMock steve = player("Steve", 0, 0);
        ItemStack sword = give(steve, WeaponType.KUROGANE);
        assertEquals(9, sword.getItemMeta().getLore().size());
    }

    private List<String> freshWarnings() {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        file.delete();
        plugin.saveDefaultConfig();
        return plugin.reload();
    }
}
