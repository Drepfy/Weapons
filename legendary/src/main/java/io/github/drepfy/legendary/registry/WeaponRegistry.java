package io.github.drepfy.legendary.registry;

import io.github.drepfy.legendary.WeaponType;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.logging.Logger;

/**
 * Every legendary weapon ever given out, where it is now, and the (hashed) IP addresses
 * players joined from for alt protection. Kept in {@code data.yml}; every change is also
 * written to {@code history.log}.
 *
 * <p>Used on the server thread only. Saving copies the data there and writes it on a
 * background thread to a temporary file that then replaces the old one, so a crash can
 * never leave a half-written file.
 */
public final class WeaponRegistry {

    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final Logger logger;
    private final Path file;
    private final Path history;
    private final LongSupplier clock;
    private final ExecutorService io;
    private final Map<UUID, WeaponRecord> records = new LinkedHashMap<>();
    /** Player → IP hash → when it was last used. */
    private final Map<UUID, Map<String, Long>> ips = new HashMap<>();
    private String salt;
    private boolean dirty;
    private boolean readOnly;

    public WeaponRegistry(Logger logger, Path folder, LongSupplier clock) {
        this.logger = logger;
        this.file = folder.resolve("data.yml");
        this.history = folder.resolve("history.log");
        this.clock = clock;
        this.io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Legendary-IO");
            thread.setDaemon(true);
            return thread;
        });
        load();
        if (salt == null) {
            byte[] bytes = new byte[16];
            new SecureRandom().nextBytes(bytes);
            salt = Base64.getEncoder().encodeToString(bytes);
            dirty = true;
        }
    }

    // ---- weapons ----------------------------------------------------------------------------------------

    public WeaponRecord get(UUID id) {
        return records.get(id);
    }

    public Collection<WeaponRecord> all() {
        return Collections.unmodifiableCollection(records.values());
    }

    /** The weapons of this type that are held or on the ground. */
    public List<WeaponRecord> existing(WeaponType type) {
        List<WeaponRecord> result = new ArrayList<>();
        for (WeaponRecord record : records.values()) {
            if (record.type() == type && record.exists()) {
                result.add(record);
            }
        }
        return result;
    }

    /** A new weapon with a fresh id. */
    public WeaponRecord create(WeaponType type, String by) {
        UUID id;
        do {
            id = UUID.randomUUID();
        } while (records.containsKey(id));
        WeaponRecord record = new WeaponRecord(id, type, clock.getAsLong(), by);
        records.put(id, record);
        changed();
        return record;
    }

    /** Registers a weapon found in the world that is not on the list (data.yml lost or restored). */
    public WeaponRecord adopt(UUID id, WeaponType type) {
        WeaponRecord record = new WeaponRecord(id, type, clock.getAsLong(), "found in the world");
        records.put(id, record);
        changed();
        return record;
    }

    public void held(WeaponRecord record, UUID player, String name, Location at) {
        boolean moved = record.state() != WeaponRecord.State.HELD || !player.equals(record.holder());
        record.held(player, name, at, clock.getAsLong());
        if (moved) {
            changed();
        } else {
            dirty = true;
        }
    }

    public void ground(WeaponRecord record, UUID item, Location at) {
        boolean moved = record.state() != WeaponRecord.State.GROUND || !item.equals(record.entity());
        record.ground(item, at, clock.getAsLong());
        if (moved) {
            changed();
        } else {
            dirty = true;
        }
    }

    /** Only the position changed (no hurry to save). */
    public void at(WeaponRecord record, Location at) {
        record.at(at, clock.getAsLong());
        dirty = true;
    }

    public void lost(WeaponRecord record, String why) {
        record.lost(why, clock.getAsLong());
        changed();
    }

    public void removed(WeaponRecord record, String why) {
        record.removed(why, clock.getAsLong());
        changed();
    }

    // ---- accounts --------------------------------------------------------------------------------------

    /** Remembers that the player joined from this address (only a salted hash is kept). */
    public void recordIp(UUID player, String address) {
        if (address == null || address.isEmpty()) {
            return;
        }
        ips.computeIfAbsent(player, key -> new HashMap<>()).put(hash(address), clock.getAsLong());
        dirty = true;
    }

    /**
     * Whether two accounts joined from the same address since {@code sinceEpochMs}. An address
     * used by more than {@code maxAccounts} accounts is a shared network and does not count.
     */
    public boolean shareIp(UUID a, UUID b, long sinceEpochMs, int maxAccounts) {
        Map<String, Long> first = ips.get(a);
        Map<String, Long> second = ips.get(b);
        if (first == null || second == null) {
            return false;
        }
        for (Map.Entry<String, Long> ip : first.entrySet()) {
            Long other = second.get(ip.getKey());
            if (ip.getValue() >= sinceEpochMs && other != null && other >= sinceEpochMs
                    && accountsOn(ip.getKey(), sinceEpochMs) <= maxAccounts) {
                return true;
            }
        }
        return false;
    }

    private int accountsOn(String hash, long since) {
        int count = 0;
        for (Map<String, Long> player : ips.values()) {
            Long seen = player.get(hash);
            if (seen != null && seen >= since) {
                count++;
            }
        }
        return count;
    }

    String hash(String address) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest((salt + "|" + address).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(bytes, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---- history ---------------------------------------------------------------------------------------

    /** Adds a line to history.log (on the background thread). */
    public void log(String line) {
        String stamped = LocalDateTime.ofInstant(Instant.ofEpochMilli(clock.getAsLong()), ZoneId.systemDefault())
                .format(LOG_TIME) + "  " + line + System.lineSeparator();
        try {
            io.execute(() -> {
                try {
                    Files.createDirectories(history.toAbsolutePath().getParent());
                    Files.writeString(history, stamped, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                } catch (IOException e) {
                    logger.fine("Could not write history.log: " + e.getMessage());
                }
            });
        } catch (RejectedExecutionException ignored) {
            // Shutting down.
        }
    }

    // ---- saving ----------------------------------------------------------------------------------------

    /** A weapon changed hands, was made or removed: written straight away. */
    private void changed() {
        dirty = true;
        save(Long.MIN_VALUE);
    }

    public boolean isDirty() {
        return dirty;
    }

    /** Writes the file in the background, dropping IP addresses last used before {@code ipCutoff}. */
    public void save(long ipCutoffEpochMs) {
        if (!dirty || readOnly) {
            return;
        }
        prune(ipCutoffEpochMs);
        String snapshot = serialize();
        dirty = false;
        try {
            io.execute(() -> write(snapshot));
        } catch (RejectedExecutionException e) {
            dirty = true;
        }
    }

    /** Writes everything now and stops the background thread (plugin shutdown). */
    public void close(long ipCutoffEpochMs) {
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (dirty && !readOnly) {
            prune(ipCutoffEpochMs);
            write(serialize());
            dirty = false;
        }
    }

    private void prune(long ipCutoff) {
        for (Map<String, Long> player : ips.values()) {
            player.values().removeIf(seen -> seen < ipCutoff);
        }
        ips.values().removeIf(Map::isEmpty);
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 1);
        yaml.set("salt", salt);
        for (WeaponRecord record : records.values()) {
            ConfigurationSection section = yaml.createSection("weapons." + record.id());
            section.set("type", record.type().key());
            section.set("state", record.state().name());
            section.set("created", record.created());
            section.set("created-by", record.createdBy());
            setUuid(section, "holder", record.holder());
            section.set("holder-name", record.holderName());
            setUuid(section, "previous-holder", record.previousHolder());
            section.set("previous-holder-name", record.previousHolderName());
            setUuid(section, "entity", record.entity());
            if (record.world() != null) {
                section.set("world", record.world());
                section.set("x", round(record.x()));
                section.set("y", round(record.y()));
                section.set("z", round(record.z()));
            }
            section.set("updated", record.updated());
            section.set("note", record.note());
        }
        for (Map.Entry<UUID, Map<String, Long>> player : ips.entrySet()) {
            yaml.createSection("accounts." + player.getKey(), new HashMap<>(player.getValue()));
        }
        return yaml.saveToString();
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static void setUuid(ConfigurationSection section, String key, UUID value) {
        if (value != null) {
            section.set(key, value.toString());
        }
    }

    private void write(String content) {
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), "data", ".tmp");
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

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            Path moved = file.resolveSibling(file.getFileName() + ".corrupt-"
                    + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
            try {
                Files.move(file, moved);
                logger.severe(file.getFileName() + " could not be read (" + e.getMessage() + "); it was moved to "
                        + moved.getFileName() + ". Weapons found in the world are registered again as players use them.");
            } catch (IOException moveError) {
                readOnly = true;
                logger.severe(file.getFileName() + " could not be read (" + e.getMessage() + ") or moved aside; "
                        + "nothing will be saved this session so it is not overwritten.");
            }
            return;
        }
        salt = yaml.getString("salt");
        int skipped = 0;
        int retired = 0;
        ConfigurationSection weapons = yaml.getConfigurationSection("weapons");
        if (weapons != null) {
            for (String key : weapons.getKeys(false)) {
                ConfigurationSection section = weapons.getConfigurationSection(key);
                try {
                    UUID id = UUID.fromString(key);
                    String typeKey = section == null ? null : section.getString("type");
                    WeaponType type = WeaponType.byKey(typeKey);
                    if (type == null) {
                        if (WeaponType.retired(typeKey)) {
                            retired++; // A weapon that no longer exists: it is let go of.
                        } else {
                            skipped++;
                        }
                        continue;
                    }
                    WeaponRecord record = new WeaponRecord(id, type, section.getLong("created"),
                            section.getString("created-by", "?"));
                    record.restore(WeaponRecord.State.valueOf(section.getString("state", "HELD")),
                            uuid(section, "holder"), section.getString("holder-name"),
                            uuid(section, "previous-holder"), section.getString("previous-holder-name"),
                            uuid(section, "entity"), section.getString("world"), section.getDouble("x"),
                            section.getDouble("y"), section.getDouble("z"), section.getLong("updated"),
                            section.getString("note"));
                    records.put(id, record);
                } catch (IllegalArgumentException e) {
                    skipped++;
                }
            }
        }
        ConfigurationSection accounts = yaml.getConfigurationSection("accounts");
        if (accounts != null) {
            for (String key : accounts.getKeys(false)) {
                ConfigurationSection section = accounts.getConfigurationSection(key);
                try {
                    UUID player = UUID.fromString(key);
                    Map<String, Long> seen = new HashMap<>();
                    if (section != null) {
                        for (String hash : section.getKeys(false)) {
                            seen.put(hash, section.getLong(hash));
                        }
                    }
                    ips.put(player, seen);
                } catch (IllegalArgumentException e) {
                    skipped++;
                }
            }
        }
        if (skipped > 0) {
            logger.warning(skipped + " broken entr" + (skipped == 1 ? "y" : "ies") + " in data.yml skipped.");
        }
        if (retired > 0) {
            logger.info(retired + " weapon" + (retired == 1 ? "" : "s") + " that no longer exist"
                    + (retired == 1 ? "s" : "") + " removed from data.yml.");
        }
    }

    private static UUID uuid(ConfigurationSection section, String key) {
        String value = section.getString(key);
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
