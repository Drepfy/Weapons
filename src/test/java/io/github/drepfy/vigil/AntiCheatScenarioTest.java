package io.github.drepfy.vigil;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.model.Physics;
import io.github.drepfy.vigil.moderation.Durations;
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
        paperMove(player, to);
        tick(1);
    }

    /**
     * A move exactly as CraftBukkit/Paper handles it (MockBukkit's own simulation does not):
     * a cancelled move puts the player back where they were, and a destination changed by a
     * plugin ({@code setTo}, e.g. a setback) makes the server teleport the player there,
     * which fires a {@link org.bukkit.event.player.PlayerTeleportEvent} with cause PLUGIN.
     */
    private org.bukkit.event.player.PlayerMoveEvent paperMove(PlayerMock player, Location to) {
        Location from = player.getLocation();
        Location requested = to.clone();
        var event = new org.bukkit.event.player.PlayerMoveEvent(player, from.clone(), to.clone());
        player.setLocation(to.clone());
        server.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            player.setLocation(from);
        } else if (!requested.equals(event.getTo())) {
            player.setLocation(from);
            player.teleport(event.getTo(), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        return event;
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

    // ---- hacked clients, as they behave against a real server -----------------------------------

    /**
     * A hacked client's Flight module (Meteor, Wurst...): it rises {@code climbPerTick} per tick
     * until {@code altitude} blocks above the ground, then moves {@code speed} blocks per tick
     * horizontally while sinking {@code sinkPerTick}, and dips 0.04 blocks every 40 ticks (Meteor's
     * anti-kick). {@code altitude} below 0 means it keeps climbing. After a setback it simply
     * carries on from wherever the server put it, like a real client.
     *
     * @return ticks until the player was auto-banned, or -1 if it never was
     */
    private int hackedFlight(PlayerMock player, double speed, double altitude, double climbPerTick,
                             double sinkPerTick, int maxTicks) {
        return hackedFlight(player, speed, altitude, climbPerTick, sinkPerTick, maxTicks, false);
    }

    /** @param claimGround the client lies that it stands on the ground (dodges vanilla's fly kick) */
    private int hackedFlight(PlayerMock player, double speed, double altitude, double climbPerTick,
                             double sinkPerTick, int maxTicks, boolean claimGround) {
        PlayerData data = data(player);
        for (int tick = 0; tick < maxTicks; tick++) {
            if (data.autoBanned) {
                return tick;
            }
            Location at = player.getLocation().clone();
            double target = groundY + altitude;
            if (altitude < 0 || at.getY() < target - 1.0E-6) {
                at.setY(altitude < 0 ? at.getY() + climbPerTick : Math.min(target, at.getY() + climbPerTick));
            } else {
                at.setY(Math.max(groundY, at.getY() - sinkPerTick));
                if (tick % 40 == 0) {
                    at.setY(at.getY() - 0.04);
                } else if (tick % 40 == 1) {
                    at.setY(at.getY() + 0.04);
                }
            }
            at.setX(at.getX() + speed);
            move(player, at, claimGround);
        }
        return data.autoBanned ? maxTicks : -1;
    }

    private void assertBannedWithin(PlayerMock player, int ticks, int limit, String what) {
        PlayerData data = data(player);
        assertTrue(ticks >= 0 && ticks <= limit, what + " should be auto-banned within " + limit / 20.0
                + " s, took " + (ticks < 0 ? "forever" : ticks / 20.0 + " s") + ". Flags:" + describe(data));
        tick(80);
        assertFalse(player.isOnline(), "kicked after the ban animation");
        assertNotNull(plugin.moderation().activeBan(player.getUniqueId()));
    }

    @Test
    void fastFlyHackIsBannedWithinSeconds() {
        // The user's test: Flight at ~20 m/s from the ground.
        PlayerMock flyer = join("FastFlyer", 0.5, groundY, 0.5);
        assertBannedWithin(flyer, hackedFlight(flyer, 1.0, 2.0, 0.4, 0.0, 400), 100, "a 20 m/s flyer");
    }

    @Test
    void flyHackFromTheGroundIsPulledDownAndBanned() {
        // Exactly the user's test: walk around first (so there is a safe spot), then fly off fast.
        PlayerMock flyer = join("GroundFlyer", 0.5, groundY, 0.5);
        PlayerData data = data(flyer);
        Location at = flyer.getLocation();
        for (int i = 0; i < 30; i++) {
            at = at.clone().add(0.2, 0, 0);
            move(flyer, at, true);
        }
        assertNotNull(data.lastSafeLocation, "walking on the ground gives a safe spot");
        long teleportBefore = data.lastTeleportMs;
        int ticks = hackedFlight(flyer, 1.0, 2.0, 0.4, 0.0, 400);
        assertEquals(teleportBefore, data.lastTeleportMs, "Vigil's own setbacks are not treated as teleports");
        assertTrue(flyer.getLocation().getY() < groundY + 1.0 || ticks >= 0, "pulled back down");
        assertBannedWithin(flyer, ticks, 100, "a flyer who took off from the ground");
    }

    @Test
    void flyHackThatClaimsToBeOnTheGroundIsBanned() {
        PlayerMock flyer = join("GroundLiar", 0.5, groundY, 0.5);
        assertBannedWithin(flyer, hackedFlight(flyer, 0.3, 3.0, 0.4, 0.0, 400, true), 100,
                "a flyer spoofing on-ground");
    }

    @Test
    void slowFlyHackFromTheGroundIsBanned() {
        PlayerMock flyer = join("SlowGroundFlyer", 0.5, groundY, 0.5);
        Location at = flyer.getLocation();
        for (int i = 0; i < 30; i++) {
            at = at.clone().add(0.2, 0, 0);
            move(flyer, at, true);
        }
        assertBannedWithin(flyer, hackedFlight(flyer, 0.2, 3.0, 0.4, 0.0, 400), 100,
                "a slow flyer who took off from the ground");
    }

    @Test
    void setbacksPullTheFlyerBackToTheGround() {
        PlayerMock flyer = join("PulledDown", 0.5, groundY, 0.5);
        PlayerData data = data(flyer);
        Location at = flyer.getLocation();
        for (int i = 0; i < 30; i++) {
            at = at.clone().add(0.1, 0, 0);
            move(flyer, at, true);
        }
        double safeX = data.lastSafeLocation.getX();
        // Fly away and watch where the server puts the player after the first flight flag.
        double lowest = Double.MAX_VALUE;
        for (int tick = 0; tick < 60 && !data.autoBanned; tick++) {
            hackedFlight(flyer, 0.2, 3.0, 0.4, 0.0, 1);
            if (flags(data, CheckType.FLIGHT) > 0) {
                lowest = Math.min(lowest, flyer.getLocation().getY());
            }
        }
        assertTrue(flags(data, CheckType.FLIGHT) > 0, describe(data));
        assertTrue(lowest <= groundY + 0.5, "the flyer was put back on the ground, lowest y " + lowest);
        assertTrue(Math.abs(data.lastSafeLocation.getX() - safeX) < 1.0, "the safe spot did not follow the flyer");
    }

    @Test
    void hoveringFlyHackIsBannedWithinSeconds() {
        PlayerMock flyer = join("Hoverer", 0.5, groundY, 0.5);
        assertBannedWithin(flyer, hackedFlight(flyer, 0.2, 3.0, 0.4, 0.0, 400), 100, "a slow flyer");
        assertEquals("Cheating (Flying)", plugin.moderation().activeBan(flyer.getUniqueId()).reason());
    }

    @Test
    void flyingStraightUpIsBannedWithinSeconds() {
        PlayerMock flyer = join("Climber", 0.5, groundY, 0.5);
        assertBannedWithin(flyer, hackedFlight(flyer, 0.0, -1, 0.3, 0.0, 400), 100, "a player flying up");
    }

    @Test
    void slowGlideIsBannedWithinSeconds() {
        PlayerMock flyer = join("Glider", 0.5, groundY, 0.5);
        assertBannedWithin(flyer, hackedFlight(flyer, 0.25, 6.0, 0.4, 0.03, 400), 100, "a glider");
    }

    @Test
    void groundSpeedHackIsBannedWithinSeconds() {
        PlayerMock speeder = join("GroundSpeeder", 0.5, groundY, 0.5);
        PlayerData data = data(speeder);
        int ticks = -1;
        for (int tick = 0; tick < 400; tick++) {
            if (data.autoBanned) {
                ticks = tick;
                break;
            }
            Location at = speeder.getLocation().clone();
            at.setX(at.getX() + 0.8); // 16 m/s on the ground
            move(speeder, at, true);
        }
        assertBannedWithin(speeder, ticks, 140, "a 16 m/s speed hacker");
    }

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
        assertTrue(staffMessages.stream().anyMatch(m -> m.contains("V\u026a\u0262\u026a\u029f | Anti-Cheat")),
                staffMessages.toString());
        assertTrue(staffMessages.stream().anyMatch(m -> m.contains("Flyer has been banned for cheating.")),
                staffMessages.toString());
        assertTrue(staffMessages.stream().anyMatch(m -> m.contains("Detected: Flying")),
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

    /** Hits {@code target} every {@code interval} ticks until the attacker is banned; returns the ticks or -1. */
    private int fightUntilBanned(PlayerMock attacker, PlayerMock target, int interval, int maxTicks) {
        PlayerData data = data(attacker);
        for (int tick = 0; tick < maxTicks; tick += interval) {
            if (data.autoBanned) {
                return tick;
            }
            attack(attacker, target, true);
            tick(interval);
        }
        return data.autoBanned ? maxTicks : -1;
    }

    @Test
    void killAuraWithLongReachIsBannedWithinSeconds() {
        // A typical hacked-client KillAura: looks at the target, hits from 4.5 blocks at sword speed.
        PlayerMock attacker = join("AuraReach", 0.5, groundY, 0.5);
        PlayerMock target = join("AuraVictim", 0.5, groundY, 5.0);
        attacker.setRotation(0, 0);
        tick(40);
        assertBannedWithin(attacker, fightUntilBanned(attacker, target, 12, 800), 160, "a 4.5-block kill aura");
        assertEquals("Cheating (Reach)", plugin.moderation().activeBan(attacker.getUniqueId()).reason());
    }

    @Test
    void killAuraWithoutRotationsIsBannedWithinSeconds() {
        PlayerMock attacker = join("AuraBlind", 0.5, groundY, 0.5);
        PlayerMock target = join("AuraBehind", 0.5, groundY, -2.0);
        attacker.setRotation(0, 0); // looking away from the target
        tick(40);
        assertBannedWithin(attacker, fightUntilBanned(attacker, target, 12, 800), 220, "a no-rotation kill aura");
        assertEquals("Cheating (Kill Aura)", plugin.moderation().activeBan(attacker.getUniqueId()).reason());
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
        PlayerMock player = join("NoFall", 0.5, groundY, 0.5);
        PlayerData data = data(player);
        player.teleport(new Location(world, 0.5, groundY + 30, 0.5));
        fall(player);
        tick(40);
        assertTrue(flags(data, CheckType.NOFALL) >= 1, "no-fall flags: " + describe(data));
    }

    @Test
    void fallingWithFallDamageIsNotFlagged() {
        PlayerMock player = join("Faller", 0.5, groundY, 0.5);
        PlayerData data = data(player);
        player.teleport(new Location(world, 0.5, groundY + 30, 0.5));
        fall(player);
        EntityDamageEvent damage = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL,
                DamageSource.builder(DamageType.FALL).build(), 20.0);
        server.getPluginManager().callEvent(damage);
        tick(40);
        assertEquals(0, totalFlags(data), "a legit 30 block fall: " + describe(data));
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

    // ---- mace, x-ray, anti-ESP -----------------------------------------------------------------

    private org.bukkit.event.entity.EntityDamageByEntityEvent maceHit(PlayerMock attacker, Entity target) {
        attacker.getInventory().setItemInMainHand(new org.bukkit.inventory.ItemStack(Material.MACE));
        DamageSource source = DamageSource.builder(DamageType.PLAYER_ATTACK).withCausingEntity(attacker)
                .withDirectEntity(attacker).build();
        var event = new org.bukkit.event.entity.EntityDamageByEntityEvent(attacker, target,
                EntityDamageEvent.DamageCause.ENTITY_ATTACK, source, 60.0);
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void maceSmashWithAFakeFallIsCancelledAndFlagged() {
        PlayerMock cheater = join("MaceKill", 0.5, groundY, 0.5);
        PlayerMock victim = join("MaceVictim", 0.5, groundY, 2.0);
        PlayerData data = data(cheater);
        // Fake fall while standing on the ground.
        cheater.setFallDistance(22);
        var standing = maceHit(cheater, victim);
        assertTrue(standing.isCancelled(), "the fake smash must not deal damage");
        tick(20);
        // Fake fall by jumping 10 blocks up and back down within one tick.
        Location ground = cheater.getLocation();
        move(cheater, ground.clone().add(0, 10, 0), false);
        paperMove(cheater, ground.clone().add(0, 0.2, 0));
        cheater.setFallDistance(9.8f);
        var teleported = maceHit(cheater, victim);
        assertTrue(teleported.isCancelled());
        assertEquals(2, flags(data, CheckType.MACE), describe(data));
    }

    @Test
    void realMaceSmashIsNotFlagged() {
        PlayerMock victim = join("MaceTarget", 0.5, groundY, 1.0);
        PlayerMock attacker = join("MaceUser", 0.5, groundY, 0.5);
        PlayerData data = data(attacker);
        // Up on a tower (teleported there), then straight off: a real client starts falling at once.
        attacker.teleport(new Location(world, 0.5, groundY + 12, 0.5));
        Location at = attacker.getLocation();
        double velocity = 0.0;
        double y = at.getY();
        float fall = 0;
        while (y > groundY + 2.3) {
            velocity = (velocity - Physics.GRAVITY) * Physics.VERTICAL_DRAG;
            y += velocity;
            fall -= (float) velocity;
            move(attacker, new Location(world, 0.5, y, 0.5), false);
        }
        attacker.setFallDistance(fall);
        var hit = maceHit(attacker, victim);
        assertFalse(hit.isCancelled(), describe(data));
        assertEquals(0, flags(data, CheckType.MACE), describe(data));
    }

    /** A solid stone bar along X with diamond ore every {@code spacing} blocks, mined straight through. */
    private void mineThroughOres(PlayerMock miner, int spacing, int veins) {
        int y = groundY + 2;
        int length = spacing * veins + 2;
        for (int x = 0; x <= length; x++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int z = -1; z <= 1; z++) {
                    world.getBlockAt(x, y + dy, z).setType(Material.STONE);
                }
            }
        }
        for (int vein = 1; vein <= veins; vein++) {
            world.getBlockAt(vein * spacing, y, 0).setType(Material.DEEPSLATE_DIAMOND_ORE);
        }
        for (int x = 0; x < length; x++) {
            miner.simulateBlockBreak(world.getBlockAt(x, y, 0));
            if (x % 4 == 0) {
                tick(1);
            }
        }
    }

    @Test
    void miningStraightToHiddenDiamondsIsFlagged() {
        PlayerMock miner = join("XRayer", -3.5, groundY, 0.5);
        mineThroughOres(miner, 8, 6);
        PlayerData data = data(miner);
        assertEquals(1, flags(data, CheckType.XRAY), describe(data));
        assertTrue(miner.isOnline(), "x-ray is alert-only by default");
    }

    @Test
    void normalBranchMiningIsNotFlaggedForXray() {
        PlayerMock miner = join("BranchMiner", -3.5, groundY, 0.5);
        mineThroughOres(miner, 70, 6);
        assertEquals(0, flags(data(miner), CheckType.XRAY), describe(data(miner)));
    }

    /** The test server cannot list block entities, so scan the few layers the tests build in. */
    private io.github.drepfy.vigil.env.BlockHider hider() {
        var hider = plugin.blockHider();
        assertNotNull(hider, "Paper API is present in the test server");
        hider.setTileSource((chunk, types) -> scanLayers(chunk, types, groundY - 4, groundY + 8));
        hider.setOreSource((chunk, types, minY, maxY, result) ->
                result.accept(scanLayers(chunk, types, Math.max(minY, groundY - 4), Math.min(maxY, groundY + 8))));
        return hider;
    }

    private static List<int[]> scanLayers(org.bukkit.Chunk chunk, java.util.Set<Material> types, int minY, int maxY) {
        List<int[]> found = new ArrayList<>();
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (types.contains(chunk.getBlock(x, y, z).getType())) {
                        found.add(new int[] {(chunk.getX() << 4) + x, y, (chunk.getZ() << 4) + z});
                    }
                }
            }
        }
        return found;
    }

    private void sendChunk(PlayerMock player, int chunkX, int chunkZ) {
        server.getPluginManager().callEvent(new io.papermc.paper.event.packet.PlayerChunkLoadEvent(
                world.getChunkAt(chunkX, chunkZ), player));
    }

    @Test
    void diamondsAreHiddenUntilSeenAndHiddenAgainWhenOutOfSight() {
        var hider = hider();
        // A diamond ore in a small cave pocket 3 blocks under the surface, 40 blocks away.
        int dx = 40;
        int dy = groundY - 3;
        for (int x = dx - 2; x <= dx + 2; x++) {
            for (int y = dy - 1; y <= dy + 3; y++) {
                for (int z = -2; z <= 2; z++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE);
                }
            }
        }
        world.getBlockAt(dx - 1, dy, 0).setType(Material.AIR); // exposed to a cave: Paper can't hide this one
        world.getBlockAt(dx, dy, 0).setType(Material.DEEPSLATE_DIAMOND_ORE);
        PlayerMock looker = join("OreEsp", 0.5, groundY, 0.5);
        sendChunk(looker, dx >> 4, 0);
        assertEquals(1, hider.hiddenCount(looker.getUniqueId()), "the cave diamond is hidden from afar");

        looker.teleport(new Location(world, dx - 3.5, groundY, 0.5)); // right above it
        tick(10);
        assertEquals(0, hider.hiddenCount(looker.getUniqueId()), "shown once the player is there");

        looker.teleport(new Location(world, 0.5, groundY, 0.5)); // walked away again
        tick(20);
        assertEquals(1, hider.hiddenCount(looker.getUniqueId()), "hidden again when out of sight");
    }

    @Test
    void chestsBehindWallsAreHiddenUntilThePlayerGetsClose() {
        var hider = hider();
        // A chest inside a closed stone room far from the player.
        int cx = 40;
        int cy = groundY + 1;
        for (int x = cx - 2; x <= cx + 2; x++) {
            for (int y = cy - 1; y <= cy + 3; y++) {
                for (int z = -2; z <= 2; z++) {
                    world.getBlockAt(x, y, z).setType(Material.STONE);
                }
            }
        }
        world.getBlockAt(cx, cy, 0).setType(Material.AIR);
        world.getBlockAt(cx, cy + 1, 0).setType(Material.AIR);
        world.getBlockAt(cx, cy, 0).setType(Material.CHEST);
        PlayerMock looker = join("Esp", 0.5, groundY, 0.5);
        sendChunk(looker, cx >> 4, 0);
        assertEquals(1, hider.hiddenCount(looker.getUniqueId()), "the chest behind walls is hidden");

        // Walking up to it reveals it.
        looker.teleport(new Location(world, cx - 4.5, groundY, 0.5));
        tick(10);
        assertEquals(0, hider.hiddenCount(looker.getUniqueId()), "the chest is shown once the player is close");

        // Walking away again hides it again.
        looker.teleport(new Location(world, 0.5, groundY, 0.5));
        tick(20);
        assertEquals(1, hider.hiddenCount(looker.getUniqueId()), "the chest is hidden again when out of sight");

        // A chest that was broken meanwhile is never faked back.
        world.getBlockAt(cx, cy, 0).setType(Material.AIR);
        looker.teleport(new Location(world, cx - 4.5, groundY, 0.5));
        tick(10);
        looker.teleport(new Location(world, 0.5, groundY, 0.5));
        tick(20);
        assertEquals(0, hider.hiddenCount(looker.getUniqueId()));
    }

    @Test
    void visibleChestsAreNeverHidden() {
        var hider = hider();
        world.getBlockAt(20, groundY, 0).setType(Material.CHEST); // in the open, 20 blocks away
        PlayerMock looker = join("Looker", 0.5, groundY, 0.5);
        server.getPluginManager().callEvent(new io.papermc.paper.event.packet.PlayerChunkLoadEvent(
                world.getChunkAt(1, 0), looker));
        assertEquals(0, hider.hiddenCount(looker.getUniqueId()));
    }

    // ---- inventory mods and hacked clients --------------------------------------------------

    private org.bukkit.event.inventory.InventoryClickEvent click(PlayerMock player, int slot) {
        var top = player.getOpenInventory().getTopInventory();
        if (top == null || top.getType() != org.bukkit.event.inventory.InventoryType.CHEST) {
            player.openInventory(server.createInventory(null, 54));
        }
        var event = new org.bukkit.event.inventory.InventoryClickEvent(player.getOpenInventory(),
                org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER, slot,
                org.bukkit.event.inventory.ClickType.SHIFT_LEFT,
                org.bukkit.event.inventory.InventoryAction.MOVE_TO_OTHER_INVENTORY);
        server.getPluginManager().callEvent(event);
        return event;
    }

    @Test
    void inventoryMacrosAreBlockedAndFlagged() {
        PlayerMock player = join("ItemScroller", 0.5, groundY, 0.5);
        int cancelled = 0;
        for (int burst = 0; burst < 3; burst++) {
            for (int slot = 9; slot < 36; slot++) {
                if (click(player, slot).isCancelled()) {
                    cancelled++;
                }
            }
            tick(25);
        }
        assertTrue(cancelled >= 3 * 20, "mass moving must be stopped, cancelled " + cancelled);
        assertTrue(flags(data(player), CheckType.INVENTORY) >= 1, describe(data(player)));
    }

    @Test
    void normalInventoryClickingIsNotFlagged() {
        PlayerMock player = join("Sorter", 0.5, groundY, 0.5);
        for (int slot = 9; slot < 36; slot++) {
            assertFalse(click(player, slot).isCancelled());
            tick(3); // about 7 clicks per second
        }
        assertEquals(0, totalFlags(data(player)), describe(data(player)));
    }

    @Test
    void worldDownloaderClientsAreKicked() {
        PlayerMock staff = join("Watcher", 0.5, groundY, 5.5);
        staff.setOp(true);
        messages(staff);
        PlayerMock player = join("Downloader", 0.5, groundY, 0.5);
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerRegisterChannelEvent(player, "wdl:init"));
        assertFalse(player.isOnline());
        assertTrue(messages(staff).stream().anyMatch(m -> m.contains("Downloader was disconnected for using")));

        PlayerMock normal = join("Normal", 0.5, groundY, 3.5);
        server.getPluginManager().callEvent(new org.bukkit.event.player.PlayerRegisterChannelEvent(normal,
                "minecraft:brand"));
        assertTrue(normal.isOnline());
    }

    @Test
    void cheatersAreHeldInPlaceDuringTheBanAnimation() {
        PlayerMock flyer = join("Frozen", 0.5, 80, 0.5);
        PlayerData data = data(flyer);
        Location at = flyer.getLocation();
        while (!data.autoBanned) {
            at = at.clone().add(0.2, 0, 0);
            move(flyer, at, false);
        }
        assertTrue(flyer.isOnline(), "kicked only after the animation");
        var escape = paperMove(flyer, at.clone().add(5, 0, 0));
        assertTrue(escape.isCancelled(), "cannot move away during the animation");
        tick(70);
        assertFalse(flyer.isOnline());
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
        assertTrue(banned.stream().anyMatch(m -> m.contains("You have banned player Griefer for Griefing for 3 days")
                && m.contains("1st offence")), banned.toString());

        staff.performCommand("unban Griefer");
        assertNull(plugin.moderation().activeBan(target.getUniqueId()));
        assertTrue(messages(staff).stream().anyMatch(m -> m.contains("unbanned player Griefer for No Reason")));

        // The same reason again gets the next time of the preset.
        staff.performCommand("ban Griefer Griefing");
        assertEquals(7L * 24 * 3600 * 1000, plugin.moderation().activeBan(target.getUniqueId()).durationMs());
        List<String> again = messages(staff);
        assertTrue(again.stream().anyMatch(m -> m.contains("for Griefing for 7 days") && m.contains("2nd offence")),
                again.toString());
        // Lifted as a mistake: it no longer counts.
        staff.performCommand("unban Griefer False_Ban");
        staff.performCommand("ban Griefer Griefing");
        assertEquals(7L * 24 * 3600 * 1000, plugin.moderation().activeBan(target.getUniqueId()).durationMs());
        staff.performCommand("unban Griefer");

        staff.performCommand("ac");
        List<String> help = messages(staff);
        assertTrue(help.stream().anyMatch(m -> m.contains("/ac check")), help.toString());
    }

    @Test
    void banWithoutArgumentsListsThePresetTimes() {
        PlayerMock staff = join("Admin", 0.5, groundY, 0.5);
        staff.setOp(true);
        messages(staff);
        staff.performCommand("ban");
        List<String> lines = messages(staff);
        assertTrue(lines.stream().anyMatch(m -> m.contains("Preset reasons")), lines.toString());
        assertTrue(lines.stream().anyMatch(m -> m.contains("Cheating - 7d, 30d, perm")), lines.toString());
        assertTrue(lines.stream().anyMatch(m -> m.contains("Doxxing - perm")), lines.toString());
        staff.performCommand("mute");
        assertTrue(messages(staff).stream().anyMatch(m -> m.contains("Spam - 15m, 1h, 6h, 1d")));
    }

    @Test
    void tooManyWarningsMuteAutomatically() {
        PlayerMock staff = join("Admin", 0.5, groundY, 0.5);
        staff.setOp(true);
        PlayerMock chatty = join("Chatty", 3.5, groundY, 0.5);
        messages(staff);
        staff.performCommand("warn Chatty 1d Spam");
        staff.performCommand("warn Chatty 3d Swearing");
        assertNull(plugin.moderation().activeMute(chatty.getUniqueId()));
        staff.performCommand("warn Chatty 12h Begging");
        Punishment mute = plugin.moderation().activeMute(chatty.getUniqueId());
        assertNotNull(mute, "the 3rd warning mutes (moderation.warn-escalation)");
        assertEquals(3_600_000L, mute.durationMs());
        assertEquals("Too many warnings (3)", mute.reason());
        List<String> lines = messages(staff);
        assertTrue(lines.stream().anyMatch(m -> m.contains("active warnings: 3")), lines.toString());
        assertTrue(lines.stream().anyMatch(m -> m.contains("muted player Chatty for Too many warnings (3) for 1 hour")),
                lines.toString());
        assertTrue(messages(chatty).stream().anyMatch(m -> m.contains("You have been muted")));

        // A longer mute is never shortened by the escalation.
        PlayerMock toxic = join("Toxic", 6.5, groundY, 0.5);
        staff.performCommand("mute Toxic perm Toxicity");
        for (int i = 0; i < 3; i++) {
            staff.performCommand("warn Toxic 1d Swearing");
        }
        assertEquals("Toxicity", plugin.moderation().activeMute(toxic.getUniqueId()).reason());
        assertTrue(plugin.moderation().activeMute(toxic.getUniqueId()).isPermanent());
    }

    private static String actionBar(PlayerMock player) {
        net.kyori.adventure.text.Component component = player.nextActionBar();
        return component == null ? null : net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                .plainText().serialize(component);
    }

    @Test
    void warningsNeedATimeBetweenOneHourAndTenDays() {
        PlayerMock staff = join("Admin", 0.5, groundY, 0.5);
        staff.setOp(true);
        PlayerMock target = join("Rude", 3.5, groundY, 0.5);
        messages(staff);
        for (String command : List.of("warn Rude Spam", "warn Rude 30m Spam", "warn Rude 11d Spam",
                "warn Rude perm Spam", "warn Rude")) {
            staff.performCommand(command);
        }
        assertEquals(0, plugin.moderation().warningCount(target.getUniqueId()), "no warning without a valid time");
        List<String> lines = messages(staff);
        assertTrue(lines.stream().anyMatch(m -> m.contains("A warning needs a time between 1 hour and 10 days")),
                lines.toString());

        staff.performCommand("warn Rude 10d Spam");
        Punishment warning = plugin.moderation().history(target.getUniqueId()).get(0);
        assertEquals(10L * 24 * 3600 * 1000, warning.durationMs());
        assertTrue(messages(staff).stream().anyMatch(m -> m.contains("warned player Rude for Spam for 10 days")));
        assertTrue(messages(target).stream().anyMatch(m -> m.contains("This warning expires in 10 days")));
    }

    @Test
    void mutesAndWarningsShowInBoldAboveTheHotbar() {
        PlayerMock staff = join("Admin", 0.5, groundY, 0.5);
        staff.setOp(true);
        PlayerMock target = join("Loud", 3.5, groundY, 0.5);

        staff.performCommand("mute Loud Spam");
        assertEquals("You have been muted for Spam", actionBar(target));
        staff.performCommand("unmute Loud");
        String next;
        while ((next = actionBar(target)) != null && !next.contains("unmuted")) {
            // Skip the repeats of the mute message.
        }
        assertEquals("You have been unmuted", next);

        staff.performCommand("warn Loud 1d Spam");
        while ((next = actionBar(target)) != null && !next.contains("warned")) {
            // Skip older ones.
        }
        assertEquals("You have been warned for Spam", next);
        tick(100);
        assertTrue(target.nextActionBar() != null, "it is shown again so it stays up for a few seconds");

        messages(staff);
        messages(target);
        staff.performCommand("unwarn Loud False_Warning");
        assertEquals(0, plugin.moderation().activeWarnings(target.getUniqueId(), -1L).size());
        assertTrue(messages(staff).stream().anyMatch(m -> m.contains("removed a warning from Loud for False Warning")));
        assertTrue(messages(target).stream().anyMatch(m -> m.contains("One of your warnings has been removed")));
        while ((next = actionBar(target)) != null && !next.contains("removed")) {
            // Skip older ones.
        }
        assertEquals("A warning has been removed", next);
        staff.performCommand("unwarn Loud");
        assertTrue(messages(staff).stream().anyMatch(m -> m.contains("Loud has no active warnings")));
    }

    @Test
    void bannedPlayersAreRefusedEvenIfTheScreenFails() {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        plugin.moderation().ban(uuid, "Evader", "Cheating", "Admin", Durations.PERMANENT);
        // Settings that throw: building the ban screen fails, the ban must still hold.
        var listener = new io.github.drepfy.vigil.moderation.ModerationListener(() -> {
            throw new IllegalStateException("broken");
        }, plugin.moderation());
        var event = new org.bukkit.event.player.AsyncPlayerPreLoginEvent("Evader",
                java.net.InetAddress.getLoopbackAddress(), uuid);
        listener.onPreLogin(event);
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.getLoginResult());
        assertTrue(event.getKickMessage().contains("banned"), event.getKickMessage());
    }

    @Test
    void replacedBansAreLabelledInTheHistory() {
        PlayerMock staff = join("Admin", 0.5, groundY, 0.5);
        staff.setOp(true);
        PlayerMock target = join("Twice", 3.5, groundY, 0.5);
        plugin.moderation().ban(target.getUniqueId(), "Twice", "Griefing", "Admin", 3L * 24 * 3600 * 1000);
        plugin.moderation().ban(target.getUniqueId(), "Twice", "Cheating", "Admin", Durations.PERMANENT);
        messages(staff);
        staff.performCommand("ac check Twice");
        List<String> lines = messages(staff);
        assertTrue(lines.stream().anyMatch(m -> m.contains("Griefing") && m.contains("[replaced]")), lines.toString());
        assertTrue(lines.stream().anyMatch(m -> m.contains("Cheating") && m.contains("[active]")), lines.toString());
    }

    @Test
    void removedCommandsAreGone() {
        assertNull(server.getPluginCommand("punish"), "no punish menu");
        PlayerMock staff = join("Admin", 0.5, groundY, 0.5);
        staff.setOp(true);
        messages(staff);
        staff.performCommand("ac");
        List<String> help = messages(staff);
        assertFalse(help.stream().anyMatch(m -> m.contains("/ac reset") || m.contains("/ac debug")), help.toString());
        assertTrue(help.stream().anyMatch(m -> m.contains("/ac reload")), help.toString());
    }

    @Test
    void everyCommandHasItsOwnPermission() {
        PlayerMock mod = join("Helper", 0.5, groundY, 0.5);
        PlayerMock target = join("Target", 3.5, groundY, 0.5);
        mod.addAttachment(plugin, "vigil.warn", true);
        messages(mod);
        mod.performCommand("warn Target 1d Spam");
        assertEquals(1, plugin.moderation().warningCount(target.getUniqueId()));
        mod.performCommand("unwarn Target");
        assertEquals(1, plugin.moderation().activeWarnings(target.getUniqueId(), -1L).size(),
                "vigil.warn alone cannot remove warnings (vigil.unwarn)");
        mod.performCommand("ban Target Cheating");
        assertNull(plugin.moderation().activeBan(target.getUniqueId()));

        PlayerMock staff = join("Moderator", 6.5, groundY, 0.5);
        staff.addAttachment(plugin, "vigil.staff", true);
        for (String node : List.of("vigil.alerts", "vigil.check", "vigil.ban", "vigil.unban", "vigil.mute",
                "vigil.unmute", "vigil.warn", "vigil.unwarn", "vigil.kick")) {
            assertTrue(staff.hasPermission(node), "vigil.staff includes " + node);
        }
        assertFalse(staff.hasPermission("vigil.reload"));
        assertNotNull(server.getPluginManager().getPermission("vigil.bypass.speed"));
    }

    @Test
    void bannedPlayersSeeTheBanScreenWhenJoining() throws Exception {
        java.util.UUID uuid = java.util.UUID.randomUUID();
        plugin.moderation().ban(uuid, "Returning", "Cheating", "Admin", 7L * 24 * 3600 * 1000);
        var event = new org.bukkit.event.player.AsyncPlayerPreLoginEvent("Returning",
                java.net.InetAddress.getLoopbackAddress(), uuid);
        Thread login = new Thread(() -> server.getPluginManager().callEvent(event));
        login.start();
        login.join();
        assertEquals(org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.KICK_BANNED, event.getLoginResult());
        String screen = org.bukkit.ChatColor.stripColor(event.getKickMessage());
        assertTrue(screen.contains("You are banned from this server."), screen);
        assertTrue(screen.contains("Reason: Cheating (1st offence)"), screen);
        assertTrue(screen.contains("Banned by: Admin"), screen);
        assertTrue(screen.contains("Expires: "), screen);
        assertFalse(screen.contains("{"), screen);
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
