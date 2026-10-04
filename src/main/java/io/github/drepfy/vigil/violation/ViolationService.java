package io.github.drepfy.vigil.violation;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.api.VigilFlagEvent;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.FlagRecord;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.storage.FlagLogWriter;
import io.github.drepfy.vigil.storage.PlayerRecord;
import io.github.drepfy.vigil.task.TpsMonitor;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * The single place where a violation becomes official: fires the API event, raises
 * the VL, stores the evidence, and hands the flag to staff alerts and the auto-ban. Checks call {@link #flag} only after their own buffering and
 * exemption logic already decided the behaviour is clearly abnormal.
 */
public final class ViolationService {

    private final Supplier<Settings> settings;
    private final AlertService alerts;
    private final AutoBanService autoBan;
    private final FlagLogWriter log;
    private final TpsMonitor tps;
    private final ServerCompat compat;

    /** Most a single flag can count for, however blatant (in units of {@code vl-per-flag}). */
    public static final double MAX_WEIGHT = 3.0;

    public ViolationService(Supplier<Settings> settings, AlertService alerts, AutoBanService autoBan,
                            FlagLogWriter log, TpsMonitor tps, ServerCompat compat) {
        this.settings = settings;
        this.alerts = alerts;
        this.autoBan = autoBan;
        this.log = log;
        this.tps = tps;
        this.compat = compat;
    }

    /**
     * Records a violation.
     *
     * @return the new VL, or -1 if another plugin cancelled the flag
     */
    public double flag(Player player, PlayerData data, CheckType type, String detail) {
        return flag(player, data, type, detail, 1.0);
    }

    /**
     * Records a violation that counts {@code weight} times {@code vl-per-flag}: blatant cheating
     * (e.g. twice the allowed speed) reaches the ban limit sooner than borderline cases.
     *
     * @param weight 1 for an ordinary flag, capped at {@link #MAX_WEIGHT}
     * @return the new VL, or -1 if another plugin cancelled the flag
     */
    public double flag(Player player, PlayerData data, CheckType type, String detail, double weight) {
        Settings config = settings.get();
        CheckSettings check = config.check(type);
        long now = Clock.now();
        double current = data.violation(type).get(now, check.decayPerMinute());
        double added = check.vlPerFlag() * (Double.isFinite(weight) ? Math.max(1.0, Math.min(MAX_WEIGHT, weight)) : 1.0);

        VigilFlagEvent event = new VigilFlagEvent(player, type, added, current, detail);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return -1;
        }
        if (type.category() == io.github.drepfy.vigil.api.CheckCategory.MOVEMENT) {
            data.lastMovementFlagMs = now;
        }

        double vl = data.violation(type).add(added, now, check.decayPerMinute());
        Location location = player.getLocation();
        String world = location.getWorld() != null ? location.getWorld().getName() : "?";
        int ping = Math.max(0, compat.ping(player));
        double currentTps = tps.tps();
        FlagRecord flag = new FlagRecord(System.currentTimeMillis(), type, vl, detail, world,
                location.getX(), location.getY(), location.getZ(), ping, currentTps);

        int historySize = config.violations().historySize();
        data.addFlag(flag, historySize);
        PlayerRecord record = data.record;
        if (record != null) {
            record.recordFlag(flag, historySize);
        } else if (data.pendingRecordFlags.size() < historySize) {
            data.pendingRecordFlags.add(flag);
        }
        if (config.violations().logToFile()) {
            log.append(player.getName() + " (" + player.getUniqueId() + ") " + flag.toLine());
        }

        alerts.onFlag(player, data, flag, check, currentTps);
        autoBan.onFlag(player, data, flag, check);
        return vl;
    }
}
