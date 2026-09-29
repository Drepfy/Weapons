package io.github.drepfy.vigil.compat;

import org.bukkit.World;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Turns on Paper's built-in anti-xray, which is the only reliable x-ray protection: the
 * server sends fake ores inside solid stone, so x-ray texture packs, x-ray mods and ore
 * ESP show thousands of ores that are not there, while real ores are only revealed once
 * they are next to air.
 *
 * <p>Done once, only while Paper's anti-xray is off, with a backup of every changed file:
 * engine-mode 2 with Paper's recommended block lists in
 * {@code config/paper-world-defaults.yml}, nether lists in each nether world's
 * {@code paper-world.yml}, and anti-xray off in the end (no ores there). A restart is
 * needed for Paper to pick it up. If an admin turns it off again later, Vigil leaves it off.
 */
public final class PaperAntiXraySetup {

    /** A world as far as this setup is concerned. */
    public record WorldInfo(String name, Path folder, World.Environment environment) {
    }

    static final List<String> OVERWORLD_HIDDEN = List.of("copper_ore", "deepslate_copper_ore", "raw_copper_block",
            "diamond_ore", "deepslate_diamond_ore", "gold_ore", "deepslate_gold_ore", "iron_ore", "deepslate_iron_ore",
            "raw_iron_block", "lapis_ore", "deepslate_lapis_ore", "redstone_ore", "deepslate_redstone_ore");
    static final List<String> OVERWORLD_REPLACEMENT = List.of("chest", "amethyst_block", "andesite",
            "budding_amethyst", "calcite", "coal_ore", "deepslate_coal_ore", "deepslate", "diorite", "dirt",
            "emerald_ore", "deepslate_emerald_ore", "granite", "gravel", "oak_planks", "smooth_basalt", "stone", "tuff");
    static final List<String> NETHER_HIDDEN = List.of("ancient_debris", "bone_block", "glowstone", "magma_block",
            "nether_bricks", "nether_gold_ore", "nether_quartz_ore", "polished_blackstone_bricks");
    static final List<String> NETHER_REPLACEMENT = List.of("basalt", "blackstone", "gravel", "netherrack",
            "soul_sand", "soul_soil");

    private PaperAntiXraySetup() {
    }

    /**
     * Checks Paper's anti-xray and, if allowed and never done before, switches it on.
     *
     * @param serverRoot the server directory (holding {@code config/})
     * @param marker     file recording that the setup already happened
     * @param setup      whether Vigil may change Paper's configuration
     */
    public static void run(Path serverRoot, Path marker, List<WorldInfo> worlds, boolean setup, Logger logger) {
        Path defaults = serverRoot.resolve("config").resolve("paper-world-defaults.yml");
        if (!Files.isRegularFile(defaults)) {
            logger.info("Anti-xray: Paper's configuration was not found (not a Paper server?). Use an anti-xray "
                    + "plugin such as Orebfuscator for ore protection.");
            return;
        }
        try {
            YamlLineEditor editor = new YamlLineEditor(Files.readAllLines(defaults, StandardCharsets.UTF_8));
            int enabled = editor.find("anticheat.anti-xray.enabled");
            int mode = editor.find("anticheat.anti-xray.engine-mode");
            if (enabled < 0) {
                logger.warning("Anti-xray: unexpected format of " + defaults.getFileName() + "; nothing changed.");
                return;
            }
            if (editor.value(enabled).equalsIgnoreCase("true")) {
                logger.info("Anti-xray: Paper anti-xray is on (engine-mode "
                        + (mode >= 0 ? editor.value(mode) : "?") + "). Ores are hidden from x-ray.");
                return;
            }
            if (Files.exists(marker)) {
                logger.warning("Anti-xray: Paper anti-xray is OFF. Vigil turned it on before, so it was switched off "
                        + "on purpose and is left off. X-ray mods can see ores.");
                return;
            }
            if (!setup) {
                logger.warning("Anti-xray: Paper anti-xray is OFF, so x-ray mods can see ores. Set "
                        + "anticheat.setup-paper-anti-xray: true (or enable it in config/paper-world-defaults.yml).");
                return;
            }

            List<Path> changed = new ArrayList<>();
            editor.setScalar(enabled, "true");
            if (mode >= 0) {
                editor.setScalar(mode, "2");
            }
            int hidden = editor.find("anticheat.anti-xray.hidden-blocks");
            if (hidden >= 0) {
                editor.setList(hidden, OVERWORLD_HIDDEN);
            }
            int replacement = editor.find("anticheat.anti-xray.replacement-blocks");
            if (replacement >= 0) {
                editor.setList(replacement, OVERWORLD_REPLACEMENT);
            }
            write(defaults, editor.lines(), changed);

            for (WorldInfo world : worlds) {
                if (world.environment() == World.Environment.NETHER) {
                    addWorldSection(world.folder().resolve("paper-world.yml"), netherSection(), changed);
                } else if (world.environment() == World.Environment.THE_END) {
                    addWorldSection(world.folder().resolve("paper-world.yml"),
                            List.of("anticheat:", "  anti-xray:", "    enabled: false"), changed);
                }
            }
            Files.createDirectories(marker.getParent());
            Files.writeString(marker, "# Vigil switched on Paper anti-xray here. Delete this file to let it do so again.\n"
                    + "configured: " + System.currentTimeMillis() + "\n", StandardCharsets.UTF_8);
            logger.warning("Anti-xray: Paper anti-xray has been switched ON (engine-mode 2) in " + changed.size()
                    + " file(s). RESTART the server (not /reload) to activate it. Backups end in .vigil-backup.");
        } catch (IOException | RuntimeException e) {
            logger.warning("Anti-xray: could not set up Paper anti-xray (" + e.getMessage() + "); nothing was changed "
                    + "that could not be restored from the .vigil-backup files.");
        }
    }

    private static List<String> netherSection() {
        List<String> lines = new ArrayList<>(List.of("anticheat:", "  anti-xray:", "    max-block-height: 128",
                "    hidden-blocks:"));
        for (String block : NETHER_HIDDEN) {
            lines.add("    - " + block);
        }
        lines.add("    replacement-blocks:");
        for (String block : NETHER_REPLACEMENT) {
            lines.add("    - " + block);
        }
        return lines;
    }

    /** Adds an anti-xray section to a world file unless it already has an {@code anticheat} section. */
    private static void addWorldSection(Path file, List<String> section, List<Path> changed) throws IOException {
        List<String> lines = Files.isRegularFile(file) ? Files.readAllLines(file, StandardCharsets.UTF_8) : List.of();
        if (new YamlLineEditor(lines).find("anticheat") >= 0) {
            return;
        }
        List<String> result = new ArrayList<>(lines);
        result.addAll(section);
        write(file, result, changed);
    }

    private static void write(Path file, List<String> lines, List<Path> changed) throws IOException {
        if (Files.exists(file)) {
            Path backup = file.resolveSibling(file.getFileName() + ".vigil-backup");
            if (!Files.exists(backup)) {
                Files.copy(file, backup);
            }
        } else if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path temp = file.resolveSibling(file.getFileName() + ".vigil-tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        changed.add(file);
    }
}
