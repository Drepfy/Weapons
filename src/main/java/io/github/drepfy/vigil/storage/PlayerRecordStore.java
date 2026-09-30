package io.github.drepfy.vigil.storage;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Loads and saves {@link PlayerRecord}s under {@code data/players/<uuid>.yml}.
 * A file that cannot be parsed is moved aside (never deleted) and a fresh record
 * is started, so one corrupt file can never break joining or flagging.
 */
public final class PlayerRecordStore {

    private final Path directory;
    private final IoExecutor io;
    private final Logger logger;

    public PlayerRecordStore(Path directory, IoExecutor io, Logger logger) {
        this.directory = directory;
        this.io = io;
        this.logger = logger;
    }

    /** Loads (or creates) the record of an online player. Never completes exceptionally. */
    public CompletableFuture<PlayerRecord> loadOrCreate(UUID uuid, String name) {
        CompletableFuture<PlayerRecord> future = new CompletableFuture<>();
        boolean queued = io.execute("load player record " + uuid, () -> {
            PlayerRecord record = read(uuid, name);
            future.complete(record != null ? record : new PlayerRecord(uuid, name));
        });
        if (!queued) {
            // The IO queue is full (very rare): read this one small file right away. Starting with a
            // fresh record instead would overwrite the player's history on the next save.
            PlayerRecord record = read(uuid, name);
            future.complete(record != null ? record : new PlayerRecord(uuid, name));
        }
        return future;
    }

    /** Loads the record of a possibly offline player; completes with {@code null} if none exists. */
    public CompletableFuture<PlayerRecord> loadExisting(UUID uuid) {
        CompletableFuture<PlayerRecord> future = new CompletableFuture<>();
        if (!io.execute("load offline record " + uuid, () -> future.complete(read(uuid, "unknown")))) {
            future.complete(null);
        }
        return future;
    }

    public void saveAsync(PlayerRecord record) {
        if (!record.isDirty()) {
            return;
        }
        io.execute("save player record " + record.uuid(), () -> write(record));
    }

    /** Synchronous save used during shutdown after the IO thread has stopped. */
    public void saveAllNow(Collection<PlayerRecord> records) {
        for (PlayerRecord record : records) {
            if (record.isDirty()) {
                write(record);
            }
        }
    }

    private PlayerRecord read(UUID uuid, String name) {
        Path file = file(uuid);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
            return PlayerRecord.parse(uuid, yaml, name);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            Path moved = AtomicFiles.quarantine(file);
            logger.warning("Player record " + file.getFileName() + " is unreadable (" + e.getMessage()
                    + "); moved to " + (moved != null ? moved.getFileName() : "<move failed>")
                    + " and starting a fresh record.");
            return null;
        }
    }

    private void write(PlayerRecord record) {
        try {
            AtomicFiles.write(file(record.uuid()), record.serialize());
        } catch (IOException e) {
            record.markDirty();
            logger.warning("Could not save player record " + record.uuid() + ": " + e.getMessage());
        }
    }

    private Path file(UUID uuid) {
        return directory.resolve(uuid + ".yml");
    }
}
