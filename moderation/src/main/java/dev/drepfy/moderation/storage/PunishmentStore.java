package dev.drepfy.moderation.storage;

import dev.drepfy.moderation.model.Actor;
import dev.drepfy.moderation.model.PlayerRecord;
import dev.drepfy.moderation.model.Punishment;
import dev.drepfy.moderation.model.PunishmentType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Blocking data access for players and punishments. Callers run these methods off the main thread
 * (see {@link Storage}). History rows are never deleted: lifting a punishment only marks it inactive
 * and records who lifted it and why.
 */
public final class PunishmentStore {

    private static final int MAX_NAME = 64;
    private static final int MAX_TEXT = 512;
    private static final int IN_CLAUSE_CHUNK = 500;

    private static final String COLUMNS = "id, type, target_uuid, target_name, actor_uuid, actor_name, reason, server, "
            + "created_at, expires_at, active, silent, removed_by_uuid, removed_by_name, removed_reason, removed_at";

    private final Database database;
    private final String players;
    private final String punishments;

    public PunishmentStore(Database database) {
        this.database = database;
        this.players = database.prefix() + "players";
        this.punishments = database.prefix() + "punishments";
    }

    // ---------------------------------------------------------------------------------- players

    /** Remembers a player's current name so they can be found (and punished) while offline. */
    public void recordLogin(UUID player, String name, long now) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(database.dialect().upsertPlayer(database.prefix()))) {
            statement.setString(1, player.toString());
            statement.setString(2, clip(name, MAX_NAME));
            statement.setString(3, clip(name.toLowerCase(Locale.ROOT), MAX_NAME));
            statement.setLong(4, now);
            statement.setLong(5, now);
            statement.executeUpdate();
        }
    }

    public void setExempt(UUID player, boolean exempt) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("UPDATE " + players + " SET exempt = ? WHERE uuid = ?")) {
            statement.setBoolean(1, exempt);
            statement.setString(2, player.toString());
            statement.executeUpdate();
        }
    }

    public Optional<PlayerRecord> findPlayer(UUID player) throws SQLException {
        return queryPlayer("SELECT uuid, name, first_seen, last_seen, exempt FROM " + players + " WHERE uuid = ?",
                player.toString());
    }

    /** Finds the player who most recently used this name (names can change hands). */
    public Optional<PlayerRecord> findPlayerByName(String name) throws SQLException {
        return queryPlayer("SELECT uuid, name, first_seen, last_seen, exempt FROM " + players
                + " WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1", name.toLowerCase(Locale.ROOT));
    }

    private Optional<PlayerRecord> queryPlayer(String sql, String parameter) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, parameter);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlayerRecord(
                        UUID.fromString(result.getString("uuid")),
                        result.getString("name"),
                        result.getLong("first_seen"),
                        result.getLong("last_seen"),
                        result.getBoolean("exempt")));
            }
        }
    }

    // ------------------------------------------------------------------------------ punishments

    /**
     * Stores a new punishment and returns it with its generated ID.
     *
     * @param punishment the punishment to store; its {@code id} is ignored
     * @param notified   whether the target has already seen it (used to deliver offline warnings)
     */
    public Punishment insert(Punishment punishment, boolean notified) throws SQLException {
        String sql = "INSERT INTO " + punishments + " (type, target_uuid, target_name, actor_uuid, actor_name, reason, "
                + "server, created_at, expires_at, active, silent, notified) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection connection = database.connection()) {
            long id;
            boolean sqlite = database.dialect() == SqlDialect.SQLITE;
            try (PreparedStatement statement = sqlite
                    ? connection.prepareStatement(sql)
                    : connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                statement.setString(1, punishment.type().key());
                statement.setString(2, punishment.targetId().toString());
                statement.setString(3, clip(punishment.targetName(), MAX_NAME));
                setUuid(statement, 4, punishment.actor().uniqueId());
                statement.setString(5, clip(punishment.actor().name(), MAX_NAME));
                statement.setString(6, clip(punishment.reason(), MAX_TEXT));
                statement.setString(7, clip(punishment.server(), MAX_NAME));
                statement.setLong(8, punishment.createdAt());
                setLong(statement, 9, punishment.expiresAt());
                statement.setBoolean(10, punishment.active());
                statement.setBoolean(11, punishment.silent());
                statement.setBoolean(12, notified);
                statement.executeUpdate();
                if (!sqlite) {
                    try (ResultSet keys = statement.getGeneratedKeys()) {
                        if (!keys.next()) {
                            throw new SQLException("No ID generated for new punishment");
                        }
                        id = keys.getLong(1);
                    }
                } else {
                    // The pool holds a single SQLite connection, so nothing can insert in between.
                    try (Statement last = connection.createStatement();
                         ResultSet keys = last.executeQuery("SELECT last_insert_rowid()")) {
                        keys.next();
                        id = keys.getLong(1);
                    }
                }
            }
            return new Punishment(id, punishment.type(), punishment.targetId(), punishment.targetName(),
                    punishment.actor(), punishment.reason(), punishment.server(), punishment.createdAt(),
                    punishment.expiresAt(), punishment.active(), punishment.silent(), null);
        }
    }

    public Optional<Punishment> findById(long id) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + punishments + " WHERE id = ?")) {
            statement.setLong(1, id);
            List<Punishment> found = readAll(statement);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
        }
    }

    /** All of a player's punishments currently in force. */
    public List<Punishment> findInForce(UUID player, long now) throws SQLException {
        return findInForce(List.of(player), now).getOrDefault(player, List.of());
    }

    /** Punishments currently in force for each of the given players (players with none are omitted). */
    public Map<UUID, List<Punishment>> findInForce(Collection<UUID> targets, long now) throws SQLException {
        Map<UUID, List<Punishment>> result = new HashMap<>();
        List<UUID> all = List.copyOf(targets);
        try (Connection connection = database.connection()) {
            for (int start = 0; start < all.size(); start += IN_CLAUSE_CHUNK) {
                List<UUID> chunk = all.subList(start, Math.min(all.size(), start + IN_CLAUSE_CHUNK));
                String sql = "SELECT " + COLUMNS + " FROM " + punishments + " WHERE target_uuid IN ("
                        + placeholders(chunk.size()) + ") AND active = ? AND type <> ? AND (expires_at IS NULL OR expires_at > ?)";
                try (PreparedStatement statement = connection.prepareStatement(sql)) {
                    int index = 1;
                    for (UUID id : chunk) {
                        statement.setString(index++, id.toString());
                    }
                    statement.setBoolean(index++, true);
                    statement.setString(index++, PunishmentType.KICK.key());
                    statement.setLong(index, now);
                    for (Punishment punishment : readAll(statement)) {
                        result.computeIfAbsent(punishment.targetId(), id -> new ArrayList<>()).add(punishment);
                    }
                }
            }
        }
        return result;
    }

    public int countInForce(UUID player, PunishmentType type, long now) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + punishments
                     + " WHERE target_uuid = ? AND type = ? AND active = ? AND (expires_at IS NULL OR expires_at > ?)")) {
            statement.setString(1, player.toString());
            statement.setString(2, type.key());
            statement.setBoolean(3, true);
            statement.setLong(4, now);
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    public int countHistory(UUID player) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) FROM " + punishments + " WHERE target_uuid = ?")) {
            statement.setString(1, player.toString());
            try (ResultSet result = statement.executeQuery()) {
                result.next();
                return result.getInt(1);
            }
        }
    }

    /** A page of a player's history, newest first. */
    public List<Punishment> history(UUID player, int offset, int limit) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + punishments
                     + " WHERE target_uuid = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")) {
            statement.setString(1, player.toString());
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            return readAll(statement);
        }
    }

    /** Warnings the player hasn't seen yet because they were offline, oldest first. */
    public List<Punishment> undeliveredWarnings(UUID player) throws SQLException {
        try (Connection connection = database.connection();
             PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + punishments
                     + " WHERE target_uuid = ? AND type = ? AND active = ? AND notified = ? ORDER BY created_at, id")) {
            statement.setString(1, player.toString());
            statement.setString(2, PunishmentType.WARN.key());
            statement.setBoolean(3, true);
            statement.setBoolean(4, false);
            return readAll(statement);
        }
    }

    public void markNotified(Collection<Long> ids) throws SQLException {
        if (ids.isEmpty()) {
            return;
        }
        List<Long> all = List.copyOf(ids);
        try (Connection connection = database.connection()) {
            for (int start = 0; start < all.size(); start += IN_CLAUSE_CHUNK) {
                List<Long> chunk = all.subList(start, Math.min(all.size(), start + IN_CLAUSE_CHUNK));
                try (PreparedStatement statement = connection.prepareStatement("UPDATE " + punishments
                        + " SET notified = ? WHERE id IN (" + placeholders(chunk.size()) + ")")) {
                    statement.setBoolean(1, true);
                    for (int i = 0; i < chunk.size(); i++) {
                        statement.setLong(i + 2, chunk.get(i));
                    }
                    statement.executeUpdate();
                }
            }
        }
    }

    /**
     * Lifts every punishment of this type currently in force for the player.
     *
     * @return the punishments that were lifted, with their removal details
     */
    public List<Punishment> lift(UUID player, PunishmentType type, Actor by, String reason, long now) throws SQLException {
        try (Connection connection = database.connection()) {
            connection.setAutoCommit(false);
            try {
                List<Punishment> inForce;
                try (PreparedStatement statement = connection.prepareStatement("SELECT " + COLUMNS + " FROM " + punishments
                        + " WHERE target_uuid = ? AND type = ? AND active = ? AND (expires_at IS NULL OR expires_at > ?)")) {
                    statement.setString(1, player.toString());
                    statement.setString(2, type.key());
                    statement.setBoolean(3, true);
                    statement.setLong(4, now);
                    inForce = readAll(statement);
                }
                List<Punishment> lifted = new ArrayList<>(inForce.size());
                for (Punishment punishment : inForce) {
                    if (markLifted(connection, punishment.id(), by, reason, now)) {
                        lifted.add(withRemoval(punishment, by, reason, now));
                    }
                }
                connection.commit();
                return lifted;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    /**
     * Lifts one punishment by ID if it is of the expected type and still in force.
     */
    public Optional<Punishment> liftById(long id, PunishmentType type, Actor by, String reason, long now) throws SQLException {
        Optional<Punishment> found = findById(id);
        if (found.isEmpty() || found.get().type() != type || !found.get().inForce(now)) {
            return Optional.empty();
        }
        try (Connection connection = database.connection()) {
            if (!markLifted(connection, id, by, reason, now)) {
                return Optional.empty();
            }
        }
        return Optional.of(withRemoval(found.get(), by, reason, now));
    }

    private boolean markLifted(Connection connection, long id, Actor by, String reason, long now) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("UPDATE " + punishments
                + " SET active = ?, removed_by_uuid = ?, removed_by_name = ?, removed_reason = ?, removed_at = ?"
                + " WHERE id = ? AND active = ?")) {
            statement.setBoolean(1, false);
            setUuid(statement, 2, by.uniqueId());
            statement.setString(3, clip(by.name(), MAX_NAME));
            statement.setString(4, clip(reason, MAX_TEXT));
            statement.setLong(5, now);
            statement.setLong(6, id);
            statement.setBoolean(7, true);
            return statement.executeUpdate() == 1;
        }
    }

    // ---------------------------------------------------------------------------------- helpers

    private static Punishment withRemoval(Punishment p, Actor by, String reason, long now) {
        return new Punishment(p.id(), p.type(), p.targetId(), p.targetName(), p.actor(), p.reason(), p.server(),
                p.createdAt(), p.expiresAt(), false, p.silent(), new Punishment.Removal(by, clip(reason, MAX_TEXT), now));
    }

    private static List<Punishment> readAll(PreparedStatement statement) throws SQLException {
        try (ResultSet result = statement.executeQuery()) {
            List<Punishment> list = new ArrayList<>();
            while (result.next()) {
                list.add(read(result));
            }
            return list;
        }
    }

    private static Punishment read(ResultSet result) throws SQLException {
        String typeKey = result.getString("type");
        PunishmentType type = PunishmentType.fromKey(typeKey)
                .orElseThrow(() -> new SQLException("Unknown punishment type '" + typeKey + "'"));
        Long expiresAt = result.getLong("expires_at");
        if (result.wasNull()) {
            expiresAt = null;
        }
        Punishment.Removal removal = null;
        String removedByName = result.getString("removed_by_name");
        if (removedByName != null) {
            String removedReason = result.getString("removed_reason");
            removal = new Punishment.Removal(new Actor(uuid(result.getString("removed_by_uuid")), removedByName),
                    removedReason == null ? "" : removedReason, result.getLong("removed_at"));
        }
        return new Punishment(
                result.getLong("id"),
                type,
                UUID.fromString(result.getString("target_uuid")),
                result.getString("target_name"),
                new Actor(uuid(result.getString("actor_uuid")), result.getString("actor_name")),
                result.getString("reason"),
                result.getString("server"),
                result.getLong("created_at"),
                expiresAt,
                result.getBoolean("active"),
                result.getBoolean("silent"),
                removal);
    }

    private static UUID uuid(String value) {
        return value == null ? null : UUID.fromString(value);
    }

    private static void setUuid(PreparedStatement statement, int index, UUID value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.VARCHAR);
        } else {
            statement.setString(index, value.toString());
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
        } else {
            statement.setLong(index, value);
        }
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    private static String clip(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max);
    }
}
