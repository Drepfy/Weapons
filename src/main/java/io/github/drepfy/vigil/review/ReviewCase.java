package io.github.drepfy.vigil.review;

import io.github.drepfy.vigil.api.CheckType;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * A manual review case: opened when a player's VL reaches a check's review
 * threshold (or by staff), collects evidence, and is closed by a human verdict.
 */
public final class ReviewCase {

    public enum Status {
        OPEN,
        RESOLVED
    }

    public enum Verdict {
        CHEATING,
        LEGIT,
        INCONCLUSIVE;

        public static Verdict parse(String input) {
            if (input == null) {
                return null;
            }
            String value = input.trim().toUpperCase(Locale.ROOT);
            for (Verdict verdict : values()) {
                if (verdict.name().equals(value)) {
                    return verdict;
                }
            }
            return switch (value) {
                case "CHEATER", "GUILTY", "CONFIRMED" -> CHEATING;
                case "CLEAN", "INNOCENT", "FALSE", "FALSE-POSITIVE", "FALSE_POSITIVE" -> LEGIT;
                case "UNSURE", "UNKNOWN" -> INCONCLUSIVE;
                default -> null;
            };
        }
    }

    private final int id;
    private final UUID uuid;
    private String name;
    private final long openedEpochMs;
    private long updatedEpochMs;
    private final String reason;
    private Status status = Status.OPEN;
    private final Map<CheckType, Double> peakVl = new EnumMap<>(CheckType.class);
    private final List<String> evidence = new ArrayList<>();
    private String claimedBy;
    private Verdict verdict;
    private String resolvedBy;
    private long resolvedEpochMs;
    private String note;

    public ReviewCase(int id, UUID uuid, String name, long openedEpochMs, String reason) {
        this.id = id;
        this.uuid = uuid;
        this.name = name;
        this.openedEpochMs = openedEpochMs;
        this.updatedEpochMs = openedEpochMs;
        this.reason = reason;
    }

    public void addEvidence(String line, int maxEvidence) {
        if (evidence.size() < maxEvidence) {
            evidence.add(line);
        } else if (!evidence.isEmpty()) {
            // Keep the oldest half (context of the opening) and roll the newest.
            evidence.remove(Math.max(1, maxEvidence / 2));
            evidence.add(line);
        }
        updatedEpochMs = System.currentTimeMillis();
    }

    public void updatePeak(CheckType check, double vl) {
        peakVl.merge(check, vl, Math::max);
        updatedEpochMs = System.currentTimeMillis();
    }

    public void claim(String staff) {
        claimedBy = staff;
        updatedEpochMs = System.currentTimeMillis();
    }

    public void resolve(Verdict verdict, String staff, String note) {
        this.status = Status.RESOLVED;
        this.verdict = verdict;
        this.resolvedBy = staff;
        this.note = note;
        this.resolvedEpochMs = System.currentTimeMillis();
        this.updatedEpochMs = resolvedEpochMs;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int id() {
        return id;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public long openedEpochMs() {
        return openedEpochMs;
    }

    public long updatedEpochMs() {
        return updatedEpochMs;
    }

    public String reason() {
        return reason;
    }

    public Status status() {
        return status;
    }

    public Map<CheckType, Double> peakVl() {
        Map<CheckType, Double> copy = new EnumMap<>(CheckType.class);
        copy.putAll(peakVl);
        return copy;
    }

    public List<String> evidence() {
        return new ArrayList<>(evidence);
    }

    public String claimedBy() {
        return claimedBy;
    }

    public Verdict verdict() {
        return verdict;
    }

    public String resolvedBy() {
        return resolvedBy;
    }

    public long resolvedEpochMs() {
        return resolvedEpochMs;
    }

    public String note() {
        return note;
    }

    void save(ConfigurationSection section) {
        section.set("uuid", uuid.toString());
        section.set("name", name);
        section.set("opened", openedEpochMs);
        section.set("updated", updatedEpochMs);
        section.set("reason", reason);
        section.set("status", status.name());
        for (Map.Entry<CheckType, Double> entry : peakVl.entrySet()) {
            section.set("peak-vl." + entry.getKey().id(), entry.getValue());
        }
        section.set("evidence", new ArrayList<>(evidence));
        section.set("claimed-by", claimedBy);
        section.set("verdict", verdict != null ? verdict.name() : null);
        section.set("resolved-by", resolvedBy);
        section.set("resolved", resolvedEpochMs == 0 ? null : resolvedEpochMs);
        section.set("note", note);
    }

    static ReviewCase load(int id, ConfigurationSection section) {
        String rawUuid = section.getString("uuid");
        if (rawUuid == null) {
            throw new IllegalArgumentException("case " + id + " has no uuid");
        }
        ReviewCase reviewCase = new ReviewCase(id, UUID.fromString(rawUuid), section.getString("name", "unknown"),
                section.getLong("opened"), section.getString("reason", ""));
        reviewCase.updatedEpochMs = section.getLong("updated", reviewCase.openedEpochMs);
        reviewCase.status = "RESOLVED".equalsIgnoreCase(section.getString("status")) ? Status.RESOLVED : Status.OPEN;
        ConfigurationSection peaks = section.getConfigurationSection("peak-vl");
        if (peaks != null) {
            for (String key : peaks.getKeys(false)) {
                CheckType type = CheckType.fromId(key);
                if (type != null) {
                    reviewCase.peakVl.put(type, peaks.getDouble(key));
                }
            }
        }
        reviewCase.evidence.addAll(section.getStringList("evidence"));
        reviewCase.claimedBy = section.getString("claimed-by");
        reviewCase.verdict = Verdict.parse(section.getString("verdict"));
        reviewCase.resolvedBy = section.getString("resolved-by");
        reviewCase.resolvedEpochMs = section.getLong("resolved", 0L);
        reviewCase.note = section.getString("note");
        return reviewCase;
    }
}
