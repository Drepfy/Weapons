package io.github.drepfy.combat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * {@code data.yml}: combat time left per player (paused while offline) and when each
 * player's Ender Pearl cooldown ends. Written to a temporary file that then replaces the
 * old one, so a crash never leaves it half-written.
 */
final class DataFile {

    private final Path file;
    private final Logger logger;

    DataFile(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    /** Loads into the tracker (as paused) and the pearl map. */
    void load(CombatTracker tracker, Map<UUID, Long> pearls, long now) {
        if (!Files.exists(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException e) {
            logger.warning(file.getFileName() + " could not be read (" + e.getMessage() + "); combat timers and pearl "
                    + "cooldowns start fresh.");
            return;
        }
        ConfigurationSection combat = yaml.getConfigurationSection("combat");
        if (combat != null) {
            for (String player : combat.getKeys(false)) {
                ConfigurationSection opponents = combat.getConfigurationSection(player);
                if (opponents == null) {
                    continue;
                }
                Map<UUID, Long> remaining = new HashMap<>();
                for (String opponent : opponents.getKeys(false)) {
                    long left = opponents.getLong(opponent);
                    UUID id = uuid(opponent);
                    if (left > 0 && id != null) {
                        remaining.put(id, left);
                    }
                }
                UUID id = uuid(player);
                if (id != null) {
                    tracker.restore(id, remaining, now);
                }
            }
        }
        ConfigurationSection pearlSection = yaml.getConfigurationSection("ender-pearls");
        if (pearlSection != null) {
            for (String player : pearlSection.getKeys(false)) {
                UUID id = uuid(player);
                if (id != null) {
                    pearls.put(id, pearlSection.getLong(player));
                }
            }
        }
    }

    /** A copy of what to save, made on the server thread. */
    String snapshot(CombatTracker tracker, Map<UUID, Long> pearls, long now) {
        YamlConfiguration yaml = new YamlConfiguration();
        for (UUID player : tracker.players()) {
            Map<UUID, Long> opponents = tracker.opponents(player, now);
            for (Map.Entry<UUID, Long> entry : opponents.entrySet()) {
                yaml.set("combat." + player + "." + entry.getKey(), entry.getValue());
            }
        }
        for (Map.Entry<UUID, Long> pearl : pearls.entrySet()) {
            if (pearl.getValue() > now) {
                yaml.set("ender-pearls." + pearl.getKey(), pearl.getValue());
            }
        }
        return yaml.saveToString();
    }

    void write(String content) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, "data", ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
        } catch (IOException e) {
            logger.warning("Could not save " + file.getFileName() + ": " + e.getMessage());
        }
    }

    private static UUID uuid(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
