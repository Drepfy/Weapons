package dev.drepfy.moderation.storage;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * Database connection settings from config.yml.
 */
public record StorageSettings(
        SqlDialect dialect,
        String tablePrefix,
        String sqliteFile,
        String host,
        int port,
        String database,
        String username,
        String password,
        int poolSize,
        Map<String, String> properties) {

    private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9_]{0,32}");

    public StorageSettings {
        // The prefix is concatenated into SQL, so it must never contain anything but identifier characters.
        if (!PREFIX.matcher(tablePrefix).matches()) {
            throw new IllegalArgumentException("storage.table-prefix may only contain letters, digits and underscores (max 32)");
        }
        if (poolSize < 1) {
            throw new IllegalArgumentException("storage.mysql.pool-size must be at least 1");
        }
        properties = Map.copyOf(properties);
    }

    public static StorageSettings sqlite(String file, String tablePrefix) {
        return new StorageSettings(SqlDialect.SQLITE, tablePrefix, file, "", 0, "", "", "", 1, Map.of());
    }

    /** Connections the pool may open; SQLite allows a single writer, so it always uses one. */
    public int effectivePoolSize() {
        return dialect == SqlDialect.SQLITE ? 1 : poolSize;
    }
}
