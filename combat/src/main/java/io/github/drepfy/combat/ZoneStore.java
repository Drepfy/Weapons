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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/** The safe zones, in {@code zones.yml} (made with {@code /combat zone}). */
final class ZoneStore {

    private final Path file;
    private final Logger logger;
    private final Map<String, SafeZone> zones = new LinkedHashMap<>();

    ZoneStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        load();
    }

    List<SafeZone> all() {
        return new ArrayList<>(zones.values());
    }

    SafeZone get(String name) {
        return zones.get(name.toLowerCase(Locale.ROOT));
    }

    void put(SafeZone zone) {
        zones.put(zone.name().toLowerCase(Locale.ROOT), zone);
        save();
    }

    boolean remove(String name) {
        boolean removed = zones.remove(name.toLowerCase(Locale.ROOT)) != null;
        if (removed) {
            save();
        }
        return removed;
    }

    /** The zone at a location, or {@code null}. */
    SafeZone at(org.bukkit.Location location) {
        for (SafeZone zone : zones.values()) {
            if (zone.contains(location)) {
                return zone;
            }
        }
        return null;
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
        } catch (IOException | InvalidConfigurationException e) {
            logger.severe(file.getFileName() + " could not be read (" + e.getMessage() + "); there are no safe zones "
                    + "until it is fixed. The file was not changed.");
            return;
        }
        ConfigurationSection section = yaml.getConfigurationSection("zones");
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            ConfigurationSection zone = section.getConfigurationSection(name);
            if (zone == null || !zone.isString("world")) {
                logger.warning("Safe zone '" + name + "' in " + file.getFileName() + " is incomplete and is ignored.");
                continue;
            }
            SafeZone loaded = SafeZone.of(name, zone.getString("world"), zone.getInt("x1"), zone.getInt("z1"),
                    zone.getInt("x2"), zone.getInt("z2"));
            zones.put(name.toLowerCase(Locale.ROOT), loaded);
        }
    }

    private void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(List.of("Safe zones that players in combat cannot enter. Made with /combat zone."));
        for (SafeZone zone : zones.values()) {
            String path = "zones." + zone.name();
            yaml.set(path + ".world", zone.world());
            yaml.set(path + ".x1", zone.minX());
            yaml.set(path + ".z1", zone.minZ());
            yaml.set(path + ".x2", zone.maxX());
            yaml.set(path + ".z2", zone.maxZ());
        }
        // Written to a temporary file first: a crash while saving never leaves a half-written file.
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temp = Files.createTempFile(parent, "zones", ".tmp");
            try {
                Files.writeString(temp, yaml.saveToString(), StandardCharsets.UTF_8);
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
}
