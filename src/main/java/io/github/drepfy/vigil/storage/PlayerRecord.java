package io.github.drepfy.vigil.storage;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.data.FlagRecord;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Persistent per-player history: lifetime flag counts, recent evidence lines and
 * staff notes. Mutated on the server thread and serialised on the IO thread, so
 * every method is synchronised.
 */
public final class PlayerRecord {

    private static final int FORMAT_VERSION = 1;
    private static final int MAX_NOTES = 50;

    private final UUID uuid;
    private String name;
    private long firstSeenEpochMs;
    private long lastSeenEpochMs;
    private final Map<CheckType, Integer> lifetimeFlags = new EnumMap<>(CheckType.class);
    private final Deque<String> recentFlags = new ArrayDeque<>();
    private final List<String> notes = new ArrayList<>();
    private boolean dirty;

    public PlayerRecord(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        this.firstSeenEpochMs = System.currentTimeMillis();
        this.lastSeenEpochMs = firstSeenEpochMs;
        this.dirty = true;
    }

    public UUID uuid() {
        return uuid;
    }

    public synchronized String name() {
        return name;
    }

    public synchronized void seen(String currentName) {
        this.name = currentName;
        this.lastSeenEpochMs = System.currentTimeMillis();
        this.dirty = true;
    }

    public synchronized void recordFlag(FlagRecord flag, int historyLimit) {
        lifetimeFlags.merge(flag.check(), 1, Integer::sum);
        recentFlags.addFirst(flag.toLine());
        while (recentFlags.size() > Math.max(1, historyLimit)) {
            recentFlags.removeLast();
        }
        dirty = true;
    }

    public synchronized void addNote(String author, String text) {
        notes.add(java.time.LocalDate.now() + " " + author + ": " + text);
        while (notes.size() > MAX_NOTES) {
            notes.remove(0);
        }
        dirty = true;
    }

    public synchronized Map<CheckType, Integer> lifetimeFlags() {
        Map<CheckType, Integer> copy = new EnumMap<>(CheckType.class);
        copy.putAll(lifetimeFlags);
        return copy;
    }

    public synchronized int totalLifetimeFlags() {
        int total = 0;
        for (int count : lifetimeFlags.values()) {
            total += count;
        }
        return total;
    }

    public synchronized List<String> recentFlags() {
        return new ArrayList<>(recentFlags);
    }

    public synchronized List<String> notes() {
        return new ArrayList<>(notes);
    }

    public synchronized long firstSeenEpochMs() {
        return firstSeenEpochMs;
    }

    public synchronized long lastSeenEpochMs() {
        return lastSeenEpochMs;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    /** Marks the record as needing a save again (e.g. after a failed write). */
    public synchronized void markDirty() {
        dirty = true;
    }

    /** Serialises the record and marks it clean. */
    public synchronized String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", FORMAT_VERSION);
        yaml.set("uuid", uuid.toString());
        yaml.set("name", name);
        yaml.set("first-seen", firstSeenEpochMs);
        yaml.set("last-seen", lastSeenEpochMs);
        for (Map.Entry<CheckType, Integer> entry : lifetimeFlags.entrySet()) {
            yaml.set("lifetime-flags." + entry.getKey().id(), entry.getValue());
        }
        yaml.set("recent-flags", new ArrayList<>(recentFlags));
        yaml.set("notes", new ArrayList<>(notes));
        dirty = false;
        return yaml.saveToString();
    }

    /** Parses a record; unknown check ids are skipped rather than failing the whole file. */
    public static PlayerRecord parse(UUID uuid, YamlConfiguration yaml, String fallbackName) {
        PlayerRecord record = new PlayerRecord(uuid, yaml.getString("name", fallbackName));
        record.firstSeenEpochMs = yaml.getLong("first-seen", record.firstSeenEpochMs);
        record.lastSeenEpochMs = yaml.getLong("last-seen", record.lastSeenEpochMs);
        ConfigurationSection counts = yaml.getConfigurationSection("lifetime-flags");
        if (counts != null) {
            for (String key : counts.getKeys(false)) {
                CheckType type = CheckType.fromId(key);
                if (type != null) {
                    record.lifetimeFlags.put(type, Math.max(0, counts.getInt(key)));
                }
            }
        }
        record.recentFlags.addAll(yaml.getStringList("recent-flags"));
        record.notes.addAll(yaml.getStringList("notes"));
        record.dirty = false;
        return record;
    }
}
