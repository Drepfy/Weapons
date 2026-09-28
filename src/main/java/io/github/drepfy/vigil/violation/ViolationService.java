package io.github.drepfy.vigil.violation;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.api.VigilFlagEvent;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.data.FlagRecord;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.review.ReviewService;
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
 * the VL, stores the evidence, and hands the flag to alerts, review and (optional)
 * punishments. Checks call {@link #flag} only after their own buffering and
 * exemption logic already decided the behaviour is clearly abnormal.
 */
public final class ViolationService {

    private final Supplier<Settings> settings;
    private final AlertService alerts;
    private final ReviewService review;
    private final PunishmentService punishments;
    private final FlagLogWriter log;
    private final TpsMonitor tps;
    private final ServerCompat compat;

    public ViolationService(Supplier<Settings> settings, AlertService alerts, ReviewService review,
                            PunishmentService punishments, FlagLogWriter log, TpsMonitor tps, ServerCompat compat) {
        this.settings = settings;
        this.alerts = alerts;
        this.review = review;
        this.punishments = punishments;
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
        Settings config = settings.get();
        CheckSettings check = config.check(type);
        long now = Clock.now();
        double current = data.violation(type).get(now, check.decayPerMinute());

        VigilFlagEvent event = new VigilFlagEvent(player, type, check.vlPerFlag(), current, detail);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            return -1;
        }

        double vl = data.violation(type).add(check.vlPerFlag(), now, check.decayPerMinute());
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
        review.onFlag(player, data, flag, check);
        punishments.onFlag(player, data, flag, check);
        return vl;
    }
}
