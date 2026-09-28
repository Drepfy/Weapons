package com.drepfy.staffvanish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.destroystokyo.paper.event.server.PaperServerListPingEvent;
import com.destroystokyo.paper.network.StatusClient;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class VanishProtectionTest extends VanishTestBase {

    @Test
    void vanishedPlayersGetFlightAndKeepItThroughGameModeChanges() {
        TestPlayer mod = staff("Mod");
        vanish.vanish(mod, mod);
        assertTrue(mod.getAllowFlight());

        mod.setGameMode(GameMode.CREATIVE);
        mod.setGameMode(GameMode.SURVIVAL);
        mod.setAllowFlight(false); // what the server does when switching to survival
        server.getScheduler().performOneTick();

        assertTrue(mod.getAllowFlight());
    }

    @Test
    void flightIsTakenAwayOnReappearingUnlessThePlayerHadItBefore() {
        TestPlayer mod = staff("Mod");
        TestPlayer flyer = staff("Flyer");
        flyer.setAllowFlight(true);

        vanish.vanish(mod, mod);
        vanish.vanish(flyer, flyer);
        vanish.unvanish(mod, mod);
        vanish.unvanish(flyer, flyer);

        assertFalse(mod.getAllowFlight());
        assertTrue(flyer.getAllowFlight());
    }

    @Test
    void reappearingMidFlightCancelsTheNextFallDamage() {
        TestPlayer mod = staff("Mod");
        vanish.vanish(mod, mod);
        mod.setFlying(true);

        vanish.unvanish(mod, mod);

        assertFalse(mod.getAllowFlight());
        assertFalse(mod.isFlying());
        assertTrue(fall(mod).isCancelled(), "landing after reappearing mid-air doesn't hurt");
        assertFalse(fall(mod).isCancelled(), "only the first fall is free");
    }

    @Test
    void reappearingOnTheGroundDoesNotProtectFromFalls() {
        TestPlayer mod = staff("Mod");
        vanish.vanish(mod, mod);
        vanish.unvanish(mod, mod);

        assertFalse(fall(mod).isCancelled());
    }

    @Test
    void vanishedPlayersTakeNoDamageAndDontGetHungry() {
        TestPlayer mod = staff("Mod");
        TestPlayer alice = regular("Alice");
        vanish.vanish(mod, mod);

        assertTrue(mod.simulateDamage(5, alice).isCancelled());
        assertEquals(20, mod.getHealth());
        assertFalse(alice.simulateDamage(5, mod).isCancelled());

        FoodLevelChangeEvent hunger = new FoodLevelChangeEvent(mod, 15, null);
        server.getPluginManager().callEvent(hunger);
        assertTrue(hunger.isCancelled());
    }

    @Test
    void mobsDontTargetVanishedPlayers() {
        TestPlayer mod = staff("Mod");
        TestPlayer alice = regular("Alice");
        vanish.vanish(mod, mod);
        Zombie zombie = world.spawn(mod.getLocation(), Zombie.class);

        EntityTargetEvent targetMod = new EntityTargetEvent(zombie, mod, EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        EntityTargetEvent targetAlice = new EntityTargetEvent(zombie, alice,
                EntityTargetEvent.TargetReason.CLOSEST_PLAYER);
        server.getPluginManager().callEvent(targetMod);
        server.getPluginManager().callEvent(targetAlice);

        assertTrue(targetMod.isCancelled());
        assertFalse(targetAlice.isCancelled());
    }

    @Test
    void vanishingMakesMobsForgetTheirTarget() {
        TestPlayer mod = staff("Mod");
        Zombie zombie = world.spawn(mod.getLocation(), Zombie.class);
        zombie.setTarget(mod);

        vanish.vanish(mod, mod);

        assertNull(zombie.getTarget());
    }

    @Test
    void vanishedPlayersDontPickUpItemsOrTriggerPressurePlates() {
        TestPlayer mod = staff("Mod");
        vanish.vanish(mod, mod);
        Item item = world.dropItem(mod.getLocation(), new ItemStack(Material.DIAMOND));

        PlayerAttemptPickupItemEvent pickup = new PlayerAttemptPickupItemEvent(mod, item, 0);
        server.getPluginManager().callEvent(pickup);
        assertTrue(pickup.isCancelled());
        assertFalse(pickup.getFlyAtPlayer());

        PlayerInteractEvent plate = new PlayerInteractEvent(mod, Action.PHYSICAL, null,
                world.getBlockAt(0, 64, 0), BlockFace.SELF, EquipmentSlot.HAND);
        server.getPluginManager().callEvent(plate);
        assertEquals(Event.Result.DENY, plate.useInteractedBlock());
    }

    @Test
    void privateMessagesToVanishedStaffFailAsIfTheyWereOffline() {
        TestPlayer mod = staff("Mod");
        TestPlayer admin = staff("Admin");
        TestPlayer alice = regular("Alice");
        vanish.vanish(mod, mod);
        messages(alice);

        PlayerCommandPreprocessEvent fromAlice = new PlayerCommandPreprocessEvent(alice, "/minecraft:tell mod hello");
        PlayerCommandPreprocessEvent fromAdmin = new PlayerCommandPreprocessEvent(admin, "/msg Mod hello");
        PlayerCommandPreprocessEvent otherCommand = new PlayerCommandPreprocessEvent(alice, "/spawn Mod");
        server.getPluginManager().callEvent(fromAlice);
        server.getPluginManager().callEvent(fromAdmin);
        server.getPluginManager().callEvent(otherCommand);

        assertTrue(fromAlice.isCancelled());
        assertFalse(fromAdmin.isCancelled());
        assertFalse(otherCommand.isCancelled());
        assertEquals(List.of("No player was found"), messages(alice));
    }

    @Test
    void serverListLeavesOutVanishedPlayers() {
        TestPlayer mod = staff("Mod");
        TestPlayer alice = regular("Alice");
        vanish.vanish(mod, mod);

        PaperServerListPingEvent ping = new PaperServerListPingEvent(new TestStatusClient(), Component.empty(),
                2, 20, "Paper", 770, null);
        ping.getListedPlayers().add(new PaperServerListPingEvent.ListedPlayerInfo(mod.getName(), mod.getUniqueId()));
        ping.getListedPlayers().add(
                new PaperServerListPingEvent.ListedPlayerInfo(alice.getName(), alice.getUniqueId()));
        // Pings are handled off the main thread.
        CompletableFuture.runAsync(() -> server.getPluginManager().callEvent(ping)).join();

        assertEquals(1, ping.getNumPlayers());
        assertEquals(List.of(alice.getUniqueId()),
                ping.getListedPlayers().stream().map(PaperServerListPingEvent.ListedPlayerInfo::id).toList());
    }

    private EntityDamageEvent fall(Player player) {
        EntityDamageEvent event = new EntityDamageEvent(player, EntityDamageEvent.DamageCause.FALL,
                DamageSource.builder(DamageType.FALL).build(), 10);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private static final class TestStatusClient implements StatusClient {
        @Override
        public InetSocketAddress getAddress() {
            return new InetSocketAddress("127.0.0.1", 25565);
        }

        @Override
        public int getProtocolVersion() {
            return 770;
        }

        @Override
        public @Nullable InetSocketAddress getVirtualHost() {
            return null;
        }
    }
}
