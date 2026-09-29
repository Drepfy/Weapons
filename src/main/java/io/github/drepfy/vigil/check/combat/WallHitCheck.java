package io.github.drepfy.vigil.check.combat;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.env.WorldProbe;
import io.github.drepfy.vigil.model.Box;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;

/**
 * Hitting an entity through solid walls. A hit is only suspicious when every line
 * from every plausible eye position to a grid of points on every lag-compensated
 * target position passes through a full, opaque block. Partial blocks (slabs,
 * glass, fences...) never count, and the eye inside a block aborts the check.
 */
public final class WallHitCheck {

    private static final CheckType TYPE = CheckType.WALLHIT;
    private static final int MAX_BOXES = 4;
    private static final double INSET = 0.05;
    private static final int GRID = 3;

    private final CheckContext ctx;

    public WallHitCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onAttack(Player attacker, PlayerData data, AttackSnapshot hit, Cancellable event, long now) {
        if (!ctx.isActive(attacker, data, TYPE, now) || ctx.recentlyRelocated(data, now)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        if (!data.traceLimiter.tryAcquire(now, settings.num("max-traces-per-second"))) {
            return;
        }
        WorldProbe probe = ctx.probe();
        double[] eyeYs = distinct(hit.eyeYs());
        for (double eyeY : eyeYs) {
            if (probe.isInsideFullBlock(hit.world(), hit.eyeX(), eyeY, hit.eyeZ())) {
                return;
            }
        }
        for (Box box : hit.sampledBoxes(MAX_BOXES)) {
            if (hasClearLine(probe, hit, eyeYs, box)) {
                data.buffer(TYPE).reduce(0.5);
                return;
            }
        }
        if (!ctx.canFlag(attacker, data, now)) {
            return;
        }
        double buffer = data.buffer(TYPE).add(1.0, now, 0.02);
        ctx.debug(attacker, TYPE, () -> "no line of sight to any target position, buffer=" + Text.num(buffer));
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        // Only an established pattern (not a single uncertain trace) cancels the hit.
        if (ctx.mitigationAllowed(settings)) {
            event.setCancelled(true);
        }
        data.buffer(TYPE).reset();
        ctx.flag(attacker, data, TYPE, "hit through a solid wall at " + Text.num(hit.distance()) + " blocks");
    }

    private static boolean hasClearLine(WorldProbe probe, AttackSnapshot hit, double[] eyeYs, Box box) {
        double spanX = box.maxX() - box.minX() - 2 * INSET;
        double spanY = box.maxY() - box.minY() - 2 * INSET;
        double spanZ = box.maxZ() - box.minZ() - 2 * INSET;
        // Centre first: the common legitimate case exits after a single trace.
        for (int i = -1; i < GRID * GRID * GRID; i++) {
            double px;
            double py;
            double pz;
            if (i < 0) {
                px = box.centerX();
                py = box.centerY();
                pz = box.centerZ();
            } else {
                int gx = i % GRID;
                int gy = (i / GRID) % GRID;
                int gz = i / (GRID * GRID);
                px = box.minX() + INSET + spanX * gx / (GRID - 1);
                py = box.minY() + INSET + spanY * gy / (GRID - 1);
                pz = box.minZ() + INSET + spanZ * gz / (GRID - 1);
            }
            for (double eyeY : eyeYs) {
                if (!probe.segmentBlocked(hit.world(), hit.eyeX(), eyeY, hit.eyeZ(), px, py, pz, 0, 0, 0, false)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double[] distinct(double[] values) {
        return java.util.Arrays.stream(values).distinct().toArray();
    }
}
