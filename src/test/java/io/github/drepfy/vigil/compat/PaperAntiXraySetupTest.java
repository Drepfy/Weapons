package io.github.drepfy.vigil.compat;

import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperAntiXraySetupTest {

    private static final String DEFAULTS = """
            # This is the world defaults configuration file for Paper.
            _version: 31
            anticheat:
              anti-xray:
                enabled: false
                engine-mode: 1
                hidden-blocks:
                - copper_ore
                - chest
                lava-obscures: false
                max-block-height: 64
                replacement-blocks:
                - stone
                - oak_planks
                update-radius: 2
                use-permission: false
              obfuscation:
                items:
                  hide-durability: false
            chunks:
              auto-save-interval: default
            feature-seeds:
              generate-random-seeds-for-all: false
            """;

    private static final Logger LOGGER = Logger.getLogger("test");

    @Test
    void switchesAntiXrayOnOnceWithBackups(@TempDir Path root) throws Exception {
        Path config = root.resolve("config");
        Files.createDirectories(config);
        Path defaults = config.resolve("paper-world-defaults.yml");
        Files.writeString(defaults, DEFAULTS, StandardCharsets.UTF_8);
        Path nether = root.resolve("world_nether");
        Files.createDirectories(nether);
        Files.writeString(nether.resolve("paper-world.yml"), "# world config\n_version: 31\n", StandardCharsets.UTF_8);
        Path end = root.resolve("world_the_end");
        Path marker = root.resolve("plugins/Vigil/data/paper-anti-xray.yml");
        List<PaperAntiXraySetup.WorldInfo> worlds = List.of(
                new PaperAntiXraySetup.WorldInfo("world", root.resolve("world"), World.Environment.NORMAL),
                new PaperAntiXraySetup.WorldInfo("world_nether", nether, World.Environment.NETHER),
                new PaperAntiXraySetup.WorldInfo("world_the_end", end, World.Environment.THE_END));

        PaperAntiXraySetup.run(root, marker, worlds, true, LOGGER);

        YamlLineEditor edited = new YamlLineEditor(Files.readAllLines(defaults));
        assertEquals("true", edited.value(edited.find("anticheat.anti-xray.enabled")));
        assertEquals("2", edited.value(edited.find("anticheat.anti-xray.engine-mode")));
        String text = Files.readString(defaults);
        assertTrue(text.contains("    - deepslate_diamond_ore"), text);
        assertFalse(text.contains("    - chest\n    lava-obscures"), "the old hidden list is replaced");
        assertTrue(text.contains("  obfuscation:\n    items:\n      hide-durability: false"), "other settings untouched");
        assertTrue(text.contains("generate-random-seeds-for-all: false"), "feature seeds are not touched");
        assertTrue(text.startsWith("# This is the world defaults configuration file for Paper."), "comments kept");
        assertEquals(DEFAULTS, Files.readString(config.resolve("paper-world-defaults.yml.vigil-backup")));

        String netherText = Files.readString(nether.resolve("paper-world.yml"));
        assertTrue(netherText.contains("_version: 31") && netherText.contains("    - ancient_debris"), netherText);
        assertTrue(Files.readString(end.resolve("paper-world.yml")).contains("enabled: false"));
        assertTrue(Files.exists(marker));

        // Turned off again by an admin: left alone.
        Files.writeString(defaults, DEFAULTS, StandardCharsets.UTF_8);
        PaperAntiXraySetup.run(root, marker, worlds, true, LOGGER);
        assertEquals(DEFAULTS, Files.readString(defaults));
    }

    @Test
    void nothingChangesWhenDisabledOrAlreadyOn(@TempDir Path root) throws Exception {
        Path config = root.resolve("config");
        Files.createDirectories(config);
        Path defaults = config.resolve("paper-world-defaults.yml");
        Files.writeString(defaults, DEFAULTS, StandardCharsets.UTF_8);
        Path marker = root.resolve("marker.yml");
        PaperAntiXraySetup.run(root, marker, List.of(), false, LOGGER);
        assertEquals(DEFAULTS, Files.readString(defaults));

        String on = DEFAULTS.replace("enabled: false", "enabled: true");
        Files.writeString(defaults, on, StandardCharsets.UTF_8);
        PaperAntiXraySetup.run(root, marker, List.of(), true, LOGGER);
        assertEquals(on, Files.readString(defaults));
        assertFalse(Files.exists(marker));
    }

    @Test
    void listsFromTheFirstVersionGetFakeCaves(@TempDir Path root) throws Exception {
        Path config = root.resolve("config");
        Files.createDirectories(config);
        Path defaults = config.resolve("paper-world-defaults.yml");
        StringBuilder v1 = new StringBuilder("anticheat:\n  anti-xray:\n    enabled: true\n    engine-mode: 2\n"
                + "    hidden-blocks:\n");
        for (String block : PaperAntiXraySetup.OVERWORLD_HIDDEN.subList(1, PaperAntiXraySetup.OVERWORLD_HIDDEN.size())) {
            v1.append("    - ").append(block).append('\n');
        }
        v1.append("    lava-obscures: false\n");
        Files.writeString(defaults, v1.toString(), StandardCharsets.UTF_8);
        Path marker = root.resolve("marker.yml");
        Files.writeString(marker, "configured: 1\n", StandardCharsets.UTF_8);

        PaperAntiXraySetup.run(root, marker, List.of(), true, LOGGER);
        String text = Files.readString(defaults);
        assertTrue(text.contains("    hidden-blocks:\n    - air\n    - copper_ore"), text);
        assertTrue(text.contains("lava-obscures: false"));
        assertTrue(Files.readString(marker).contains("lists-version: 2"));

        // Customised lists are left alone.
        String custom = "anticheat:\n  anti-xray:\n    enabled: true\n    hidden-blocks:\n    - diamond_ore\n";
        Files.writeString(defaults, custom, StandardCharsets.UTF_8);
        Files.writeString(marker, "configured: 1\n", StandardCharsets.UTF_8);
        PaperAntiXraySetup.run(root, marker, List.of(), true, LOGGER);
        assertEquals(custom, Files.readString(defaults));
    }

    @Test
    void noPaperConfigMeansNoChanges(@TempDir Path root) {
        PaperAntiXraySetup.run(root, root.resolve("marker.yml"), List.of(), true, LOGGER);
        assertFalse(Files.exists(root.resolve("config")));
    }

    @Test
    void editorHandlesInlineListsAndQuotedKeys() {
        YamlLineEditor editor = new YamlLineEditor(List.of("a:", "  'b':", "    list: [x, y]", "    after: 1"));
        int list = editor.find("a.b.list");
        assertEquals(2, list);
        editor.setList(list, List.of("z"));
        assertEquals(List.of("a:", "  'b':", "    list:", "    - z", "    after: 1"), editor.lines());
        assertEquals("1", editor.value(editor.find("a.b.after")));
        assertEquals(-1, editor.find("a.after"));
    }
}
