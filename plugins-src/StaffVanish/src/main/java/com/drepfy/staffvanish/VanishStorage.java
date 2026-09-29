package com.drepfy.staffvanish;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;

/**
 * Remembers who is vanished, so players stay vanished when they reconnect or the server restarts. The file is
 * rewritten on every change; vanish toggles are rare and the file is tiny.
 */
public final class VanishStorage {

    /**
     * @param name          last known name, to keep the file readable and for {@code /vanish list}
     * @param restoreFlight whether the player could fly before vanishing, independent of their game mode
     */
    public record Entry(String name, boolean restoreFlight) {
    }

    private final Path file;
    private final Logger logger;
    /** Concurrent because the server list ping reads it off the main thread. */
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public VanishStorage(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public void load() {
        entries.clear();
        if (!Files.exists(file)) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file.toFile());
        ConfigurationSection players = yaml.getConfigurationSection("players");
        if (players == null) {
            return;
        }
        for (String key : players.getKeys(false)) {
            UUID id;
            try {
                id = UUID.fromString(key);
            } catch (IllegalArgumentException e) {
                logger.warning("Ignoring invalid UUID '" + key + "' in " + file.getFileName());
                continue;
            }
            entries.put(id, new Entry(
                    players.getString(key + ".name", key),
                    players.getBoolean(key + ".restore-flight")));
        }
    }

    public @Nullable Entry get(UUID id) {
        return entries.get(id);
    }

    public boolean contains(UUID id) {
        return entries.containsKey(id);
    }

    public Map<UUID, Entry> entries() {
        return Collections.unmodifiableMap(entries);
    }

    public void put(UUID id, Entry entry) {
        if (!entry.equals(entries.put(id, entry))) {
            save();
        }
    }

    public @Nullable Entry remove(UUID id) {
        Entry removed = entries.remove(id);
        if (removed != null) {
            save();
        }
        return removed;
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of(
                "Players who are vanished. They stay vanished when they reconnect.",
                "Managed by the plugin - use /vanish instead of editing this file."));
        entries.forEach((id, entry) -> {
            yaml.set("players." + id + ".name", entry.name());
            yaml.set("players." + id + ".restore-flight", entry.restoreFlight());
        });
        try {
            Files.createDirectories(file.getParent());
            Path temp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, "Could not save " + file.getFileName(), e);
        }
    }
}
