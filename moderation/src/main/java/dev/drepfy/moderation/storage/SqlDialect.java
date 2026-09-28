package dev.drepfy.moderation.storage;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * The SQL differences between the supported databases. Both drivers ship with Paper.
 */
public enum SqlDialect {
    SQLITE("SQLite", "org.sqlite.JDBC") {
        @Override
        List<String> schema(String p) {
            return List.of(
                    "CREATE TABLE IF NOT EXISTS " + p + "meta ("
                            + "meta_key TEXT NOT NULL PRIMARY KEY, "
                            + "meta_value TEXT NOT NULL)",
                    "CREATE TABLE IF NOT EXISTS " + p + "players ("
                            + "uuid TEXT NOT NULL PRIMARY KEY, "
                            + "name TEXT NOT NULL, "
                            + "name_lower TEXT NOT NULL, "
                            + "first_seen INTEGER NOT NULL, "
                            + "last_seen INTEGER NOT NULL, "
                            + "exempt INTEGER NOT NULL DEFAULT 0)",
                    "CREATE INDEX IF NOT EXISTS " + p + "players_name ON " + p + "players (name_lower, last_seen)",
                    "CREATE TABLE IF NOT EXISTS " + p + "punishments ("
                            + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                            + "type TEXT NOT NULL, "
                            + "target_uuid TEXT NOT NULL, "
                            + "target_name TEXT NOT NULL, "
                            + "actor_uuid TEXT, "
                            + "actor_name TEXT NOT NULL, "
                            + "reason TEXT NOT NULL, "
                            + "server TEXT NOT NULL, "
                            + "created_at INTEGER NOT NULL, "
                            + "expires_at INTEGER, "
                            + "active INTEGER NOT NULL, "
                            + "silent INTEGER NOT NULL DEFAULT 0, "
                            + "notified INTEGER NOT NULL DEFAULT 0, "
                            + "removed_by_uuid TEXT, "
                            + "removed_by_name TEXT, "
                            + "removed_reason TEXT, "
                            + "removed_at INTEGER)",
                    "CREATE INDEX IF NOT EXISTS " + p + "punishments_target ON " + p + "punishments (target_uuid, active)");
        }

        @Override
        String upsertPlayer(String p) {
            return "INSERT INTO " + p + "players (uuid, name, name_lower, first_seen, last_seen) VALUES (?, ?, ?, ?, ?) "
                    + "ON CONFLICT(uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower, "
                    + "last_seen = excluded.last_seen";
        }
    },

    MYSQL("MySQL", "com.mysql.cj.jdbc.Driver") {
        @Override
        List<String> schema(String p) {
            String table = " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4";
            return List.of(
                    "CREATE TABLE IF NOT EXISTS " + p + "meta ("
                            + "meta_key VARCHAR(64) NOT NULL PRIMARY KEY, "
                            + "meta_value VARCHAR(255) NOT NULL)" + table,
                    "CREATE TABLE IF NOT EXISTS " + p + "players ("
                            + "uuid CHAR(36) NOT NULL PRIMARY KEY, "
                            + "name VARCHAR(64) NOT NULL, "
                            + "name_lower VARCHAR(64) NOT NULL, "
                            + "first_seen BIGINT NOT NULL, "
                            + "last_seen BIGINT NOT NULL, "
                            + "exempt BOOLEAN NOT NULL DEFAULT FALSE, "
                            + "INDEX idx_name (name_lower, last_seen))" + table,
                    "CREATE TABLE IF NOT EXISTS " + p + "punishments ("
                            + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                            + "type VARCHAR(16) NOT NULL, "
                            + "target_uuid CHAR(36) NOT NULL, "
                            + "target_name VARCHAR(64) NOT NULL, "
                            + "actor_uuid CHAR(36) NULL, "
                            + "actor_name VARCHAR(64) NOT NULL, "
                            + "reason VARCHAR(512) NOT NULL, "
                            + "server VARCHAR(64) NOT NULL, "
                            + "created_at BIGINT NOT NULL, "
                            + "expires_at BIGINT NULL, "
                            + "active BOOLEAN NOT NULL, "
                            + "silent BOOLEAN NOT NULL DEFAULT FALSE, "
                            + "notified BOOLEAN NOT NULL DEFAULT FALSE, "
                            + "removed_by_uuid CHAR(36) NULL, "
                            + "removed_by_name VARCHAR(64) NULL, "
                            + "removed_reason VARCHAR(512) NULL, "
                            + "removed_at BIGINT NULL, "
                            + "INDEX idx_target (target_uuid, active))" + table);
        }

        @Override
        String upsertPlayer(String p) {
            return "INSERT INTO " + p + "players (uuid, name, name_lower, first_seen, last_seen) VALUES (?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE name = VALUES(name), name_lower = VALUES(name_lower), "
                    + "last_seen = VALUES(last_seen)";
        }
    };

    private final String displayName;
    private final String driverClass;

    SqlDialect(String displayName, String driverClass) {
        this.displayName = displayName;
        this.driverClass = driverClass;
    }

    public String displayName() {
        return displayName;
    }

    public String driverClass() {
        return driverClass;
    }

    /** Idempotent statements creating the current schema. */
    abstract List<String> schema(String prefix);

    /** Inserts a player or refreshes their name and last-seen time, keeping first_seen and exempt. */
    abstract String upsertPlayer(String prefix);

    public static Optional<SqlDialect> fromConfig(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "sqlite" -> Optional.of(SQLITE);
            case "mysql", "mariadb" -> Optional.of(MYSQL);
            default -> Optional.empty();
        };
    }
}
