package io.github.drepfy.combat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Arrow;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The combat timer and Ender Pearl cooldown on a simulated server. */
class CombatTest {

    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    /** The pearl cooldown shown on each player's hotbar (MockBukkit has no item cooldowns). */
    private final java.util.Map<String, Integer> overlay = new java.util.HashMap<>();
    private ServerMock server;
    private CombatPlugin plugin;
    private WorldMock world;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        world = server.addSimpleWorld("world");
        plugin = MockBukkit.load(CombatPlugin.class);
        plugin.setClock(clock::get);
        plugin.itemCooldown = (player, ticks) -> overlay.put(player.getName(), ticks);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    // ---- helpers ------------------------------------------------------------------------------------

    private PlayerMock player(String name) {
        PlayerMock player = server.addPlayer(name);
        player.teleport(new Location(world, 0.5, 64, 0.5));
        chat(player);
        bars(player);
        return player;
    }

    /** Runs server ticks, 50 ms each. */
    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            clock.addAndGet(50);
            server.getScheduler().performTicks(1);
        }
    }

    private void seconds(int seconds) {
        tick(seconds * 20);
    }

    private void hit(PlayerMock attacker, PlayerMock victim) {
        damage(new EntityDamageByEntityEvent(attacker, victim, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK)
                        .withCausingEntity(attacker).withDirectEntity(attacker).build(), 1.0));
    }

    private void damage(EntityDamageByEntityEvent event) {
        server.getPluginManager().callEvent(event);
    }

    private void kill(PlayerMock killer, PlayerMock victim) {
        hit(killer, victim);
        victim.setKiller(killer);
        victim.setHealth(0);
        victim.respawn();
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
        return bars.isEmpty() ? null : bars.get(bars.size() - 1);
    }

    private static boolean has(List<String> lines, String text) {
        return lines.stream().anyMatch(line -> line.contains(text));
    }

    private boolean inCombat(PlayerMock player) {
        return plugin.isInCombat(player);
    }

    private long left(PlayerMock player) {
        return CombatPlugin.seconds(plugin.combatRemaining(player));
    }

    private ProjectileLaunchEvent throwPearl(PlayerMock player) {
        EnderPearl pearl = (EnderPearl) world.spawnEntity(player.getLocation(), EntityType.ENDER_PEARL);
        pearl.setShooter(player);
        ProjectileLaunchEvent launch = new ProjectileLaunchEvent(pearl);
        server.getPluginManager().callEvent(launch);
        return launch;
    }

    private PlayerInteractEvent usePearl(PlayerMock player, EquipmentSlot hand) {
        ItemStack pearls = new ItemStack(Material.ENDER_PEARL, 16);
        if (hand == EquipmentSlot.HAND) {
            player.getInventory().setItemInMainHand(pearls);
        } else {
            player.getInventory().setItemInOffHand(pearls);
        }
        PlayerInteractEvent use = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, pearls, null, BlockFace.SELF, hand);
        server.getPluginManager().callEvent(use);
        return use;
    }

    // ---- the combat timer ---------------------------------------------------------------------------------

    @Test
    void aHitPutsBothPlayersInCombatAboveTheHotbar() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        hit(steve, alex);
        tick(1);
        assertEquals("⚔ Combat: 60s", lastBar(steve));
        assertEquals("⚔ Combat: 60s", lastBar(alex));
        assertTrue(has(chat(steve), "You are in combat."));
        seconds(15);
        assertEquals("⚔ Combat: 45s", lastBar(steve));
        seconds(35);
        assertEquals("⚔ Combat: 10s", lastBar(alex));
        seconds(10);
        assertFalse(inCombat(steve));
        assertEquals("", lastBar(steve), "the action bar disappears at 0");
        assertTrue(has(chat(steve), "You are no longer in combat."));
        seconds(3);
        assertEquals(List.of(), bars(steve), "nothing more is shown");
    }

    @Test
    void theBarCountsDownEverySecond() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        hit(steve, alex);
        tick(1);
        bars(steve);
        List<String> shown = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            seconds(1);
            shown.addAll(bars(steve));
        }
        assertEquals(List.of("⚔ Combat: 59s", "⚔ Combat: 58s", "⚔ Combat: 57s", "⚔ Combat: 56s", "⚔ Combat: 55s"),
                shown);
    }

    @Test
    void everyHitStartsTheSixtySecondsAgain() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        hit(steve, alex);
        seconds(40);
        assertEquals(20, left(alex));
        hit(alex, steve);
        tick(1);
        assertEquals(60, left(steve));
        assertEquals(60, left(alex));
    }

    @Test
    void mobsSelfDamageAndCancelledHitsDoNotCount() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        Zombie zombie = (Zombie) world.spawnEntity(new Location(world, 2, 64, 2), EntityType.ZOMBIE);
        damage(new EntityDamageByEntityEvent(zombie, steve, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MOB_ATTACK)
                        .withCausingEntity(zombie).withDirectEntity(zombie).build(), 1.0));
        Arrow own = (Arrow) world.spawnEntity(steve.getLocation(), EntityType.ARROW);
        own.setShooter(steve);
        damage(new EntityDamageByEntityEvent(own, steve, EntityDamageEvent.DamageCause.PROJECTILE,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.ARROW)
                        .withCausingEntity(steve).withDirectEntity(own).build(), 1.0));
        EntityDamageByEntityEvent blocked = new EntityDamageByEntityEvent(alex, steve,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, org.bukkit.damage.DamageSource.builder(
                        org.bukkit.damage.DamageType.PLAYER_ATTACK).withCausingEntity(alex).withDirectEntity(alex)
                        .build(), 1.0);
        blocked.setCancelled(true); // e.g. PvP is off in this area
        damage(blocked);
        tick(1);
        assertFalse(inCombat(steve));
        assertFalse(inCombat(alex));
    }

    @Test
    void arrowsTntWolvesAndCrystalsCount() {
        PlayerMock archer = player("Archer");
        PlayerMock target = player("Target");
        Arrow arrow = (Arrow) world.spawnEntity(target.getLocation(), EntityType.ARROW);
        arrow.setShooter(archer);
        damage(new EntityDamageByEntityEvent(arrow, target, EntityDamageEvent.DamageCause.PROJECTILE,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.ARROW)
                        .withDirectEntity(arrow).build(), 4.0));
        assertTrue(inCombat(archer), "bows");
        assertTrue(inCombat(target));

        PlayerMock bomber = player("Bomber");
        PlayerMock other = player("Other");
        org.bukkit.entity.TNTPrimed tnt = (org.bukkit.entity.TNTPrimed) world.spawnEntity(other.getLocation(),
                EntityType.TNT);
        tnt.setSource(bomber);
        damage(new EntityDamageByEntityEvent(tnt, other, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.EXPLOSION)
                        .withDirectEntity(tnt).build(), 6.0));
        assertTrue(inCombat(bomber), "TNT");

        PlayerMock crystalPvp = player("CrystalPvp");
        PlayerMock crystalVictim = player("CrystalVictim");
        org.bukkit.entity.EnderCrystal crystal = (org.bukkit.entity.EnderCrystal) world.spawnEntity(
                crystalVictim.getLocation(), EntityType.END_CRYSTAL);
        damage(new EntityDamageByEntityEvent(crystalPvp, crystal, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.PLAYER_ATTACK)
                        .withCausingEntity(crystalPvp).withDirectEntity(crystalPvp).build(), 1.0));
        damage(new EntityDamageByEntityEvent(crystal, crystalVictim, EntityDamageEvent.DamageCause.ENTITY_EXPLOSION,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.EXPLOSION)
                        .withDirectEntity(crystal).build(), 10.0));
        assertTrue(inCombat(crystalPvp), "end crystals");
        assertTrue(inCombat(crystalVictim));

        PlayerMock owner = player("Owner");
        PlayerMock bitten = player("Bitten");
        org.bukkit.entity.Wolf wolf = (org.bukkit.entity.Wolf) world.spawnEntity(bitten.getLocation(), EntityType.WOLF);
        wolf.setOwner(owner);
        damage(new EntityDamageByEntityEvent(wolf, bitten, EntityDamageEvent.DamageCause.ENTITY_ATTACK,
                org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.MOB_ATTACK)
                        .withCausingEntity(wolf).withDirectEntity(wolf).build(), 2.0));
        assertTrue(inCombat(owner), "tamed wolves");
    }

    @Test
    void nothingAPlayerDoesEndsCombat() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        hit(steve, alex);
        seconds(5);
        // Commands are never blocked, and do not end combat.
        PlayerCommandPreprocessEvent command = new PlayerCommandPreprocessEvent(steve, "/spawn");
        server.getPluginManager().callEvent(command);
        assertFalse(command.isCancelled(), "every command works in combat");
        steve.performCommand("combat");
        assertTrue(has(chat(steve), "Combat: 55s left"));
        // Shops and menus open normally.
        org.bukkit.inventory.InventoryView shop = steve.openInventory(server.createInventory(null, 54, "Market"));
        assertTrue(shop != null && steve.getOpenInventory().getTopInventory().getSize() == 54, "menus open");
        steve.closeInventory();
        // Swapping items, teleporting and changing worlds.
        steve.getInventory().setHeldItemSlot(4);
        steve.teleport(new Location(world, 5000, 80, 5000));
        WorldMock nether = server.addSimpleWorld("world_nether");
        steve.teleport(new Location(nether, 0, 64, 0));
        tick(1);
        assertTrue(inCombat(steve));
        assertEquals(55, left(steve), "the timer just keeps running");
    }

    @Test
    void loggingOutPausesTheTimer() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        hit(steve, alex);
        seconds(10);
        assertEquals(50, left(steve));
        steve.disconnect();
        seconds(300);
        assertTrue(inCombat(alex) == false, "Alex's own timer ran out normally");
        steve.reconnect();
        assertEquals(50, left(steve), "still 50 seconds after 5 minutes offline");
        assertTrue(has(chat(steve), "You are still in combat: 50s left."));
        tick(1);
        assertEquals("⚔ Combat: 50s", lastBar(steve));
        seconds(50);
        assertFalse(inCombat(steve));
    }

    @Test
    void loggingOutCanKill() throws Exception {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        String yaml = java.nio.file.Files.readString(file.toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml.replace("logout: keep", "logout: kill"));
        assertEquals(List.of(), plugin.reload());
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        hit(alex, steve);
        steve.disconnect();
        assertTrue(steve.isDead(), "logged out in combat: dead");
        assertTrue(has(chat(alex), "Steve logged out during combat and died."));
        steve.reconnect();
        assertFalse(inCombat(steve), "dead players are out of combat");

        // Kicked players are not killed.
        PlayerMock sam = player("Sam");
        hit(alex, sam);
        sam.kick();
        assertFalse(sam.isDead());
    }

    @Test
    void harmfulPotionsCountHealingDoesNot() {
        PlayerMock witch = player("Witch");
        PlayerMock victim = player("Victim");
        PlayerMock friend = player("Friend");
        server.getPluginManager().callEvent(splash(witch, victim, org.bukkit.potion.PotionType.POISON));
        assertTrue(inCombat(witch), "a poison potion is an attack");
        assertTrue(inCombat(victim));
        server.getPluginManager().callEvent(splash(witch, friend, org.bukkit.potion.PotionType.HEALING));
        assertFalse(inCombat(friend), "healing a teammate is not");
    }

    private org.bukkit.event.entity.PotionSplashEvent splash(PlayerMock thrower, PlayerMock target,
                                                               org.bukkit.potion.PotionType type) {
        org.bukkit.entity.ThrownPotion potion = (org.bukkit.entity.ThrownPotion) world.spawnEntity(target.getLocation(),
                EntityType.POTION);
        ItemStack item = new ItemStack(Material.SPLASH_POTION);
        org.bukkit.inventory.meta.PotionMeta meta = (org.bukkit.inventory.meta.PotionMeta) item.getItemMeta();
        meta.setBasePotionType(type);
        item.setItemMeta(meta);
        potion.setItem(item);
        potion.setShooter(thrower);
        return new org.bukkit.event.entity.PotionSplashEvent(potion, target, null, null,
                java.util.Map.of(target, 1.0));
    }

    @Test
    void combatAndPearlsSurviveARestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) {
        java.util.UUID steve = java.util.UUID.randomUUID();
        java.util.UUID alex = java.util.UUID.randomUUID();
        long now = 1_000_000L;
        CombatTracker tracker = new CombatTracker();
        tracker.tag(steve, alex, now, 60_000);
        tracker.tag(steve, CombatTracker.STAFF, now, 20_000);
        tracker.pause(steve, now + 15_000); // logged out with 45 seconds left
        java.util.Map<java.util.UUID, Long> pearls = new java.util.HashMap<>(java.util.Map.of(alex, now + 40_000,
                steve, now + 20_000));
        DataFile file = new DataFile(dir.resolve("data.yml"), java.util.logging.Logger.getLogger("test"));
        file.write(file.snapshot(tracker, pearls, now + 30_000));

        CombatTracker loaded = new CombatTracker();
        java.util.Map<java.util.UUID, Long> loadedPearls = new java.util.HashMap<>();
        long later = now + 3_600_000;
        file.load(loaded, loadedPearls, later);
        assertEquals(45_000, loaded.remaining(steve, later), "still 45 seconds after the restart");
        loaded.resume(steve, later + 1000);
        assertEquals(45_000, loaded.remaining(steve, later + 1000), "the timer starts when they join");
        assertEquals(java.util.Map.of(alex, 45_000L, CombatTracker.STAFF, 5_000L), loaded.opponents(steve, later + 1000));
        assertEquals(now + 40_000, loadedPearls.get(alex), "pearl cooldowns are kept as a time of day");
        assertFalse(loadedPearls.containsKey(steve), "finished cooldowns are not kept");
    }

    @Test
    void badSettingsFallBack() throws Exception {
        org.bukkit.configuration.file.YamlConfiguration yaml = new org.bukkit.configuration.file.YamlConfiguration();
        yaml.loadFromString("""
                combat: {duration: soon, armor: shiny, logout: vanish}
                ender-pearl: {cooldown: "-5s", show-on-item: maybe}
                """);
        io.github.drepfy.combat.config.Settings settings = io.github.drepfy.combat.config.SettingsLoader.load(yaml);
        assertEquals(60_000, settings.combatMs());
        assertEquals(15_000, settings.pearlMs());
        assertEquals(io.github.drepfy.combat.config.Settings.ArmorRule.ARMOR_PIECES, settings.armor());
        assertEquals(io.github.drepfy.combat.config.Settings.LogoutRule.KEEP, settings.logout());
        assertEquals(5, settings.warnings().size(), settings.warnings().toString());
        assertEquals(List.of(), io.github.drepfy.combat.config.SettingsLoader.load(
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(
                        CombatTest.class.getResourceAsStream("/config.yml"),
                        java.nio.charset.StandardCharsets.UTF_8))).warnings(), "the bundled config is clean");
    }

    // ---- the kill rule -----------------------------------------------------------------------------------

    @Test
    void killingAnArmoredPlayerEndsYourCombat() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        alex.getInventory().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
        hit(alex, steve);
        seconds(5);
        chat(steve);
        kill(steve, alex);
        tick(1);
        assertFalse(inCombat(steve), "not in combat because of the kill");
        assertFalse(inCombat(alex), "the dead are not in combat");
        assertEquals("", lastBar(steve));
        assertTrue(has(chat(steve), "You are no longer in combat."));
    }

    @Test
    void killingANakedPlayerKeepsYouInCombat() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        hit(alex, steve);
        seconds(5);
        kill(steve, alex);
        tick(1);
        assertTrue(inCombat(steve));
        assertEquals(60, left(steve), "the killing hit started 60 seconds");
        assertEquals("⚔ Combat: 60s", lastBar(steve));
    }

    @Test
    void aOneHitKillOfAnArmoredPlayerNeverStartsCombat() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        alex.getInventory().setBoots(new ItemStack(Material.LEATHER_BOOTS));
        kill(steve, alex);
        tick(1);
        assertFalse(inCombat(steve));
        assertEquals(List.of(), bars(steve), "no bar at all");
        assertFalse(has(chat(steve), "combat"), "no messages");
    }

    @Test
    void onlyRealArmorCountsByDefault() throws Exception {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        alex.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
        alex.getInventory().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
        kill(steve, alex);
        tick(1);
        assertTrue(inCombat(steve), "an elytra and a pumpkin are not armor");

        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        String yaml = java.nio.file.Files.readString(file.toPath());
        java.nio.file.Files.writeString(file.toPath(), yaml.replace("armor: armor-pieces", "armor: any-item"));
        plugin.reload();
        PlayerMock sam = player("Sam");
        PlayerMock kim = player("Kim");
        kim.getInventory().setChestplate(new ItemStack(Material.ELYTRA));
        kill(sam, kim);
        tick(1);
        assertFalse(inCombat(sam), "with any-item, everything worn counts");
    }

    @Test
    void killingOneOpponentDoesNotEndAnotherFight() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        PlayerMock sam = player("Sam");
        alex.getInventory().setHelmet(new ItemStack(Material.DIAMOND_HELMET));
        hit(sam, steve);
        seconds(20);
        kill(steve, alex);
        tick(1);
        assertTrue(inCombat(steve), "still fighting Sam");
        assertEquals(40, left(steve), "the time from the fight with Sam");
    }

    // ---- Ender Pearls ------------------------------------------------------------------------------------------

    @Test
    void enderPearlsHaveAFifteenSecondCooldown() {
        PlayerMock steve = player("Steve");
        assertFalse(throwPearl(steve).isCancelled());
        assertEquals(15, CombatPlugin.seconds(plugin.pearlCooldown(steve)));
        assertEquals(300, overlay.get("Steve"), "the grey sweep on the pearl: 15 seconds");
        tick(1);
        assertEquals(299, overlay.get("Steve"), "re-sent a tick later, over the game's own 1 second");

        PlayerInteractEvent use = usePearl(steve, EquipmentSlot.HAND);
        assertEquals(Event.Result.DENY, use.useItemInHand(), "the throw is refused");
        assertTrue(has(chat(steve), "You can use an Ender Pearl again in 15s."));
        // Other hand, another hotbar slot: the cooldown is the player's, not the item's.
        steve.getInventory().setHeldItemSlot(7);
        assertEquals(Event.Result.DENY, usePearl(steve, EquipmentSlot.OFF_HAND).useItemInHand());
        // A pearl launched anyway (another plugin, a hacked client) is removed.
        assertTrue(throwPearl(steve).isCancelled());

        seconds(14);
        assertTrue(throwPearl(steve).isCancelled(), "still 1 second left");
        seconds(1);
        assertFalse(throwPearl(steve).isCancelled(), "15 seconds later it works");
        assertEquals(15, CombatPlugin.seconds(plugin.pearlCooldown(steve)), "and the cooldown starts again");
    }

    @Test
    void reconnectingDoesNotResetThePearlCooldown() {
        PlayerMock steve = player("Steve");
        throwPearl(steve);
        seconds(5);
        steve.disconnect();
        seconds(3);
        steve.reconnect();
        assertEquals(7, CombatPlugin.seconds(plugin.pearlCooldown(steve)));
        assertEquals(140, overlay.get("Steve"), "shown again on the pearl: 7 seconds");
        assertEquals(Event.Result.DENY, usePearl(steve, EquipmentSlot.HAND).useItemInHand());
        seconds(7);
        assertFalse(throwPearl(steve).isCancelled());
    }

    @Test
    void commandsCannotResetTheCooldown() {
        PlayerMock steve = player("Steve");
        throwPearl(steve);
        steve.performCommand("combat");
        List<String> status = chat(steve);
        assertTrue(has(status, "Ender Pearl: 15s"), status.toString());
        overlay.clear(); // e.g. another plugin or the client clears the item cooldown
        assertTrue(throwPearl(steve).isCancelled(), "the server still refuses");
    }

    // ---- elytra, riptide and pearls in combat --------------------------------------------------------------------

    private boolean glide(PlayerMock player) {
        org.bukkit.event.entity.EntityToggleGlideEvent glide = new org.bukkit.event.entity.EntityToggleGlideEvent(player, true);
        server.getPluginManager().callEvent(glide);
        return !glide.isCancelled();
    }

    @Test
    void noElytraNearSomeoneYouAreFighting() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        assertTrue(glide(steve), "out of combat an elytra works");
        hit(alex, steve);
        alex.teleport(new Location(world, 10.5, 64, 0.5));
        assertFalse(glide(steve), "Alex is 10 blocks away");
        assertTrue(has(chat(steve), "You cannot glide with an elytra in combat."));
        alex.teleport(new Location(world, 20.5, 64, 0.5));
        assertTrue(glide(steve), "20 blocks away: allowed (radius 15)");

        // Someone already gliding comes down when the fight gets close.
        steve.setGliding(true);
        alex.teleport(new Location(world, 5.5, 64, 0.5));
        tick(5);
        assertFalse(steve.isGliding());
    }

    @Test
    void radiusZeroBlocksTheElytraForTheWholeCombat() throws Exception {
        java.io.File file = new java.io.File(plugin.getDataFolder(), "config.yml");
        String yaml = java.nio.file.Files.readString(file.toPath());
        String edited = yaml.replaceFirst("(elytra:\\R\\s+blocked: true\\R(?:\\s*#[^\\n]*\\R)?\\s+)radius: 15",
                "$1radius: 0");
        assertFalse(edited.equals(yaml), "the config was edited");
        java.nio.file.Files.writeString(file.toPath(), edited);
        assertEquals(List.of(), plugin.reload());
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        hit(alex, steve);
        alex.teleport(new Location(world, 500.5, 64, 0.5));
        assertFalse(glide(steve), "far away, still in combat");
    }

    @Test
    void noRiptideNearSomeoneYouAreFighting() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        ItemStack riptide = new ItemStack(Material.TRIDENT);
        riptide.addUnsafeEnchantment(org.bukkit.enchantments.Enchantment.RIPTIDE, 3);
        ItemStack plain = new ItemStack(Material.TRIDENT);
        assertEquals(Event.Result.DEFAULT, useTrident(steve, riptide), "out of combat it works");
        hit(alex, steve);
        assertEquals(Event.Result.DENY, useTrident(steve, riptide));
        assertTrue(has(chat(steve), "You cannot use riptide in combat."));
        assertEquals(Event.Result.DEFAULT, useTrident(steve, plain), "throwing a trident at someone is fine");
        alex.teleport(new Location(world, 30.5, 64, 0.5));
        assertEquals(Event.Result.DEFAULT, useTrident(steve, riptide), "30 blocks away");
    }

    private Event.Result useTrident(PlayerMock player, ItemStack trident) {
        player.getInventory().setItemInMainHand(trident);
        PlayerInteractEvent use = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, trident, null,
                BlockFace.SELF, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(use);
        return use.useItemInHand();
    }

    @Test
    void anEnderPearlStartsTheSixtySecondsAgain() {
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        hit(steve, alex);
        seconds(30);
        assertEquals(30, left(steve));
        throwPearl(steve);
        assertEquals(60, left(steve), "back to 60");
        assertEquals(30, left(alex), "only for the one who threw it");

        PlayerMock sam = player("Sam");
        throwPearl(sam);
        assertFalse(inCombat(sam), "a pearl out of combat does not start combat");
    }

    // ---- safe zones -----------------------------------------------------------------------------------------------

    private PlayerMock spawnZone() {
        PlayerMock staff = player("Staff");
        staff.setOp(true);
        staff.teleport(new Location(world, 0.5, 64, 0.5));
        staff.performCommand("combat zone create spawn 10");
        assertTrue(has(chat(staff), "Safe zone created: spawn (world, -10 -10 to 10 10)"));
        staff.teleport(new Location(world, 500.5, 64, 500.5));
        return staff;
    }

    private boolean move(PlayerMock player, double x, double z) {
        Location from = player.getLocation();
        Location to = new Location(world, x, 64, z);
        org.bukkit.event.player.PlayerMoveEvent move = new org.bukkit.event.player.PlayerMoveEvent(player, from, to);
        server.getPluginManager().callEvent(move);
        if (!move.isCancelled()) {
            player.setLocation(to);
        }
        return !move.isCancelled();
    }

    @Test
    void playersInCombatCannotEnterSpawn() {
        spawnZone();
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        steve.teleport(new Location(world, 11.5, 64, 0.5));
        alex.teleport(new Location(world, 30.5, 64, 0.5));
        assertTrue(move(steve, 10.5, 0.5), "out of combat anyone walks in");
        assertTrue(move(steve, 11.5, 0.5), "and out");
        hit(alex, steve);
        chat(steve);
        assertFalse(move(steve, 10.5, 0.5), "in combat: refused at the border");
        assertTrue(has(chat(steve), "You cannot enter spawn in combat. (60s left)"));
        assertTrue(steve.getVelocity().getX() > 0, "pushed back out");
        assertTrue(move(steve, 11.5, 30.5), "walking along outside is fine");

        // Ender Pearls, /spawn, portals...: every teleport into the zone.
        org.bukkit.event.player.PlayerTeleportEvent pearl = new org.bukkit.event.player.PlayerTeleportEvent(steve,
                steve.getLocation(), new Location(world, 0.5, 64, 0.5),
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.ENDER_PEARL);
        server.getPluginManager().callEvent(pearl);
        assertTrue(pearl.isCancelled());
        org.bukkit.event.player.PlayerTeleportEvent command = new org.bukkit.event.player.PlayerTeleportEvent(steve,
                steve.getLocation(), new Location(world, 0.5, 64, 0.5),
                org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.COMMAND);
        server.getPluginManager().callEvent(command);
        assertTrue(command.isCancelled(), "/spawn while in combat");

        seconds(60);
        assertTrue(move(steve, 10.5, 0.5), "after combat it is open again");
    }

    @Test
    void playersAlreadyInsideCanStayAndLeave() {
        spawnZone();
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        steve.teleport(new Location(world, 5.5, 64, 0.5));
        hit(alex, steve);
        assertTrue(move(steve, 6.5, 0.5), "moving inside");
        assertTrue(move(steve, 12.5, 0.5), "walking out");
        assertFalse(move(steve, 9.5, 0.5), "but not back in");
    }

    @Test
    void ridingIntoSpawnTakesThePlayerOff() {
        spawnZone();
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        org.bukkit.entity.Boat boat = (org.bukkit.entity.Boat) world.spawnEntity(new Location(world, 11.5, 64, 0.5),
                EntityType.OAK_BOAT);
        steve.teleport(new Location(world, 11.5, 64, 0.5));
        boat.addPassenger(steve);
        hit(alex, steve);
        server.getPluginManager().callEvent(new org.bukkit.event.vehicle.VehicleMoveEvent(boat,
                new Location(world, 11.5, 64, 0.5), new Location(world, 10.5, 64, 0.5)));
        assertFalse(boat.getPassengers().contains(steve), "taken off the boat");
        assertFalse(plugin.zones().get("spawn").contains(steve.getLocation()), "and left outside");
        assertTrue(has(chat(steve), "You cannot enter spawn in combat."));
    }

    @Test
    void zonesAreMadeBetweenTwoCornersAndKept() {
        PlayerMock staff = player("Staff");
        staff.setOp(true);
        staff.teleport(new Location(world, -20.5, 70, 15.5));
        staff.performCommand("combat zone pos1");
        staff.teleport(new Location(world, 40.5, 64, -5.5));
        staff.performCommand("combat zone pos2");
        staff.performCommand("combat zone create Spawn");
        assertTrue(has(chat(staff), "Safe zone created: Spawn (world, -21 -6 to 40 15)"));
        staff.performCommand("combat zone list");
        assertTrue(has(chat(staff), "Spawn (world, -21 -6 to 40 15)"));

        ZoneStore reloaded = new ZoneStore(plugin.getDataFolder().toPath().resolve("zones.yml"),
                java.util.logging.Logger.getLogger("test"));
        assertEquals(1, reloaded.all().size(), "saved in zones.yml");
        assertTrue(reloaded.at(new Location(world, 40.9, 200, 15.9)) != null, "corners are inside, any height");
        assertTrue(reloaded.at(new Location(world, 41.1, 64, 0)) == null);

        staff.performCommand("combat zone delete spawn");
        assertTrue(has(chat(staff), "Safe zone spawn deleted."));
        assertTrue(plugin.zones().all().isEmpty());

        PlayerMock player = player("Player");
        player.performCommand("combat zone create mine 5");
        assertTrue(plugin.zones().all().isEmpty(), "only staff");
    }

    @Test
    void zoneEdges() {
        SafeZone zone = SafeZone.of("spawn", "world", 10, 10, -10, -10);
        assertTrue(zone.contains(-10.0, 10.99));
        assertFalse(zone.contains(11.0, 0));
        assertEquals(0.0, zone.distance(0, 0));
        assertEquals(4.0, zone.distance(15, 0), 1e-9);
        assertEquals(5.0, zone.distance(-13, 15), 1e-9);
    }

    // ---- staff ---------------------------------------------------------------------------------------------------

    @Test
    void staffCanCheckTagAndUntag() {
        PlayerMock staff = player("Staff");
        staff.setOp(true);
        PlayerMock steve = player("Steve");
        PlayerMock alex = player("Alex");
        hit(alex, steve);
        tick(1);
        staff.performCommand("combat info Steve");
        List<String> info = chat(staff);
        assertTrue(has(info, "Combat: 60s left"), info.toString());
        assertTrue(has(info, "In combat with: Alex (60s)"), info.toString());
        staff.performCommand("combat untag Steve");
        assertFalse(inCombat(steve));
        assertTrue(has(chat(steve), "You are no longer in combat."));
        staff.performCommand("combat tag Steve 30s");
        assertEquals(30, left(steve));

        steve.performCommand("combat untag Steve");
        assertTrue(inCombat(steve), "players cannot untag themselves");
        assertTrue(has(chat(steve), "You do not have permission"));
    }
}
