package dev.drepfy.moderation.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Connection pool plus schema management.
 */
public final class Database implements AutoCloseable {

    /** Bump when the schema changes, and add a migration step to {@link #migrate()}. */
    public static final int SCHEMA_VERSION = 1;

    private final HikariDataSource dataSource;
    private final SqlDialect dialect;
    private final String prefix;
    private final String description;

    private Database(HikariDataSource dataSource, StorageSettings settings, String description) {
        this.dataSource = dataSource;
        this.dialect = settings.dialect();
        this.prefix = settings.tablePrefix();
        this.description = description;
    }

    /**
     * Connects and brings the schema up to date.
     *
     * @param dataFolder directory relative SQLite paths are resolved against
     */
    public static Database open(StorageSettings settings, Path dataFolder) throws Exception {
        HikariConfig config = new HikariConfig();
        config.setPoolName("StaffModeration");
        config.setDriverClassName(settings.dialect().driverClass());
        config.setMaximumPoolSize(settings.effectivePoolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(10_000);

        String description;
        if (settings.dialect() == SqlDialect.SQLITE) {
            Path file = dataFolder.resolve(settings.sqliteFile()).toAbsolutePath().normalize();
            Files.createDirectories(file.getParent());
            config.setJdbcUrl("jdbc:sqlite:" + file);
            // WAL keeps reads from blocking on writes; busy_timeout rides out short lock contention.
            config.addDataSourceProperty("journal_mode", "WAL");
            config.addDataSourceProperty("busy_timeout", "5000");
            config.addDataSourceProperty("synchronous", "NORMAL");
            description = "SQLite (" + file + ")";
        } else {
            config.setJdbcUrl("jdbc:mysql://" + settings.host() + ":" + settings.port() + "/" + settings.database());
            config.setUsername(settings.username());
            config.setPassword(settings.password());
            config.setMaxLifetime(30 * 60_000);
            config.setKeepaliveTime(5 * 60_000);
            settings.properties().forEach(config::addDataSourceProperty);
            description = "MySQL (" + settings.host() + ":" + settings.port() + "/" + settings.database() + ")";
        }

        HikariDataSource dataSource = new HikariDataSource(config);
        Database database = new Database(dataSource, settings, description);
        try {
            database.migrate();
        } catch (Exception e) {
            database.close();
            throw e;
        }
        return database;
    }

    public Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    public SqlDialect dialect() {
        return dialect;
    }

    public String prefix() {
        return prefix;
    }

    public String description() {
        return description;
    }

    private void migrate() throws SQLException {
        try (Connection connection = connection()) {
            try (Statement statement = connection.createStatement()) {
                for (String sql : dialect.schema(prefix)) {
                    statement.execute(sql);
                }
            }
            int version = readSchemaVersion(connection);
            if (version > SCHEMA_VERSION) {
                // A newer plugin version changed the tables; running against them could corrupt history.
                throw new SQLException("The database schema (version " + version + ") is newer than this plugin supports ("
                        + SCHEMA_VERSION + "). Update StaffModeration.");
            }
            // Future schema changes: `if (version < 2) { ...; }` etc. Version 1 is created above.
            if (version != SCHEMA_VERSION) {
                writeSchemaVersion(connection);
            }
        }
    }

    private int readSchemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT meta_value FROM " + prefix + "meta WHERE meta_key = 'schema_version'")) {
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return 0;
                }
                try {
                    return Integer.parseInt(result.getString(1));
                } catch (NumberFormatException e) {
                    throw new SQLException("Invalid schema_version in " + prefix + "meta: " + result.getString(1), e);
                }
            }
        }
    }

    private void writeSchemaVersion(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "REPLACE INTO " + prefix + "meta (meta_key, meta_value) VALUES ('schema_version', ?)")) {
            statement.setString(1, Integer.toString(SCHEMA_VERSION));
            statement.executeUpdate();
        }
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
