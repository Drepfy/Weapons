package io.github.drepfy.vigil;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.model.Physics;
import io.github.drepfy.vigil.moderation.Punishment;
import io.github.drepfy.vigil.util.Clock;
import io.papermc.paper.event.packet.ClientTickEndEvent;
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end scenarios on a simulated server: legitimate play must never be flagged,
 * common cheats must be flagged, and enough flags must lead to an automatic ban.
 * Players send Paper client-tick-end events like a modern client does.
 */
class AntiCheatScenarioTest {

    private final AtomicLong clock = new AtomicLong(1_000_000);
    private final List<PlayerMock> ticking = new ArrayList<>();
    private ServerMock server;
    private VigilPlugin plugin;
    private WorldMock world;
    private int groundY;

    @BeforeEach
    void setUp() {
        Clock.setTestSource(clock::get);
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VigilPlugin.class);
        world = server.addSimpleWorld("test");
        for (int cx = -4; cx <= 12; cx++) {
            for (int cz = -4; cz <= 4; cz++) {
                world.loadChunk(cx, cz);
            }
        }
        groundY = 0;
        while (world.getType(0, groundY, 0) != Material.AIR && groundY < 100) {
            groundY++;
        }
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
        Clock.setTestSource(null);
    }

    // ---- harness ------------------------------------------------------------------------------

    private void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            clock.addAndGet(50);
            for (PlayerMock player : ticking) {
                if (player.isOnline()) {
                    server.getPluginManager().callEvent(new ClientTickEndEvent(player));
                }
            }
            server.getScheduler().performTicks(1);
        }
    }

    private PlayerMock join(String name, double x, double y, double z) {
        PlayerMock player = server.addPlayer(name);
        player.setGameMode(GameMode.SURVIVAL);
        player.teleport(new Location(world, x, y, z));
        player.setOnGround(y == groundY);
        ticking.add(player);
        tick(80); // join and teleport grace
        return player;
    }

    private PlayerData data(PlayerMock player) {
        return plugin.players().get(player);
    }

    private static int flags(PlayerData data, CheckType type) {
        return data.violation(type).totalFlags();
    }

    private static int totalFlags(PlayerData data) {
        int total = 0;
        for (CheckType type : CheckType.values()) {
            total += data.violation(type).totalFlags();
        }
        return total;
    }

    private static String describe(PlayerData data) {
        StringBuilder text = new StringBuilder();
        data.recentFlags().forEach(flag -> text.append("\n  ").append(flag.toLine()));
        return text.toString();
    }

    private void move(PlayerMock player, Location to, boolean onGround) {
        player.setOnGround(onGround);
        player.simulatePlayerMove(to);
        tick(1);
    }

    private void swing(PlayerMock player) {
        server.getPluginManager().callEvent(new PlayerAnimationEvent(player, PlayerAnimationType.ARM_SWING));
    }

    private void attack(PlayerMock attacker, Entity target, boolean swing) {
        server.getPluginManager().callEvent(new PrePlayerAttackEntityEvent(attacker, target, true));
        if (swing) {
            swing(attacker);
        }
    }

    private List<String> messages(PlayerMock player) {
        List<String> result = new ArrayList<>();
        String message;
        while ((message = player.nextMessage()) != null) {
            result.add(org.bukkit.ChatColor.stripColor(message));
        }
        return result;
    }

    // ---- legitimate play ----------------------------------------------------------------------

    @Test
    void legitimateWalkingSprintingAndJumpingIsNeverFlagged() {
        PlayerMock player = join("Legit", 0.5, groundY, 0.5);
        PlayerData data = data(player);
        Location at = player.getLocation();
        // One minute of sprinting with a vanilla jump every 20 ticks.
        for (int cycle = 0; cycle < 60; cycle++) {
            double velocity = Physics.DEFAULT_JUMP_STRENGTH;
            double y = groundY;
            for (int t = 0; t < 20; t++) {
                boolean inAir = t < 12;
                if (inAir) {
                    y += velocity;
                    velocity = (velocity - Physics.GRAVITY) * Physics.VERTICAL_DRAG;
                    if (y <= groundY) {
                        y = groundY;
                        inAir = false;
                    }
                } else {
                    y = groundY;
                }
                double dx = t == 0 ? 0.48 : 0.28;
                double nextX = at.getX() + dx;
                if (nextX > 150) {
                    nextX = 0.5; // stay inside the loaded chunks
                    at = new Location(world, nextX, y, at.getZ());
                    player.teleport(at);
                    tick(30);
                    continue;
                }
                at = new Location(world, nextX, y, at.getZ(), at.getYaw(), at.getPitch());
                move(player, at, !inAir);
            }
        }
        assertEquals(0, totalFlags(data), "legit movement was flagged:" + describe(data));
        assertTrue(player.isOnline());
    }

    @Test
    void legitimateCombatIsNeverFlagged() {
        PlayerMock attacker = join("Fighter", 0.5, groundY, 0.5);
        PlayerMock target = join("Target", 0.5, groundY, 3.0);
        tick(40);
        attacker.setRotation(0, 10); // yaw 0 looks towards +Z, where the target stands
        tick(1);
        for (int i = 0; i < 60; i++) {
            attack(attacker, target, true);
            tick(10);
        }
        PlayerData data = data(attacker);
        assertEquals(0, totalFlags(data), "legit combat was flagged:" + describe(data));
    }

    @Test
    void jumpingAgainstAWallAndClimbingStairsIsNeverFlagged() {
        // A wall along x = 3 and a staircase of full blocks towards +X further along.
        for (int y = groundY; y < groundY + 4; y++) {
            for (int z = -3; z <= 3; z++) {
                world.getBlockAt(3, y, z).setType(Material.STONE);
            }
        }
        PlayerMock player = join("Climber", 2.69, groundY, 0.5); // touching the wall
        PlayerData data = data(player);
        for (int jump = 0; jump < 20; jump++) {
            double velocity = Physics.DEFAULT_JUMP_STRENGTH;
            double y = groundY;
            for (int t = 0; t < 14; t++) {
                y = Math.max(groundY, y + velocity);
                velocity = (velocity - Physics.GRAVITY) * Physics.VERTICAL_DRAG;
                move(player, new Location(world, 2.69, y, 0.5 + (jump % 2 == 0 ? 0.01 : -0.01) * t), y == groundY);
            }
            tick(3);
        }
        // Walk up steps (a jump onto every block).
        for (int step = 0; step < 4; step++) {
            for (int z = -3; z <= 3; z++) {
                world.getBlockAt(10 + step, groundY + step, z).setType(Material.STONE);
            }
        }
        player.teleport(new Location(world, 8.5, groundY, 0.5));
        tick(30);
        double x = 8.5;
        double base = groundY;
        for (int step = 0; step < 4; step++) {
            double velocity = Physics.DEFAULT_JUMP_STRENGTH;
            double y = base;
            double top = base + 1;
            for (int t = 0; t < 12; t++) {
                y += velocity;
                velocity = (velocity - Physics.GRAVITY) * Physics.VERTICAL_DRAG;
                x += 0.2;
                boolean landed = velocity < 0 && y <= top && x > 10 + step - 0.3;
                if (landed) {
                    y = top;
                }
                move(player, new Location(world, x, y, 0.5), landed);
                if (landed) {
                    break;
                }
            }
            base = y;
            x = Math.max(x, 10 + step + 0.5);
            move(player, new Location(world, x, base, 0.5), true);
        }
        assertEquals(0, totalFlags(data), "legit jumping was flagged:" + describe(data));
    }

    @Test
    void legitimateBridgingIsNotFlaggedButScaffoldIs() {
        for (int x = -3; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.getBlockAt(x, groundY + 10, z).setType(Material.STONE);
            }
        }
        PlayerMock builder = join("Bridger", 0.5, groundY + 11, 0.5);
        PlayerData data = data(builder);
        // Legit: standing at the north edge (eyes past the face) and clicking the north face.
        for (int i = 0; i < 10; i++) {
            builder.teleport(new Location(world, 0.5, groundY + 11, -3.25, 180, 80));
            tick(25);
            place(builder, world.getBlockAt(0, groundY + 10, -3), world.getBlockAt(0, groundY + 10, -4));
            world.getBlockAt(0, groundY + 10, -4).setType(Material.AIR);
        }
        assertEquals(0, flags(data, CheckType.INTERACT), describe(data));
        // Scaffold: placing against the north face while standing in the middle of the block.
        for (int i = 0; i < 10; i++) {
            builder.teleport(new Location(world, 0.5, groundY + 11, -2.5, 180, 80));
            tick(25);
            place(builder, world.getBlockAt(0, groundY + 10, -3), world.getBlockAt(0, groundY + 10, -4));
            world.getBlockAt(0, groundY + 10, -4).setType(Material.AIR);
        }
        assertTrue(flags(data, CheckType.INTERACT) >= 2, describe(data));
    }

    private void place(PlayerMock player, org.bukkit.block.Block against, org.bukkit.block.Block placed) {
        org.bukkit.block.BlockState replaced = placed.getState();
        placed.setType(Material.COBBLESTONE);
        server.getPluginManager().callEvent(new org.bukkit.event.block.BlockPlaceEvent(placed, replaced, against,
                new org.bukkit.inventory.ItemStack(Material.COBBLESTONE), player, true,
                org.bukkit.inventory.EquipmentSlot.HAND));
    }

    @Test
    void hittingAStrafingTargetIsNotFlagged() {
        PlayerMock attacker = join("Tracker", 0.5, groundY, 0.5);
        PlayerMock target = join("Strafer", 0.5, groundY, 3.0);
        PlayerData data = data(attacker);
        double tx = 0.5;
        double direction = 0.28;
        List<Double> path = new ArrayList<>();
        for (int t = 0; t < 400; t++) {
            if (Math.abs(tx - 0.5) > 2.0) {
                direction = -direction;
            }
            tx += direction;
            path.add(tx);
            move(target, new Location(world, tx, groundY, 3.0), true);
            // The attacker aims at where the target was two ticks ago (what its client shows).
            double seenX = path.get(Math.max(0, path.size() - 3));
            double yaw = Math.toDegrees(Math.atan2(-(seenX - 0.5), 3.0 - 0.5));
            attacker.setRotation((float) yaw, 20);
            if (t % 8 == 0) {
                attack(attacker, target, true);
            }
        }
        assertEquals(0, totalFlags(data), "legit tracking was flagged:" + describe(data));
    }

    // ---- cheats -------------------------------------------------------------------------------

    @Test
    void flyingIsFlaggedAndAutoBanned() throws Exception {
        PlayerMock staff = join("Staff", 0.5, groundY, 20.5);
        staff.setOp(true);
        messages(staff);
        PlayerMock flyer = join("Flyer", 0.5, 80, 0.5);
        PlayerData data = data(flyer);
        Location at = flyer.getLocation();
        for (int i = 0; i < 600 && flyer.isOnline(); i++) {
            at = at.clone().add(0.2, 0, 0);
            move(flyer, at, false);
        }
        tick(2);
        assertTrue(flags(data, CheckType.FLIGHT) >= 8, "flight flags: " + describe(data));
        assertFalse(flyer.isOnline(), "the flyer should have been kicked");
        Punishment ban = plugin.moderation().activeBan(flyer.getUniqueId());
        assertNotNull(ban, "the flyer should be banned");
        assertEquals("Cheating (Flying)", ban.reason());
        assertEquals("Anti-Cheat", ban.staff());

        List<String> staffMessages = messages(staff);
        assertTrue(staffMessages.stream().anyMatch(m -> m.contains("Flyer has been flagged for Flying")),
                staffMessages.toString());
        assertTrue(staffMessages.stream().anyMatch(m -> m.contains("Flyer has been banned for Flying")),
                staffMessages.toString());

        // The ban is enforced at login.
        AtomicReference<AsyncPlayerPreLoginEvent> login = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            AsyncPlayerPreLoginEvent event = new AsyncPlayerPreLoginEvent("Flyer", InetAddress.getLoopbackAddress(),
                    flyer.getUniqueId());
            server.getPluginManager().callEvent(event);
            login.set(event);
        });
        thread.start();
        thread.join();
        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, login.get().getLoginResult());
    }

    @Test
    void speedIsFlagged() {
        PlayerMock speeder = join("Speeder", 0.5, groundY, 0.5);
        PlayerData data = data(speeder);
        Location at = speeder.getLocation();
        for (int i = 0; i < 200 && speeder.isOnline(); i++) {
            at = at.clone().add(0.6, 0, 0);
            move(speeder, at, true);
        }
        assertTrue(flags(data, CheckType.SPEED) >= 2, "speed flags: " + describe(data));
    }

    @Test
    void jesusWalkingOnWaterIsFlagged() {
        for (int x = -2; x <= 60; x++) {
            for (int z = -3; z <= 3; z++) {
                world.getBlockAt(x, groundY - 1, z).setType(Material.WATER);
                world.getBlockAt(x, groundY - 2, z).setType(Material.WATER);
            }
        }
        PlayerMock walker = join("Jesus", 0.5, groundY, 0.5);
        PlayerData data = data(walker);
        Location at = walker.getLocation();
        for (int i = 0; i < 200 && walker.isOnline(); i++) {
            at = at.clone().add(0.2, 0, 0);
            move(walker, at, true);
        }
        assertTrue(flags(data, CheckType.FLIGHT) + flags(data, CheckType.NOFALL) >= 2,
                "walking on water: " + describe(data));
    }

    @Test
    void reachIsFlagged() {
        PlayerMock attacker = join("Reacher", 0.5, groundY, 0.5);
        PlayerMock target = join("Victim", 0.5, groundY, 5.5);
        attacker.setRotation(0, 0);
        tick(40);
        for (int i = 0; i < 20; i++) {
            attack(attacker, target, true);
            tick(10);
        }
        PlayerData data = data(attacker);
        assertTrue(flags(data, CheckType.REACH) >= 3, "reach flags: " + describe(data));
    }

    @Test
    void killAuraHittingWithoutLookingIsFlagged() {
        PlayerMock attacker = join("Aura", 0.5, groundY, 0.5);
        PlayerMock target = join("Behind", 0.5, groundY, -2.0);
        attacker.setRotation(0, 0); // looking towards +Z, the target is behind
        tick(40);
        for (int i = 0; i < 20; i++) {
            attack(attacker, target, true);
            tick(10);
        }
        PlayerData data = data(attacker);
        assertTrue(flags(data, CheckType.KILLAURA) >= 3, "kill aura flags: " + describe(data));
        assertEquals(0, flags(data, CheckType.REACH), describe(data));
    }

    @Test
    void attacksWithoutSwingingAreFlagged() {
        PlayerMock attacker = join("NoSwing", 0.5, groundY, 0.5);
        PlayerMock target = join("Hit", 0.5, groundY, 2.5);
        attacker.setRotation(0, 10);
        tick(40);
        for (int i = 0; i < 20; i++) {
            attack(attacker, target, false);
            tick(10);
        }
        PlayerData data = data(attacker);
        assertTrue(flags(data, CheckType.NOSWING) >= 3, "no-swing flags: " + describe(data));
    }

    @Test
    void autoClickingIsFlagged() {
        PlayerMock clicker = join("Clicker", 0.5, groundY, 0.5);
        for (int second = 0; second < 8; second++) {
            for (int t = 0; t < 20; t++) {
                swing(clicker);
                swing(clicker); // 40 clicks per second
                tick(1);
            }
        }
        assertTrue(flags(data(clicker), CheckType.AUTOCLICKER) >= 1, describe(data(clicker)));
        assertTrue(clicker.isOnline(), "auto clicker is alert-only by default");
    }

    @Test
    void ignoringKnockbackIsFlagged() {
        PlayerMock player = join("NoKnockback", 0.5, groundY, 0.5);
        PlayerData data = data(player);
        for (int i = 0; i < 6; i++) {
            server.getPluginManager().callEvent(new PlayerVelocityEvent(player, new Vector(0.3, 0.4, 0.0)));
            tick(40);
        }
        assertTrue(flags(data, CheckType.VELOCITY) >= 2, "velocity flags: " + describe(data));
    }

    @Test
    void ignoringKnockbackInACombatComboIsFlagged() {
        PlayerMock player = join("ComboVictim", 0.5, groundY, 0.5);
        PlayerData data = data(player);
        for (int i = 0; i < 30; i++) {
            server.getPluginManager().callEvent(new PlayerVelocityEvent(player, new Vector(0.3, 0.36, 0.0)));
            tick(10); // a hit every half second
        }
        assertTrue(flags(data, CheckType.VELOCITY) >= 2, "velocity flags: " + describe(data));
    }

    @Test
    void takingKnockbackIsNotFlagged() {
        PlayerMock player = join("TakesKnockback", 0.5, groundY, 0.5);
        PlayerData data = data(player);
        for (int i = 0; i < 6; i++) {
            server.getPluginManager().callEvent(new PlayerVelocityEvent(player, new Vector(0.3, 0.4, 0.0)));
            double y = groundY;
            double velocity = 0.4;
            Location at = player.getLocation();
            for (int t = 0; t < 40; t++) {
                y = Math.max(groundY, y + velocity);
                velocity = (velocity - Physics.GRAVITY) * Physics.VERTICAL_DRAG;
                at = new Location(world, at.getX() + 0.1, y, at.getZ());
                move(player, at, y == groundY);
            }
        }
        assertEquals(0, totalFlags(data), describe(data));
    }

    @Test
    void fallingWithoutFallDamageIsFlagged() {
        PlayerMock player = join("NoFall", 0.5, groundY + 30, 0.5);
        PlayerData data = data(player);
        fall(player);
        tick(40);
        assertTrue(flags(data, CheckType.NOFALL) >= 1, "no-fall flags: " + describe(data));
    }

    @Test
    void fallingWithFallDamageIsNotFlagged() {
        PlayerMock player = join("Faller", 0.5, groundY + 30, 0.5);
        PlayerData data = data(player);
        fall(player);
        EntityDamageEvent damage = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL,
                DamageSource.builder(DamageType.FALL).build(), 20.0);
        server.getPluginManager().callEvent(damage);
        tick(40);
        assertEquals(0, flags(data, CheckType.NOFALL), describe(data));
    }

    private void fall(PlayerMock player) {
        Location at = player.getLocation();
        double velocity = 0.0;
        double y = at.getY();
        while (y > groundY) {
            velocity = (velocity - Physics.GRAVITY) * Physics.VERTICAL_DRAG;
            y = Math.max(groundY, y + velocity);
            at = new Location(world, at.getX(), y, at.getZ());
            move(player, at, false);
        }
        move(player, at.clone().add(0.1, 0, 0), true);
    }

    // ---- commands and configuration -----------------------------------------------------------

    @Test
    void staffCommandsWork() {
        PlayerMock staff = join("Admin", 0.5, groundY, 0.5);
        staff.setOp(true);
        PlayerMock target = join("Griefer", 3.5, groundY, 0.5);
        messages(staff);

        staff.performCommand("ac check Griefer");
        List<String> check = messages(staff);
        assertTrue(check.stream().anyMatch(m -> m.contains("Griefer")), check.toString());
        assertTrue(check.stream().anyMatch(m -> m.contains("Violations: none")), check.toString());

        staff.performCommand("ban Griefer Griefing");
        assertFalse(target.isOnline());
        Punishment ban = plugin.moderation().activeBan(target.getUniqueId());
        assertNotNull(ban);
        assertEquals("Griefing", ban.reason());
        List<String> banned = messages(staff);
        assertTrue(banned.stream().anyMatch(m -> m.contains("You have banned player Griefer for Griefing for 7 days")),
                banned.toString());

        staff.performCommand("unban Griefer");
        assertNull(plugin.moderation().activeBan(target.getUniqueId()));
        assertTrue(messages(staff).stream().anyMatch(m -> m.contains("unbanned player Griefer for No Reason")));

        staff.performCommand("ac");
        List<String> help = messages(staff);
        assertTrue(help.stream().anyMatch(m -> m.contains("/ac check")), help.toString());
    }

    @Test
    void oldConfigIsBackedUpAndReplaced() throws Exception {
        var folder = plugin.getDataFolder().toPath();
        server.getPluginManager().disablePlugin(plugin);
        Files.writeString(folder.resolve("config.yml"), """
                config-version: 1
                general:
                  enabled: true
                  disabled-worlds: [creative_world]
                checks:
                  speed:
                    alert-vl: 3
                messages:
                  prefix: "&c[Old] "
                  no-reason: "Nothing given"
                moderation:
                  broadcast: all
                """, StandardCharsets.UTF_8);
        server.getPluginManager().enablePlugin(plugin);
        assertTrue(Files.exists(folder.resolve("config-1.x-backup.yml")));
        assertTrue(plugin.settings().autoBan().enabled());
        assertTrue(plugin.settings().general().disabledWorlds().contains("creative_world"));
        assertEquals("&c[Old] ", plugin.settings().messages().get("prefix"));
        assertEquals("Nothing given", plugin.settings().messages().get("no-reason"));
        assertEquals("all", plugin.settings().moderation().broadcast());
        assertEquals(List.of(), plugin.settings().warnings());
    }
}
