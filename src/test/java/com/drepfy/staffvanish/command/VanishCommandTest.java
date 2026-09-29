package com.drepfy.staffvanish.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drepfy.staffvanish.VanishPermissions;
import com.drepfy.staffvanish.VanishTestBase;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.junit.jupiter.api.Test;

class VanishCommandTest extends VanishTestBase {

    @Test
    void vanishTogglesTheSender() {
        TestPlayer mod = staff("Mod");
        messages(mod);

        mod.performCommand("vanish");
        assertTrue(vanish.isVanished(mod));
        assertEquals(List.of("Mod left the game", "ᴠᴀɴɪʟʟᴀ sᴍᴘ » You are now vanished. Only staff can see you."),
                messages(mod));

        mod.performCommand("v");
        assertFalse(vanish.isVanished(mod));
        assertEquals(List.of("Mod joined the game", "ᴠᴀɴɪʟʟᴀ sᴍᴘ » You are now visible to everyone."), messages(mod));
    }

    @Test
    void takesOverVanishFromOtherPlugins() {
        Map<String, Command> knownCommands = server.getCommandMap().getKnownCommands();
        Command otherVanish = new Command("vanish") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] args) {
                sender.sendMessage("the other plugin's vanish");
                return true;
            }
        };
        knownCommands.put("vanish", otherVanish);
        knownCommands.put("v", otherVanish);

        server.getScheduler().performOneTick();

        assertSame(plugin.getCommand("vanish"), knownCommands.get("vanish"));
        assertSame(plugin.getCommand("vanish"), knownCommands.get("v"));
        TestPlayer mod = staff("Mod");
        mod.performCommand("v");
        assertTrue(vanish.isVanished(mod));
    }

    @Test
    void onAndOffAreIdempotent() {
        TestPlayer mod = staff("Mod");
        mod.performCommand("vanish on");
        messages(mod);

        mod.performCommand("vanish on");
        assertTrue(vanish.isVanished(mod));
        assertEquals(List.of("ᴠᴀɴɪʟʟᴀ sᴍᴘ » Mod is already vanished."), messages(mod));

        mod.performCommand("vanish off");
        mod.performCommand("vanish off");
        assertFalse(vanish.isVanished(mod));
    }

    @Test
    void staffCanVanishOtherStaffButNotRegularPlayers() {
        TestPlayer mod = staff("Mod");
        TestPlayer admin = staff("Admin");
        TestPlayer alice = regular("Alice");
        messages(mod);
        messages(admin);

        mod.performCommand("vanish Admin");
        assertTrue(vanish.isVanished(admin));
        assertEquals(List.of("Admin left the game", "ᴠᴀɴɪʟʟᴀ sᴍᴘ » Vanished Admin."), messages(mod));
        assertEquals(List.of("Admin left the game", "ᴠᴀɴɪʟʟᴀ sᴍᴘ » You are now vanished. Only staff can see you.",
                "ᴠᴀɴɪʟʟᴀ sᴍᴘ » Mod vanished you."), messages(admin));

        mod.performCommand("vanish on Alice");
        assertFalse(vanish.isVanished(alice));
        assertEquals(List.of("ᴠᴀɴɪʟʟᴀ sᴍᴘ » Alice doesn't have permission to vanish."), messages(mod));
    }

    @Test
    void togglingOthersNeedsPermission() {
        TestPlayer mod = staff("Mod");
        TestPlayer admin = staff("Admin");
        mod.addAttachment(plugin, VanishPermissions.OTHERS, false);
        messages(mod);

        mod.performCommand("vanish Admin");

        assertFalse(vanish.isVanished(admin));
        assertEquals(List.of("ᴠᴀɴɪʟʟᴀ sᴍᴘ » You don't have permission to do that."), messages(mod));
    }

    @Test
    void listShowsOnlineAndOfflineVanishedPlayers() {
        TestPlayer mod = staff("Mod");
        TestPlayer admin = staff("Admin");
        vanish.vanish(mod, mod);
        vanish.vanish(admin, admin);
        admin.disconnect();
        messages(mod);

        mod.performCommand("vanish list");

        String list = messages(mod).getFirst();
        assertTrue(list.startsWith("ᴠᴀɴɪʟʟᴀ sᴍᴘ » Vanished (2): "), list);
        assertTrue(list.contains("Mod"), list);
        assertTrue(list.contains("Admin (offline)"), list);
    }

    @Test
    void teleportIsOnlyForVanishedStaff() {
        TestPlayer mod = staff("Mod");
        TestPlayer alice = regular("Alice");
        alice.teleport(new Location(world, 50, 80, 50));
        messages(mod);

        mod.performCommand("vanish tp Alice");
        assertEquals(List.of("ᴠᴀɴɪʟʟᴀ sᴍᴘ » You need to be vanished to do that."), messages(mod));

        mod.performCommand("vanish");
        messages(mod);
        mod.performCommand("vanish tp Alice");
        assertEquals(alice.getLocation(), mod.getLocation());
        assertEquals(List.of("ᴠᴀɴɪʟʟᴀ sᴍᴘ » Teleported to Alice."), messages(mod));
    }

    @Test
    void selectorCommandReplacesALostSelector() {
        TestPlayer mod = staff("Mod");
        mod.performCommand("vanish");
        mod.getInventory().clear();
        messages(mod);

        mod.performCommand("vanish stick");

        assertTrue(hasSelector(mod));
        assertEquals(List.of("ᴠᴀɴɪʟʟᴀ sᴍᴘ » You received the ᴠᴀɴɪsʜ sᴛɪᴄᴋ."), messages(mod));
    }

    @Test
    void tabCompletionNeverSuggestsPlayersTheSenderCannotSee() {
        TestPlayer mod = staff("Mod");
        TestPlayer admin = staff("Admin");
        TestPlayer helper = regular("Helper");
        helper.addAttachment(plugin, VanishPermissions.OTHERS, true);
        vanish.vanish(mod, mod);
        PluginCommand command = plugin.getCommand("vanish");
        assertNotNull(command);
        TabCompleter completer = command.getTabCompleter();
        assertNotNull(completer);

        List<String> helperNames = completer.onTabComplete(helper, command, "vanish", new String[] {"on", ""});
        List<String> adminNames = completer.onTabComplete(admin, command, "vanish", new String[] {"on", ""});

        assertEquals(List.of("Admin", "Helper"), helperNames.stream().sorted().toList());
        assertEquals(List.of("Admin", "Helper", "Mod"), adminNames.stream().sorted().toList());
        assertEquals(List.of("list"), completer.onTabComplete(admin, command, "vanish", new String[] {"li"}));
    }

    @Test
    void reloadAppliesNewSettings() {
        TestPlayer mod = staff("Mod");
        mod.performCommand("vanish");
        plugin.getConfig().set("vanish.show-in-tab-for-staff", true);
        plugin.getConfig().set("vanish.tab-list-format", "<name> &7hidden");
        plugin.saveConfig();
        messages(mod);

        mod.performCommand("vanish reload");

        assertEquals(List.of("ᴠᴀɴɪʟʟᴀ sᴍᴘ » Configuration reloaded."), messages(mod));
        assertEquals("Mod hidden", PlainTextComponentSerializer.plainText().serialize(mod.playerListName()));
    }

    @Test
    void optionsMissingFromAnOlderConfigUseTheDefaults() {
        TestPlayer mod = staff("Mod");
        plugin.getConfig().set("messages.vanished", null);
        plugin.getConfig().set("selector.item.name", null);
        plugin.saveConfig();
        module.reload();
        messages(mod);

        mod.performCommand("vanish");

        assertEquals(List.of("Mod left the game", "ᴠᴀɴɪʟʟᴀ sᴍᴘ » You are now vanished. Only staff can see you."),
                messages(mod));
        assertEquals("ᴠᴀɴɪsʜ sᴛɪᴄᴋ", PlainTextComponentSerializer.plainText()
                .serialize(mod.getInventory().getItem(8).getItemMeta().customName()));
    }

    @Test
    void vanishedPlayersAreSavedToDisk() throws Exception {
        TestPlayer mod = staff("Mod");
        mod.performCommand("vanish");

        Path data = plugin.getDataFolder().toPath().resolve("vanish-data.yml");
        assertTrue(Files.readString(data).contains(mod.getUniqueId().toString()));

        mod.performCommand("vanish");
        assertFalse(Files.readString(data).contains(mod.getUniqueId().toString()));
    }
}
