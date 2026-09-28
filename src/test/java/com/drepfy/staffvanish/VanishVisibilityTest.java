package com.drepfy.staffvanish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.junit.jupiter.api.Test;

class VanishVisibilityTest extends VanishTestBase {

    @Test
    void vanishedPlayersAreHiddenFromPlayersButNotStaff() {
        TestPlayer mod = staff("Mod");
        TestPlayer admin = staff("Admin");
        TestPlayer alice = regular("Alice");

        assertTrue(vanish.vanish(mod, mod));

        assertTrue(vanish.isVanished(mod));
        assertFalse(alice.canSee(mod));
        assertTrue(admin.canSee(mod));
        assertTrue(mod.canSee(alice));
        assertTrue(mod.hasMetadata(VanishManager.METADATA_KEY));
        assertFalse(mod.isCollidable());
        assertTrue(mod.isSleepingIgnored());
        assertFalse(mod.getAffectsSpawning());
    }

    @Test
    void unvanishingRevealsAndRestores() {
        TestPlayer mod = staff("Mod");
        TestPlayer alice = regular("Alice");
        vanish.vanish(mod, mod);

        assertTrue(vanish.unvanish(mod, mod));

        assertFalse(vanish.isVanished(mod));
        assertTrue(alice.canSee(mod));
        assertFalse(mod.hasMetadata(VanishManager.METADATA_KEY));
        assertTrue(mod.isCollidable());
        assertFalse(mod.isSleepingIgnored());
        assertTrue(mod.getAffectsSpawning());
        assertEquals(Component.text("Mod"), mod.playerListName());
    }

    @Test
    void vanishingTwiceOrUnvanishingAVisiblePlayerDoesNothing() {
        TestPlayer mod = staff("Mod");
        assertFalse(vanish.unvanish(mod, mod));
        assertTrue(vanish.vanish(mod, mod));
        assertFalse(vanish.vanish(mod, mod));
    }

    @Test
    void tabListMarksVanishedPlayersForStaff() {
        TestPlayer mod = staff("Mod");
        vanish.vanish(mod, mod);

        assertEquals("Mod [V]", PlainTextComponentSerializer.plainText().serialize(mod.playerListName()));
    }

    @Test
    void playersJoiningLaterCannotSeeVanishedStaff() {
        TestPlayer mod = staff("Mod");
        vanish.vanish(mod, mod);

        TestPlayer bob = regular("Bob");
        TestPlayer helper = staff("Helper");

        assertFalse(bob.canSee(mod));
        assertTrue(helper.canSee(mod));
    }

    @Test
    void playersWhoCannotSeeGetFakeLeaveAndJoinMessages() {
        TestPlayer mod = staff("Mod");
        TestPlayer admin = staff("Admin");
        TestPlayer alice = regular("Alice");
        messages(alice);
        messages(admin);

        vanish.vanish(mod, mod);
        assertTranslatable("multiplayer.player.left", alice.nextComponentMessage());
        assertNull(alice.nextComponentMessage());
        assertEquals(List.of("[Vanish] Mod vanished."), messages(admin));

        vanish.unvanish(mod, mod);
        assertTranslatable("multiplayer.player.joined", alice.nextComponentMessage());
        assertEquals(List.of("[Vanish] Mod reappeared."), messages(admin));
    }

    @Test
    void vanishedPlayersReconnectVanishedWithoutJoinOrQuitMessages() {
        TestPlayer mod = staff("Mod");
        TestPlayer admin = staff("Admin");
        TestPlayer alice = regular("Alice");
        vanish.vanish(mod, mod);
        MessageRecorder recorder = new MessageRecorder();
        server.getPluginManager().registerEvents(recorder, plugin);
        messages(admin);

        mod.disconnect();
        assertFalse(vanish.isVanished(mod));
        assertTrue(vanish.isMarkedVanished(mod.getUniqueId()));
        assertEquals(List.of("[Vanish] Mod left silently."), messages(admin));

        mod.reconnect();
        assertTrue(vanish.isVanished(mod));
        assertFalse(alice.canSee(mod));
        assertTrue(admin.canSee(mod));
        assertTrue(mod.getAllowFlight());
        assertEquals(List.of("[Vanish] Mod joined silently."), messages(admin));

        List<Component> expected = new ArrayList<>();
        expected.add(null);
        expected.add(null);
        assertEquals(expected, recorder.messages);
    }

    @Test
    void reconnectingWithoutPermissionMakesThePlayerVisible() {
        TestPlayer mod = staff("Mod");
        TestPlayer alice = regular("Alice");
        vanish.vanish(mod, mod);

        mod.disconnect();
        demote(mod);
        mod.reconnect();

        assertFalse(vanish.isVanished(mod));
        assertFalse(vanish.isMarkedVanished(mod.getUniqueId()));
        assertTrue(alice.canSee(mod));
        assertFalse(mod.getAllowFlight());
        assertTrue(messages(mod).contains(
                "[Vanish] You're visible again because you no longer have permission to vanish."));
    }

    @Test
    void losingPermissionWhileOnlineMakesThePlayerVisible() {
        TestPlayer mod = staff("Mod");
        TestPlayer alice = regular("Alice");
        vanish.vanish(mod, mod);

        demote(mod);
        server.getScheduler().performTicks(module.settings().refreshIntervalTicks());

        assertFalse(vanish.isVanished(mod));
        assertTrue(alice.canSee(mod));
    }

    @Test
    void gainingSeePermissionWhileOnlineRevealsVanishedStaff() {
        TestPlayer mod = staff("Mod");
        TestPlayer alice = regular("Alice");
        vanish.vanish(mod, mod);
        assertFalse(alice.canSee(mod));

        alice.addAttachment(plugin, VanishPermissions.SEE, true);
        server.getScheduler().performTicks(module.settings().refreshIntervalTicks());

        assertTrue(alice.canSee(mod));
    }

    @Test
    void vanishedPlayersGetAnActionBarReminder() {
        TestPlayer mod = staff("Mod");
        vanish.vanish(mod, mod);

        server.getScheduler().performTicks(module.settings().refreshIntervalTicks());

        assertEquals("You are vanished | Only staff can see you",
                PlainTextComponentSerializer.plainText().serialize(mod.nextActionBar()));
    }

    @Test
    void vanishSurvivesAPluginReload() {
        TestPlayer mod = staff("Mod");
        TestPlayer alice = regular("Alice");
        vanish.vanish(mod, mod);

        server.getPluginManager().disablePlugin(plugin);
        assertTrue(alice.canSee(mod), "players are revealed while the plugin is disabled");
        assertTrue(mod.getAllowFlight(), "flight is kept so nobody falls");

        server.getPluginManager().enablePlugin(plugin);
        VanishManager reloaded = plugin.vanish().manager();
        assertTrue(reloaded.isVanished(mod));
        assertFalse(alice.canSee(mod));
    }

    private static void assertTranslatable(String key, Component message) {
        TranslatableComponent translatable = assertInstanceOf(TranslatableComponent.class, message);
        assertEquals(key, translatable.key());
    }

    /** Records the final join and quit messages, after every other listener has run. */
    private static final class MessageRecorder implements Listener {
        private final List<Component> messages = new ArrayList<>();

        @EventHandler(priority = EventPriority.MONITOR)
        public void onJoin(PlayerJoinEvent event) {
            messages.add(event.joinMessage());
        }

        @EventHandler(priority = EventPriority.MONITOR)
        public void onQuit(PlayerQuitEvent event) {
            messages.add(event.quitMessage());
        }
    }
}
