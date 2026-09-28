package io.github.drepfy.vigil.data;

import io.github.drepfy.vigil.api.CheckCategory;
import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.model.FlightTracker;
import io.github.drepfy.vigil.model.PositionHistory;
import io.github.drepfy.vigil.model.RateLimiter;
import io.github.drepfy.vigil.model.RollingMax;
import io.github.drepfy.vigil.model.SpeedBudget;
import io.github.drepfy.vigil.model.SuspicionBuffer;
import io.github.drepfy.vigil.model.TimerBalance;
import io.github.drepfy.vigil.model.ViolationLevel;
import io.github.drepfy.vigil.storage.PlayerRecord;
import org.bukkit.Location;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * All per-player state. Accessed from the server thread only, except
 * {@link #record} which is published by the IO thread (volatile).
 *
 * <p>Timestamps are monotonic milliseconds from {@link io.github.drepfy.vigil.util.Clock}.
 */
public final class PlayerData {

    private static final long NEVER = Long.MIN_VALUE / 4;

    private final UUID uuid;
    private volatile String name;

    // ---- lifecycle and exemption timestamps (monotonic ms) -------------------------------
    public long joinMs = NEVER;
    public long lastTeleportMs = NEVER;
    public long lastRespawnMs = NEVER;
    public long lastWorldChangeMs = NEVER;
    public long lastGamemodeChangeMs = NEVER;
    public long lastVelocityMs = NEVER;
    public long lastDamageMs = NEVER;
    public long lastVehicleMs = NEVER;
    public long lastGlideMs = NEVER;
    public long lastRiptideMs = NEVER;
    public long lastFlyingMs = NEVER;
    public long lastLiquidMs = NEVER;
    public long lastClimbMs = NEVER;
    public long lastSlowingMs = NEVER;
    public long lastBouncyMs = NEVER;
    public long lastIceMs = NEVER;
    public long lastSlimeMs = NEVER;
    public long lastSoulMs = NEVER;
    public long lastCeilingMs = NEVER;
    public long lastImpulseMs = NEVER;
    public long lastSetbackMs = NEVER;

    // ---- client activity -----------------------------------------------------------------
    public int moveEventsSinceSample;
    public int clientTicksSinceSample;
    public long lastClientTickMs = NEVER;
    public long lastMoveEventMs = NEVER;
    public long lastSampleMs = NEVER;
    public boolean hasSample;
    public double sampleX;
    public double sampleY;
    public double sampleZ;
    public int cachedSurroundings;
    public boolean surroundingsValid;
    public long surroundingsMs = NEVER;

    // ---- check state ---------------------------------------------------------------------
    public final PositionHistory positions = new PositionHistory(40);
    public final RollingMax ping = new RollingMax(10, 1000);
    public final SpeedBudget speed = new SpeedBudget();
    public final TimerBalance timer = new TimerBalance();
    public final FlightTracker flight = new FlightTracker();
    public final RateLimiter traceLimiter = new RateLimiter();
    /** Monotonic time from which a flight suspicion has been waiting for confirmation, or -1. */
    public long flightSuspectSinceMs = -1;
    public double flightConfirmActiveMs;
    public String flightSuspectDetail = "";
    public Location lastSafeLocation;
    public long lastSafeUpdateMs = NEVER;
    /** Last time the ground-spoof check re-sent nearby blocks to this player. */
    public long lastGroundSpoofResyncMs = NEVER;

    private final Map<CheckType, SuspicionBuffer> buffers = new EnumMap<>(CheckType.class);
    private final Map<CheckType, ViolationLevel> violations = new EnumMap<>(CheckType.class);
    private final Map<CheckType, Long> exemptUntil = new EnumMap<>(CheckType.class);
    private long exemptAllUntil = NEVER;

    // ---- bypass permission cache -----------------------------------------------------------
    private boolean bypassAll;
    private final Set<CheckType> bypass = EnumSet.noneOf(CheckType.class);
    private long bypassCheckedMs = NEVER;

    // ---- flags, alerts, punishments --------------------------------------------------------
    private final Deque<FlagRecord> recentFlags = new ArrayDeque<>();
    public final Map<CheckType, Long> lastAlertMs = new EnumMap<>(CheckType.class);
    public final Map<CheckType, Integer> suppressedAlerts = new EnumMap<>(CheckType.class);
    public final Map<CheckType, Deque<Long>> punishWindow = new EnumMap<>(CheckType.class);
    public final Map<CheckType, Long> lastPunishMs = new EnumMap<>(CheckType.class);
    public final Map<CheckType, Double> highestExecutedRule = new EnumMap<>(CheckType.class);

    /** Persistent record, loaded asynchronously after join (may be null for a moment). */
    public volatile PlayerRecord record;
    /** Flags recorded before the persistent record finished loading; replayed on load. */
    public final List<FlagRecord> pendingRecordFlags = new ArrayList<>();

    public PlayerData(UUID uuid, String name) {
        this.uuid = uuid;
        this.name = name;
        for (CheckType type : CheckType.values()) {
            buffers.put(type, new SuspicionBuffer());
            violations.put(type, new ViolationLevel());
        }
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public SuspicionBuffer buffer(CheckType type) {
        return buffers.get(type);
    }

    public ViolationLevel violation(CheckType type) {
        return violations.get(type);
    }

    // ---- exemptions ------------------------------------------------------------------------

    public void exempt(CheckType type, long untilMs) {
        exemptUntil.merge(type, untilMs, Math::max);
    }

    public void exempt(CheckCategory category, long untilMs) {
        for (CheckType type : CheckType.values()) {
            if (type.category() == category) {
                exempt(type, untilMs);
            }
        }
    }

    public void exemptAll(long untilMs) {
        exemptAllUntil = Math.max(exemptAllUntil, untilMs);
    }

    public void clearExemptions() {
        exemptUntil.clear();
        exemptAllUntil = NEVER;
    }

    public boolean isManuallyExempt(CheckType type, long nowMs) {
        if (nowMs < exemptAllUntil) {
            return true;
        }
        Long until = exemptUntil.get(type);
        return until != null && nowMs < until;
    }

    /** Remaining manual exemptions for display, check → remaining ms. */
    public Map<String, Long> activeExemptions(long nowMs) {
        Map<String, Long> result = new java.util.LinkedHashMap<>();
        if (nowMs < exemptAllUntil) {
            result.put("all", exemptAllUntil - nowMs);
        }
        for (Map.Entry<CheckType, Long> entry : exemptUntil.entrySet()) {
            if (nowMs < entry.getValue()) {
                result.put(entry.getKey().id(), entry.getValue() - nowMs);
            }
        }
        return result;
    }

    // ---- bypass cache ----------------------------------------------------------------------

    public boolean bypassCacheExpired(long nowMs, long ttlMs) {
        return nowMs - bypassCheckedMs >= ttlMs;
    }

    public void updateBypass(boolean all, Set<CheckType> perCheck, long nowMs) {
        bypassAll = all;
        bypass.clear();
        bypass.addAll(perCheck);
        bypassCheckedMs = nowMs;
    }

    public void invalidateBypass() {
        bypassCheckedMs = NEVER;
    }

    public boolean bypasses(CheckType type) {
        return bypassAll || bypass.contains(type);
    }

    // ---- flag history ----------------------------------------------------------------------

    public void addFlag(FlagRecord record, int limit) {
        recentFlags.addFirst(record);
        while (recentFlags.size() > Math.max(1, limit)) {
            recentFlags.removeLast();
        }
    }

    public List<FlagRecord> recentFlags() {
        return new ArrayList<>(recentFlags);
    }

    public void resetViolations(CheckType onlyType) {
        for (CheckType type : CheckType.values()) {
            if (onlyType == null || onlyType == type) {
                violations.get(type).reset();
                buffers.get(type).reset();
                highestExecutedRule.remove(type);
                punishWindow.remove(type);
            }
        }
    }

    /**
     * Clears movement state, e.g. after a teleport. The position history is kept on
     * purpose: attackers may still see (and hit) the pre-teleport position for a
     * moment, and lag compensation needs it.
     */
    public void resetMovementState() {
        speed.reset();
        timer.reset();
        hasSample = false;
        surroundingsValid = false;
        flightSuspectSinceMs = -1;
        flightConfirmActiveMs = 0;
        moveEventsSinceSample = 0;
        clientTicksSinceSample = 0;
    }

    public static long never() {
        return NEVER;
    }
}
