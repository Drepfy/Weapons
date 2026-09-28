package io.github.drepfy.vigil.review;

import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.FlagRecord;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.storage.AtomicFiles;
import io.github.drepfy.vigil.storage.IoExecutor;
import io.github.drepfy.vigil.util.Text;
import io.github.drepfy.vigil.violation.AlertService;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Logger;

/**
 * Owns the review queue. Main thread only; persistence happens on the IO thread
 * from a serialised snapshot.
 */
public final class ReviewService {

    public static final String REVIEW_PERMISSION = "vigil.review";

    private final Supplier<Settings> settings;
    private final Logger logger;
    private final IoExecutor io;
    private final Path file;
    private final Path archiveFile;
    private final AlertService alerts;
    private final Map<Integer, ReviewCase> cases = new LinkedHashMap<>();
    private int nextId = 1;
    private boolean dirty;
    /** When the file could not be read we never overwrite it, to avoid destroying data. */
    private boolean readOnly;

    public ReviewService(Supplier<Settings> settings, Logger logger, IoExecutor io, Path file, Path archiveFile,
                         AlertService alerts) {
        this.settings = settings;
        this.logger = logger;
        this.io = io;
        this.file = file;
        this.archiveFile = archiveFile;
        this.alerts = alerts;
        load();
    }

    /** Opens or updates a case when a flag reaches the check's review threshold. */
    public void onFlag(Player player, PlayerData data, FlagRecord flag, CheckSettings check) {
        Settings.Review config = settings.get().review();
        if (!config.enabled() || check.reviewVl() <= 0 || flag.vl() < check.reviewVl()) {
            return;
        }
        ReviewCase open = findOpen(player.getUniqueId());
        if (open != null) {
            open.setName(player.getName());
            open.addEvidence(flag.toLine(), config.maxEvidence());
            open.updatePeak(flag.check(), flag.vl());
            dirty = true;
            return;
        }
        String reason = flag.check().displayName() + " reached VL " + Text.num(flag.vl());
        ReviewCase created = open(player.getUniqueId(), player.getName(), reason);
        // Include recent context from any check, oldest first.
        List<FlagRecord> recent = data.recentFlags();
        for (int i = Math.min(recent.size(), config.maxEvidence()) - 1; i >= 0; i--) {
            created.addEvidence(recent.get(i).toLine(), config.maxEvidence());
        }
        created.updatePeak(flag.check(), flag.vl());
        if (config.notifyStaff()) {
            alerts.notify(REVIEW_PERMISSION, Text.color(settings.get().messages().get("prefix")
                    + Text.replace(settings.get().messages().get("case-opened"),
                    "id", created.id(), "player", player.getName(), "reason", reason)));
        }
    }

    /** Opens a case (also used by staff through /vigil review open). */
    public ReviewCase open(UUID uuid, String name, String reason) {
        ReviewCase created = new ReviewCase(nextId++, uuid, name, System.currentTimeMillis(), reason);
        cases.put(created.id(), created);
        dirty = true;
        enforceLimit();
        return created;
    }

    public ReviewCase get(int id) {
        return cases.get(id);
    }

    public ReviewCase findOpen(UUID uuid) {
        for (ReviewCase reviewCase : cases.values()) {
            if (reviewCase.status() == ReviewCase.Status.OPEN && reviewCase.uuid().equals(uuid)) {
                return reviewCase;
            }
        }
        return null;
    }

    /** Cases matching the filter, newest first. */
    public List<ReviewCase> list(boolean openOnly) {
        List<ReviewCase> result = new ArrayList<>();
        for (ReviewCase reviewCase : cases.values()) {
            if (!openOnly || reviewCase.status() == ReviewCase.Status.OPEN) {
                result.add(reviewCase);
            }
        }
        result.sort(Comparator.comparingInt(ReviewCase::id).reversed());
        return result;
    }

    public List<ReviewCase> casesOf(UUID uuid) {
        List<ReviewCase> result = new ArrayList<>();
        for (ReviewCase reviewCase : cases.values()) {
            if (reviewCase.uuid().equals(uuid)) {
                result.add(reviewCase);
            }
        }
        result.sort(Comparator.comparingInt(ReviewCase::id).reversed());
        return result;
    }

    public int openCount() {
        int count = 0;
        for (ReviewCase reviewCase : cases.values()) {
            if (reviewCase.status() == ReviewCase.Status.OPEN) {
                count++;
            }
        }
        return count;
    }

    public void markDirty() {
        dirty = true;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    /** Writes the queue on the IO thread if anything changed. */
    public void saveIfDirty() {
        if (!dirty || readOnly) {
            return;
        }
        String snapshot = serialize();
        dirty = false;
        io.execute("save review cases", () -> {
            try {
                AtomicFiles.write(file, snapshot);
            } catch (IOException e) {
                logger.warning("Could not save review cases: " + e.getMessage());
            }
        });
    }

    /** Synchronous save for shutdown. */
    public void saveNow() {
        if (!dirty || readOnly) {
            return;
        }
        try {
            AtomicFiles.write(file, serialize());
            dirty = false;
        } catch (IOException e) {
            logger.warning("Could not save review cases: " + e.getMessage());
        }
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 1);
        yaml.set("next-id", nextId);
        for (ReviewCase reviewCase : cases.values()) {
            reviewCase.save(yaml.createSection("cases." + reviewCase.id()));
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
                logger.severe("Review cases file is unreadable and could not be moved aside (" + e.getMessage()
                        + "). Review cases will NOT be saved this session to avoid overwriting it.");
            } else {
                logger.warning("Review cases file is unreadable (" + e.getMessage() + "); moved to "
                        + moved.getFileName() + " and starting an empty queue.");
            }
            return;
        }
        nextId = Math.max(1, yaml.getInt("next-id", 1));
        ConfigurationSection section = yaml.getConfigurationSection("cases");
        if (section == null) {
            return;
        }
        int skipped = 0;
        for (String key : section.getKeys(false)) {
            try {
                int id = Integer.parseInt(key);
                ConfigurationSection caseSection = section.getConfigurationSection(key);
                if (caseSection == null) {
                    skipped++;
                    continue;
                }
                cases.put(id, ReviewCase.load(id, caseSection));
                nextId = Math.max(nextId, id + 1);
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            logger.warning(skipped + " malformed review case(s) were skipped (the file is kept until the next save).");
        }
    }

    /** Archives the oldest resolved cases (append-only log) when the queue exceeds max-cases. */
    private void enforceLimit() {
        int max = settings.get().review().maxCases();
        if (cases.size() <= max) {
            return;
        }
        List<ReviewCase> resolved = new ArrayList<>();
        for (ReviewCase reviewCase : cases.values()) {
            if (reviewCase.status() == ReviewCase.Status.RESOLVED) {
                resolved.add(reviewCase);
            }
        }
        resolved.sort(Comparator.comparingInt(ReviewCase::id));
        StringBuilder archive = new StringBuilder();
        for (ReviewCase reviewCase : resolved) {
            if (cases.size() <= max) {
                break;
            }
            cases.remove(reviewCase.id());
            archive.append('#').append(reviewCase.id()).append(' ').append(reviewCase.name())
                    .append(' ').append(reviewCase.uuid())
                    .append(" opened=").append(Instant.ofEpochMilli(reviewCase.openedEpochMs()))
                    .append(" verdict=").append(reviewCase.verdict())
                    .append(" by=").append(reviewCase.resolvedBy())
                    .append(" reason=").append(reviewCase.reason())
                    .append(" note=").append(reviewCase.note())
                    .append(System.lineSeparator());
            for (String line : reviewCase.evidence()) {
                archive.append("    ").append(line).append(System.lineSeparator());
            }
        }
        if (archive.length() > 0) {
            String text = archive.toString();
            io.execute("archive review cases", () -> {
                try {
                    Files.createDirectories(archiveFile.getParent());
                    Files.writeString(archiveFile, text, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND);
                } catch (IOException e) {
                    logger.warning("Could not archive review cases: " + e.getMessage());
                }
            });
        }
    }
}
