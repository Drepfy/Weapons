package io.github.drepfy.vigil.moderation;

import io.github.drepfy.vigil.storage.AtomicFiles;
import io.github.drepfy.vigil.storage.IoExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Stores bans, mutes, warnings and kicks in {@code data/punishments.yml}.
 *
 * <p>Changes happen on the server thread; the active ban/mute indexes are
 * concurrent maps because logins and chat are handled on other threads.
 */
public final class ModerationService {

    private final Logger logger;
    private final IoExecutor io;
    private final Path file;
    private final Map<Integer, Punishment> all = new LinkedHashMap<>();
    private final Map<UUID, Punishment> activeBans = new ConcurrentHashMap<>();
    private final Map<UUID, Punishment> activeMutes = new ConcurrentHashMap<>();
    private int nextId = 1;
    private boolean dirty;
    /** When the file could not be read or moved aside it is never overwritten. */
    private boolean readOnly;

    public ModerationService(Logger logger, IoExecutor io, Path file) {
        this.logger = logger;
        this.io = io;
        this.file = file;
        load();
    }

    // ---- actions (server thread) --------------------------------------------------------------

    /** Bans a player, replacing any existing ban. */
    public Punishment ban(UUID uuid, String name, String reason, String staff, long durationMs) {
        return place(PunishmentType.BAN, activeBans, uuid, name, reason, staff, durationMs);
    }

    /** Mutes a player, replacing any existing mute. */
    public Punishment mute(UUID uuid, String name, String reason, String staff, long durationMs) {
        return place(PunishmentType.MUTE, activeMutes, uuid, name, reason, staff, durationMs);
    }

    /** A warning that counts for {@code durationMs}. */
    public Punishment warn(UUID uuid, String name, String reason, String staff, long durationMs) {
        return record(new Punishment(nextId++, PunishmentType.WARN, uuid, name, reason, staff,
                System.currentTimeMillis(), durationMs, false, null, null, 0L));
    }

    /**
     * Removes the newest warning that still counts.
     *
     * @param legacyExpireMs how long warnings from before 2.4 (without their own time) count
     * @return the removed warning, or {@code null} if the player has none
     */
    public Punishment unwarn(UUID uuid, String staff, String reason, long legacyExpireMs) {
        Punishment newest = null;
        for (Punishment punishment : activeWarnings(uuid, legacyExpireMs)) {
            if (newest == null || punishment.id() > newest.id()) {
                newest = punishment;
            }
        }
        if (newest == null) {
            return null;
        }
        Punishment lifted = newest.revoke(staff, reason, System.currentTimeMillis());
        all.put(lifted.id(), lifted);
        dirty = true;
        saveIfDirty();
        return lifted;
    }

    public Punishment kick(UUID uuid, String name, String reason, String staff) {
        return record(new Punishment(nextId++, PunishmentType.KICK, uuid, name, reason, staff,
                System.currentTimeMillis(), 0L, false, null, null, 0L));
    }

    /** @return the lifted ban, or {@code null} if the player was not banned */
    public Punishment unban(UUID uuid, String staff, String reason) {
        return lift(activeBans, uuid, staff, reason);
    }

    /** @return the lifted mute, or {@code null} if the player was not muted */
    public Punishment unmute(UUID uuid, String staff, String reason) {
        return lift(activeMutes, uuid, staff, reason);
    }

    // ---- lookups (any thread for active bans/mutes) --------------------------------------------

    public Punishment activeBan(UUID uuid) {
        return inEffect(activeBans, uuid);
    }

    public Punishment activeMute(UUID uuid) {
        return inEffect(activeMutes, uuid);
    }

    /** Active ban of a player with this name (for players Bukkit does not know by name). */
    public Punishment activeBanByName(String name) {
        return byName(activeBans.values(), name);
    }

    public Punishment activeMuteByName(String name) {
        return byName(activeMutes.values(), name);
    }

    public List<String> bannedNames() {
        return names(activeBans.values());
    }

    public List<String> mutedNames() {
        return names(activeMutes.values());
    }

    /** All punishments of a player, newest first. */
    public List<Punishment> history(UUID uuid) {
        List<Punishment> result = new ArrayList<>();
        for (Punishment punishment : all.values()) {
            if (punishment.uuid().equals(uuid)) {
                result.add(punishment);
            }
        }
        result.sort(Comparator.comparingInt(Punishment::id).reversed());
        return result;
    }

    /** Every warning the player ever got (also expired and removed ones). */
    public int warningCount(UUID uuid) {
        int count = 0;
        for (Punishment punishment : all.values()) {
            if (punishment.type() == PunishmentType.WARN && punishment.uuid().equals(uuid)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Warnings that still count: not removed and not expired.
     *
     * @param legacyExpireMs how long warnings from before 2.4 (without their own time) count
     */
    public List<Punishment> activeWarnings(UUID uuid, long legacyExpireMs) {
        long now = System.currentTimeMillis();
        List<Punishment> result = new ArrayList<>();
        for (Punishment punishment : all.values()) {
            if (punishment.type() != PunishmentType.WARN || !punishment.uuid().equals(uuid) || punishment.revoked()) {
                continue;
            }
            boolean counts = punishment.durationMs() != 0L ? punishment.isInEffect(now)
                    : legacyExpireMs == Durations.PERMANENT || now < punishment.createdEpochMs() + legacyExpireMs;
            if (counts) {
                result.add(punishment);
            }
        }
        return result;
    }

    /**
     * Earlier punishments of this type for this reason, used to pick the next step of an
     * escalating preset. Punishments lifted as a mistake or after an accepted appeal do
     * not count.
     *
     * @param reason   the preset's display name; reasons starting with it count too
     * @param beforeId only punishments older than this id count ({@code Integer.MAX_VALUE} = all)
     */
    public int previousOffences(UUID uuid, PunishmentType type, String reason, int beforeId) {
        String wanted = reason.toLowerCase(java.util.Locale.ROOT);
        int count = 0;
        for (Punishment punishment : all.values()) {
            if (punishment.type() != type || !punishment.uuid().equals(uuid) || punishment.id() >= beforeId) {
                continue;
            }
            String text = punishment.reason().toLowerCase(java.util.Locale.ROOT);
            if (!text.equals(wanted) && !text.startsWith(wanted + " ")) {
                continue;
            }
            if (punishment.revoked() && isExcuse(punishment.revokeReason())) {
                continue;
            }
            count++;
        }
        return count;
    }

    /** Earlier automatic anti-cheat bans (for the auto-ban length ladder). */
    public int previousAutoBans(UUID uuid, String staffName) {
        return previousAutoBans(uuid, staffName, Integer.MAX_VALUE);
    }

    /** Earlier automatic bans older than {@code beforeId}. */
    public int previousAutoBans(UUID uuid, String staffName, int beforeId) {
        int count = 0;
        for (Punishment punishment : all.values()) {
            if (punishment.type() == PunishmentType.BAN && punishment.uuid().equals(uuid)
                    && staffName.equals(punishment.staff()) && punishment.id() < beforeId
                    && !(punishment.revoked() && isExcuse(punishment.revokeReason()))) {
                count++;
            }
        }
        return count;
    }

    private static boolean isExcuse(String revokeReason) {
        if (revokeReason == null) {
            return false;
        }
        String text = revokeReason.toLowerCase(java.util.Locale.ROOT);
        return text.contains("false") || text.contains("appeal") || text.contains("mistake");
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    // ---- persistence ----------------------------------------------------------------------------

    public void saveIfDirty() {
        if (!dirty || readOnly) {
            return;
        }
        String snapshot = serialize();
        dirty = false;
        io.execute("save punishments", () -> {
            try {
                AtomicFiles.write(file, snapshot);
            } catch (IOException e) {
                logger.warning("Could not save punishments: " + e.getMessage());
            }
        });
    }

    public void saveNow() {
        if (!dirty || readOnly) {
            return;
        }
        try {
            AtomicFiles.write(file, serialize());
            dirty = false;
        } catch (IOException e) {
            logger.warning("Could not save punishments: " + e.getMessage());
        }
    }

    // ---- internals --------------------------------------------------------------------------------

    private Punishment place(PunishmentType type, Map<UUID, Punishment> index, UUID uuid, String name, String reason,
                             String staff, long durationMs) {
        long now = System.currentTimeMillis();
        Punishment previous = index.get(uuid);
        if (previous != null && !previous.revoked()) {
            all.put(previous.id(), previous.revoke(staff, "Replaced by #" + nextId, now));
        }
        Punishment created = new Punishment(nextId++, type, uuid, name, reason, staff, now, durationMs, false,
                null, null, 0L);
        index.put(uuid, created);
        return record(created);
    }

    private Punishment lift(Map<UUID, Punishment> index, UUID uuid, String staff, String reason) {
        Punishment active = inEffect(index, uuid);
        if (active == null) {
            return null;
        }
        Punishment lifted = active.revoke(staff, reason, System.currentTimeMillis());
        index.remove(uuid);
        all.put(lifted.id(), lifted);
        dirty = true;
        saveIfDirty();
        return lifted;
    }

    private Punishment record(Punishment punishment) {
        all.put(punishment.id(), punishment);
        dirty = true;
        saveIfDirty();
        return punishment;
    }

    private static Punishment inEffect(Map<UUID, Punishment> index, UUID uuid) {
        Punishment punishment = index.get(uuid);
        if (punishment == null) {
            return null;
        }
        if (!punishment.isInEffect(System.currentTimeMillis())) {
            // Expired: drop it from the index (the history keeps it).
            index.remove(uuid, punishment);
            return null;
        }
        return punishment;
    }

    private static Punishment byName(Collection<Punishment> punishments, String name) {
        long now = System.currentTimeMillis();
        for (Punishment punishment : punishments) {
            if (punishment.name().equalsIgnoreCase(name) && punishment.isInEffect(now)) {
                return punishment;
            }
        }
        return null;
    }

    private static List<String> names(Collection<Punishment> punishments) {
        long now = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        for (Punishment punishment : punishments) {
            if (punishment.isInEffect(now)) {
                names.add(punishment.name());
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 1);
        yaml.set("next-id", nextId);
        for (Punishment punishment : all.values()) {
            punishment.save(yaml.createSection("punishments." + punishment.id()));
        }
        return yaml.saveToString();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            Path moved = AtomicFiles.quarantine(file);
            if (moved == null) {
                readOnly = true;
                logger.severe("punishments.yml is unreadable and could not be moved aside (" + e.getMessage()
                        + "). New punishments will NOT be saved this session to avoid overwriting it.");
            } else {
                logger.severe("punishments.yml is unreadable (" + e.getMessage() + "); moved to "
                        + moved.getFileName() + ". Existing bans/mutes from that file are not enforced until it is fixed.");
            }
            return;
        }
        nextId = Math.max(1, yaml.getInt("next-id", 1));
        ConfigurationSection section = yaml.getConfigurationSection("punishments");
        if (section == null) {
            return;
        }
        int skipped = 0;
        long now = System.currentTimeMillis();
        for (String key : section.getKeys(false)) {
            try {
                int id = Integer.parseInt(key);
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) {
                    skipped++;
                    continue;
                }
                Punishment punishment = Punishment.load(id, entry);
                all.put(id, punishment);
                nextId = Math.max(nextId, id + 1);
                if (punishment.isInEffect(now)) {
                    Map<UUID, Punishment> index = punishment.type() == PunishmentType.BAN ? activeBans : activeMutes;
                    Punishment existing = index.get(punishment.uuid());
                    if (existing == null || existing.id() < punishment.id()) {
                        index.put(punishment.uuid(), punishment);
                    }
                }
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            logger.warning(skipped + " malformed punishment(s) in punishments.yml were skipped.");
        }
        logger.info("Loaded " + all.size() + " punishment(s): " + activeBans.size() + " active ban(s), "
                + activeMutes.size() + " active mute(s).");
    }
}
