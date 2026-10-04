package io.github.drepfy.lifesteal.data;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Everything Lifesteal remembers, in {@code data.yml}: hearts per player, heart-steal
 * cooldowns, IP addresses (hashed, never stored as written) and alt account links.
 *
 * <p>Used on the server thread only. Saving copies the data there and writes it on a
 * background thread to a temporary file that then replaces the old one, so a crash can
 * never leave a half-written file.
 */
public final class LifestealStore {

    /** One player. */
    public static final class Entry {
        private String name;
        private int hearts;
        /** IP hash → when it was last used. */
        private final Map<String, Long> ips = new HashMap<>();

        Entry(String name, int hearts) {
            this.name = name;
            this.hearts = hearts;
        }

        public String name() {
            return name;
        }

        public int hearts() {
            return hearts;
        }
    }

    private final Logger logger;
    private final Path file;
    private final ExecutorService io;
    private final Map<UUID, Entry> players = new HashMap<>();
    private final Map<String, Long> cooldowns = new HashMap<>();
    private final Set<String> links = new HashSet<>();
    private final Set<String> allowed = new HashSet<>();
    private final Map<String, Set<UUID>> accountsByIp = new HashMap<>();
    private String salt;
    private boolean dirty;
    private boolean readOnly;

    public LifestealStore(Logger logger, Path file) {
        this.logger = logger;
        this.file = file;
        this.io = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "Lifesteal-IO");
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

    // ---- hearts ------------------------------------------------------------------------------------

    public Entry entry(UUID uuid) {
        return players.get(uuid);
    }

    /** The player's entry, created with {@code startHearts} when they are new. Updates the name. */
    public Entry entry(UUID uuid, String name, int startHearts) {
        Entry entry = players.get(uuid);
        if (entry == null) {
            entry = new Entry(name, startHearts);
            players.put(uuid, entry);
            dirty = true;
        } else if (name != null && !name.equals(entry.name)) {
            entry.name = name;
            dirty = true;
        }
        return entry;
    }

    public void setHearts(Entry entry, int hearts) {
        if (entry.hearts != hearts) {
            entry.hearts = hearts;
            dirty = true;
        }
    }

    /** A known player by name (any case), or {@code null}. */
    public UUID findByName(String name) {
        for (Map.Entry<UUID, Entry> entry : players.entrySet()) {
            if (entry.getValue().name != null && entry.getValue().name.equalsIgnoreCase(name)) {
                return entry.getKey();
            }
        }
        return null;
    }

    public Map<UUID, Entry> players() {
        return java.util.Collections.unmodifiableMap(players);
    }

    // ---- cooldowns --------------------------------------------------------------------------------------

    /** When {@code killer} may steal from {@code victim} again (epoch ms), or 0. */
    public long cooldownUntil(UUID killer, UUID victim) {
        Long until = cooldowns.get(killer + ">" + victim);
        return until == null ? 0L : until;
    }

    public void setCooldown(UUID killer, UUID victim, long untilEpochMs) {
        cooldowns.put(killer + ">" + victim, untilEpochMs);
        dirty = true;
    }

    /** Clears every cooldown involving the player; returns how many. */
    public int clearCooldowns(UUID player) {
        String id = player.toString();
        int removed = 0;
        for (Iterator<String> it = cooldowns.keySet().iterator(); it.hasNext(); ) {
            String key = it.next();
            if (key.startsWith(id + ">") || key.endsWith(">" + id)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            dirty = true;
        }
        return removed;
    }

    // ---- alt accounts ------------------------------------------------------------------------------------

    /** Remembers that the player joined from this address (only a salted hash is kept). */
    public void recordIp(UUID uuid, String address, long now) {
        Entry entry = players.get(uuid);
        if (entry == null || address == null) {
            return;
        }
        String hash = hash(address);
        entry.ips.put(hash, now);
        accountsByIp.computeIfAbsent(hash, key -> new HashSet<>()).add(uuid);
        dirty = true;
    }

    /** IP hashes the player used since {@code sinceEpochMs}. */
    public Set<String> ipsSince(UUID uuid, long sinceEpochMs) {
        Entry entry = players.get(uuid);
        Set<String> result = new HashSet<>();
        if (entry != null) {
            for (Map.Entry<String, Long> ip : entry.ips.entrySet()) {
                if (ip.getValue() >= sinceEpochMs) {
                    result.add(ip.getKey());
                }
            }
        }
        return result;
    }

    /** Every account that ever used this IP hash (within what is remembered). */
    public Set<UUID> accountsOnIp(String hash) {
        return accountsByIp.getOrDefault(hash, Set.of());
    }

    public boolean linked(UUID a, UUID b) {
        return links.contains(pair(a, b));
    }

    public boolean allowed(UUID a, UUID b) {
        return allowed.contains(pair(a, b));
    }

    /** Marks two accounts as the same person (true) or removes that (false). */
    public boolean setLinked(UUID a, UUID b, boolean linked) {
        boolean changed = linked ? links.add(pair(a, b)) : links.remove(pair(a, b));
        dirty |= changed;
        return changed;
    }

    /** Marks two accounts as different people even if they share an IP (siblings...). */
    public boolean setAllowed(UUID a, UUID b, boolean value) {
        boolean changed = value ? allowed.add(pair(a, b)) : allowed.remove(pair(a, b));
        dirty |= changed;
        return changed;
    }

    /** Accounts manually linked to this one. */
    public List<UUID> linksOf(UUID uuid) {
        return partners(links, uuid);
    }

    /** Accounts manually allowed with this one. */
    public List<UUID> allowedOf(UUID uuid) {
        return partners(allowed, uuid);
    }

    private static List<UUID> partners(Collection<String> pairs, UUID uuid) {
        List<UUID> result = new ArrayList<>();
        String id = uuid.toString();
        for (String pair : pairs) {
            String[] parts = pair.split("\\|");
            if (parts[0].equals(id)) {
                result.add(UUID.fromString(parts[1]));
            } else if (parts[1].equals(id)) {
                result.add(UUID.fromString(parts[0]));
            }
        }
        return result;
    }

    static String pair(UUID a, UUID b) {
        String x = a.toString();
        String y = b.toString();
        return x.compareTo(y) <= 0 ? x + "|" + y : y + "|" + x;
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

    // ---- saving ---------------------------------------------------------------------------------------------

    /** Appends a line to a log file on the background thread. */
    public void appendLog(Path log, String line) {
        try {
            io.execute(() -> {
                try {
                    Files.createDirectories(log.toAbsolutePath().getParent());
                    Files.writeString(log, line + System.lineSeparator(), StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException e) {
                    logger.fine("Could not write " + log.getFileName() + ": " + e.getMessage());
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException ignored) {
            // Shutting down.
        }
    }

    public boolean isDirty() {
        return dirty;
    }

    /**
     * Drops what is no longer needed (finished cooldowns, IPs older than {@code ipCutoff})
     * and writes the file in the background.
     */
    public void save(long now, long ipCutoffEpochMs) {
        if (!dirty || readOnly) {
            return;
        }
        prune(now, ipCutoffEpochMs);
        String snapshot = serialize();
        dirty = false;
        try {
            io.execute(() -> write(snapshot));
        } catch (java.util.concurrent.RejectedExecutionException e) {
            dirty = true;
        }
    }

    /** Writes everything now and stops the background thread (plugin shutdown). */
    public void close(long now, long ipCutoffEpochMs) {
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (dirty && !readOnly) {
            prune(now, ipCutoffEpochMs);
            write(serialize());
            dirty = false;
        }
    }

    private void prune(long now, long ipCutoff) {
        cooldowns.values().removeIf(until -> until <= now);
        accountsByIp.clear();
        for (Map.Entry<UUID, Entry> player : players.entrySet()) {
            player.getValue().ips.values().removeIf(seen -> seen < ipCutoff);
            for (String ip : player.getValue().ips.keySet()) {
                accountsByIp.computeIfAbsent(ip, key -> new HashSet<>()).add(player.getKey());
            }
        }
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 1);
        yaml.set("salt", salt);
        for (Map.Entry<UUID, Entry> player : players.entrySet()) {
            ConfigurationSection section = yaml.createSection("players." + player.getKey());
            section.set("name", player.getValue().name);
            section.set("hearts", player.getValue().hearts);
            if (!player.getValue().ips.isEmpty()) {
                section.createSection("ips", new HashMap<>(player.getValue().ips));
            }
        }
        for (Map.Entry<String, Long> cooldown : cooldowns.entrySet()) {
            yaml.set("cooldowns." + cooldown.getKey(), cooldown.getValue());
        }
        yaml.set("links", new ArrayList<>(links));
        yaml.set("allowed", new ArrayList<>(allowed));
        return yaml.saveToString();
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
                        + moved.getFileName() + " and everyone starts again. Restore it to get the hearts back.");
            } catch (IOException moveError) {
                readOnly = true;
                logger.severe(file.getFileName() + " could not be read (" + e.getMessage() + ") or moved aside; "
                        + "nothing will be saved this session so it is not overwritten.");
            }
            return;
        }
        salt = yaml.getString("salt");
        ConfigurationSection section = yaml.getConfigurationSection("players");
        int skipped = 0;
        if (section != null) {
            for (String key : section.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    ConfigurationSection player = section.getConfigurationSection(key);
                    if (player == null || !player.isInt("hearts")) {
                        skipped++;
                        continue;
                    }
                    Entry entry = new Entry(player.getString("name"), player.getInt("hearts"));
                    ConfigurationSection ips = player.getConfigurationSection("ips");
                    if (ips != null) {
                        for (String ip : ips.getKeys(false)) {
                            entry.ips.put(ip, ips.getLong(ip));
                            accountsByIp.computeIfAbsent(ip, k -> new HashSet<>()).add(uuid);
                        }
                    }
                    players.put(uuid, entry);
                } catch (IllegalArgumentException e) {
                    skipped++;
                }
            }
        }
        ConfigurationSection cooldownSection = yaml.getConfigurationSection("cooldowns");
        if (cooldownSection != null) {
            for (String key : cooldownSection.getKeys(false)) {
                cooldowns.put(key, cooldownSection.getLong(key));
            }
        }
        links.addAll(yaml.getStringList("links"));
        allowed.addAll(yaml.getStringList("allowed"));
        if (skipped > 0) {
            logger.warning(skipped + " broken player entr" + (skipped == 1 ? "y" : "ies") + " in "
                    + file.getFileName() + " were skipped.");
        }
    }
}
