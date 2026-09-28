package io.github.drepfy.vigil.data;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.util.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * One recorded violation with enough context for a human reviewer.
 */
public record FlagRecord(long timeMs,
                         CheckType check,
                         double vl,
                         String detail,
                         String world,
                         double x,
                         double y,
                         double z,
                         int ping,
                         double tps) {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    /** Single line used in logs, player records and review evidence. */
    public String toLine() {
        return TIME.format(Instant.ofEpochMilli(timeMs)) + " " + check.displayName()
                + " vl=" + Text.num(vl)
                + " [" + detail + "]"
                + " @" + world + " " + Text.num(x) + "," + Text.num(y) + "," + Text.num(z)
                + " ping=" + ping + "ms tps=" + Text.num(tps);
    }
}
