package io.github.drepfy.lifesteal;

import io.github.drepfy.lifesteal.api.HeartStealEvent;
import io.github.drepfy.lifesteal.util.Compat;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Statistic;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Sheep;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.Recipe;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.ItemMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Every rule of the plugin on a simulated server. */
class LifestealTest {

    private static final long MINUTE = 60_000L;

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private ServerMock server;
    private LifestealPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(LifestealPlugin.class);
        plugin.setClock(clock::get);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---- helpers ----------------------------------------------------------------------------------------

    /** A player who has played for an hour (older than the new-account protection). */
    private PlayerMock player(String name) {
        PlayerMock player = server.addPlayer(name);
        player.setStatistic(Statistic.PLAY_ONE_MINUTE, 20 * 60 * 60);
        player.teleport(new Location(world, 0.5, 64, 0.5));
        messages(player);
        return player;
    }

    private void kill(PlayerMock killer, PlayerMock victim) {
        victim.setKiller(killer);
        victim.setHealth(0);
        assertTrue(victim.isDead(), "the victim died");
        victim.respawn();
        server.getScheduler().performTicks(1);
        clock.addAndGet(1000);
    }

    private int hearts(PlayerMock player) {
        return plugin.hearts().hearts(player);
    }

    private double maxHealth(PlayerMock player) {
        return player.getAttribute(Compat.MAX_HEALTH).getBaseValue();
    }

    private static List<String> messages(PlayerMock player) {
        List<String> result = new ArrayList<>();
        String message;
        while ((message = player.nextMessage()) != null) {
            result.add(ChatColor.stripColor(message));
        }
        return result;
    }

    private static boolean has(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    private int heartItems(PlayerMock player) {
        int count = 0;
        for (ItemStack item : player.getInventory().getContents()) {
            if (plugin.items().isHeart(item)) {
                count += item.getAmount();
            }
        }
        return count;
    }

    private void rightClick(PlayerMock player) {
        clock.addAndGet(250);
        server.getPluginManager().callEvent(new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR,
                player.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND));
    }

    private void command(PlayerMock player, String command) {
        player.performCommand(command);
    }

    // ---- hearts and kills -----------------------------------------------------------------------------------

    @Test
    void theTexturePackIsReadyToUpload() throws Exception {
        java.nio.file.Path pack = plugin.getDataFolder().toPath().resolve(LifestealPlugin.PACK_FILE);
        assertTrue(java.nio.file.Files.exists(pack));
        byte[] bytes = java.nio.file.Files.readAllBytes(pack);
        assertEquals(20, plugin.packHash().length);
        assertTrue(java.util.Arrays.equals(java.security.MessageDigest.getInstance("SHA-1").digest(bytes),
                plugin.packHash()));
        try (java.util.zip.ZipInputStream zip = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(bytes))) {
            List<String> names = new ArrayList<>();
            for (java.util.zip.ZipEntry entry; (entry = zip.getNextEntry()) != null; ) {
                names.add(entry.getName());
            }
            assertTrue(names.containsAll(List.of("pack.mcmeta", "assets/lifesteal/textures/item/heart.png",
                    "assets/minecraft/items/red_dye.json", "assets/minecraft/models/item/red_dye.json")), names.toString());
        }
    }

    @Test
    void everyoneStartsWithTenHearts() {
        PlayerMock steve = player("Steve");
        assertEquals(10, hearts(steve));
        assertEquals(20.0, maxHealth(steve));
    }

    @Test
    void aKillStealsOneHeart() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        steve.setHealth(10.0);
        kill(steve, alex);
        assertEquals(11, hearts(steve));
        assertEquals(9, hearts(alex));
        assertEquals(22.0, maxHealth(steve));
        assertEquals(12.0, steve.getHealth(), "the gained heart is filled");
        assertEquals(18.0, maxHealth(alex));
        assertEquals(18.0, alex.getHealth(), "respawned with full (fewer) hearts");
        assertTrue(has(messages(steve), "You stole a heart from Alex. You now have 11 hearts."));
        assertTrue(has(messages(alex), "Steve stole one of your hearts. You now have 9 hearts."));
    }

    @Test
    void aStolenHeartShowsATitleAndPlaysASound() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        kill(steve, alex);
        assertEquals("+1 ❤", ChatColor.stripColor(steve.nextTitle()));
        assertEquals("stolen from Alex", ChatColor.stripColor(steve.nextSubTitle()));
        assertEquals("-1 ❤", ChatColor.stripColor(alex.nextTitle()));
        assertEquals("taken by Steve", ChatColor.stripColor(alex.nextSubTitle()));
        assertTrue(steve.getHeardSounds().stream().anyMatch(s -> s.getSound().equals("entity.player.levelup")),
                "the killer hears it");
        assertTrue(alex.getHeardSounds().stream().anyMatch(s -> s.getSound().equals("block.respawn_anchor.deplete")),
                "the victim hears it");

        // A Heart item: a title with the new total.
        steve.getInventory().setItemInMainHand(plugin.items().create(1));
        rightClick(steve);
        assertEquals("+1 ❤", ChatColor.stripColor(steve.nextTitle()));
        assertEquals("You now have 12 hearts", ChatColor.stripColor(steve.nextSubTitle()));
    }

    @Test
    void titlesAndSoundsCanBeTurnedOff() throws Exception {
        plugin.getConfig().set("effects.titles", false);
        plugin.getConfig().set("effects.sound-gain", "");
        plugin.getConfig().set("effects.sound-lose", "nonsense 9000 lots");
        plugin.saveConfig();
        List<String> warnings = plugin.reload();
        assertEquals(1, warnings.size(), "the broken sound is reported: " + warnings);
        assertTrue(warnings.get(0).contains("effects.sound-lose"));
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        kill(steve, alex);
        assertNull(steve.nextTitle());
        assertNull(alex.nextTitle());
        assertTrue(steve.getHeardSounds().stream().noneMatch(s -> s.getSound().equals("entity.player.levelup")));
        assertTrue(alex.getHeardSounds().stream().anyMatch(s -> s.getSound().equals("block.respawn_anchor.deplete")),
                "the broken sound falls back to the default");
    }

    @Test
    void placeholdersForScoreboardsAndHolograms() throws Exception {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        kill(steve, alex);
        io.github.drepfy.lifesteal.hook.LifestealPlaceholders placeholders =
                new io.github.drepfy.lifesteal.hook.LifestealPlaceholders(plugin);
        placeholders.refresh();
        assertEquals("lifesteal", placeholders.getIdentifier());
        assertTrue(placeholders.persist(), "survives /papi reload");
        assertEquals("11", placeholders.onRequest(steve, "hearts"));
        assertEquals("9", placeholders.onRequest(alex, "HEARTS"));
        assertEquals("20", placeholders.onRequest(null, "max"));
        assertEquals("3", placeholders.onRequest(null, "min"));
        assertEquals("Steve", placeholders.onRequest(null, "top_1_name"));
        assertEquals("11", placeholders.onRequest(null, "top_1_hearts"));
        assertEquals("Alex", placeholders.onRequest(null, "top_2_name"));
        assertEquals("-", placeholders.onRequest(null, "top_3_name"), "nobody third yet");
        assertEquals("0", placeholders.onRequest(null, "top_3_hearts"));
        assertNull(placeholders.onRequest(steve, "nonsense"), "not ours");
        assertNull(placeholders.onRequest(null, "top_11_name"));
        assertNull(placeholders.onRequest(null, "top_x_name"));
        assertEquals("", placeholders.onRequest(null, "hearts"), "no player");
        // Scoreboard plugins often ask from their own thread: the value of the last second.
        String[] asked = new String[1];
        Thread other = new Thread(() -> asked[0] = placeholders.onRequest(steve, "hearts"));
        other.start();
        other.join();
        assertEquals("11", asked[0]);
    }

    @Test
    void theSamePairHasAThirtyMinuteCooldown() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        kill(steve, alex);
        messages(steve);
        kill(steve, alex);
        assertEquals(11, hearts(steve), "no heart within 30 minutes");
        assertEquals(9, hearts(alex));
        List<String> toSteve = messages(steve);
        assertTrue(has(toSteve, "You took a heart from Alex recently. You can take another in 29m"), toSteve.toString());
        assertTrue(has(messages(alex), "You did not lose a heart: Steve took one from you recently."));

        // Alex may take it back straight away (the cooldown is per killer).
        kill(alex, steve);
        assertEquals(10, hearts(steve));
        assertEquals(10, hearts(alex));

        // Other victims are not affected.
        PlayerMock sam = player("Sam");
        kill(steve, sam);
        assertEquals(11, hearts(steve));

        clock.addAndGet(30 * MINUTE);
        kill(steve, alex);
        assertEquals(12, hearts(steve), "after 30 minutes it works again");
        assertEquals(9, hearts(alex));
    }

    @Test
    void cooldownSurvivesARestartOfTheData() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        kill(steve, alex);
        long until = plugin.store().cooldownUntil(steve.getUniqueId(), alex.getUniqueId());
        assertTrue(until > clock.get() + 28 * MINUTE && until <= clock.get() + 30 * MINUTE, String.valueOf(until));
    }

    @Test
    void aKillerAtTwentyHeartsDropsTheHeart() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        command(steve, "lifesteal sethearts Steve 20");
        steve.setOp(true);
        command(steve, "lifesteal sethearts Steve 20");
        assertEquals(20, hearts(steve));
        alex.teleport(new Location(world, 30.5, 64, 30.5));
        kill(steve, alex);
        assertEquals(20, hearts(steve), "never above 20");
        assertEquals(9, hearts(alex), "the victim still loses the heart");
        List<Item> drops = new ArrayList<>();
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Item item && plugin.items().isHeart(item.getItemStack())) {
                drops.add(item);
            }
        }
        assertEquals(1, drops.size(), "one Heart item dropped");
        assertEquals(1, drops.get(0).getItemStack().getAmount());
        assertTrue(drops.get(0).getLocation().distance(new Location(world, 30.5, 64, 30.5)) < 2,
                "where the victim died: " + drops.get(0).getLocation());
        assertTrue(has(messages(steve), "You already have the maximum of 20 hearts, so the heart from Alex dropped as an item."));
    }

    @Test
    void nobodyGoesBelowThreeHearts() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        plugin.hearts().set(alex, 3, false);
        kill(steve, alex);
        assertEquals(3, hearts(alex));
        assertEquals(10, hearts(steve), "nothing to steal");
        assertTrue(has(messages(steve), "Alex only has 3 hearts left, so there was no heart to steal."));
        assertEquals(0L, plugin.store().cooldownUntil(steve.getUniqueId(), alex.getUniqueId()), "no cooldown started");
        assertEquals(6.0, maxHealth(alex));
    }

    @Test
    void naturalDeathsAndSuicidesCostNothing() {
        PlayerMock steve = player("Steve");
        steve.setHealth(0);
        steve.respawn();
        assertEquals(10, hearts(steve));
        steve.setKiller(steve);
        steve.setHealth(0);
        steve.respawn();
        assertEquals(10, hearts(steve));
    }

    @Test
    void otherPluginsCanCancelASteal() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onSteal(HeartStealEvent event) {
                event.setCancelled(true);
            }
        }, plugin);
        kill(steve, alex);
        assertEquals(10, hearts(steve));
        assertEquals(10, hearts(alex));
    }

    // ---- alt accounts -------------------------------------------------------------------------------------------

    @Test
    void accountsOnTheSameIpCannotFarmEachOther() throws Exception {
        PlayerMock main = player("Main");
        PlayerMock alt = player("Alt");
        PlayerMock staff = player("Staff");
        staff.setOp(true);
        plugin.alts().recordJoin(main, InetAddress.getByName("203.0.113.5"));
        plugin.alts().recordJoin(alt, InetAddress.getByName("203.0.113.5"));
        kill(main, alt);
        assertEquals(10, hearts(main), "no heart for the main account");
        assertEquals(10, hearts(alt), "the alt keeps its heart");
        assertTrue(has(messages(main), "No heart was stolen: this kill was not counted by the alt account protection."));
        assertTrue(has(messages(staff), "Main killed Alt, not counted: same IP address"));
        kill(alt, main);
        assertEquals(10, hearts(main), "not the other way round either");

        // Siblings on one network: staff allow them.
        staff.performCommand("lifesteal alts allow Main Alt");
        kill(main, alt);
        assertEquals(11, hearts(main));
    }

    @Test
    void addressesSharedByManyAccountsAreIgnored() throws Exception {
        List<PlayerMock> school = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            PlayerMock player = player("Student" + i);
            plugin.alts().recordJoin(player, InetAddress.getByName("198.51.100.7"));
            school.add(player);
        }
        kill(school.get(0), school.get(1));
        assertEquals(11, hearts(school.get(0)), "6 accounts on one address: a shared network");
        // Local addresses (a proxy without IP forwarding) are never recorded.
        PlayerMock a = player("LocalA");
        PlayerMock b = player("LocalB");
        plugin.alts().recordJoin(a, InetAddress.getByName("127.0.0.1"));
        plugin.alts().recordJoin(b, InetAddress.getByName("127.0.0.1"));
        kill(a, b);
        assertEquals(11, hearts(a));
    }

    @Test
    void staffCanLinkAccounts() {
        PlayerMock staff = player("Staff");
        staff.setOp(true);
        PlayerMock main = player("Main");
        PlayerMock alt = player("Alt");
        staff.performCommand("lifesteal alts link Main Alt");
        assertTrue(has(messages(staff), "Main and Alt are now treated as the same person"));
        kill(main, alt);
        assertEquals(10, hearts(main));
        assertTrue(has(messages(staff), "not counted: accounts linked by staff"));
        staff.performCommand("lifesteal alts unlink Main Alt");
        kill(main, alt);
        assertEquals(11, hearts(main));
    }

    @Test
    void brandNewAccountsDoNotTradeHearts() {
        PlayerMock veteran = player("Veteran");
        PlayerMock fresh = server.addPlayer("Fresh");
        fresh.setStatistic(Statistic.PLAY_ONE_MINUTE, 20 * 60 * 10); // 10 minutes
        kill(veteran, fresh);
        assertEquals(10, hearts(veteran));
        assertEquals(10, hearts(fresh));
        kill(fresh, veteran);
        assertEquals(10, hearts(fresh), "nor can a new account take hearts");
        fresh.setStatistic(Statistic.PLAY_ONE_MINUTE, 20 * 60 * 31);
        kill(veteran, fresh);
        assertEquals(11, hearts(veteran));
    }

    // ---- Heart items ------------------------------------------------------------------------------------------------

    @Test
    void theHeartItemIsClearlyAHeart() {
        ItemStack heart = plugin.items().create(1);
        assertEquals(Material.RED_DYE, heart.getType());
        ItemMeta meta = heart.getItemMeta();
        assertEquals("❤ Heart", ChatColor.stripColor(meta.getDisplayName()));
        assertTrue(ChatColor.stripColor(String.join("\n", meta.getLore())).contains("A stolen heart."));
        assertTrue(ChatColor.stripColor(String.join("\n", meta.getLore())).contains("Right-click to consume"));
        assertEquals(1001, meta.getCustomModelData(), "the resource pack model");
        assertTrue(plugin.items().create(5).isSimilar(heart), "Hearts stack with each other");

        ItemStack fake = new ItemStack(Material.RED_DYE);
        ItemMeta fakeMeta = fake.getItemMeta();
        fakeMeta.setDisplayName(meta.getDisplayName());
        fakeMeta.setLore(meta.getLore());
        fake.setItemMeta(fakeMeta);
        assertFalse(plugin.items().isHeart(fake), "red dye renamed in an anvil is not a Heart");
        assertFalse(plugin.items().isHeart(new ItemStack(Material.RED_DYE)));
    }

    @Test
    void usingHeartsAddsHeartsUpToTwenty() {
        PlayerMock steve = player("Steve");
        steve.getInventory().setItemInMainHand(plugin.items().create(5));
        for (int i = 0; i < 5; i++) {
            rightClick(steve);
        }
        assertEquals(15, hearts(steve));
        assertEquals(30.0, maxHealth(steve));
        assertTrue(steve.getInventory().getItemInMainHand().getType().isAir(), "all 5 used");

        // Sneak + right-click uses only what fits.
        steve.getInventory().setItemInMainHand(plugin.items().create(10));
        steve.setSneaking(true);
        rightClick(steve);
        assertEquals(20, hearts(steve));
        assertEquals(5, steve.getInventory().getItemInMainHand().getAmount(), "5 used, 5 kept");
        messages(steve);
        steve.setSneaking(false);
        rightClick(steve);
        assertEquals(20, hearts(steve));
        assertEquals(5, steve.getInventory().getItemInMainHand().getAmount());
        assertTrue(has(messages(steve), "You already have the maximum of 20 hearts."));
    }

    @Test
    void oneClickIsOneHeart() {
        PlayerMock steve = player("Steve");
        steve.getInventory().setItemInMainHand(plugin.items().create(3));
        steve.getInventory().setItemInOffHand(plugin.items().create(3));
        clock.addAndGet(250);
        server.getPluginManager().callEvent(new PlayerInteractEvent(steve, Action.RIGHT_CLICK_AIR,
                steve.getInventory().getItemInMainHand(), null, BlockFace.SELF, EquipmentSlot.HAND));
        server.getPluginManager().callEvent(new PlayerInteractEvent(steve, Action.RIGHT_CLICK_AIR,
                steve.getInventory().getItemInOffHand(), null, BlockFace.SELF, EquipmentSlot.OFF_HAND));
        assertEquals(11, hearts(steve), "the off-hand event of the same click is ignored");
        assertEquals(3, steve.getInventory().getItemInOffHand().getAmount());
    }

    @Test
    void chestsOpenNormallyWithAHeartInHand() {
        PlayerMock steve = player("Steve");
        Block chest = world.getBlockAt(2, 64, 2);
        chest.setType(Material.CHEST);
        steve.getInventory().setItemInMainHand(plugin.items().create(2));
        clock.addAndGet(250);
        PlayerInteractEvent click = new PlayerInteractEvent(steve, Action.RIGHT_CLICK_BLOCK,
                steve.getInventory().getItemInMainHand(), chest, BlockFace.UP, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(click);
        assertEquals(10, hearts(steve), "not used");
        assertEquals(org.bukkit.event.Event.Result.DENY, click.useItemInHand(), "never used as dye");
        assertTrue(click.useInteractedBlock() != org.bukkit.event.Event.Result.DENY, "the chest opens");

        Block grass = world.getBlockAt(3, 63, 3);
        grass.setType(Material.GRASS_BLOCK);
        clock.addAndGet(250);
        server.getPluginManager().callEvent(new PlayerInteractEvent(steve, Action.RIGHT_CLICK_BLOCK,
                steve.getInventory().getItemInMainHand(), grass, BlockFace.UP, EquipmentSlot.HAND));
        assertEquals(11, hearts(steve), "clicking the ground uses it");
    }

    @Test
    void heartsCannotDyeSheep() {
        PlayerMock steve = player("Steve");
        Sheep sheep = (Sheep) world.spawnEntity(new Location(world, 1, 64, 1), EntityType.SHEEP);
        steve.getInventory().setItemInMainHand(plugin.items().create(1));
        PlayerInteractEntityEvent event = new PlayerInteractEntityEvent(steve, sheep, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
        steve.getInventory().setItemInMainHand(new ItemStack(Material.RED_DYE));
        PlayerInteractEntityEvent dye = new PlayerInteractEntityEvent(steve, sheep, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(dye);
        assertFalse(dye.isCancelled(), "normal red dye still works");
    }

    @Test
    void heartsAreNotRedDyeInCrafting() {
        PlayerMock steve = player("Steve");
        org.bukkit.inventory.CraftingInventory table = (org.bukkit.inventory.CraftingInventory)
                server.createInventory(steve, org.bukkit.event.inventory.InventoryType.WORKBENCH);
        org.bukkit.inventory.InventoryView view = steve.openInventory(table);
        ItemStack[] matrix = new ItemStack[9];
        matrix[0] = plugin.items().create(1);
        matrix[1] = new ItemStack(Material.WHITE_WOOL);
        table.setMatrix(matrix);
        table.setResult(new ItemStack(Material.RED_WOOL));
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.PrepareItemCraftEvent(table, view, false));
        assertTrue(table.getResult() == null || table.getResult().getType().isAir(), "no red wool from a Heart");

        matrix[0] = new ItemStack(Material.RED_DYE);
        table.setMatrix(matrix);
        table.setResult(new ItemStack(Material.RED_WOOL));
        server.getPluginManager().callEvent(new org.bukkit.event.inventory.PrepareItemCraftEvent(table, view, false));
        assertEquals(Material.RED_WOOL, table.getResult().getType(), "normal red dye still crafts");
    }

    @Test
    void theRecipeMakesAHeart() {
        Recipe recipe = Bukkit.getRecipe(plugin.recipe().key());
        assertNotNull(recipe);
        ShapedRecipe shaped = (ShapedRecipe) recipe;
        assertEquals(List.of("DND", "DSD", "DND"), List.of(shaped.getShape()));
        assertEquals(Material.DIAMOND_BLOCK, shaped.getIngredientMap().get('D').getType());
        assertEquals(Material.NETHERITE_INGOT, shaped.getIngredientMap().get('N').getType());
        assertEquals(Material.NETHER_STAR, shaped.getIngredientMap().get('S').getType());
        assertTrue(plugin.items().isHeart(shaped.getResult()), "a real Heart, not red dye");
        assertEquals(1, shaped.getResult().getAmount());
    }

    // ---- /withdraw -------------------------------------------------------------------------------------------

    @Test
    void withdrawTurnsHeartsIntoItems() {
        PlayerMock steve = player("Steve");
        plugin.hearts().set(steve, 20, false);
        command(steve, "withdraw 17");
        assertEquals(3, hearts(steve));
        assertEquals(17, heartItems(steve));
        assertEquals(6.0, maxHealth(steve));
        assertTrue(has(messages(steve), "You withdrew 17 hearts. You now have 3 hearts."));
        command(steve, "withdraw 1");
        assertTrue(has(messages(steve), "You only have 3 hearts, so you cannot withdraw any."));
    }

    @Test
    void withdrawChecksTheAmount() {
        PlayerMock steve = player("Steve");
        plugin.hearts().set(steve, 20, false);
        command(steve, "withdraw 18");
        assertTrue(has(messages(steve), "You can withdraw at most 17 hearts at once."));
        for (String bad : List.of("abc", "0", "-1", "1.5", "+2")) {
            command(steve, "withdraw " + bad);
            assertTrue(has(messages(steve), "'" + bad + "' is not a valid amount. Use a whole number from 1 to 17."), bad);
        }
        command(steve, "withdraw 99999999999");
        assertTrue(has(messages(steve), "at most 17"));
        command(steve, "withdraw");
        assertTrue(has(messages(steve), "Usage: /withdraw <amount>"));
        assertEquals(20, hearts(steve), "nothing was taken");
        assertEquals(0, heartItems(steve));

        plugin.hearts().set(steve, 10, false);
        command(steve, "withdraw 8");
        assertTrue(has(messages(steve), "You must keep at least 3 hearts. You can withdraw up to 7 right now."));
        command(steve, "withdraw 1");
        assertTrue(has(messages(steve), "You withdrew 1 heart. You now have 9 hearts."));
        command(steve, "withdraw all");
        assertEquals(3, hearts(steve));
        assertEquals(7, heartItems(steve));
    }

    @Test
    void withdrawnHeartsThatDoNotFitAreDropped() {
        PlayerMock steve = player("Steve");
        // Every slot (MockBukkit would also use the armor and off-hand slots, a real server does not).
        for (int slot = 0; slot < steve.getInventory().getSize(); slot++) {
            steve.getInventory().setItem(slot, new ItemStack(Material.DIRT, 64));
        }
        command(steve, "withdraw 2");
        assertEquals(8, hearts(steve));
        List<String> lines = messages(steve);
        assertTrue(has(lines, "Your inventory is full, so 2 Hearts dropped at your feet."),
                lines + " / items: " + java.util.Arrays.toString(steve.getInventory().getContents()));
        int dropped = 0;
        for (Entity entity : world.getEntities()) {
            if (entity instanceof Item item && plugin.items().isHeart(item.getItemStack())) {
                dropped += item.getItemStack().getAmount();
            }
        }
        assertEquals(2, dropped);
    }

    // ---- commands -----------------------------------------------------------------------------------------------

    @Test
    void staffCommandsChangeHearts() {
        PlayerMock staff = player("Staff");
        staff.setOp(true);
        PlayerMock steve = player("Steve");
        staff.performCommand("lifesteal sethearts Steve 25");
        assertEquals(20, hearts(steve), "never above the maximum");
        staff.performCommand("lifesteal removehearts Steve 30");
        assertEquals(3, hearts(steve), "never below the minimum");
        staff.performCommand("lifesteal addhearts Steve 4");
        assertEquals(7, hearts(steve));
        assertTrue(has(messages(steve), "Your hearts were changed by staff. You now have 7 hearts."));
        staff.performCommand("lifesteal giveheart Steve 3");
        assertEquals(3, heartItems(steve));
        staff.performCommand("lifesteal addhearts Steve lots");
        assertTrue(has(messages(staff), "'lots' is not a valid number."));

        // Offline players get the change when they join.
        steve.disconnect();
        staff.performCommand("lifesteal sethearts Steve 15");
        assertEquals(15, plugin.hearts().hearts(steve.getUniqueId()));
        steve.reconnect();
        assertEquals(30.0, maxHealth(steve));

        PlayerMock alex = player("Alex");
        alex.performCommand("lifesteal sethearts Alex 20");
        assertEquals(10, hearts(alex), "players cannot use staff commands");
        assertTrue(has(messages(alex), "You do not have permission"));
    }

    @Test
    void heartsAndTopList() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        plugin.hearts().set(alex, 18, false);
        steve.performCommand("hearts");
        assertTrue(has(messages(steve), "You have 10 hearts."));
        steve.performCommand("hearts Alex");
        assertTrue(has(messages(steve), "Alex has 18 hearts."));
        steve.performCommand("hearts top");
        List<String> top = messages(steve);
        assertTrue(has(top, "1. Alex - 18 hearts"), top.toString());
        assertTrue(has(top, "2. Steve - 10 hearts"), top.toString());
    }

    @Test
    void resetCooldownsAndInfo() {
        PlayerMock staff = player("Staff");
        staff.setOp(true);
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        kill(steve, alex);
        staff.performCommand("lifesteal info Steve");
        List<String> info = messages(staff);
        assertTrue(has(info, "Hearts: 11"), info.toString());
        assertTrue(has(info, "Cannot steal from again yet: Alex"), info.toString());
        staff.performCommand("lifesteal resetcooldowns Steve");
        assertTrue(has(messages(staff), "Cleared 1 cooldown of Steve."));
        kill(steve, alex);
        assertEquals(12, hearts(steve));
    }

    @Test
    void worldsCanBeExcluded() throws Exception {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        String yaml = java.nio.file.Files.readString(file.toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml.replace("disabled-worlds: []", "disabled-worlds: [world]")
                .replace("lose-on-natural-death: false", "lose-on-natural-death: true"));
        assertEquals(List.of(), plugin.reload());
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        kill(steve, alex);
        assertEquals(10, hearts(steve));
        assertEquals(10, hearts(alex));
    }

    @Test
    void naturalDeathsCanCostAHeart() throws Exception {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        String yaml = java.nio.file.Files.readString(file.toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml.replace("lose-on-natural-death: false",
                "lose-on-natural-death: true"));
        plugin.reload();
        PlayerMock steve = player("Steve");
        steve.setHealth(0);
        steve.respawn();
        assertEquals(9, hearts(steve));
        assertTrue(has(messages(steve), "You lost a heart. You now have 9 hearts."));
    }
}
