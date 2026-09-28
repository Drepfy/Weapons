package io.github.drepfy.vigil.check.combat;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.model.Box;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;

/**
 * Everything the combat checks need about one melee hit, computed once.
 *
 * <p>Lag compensation: the attacker saw the target where it was roughly
 * {@code ping + interpolation} milliseconds ago. For player targets the recorded
 * path over that window (with interpolation between samples) is used; for other
 * entities, the box is extended backwards along their velocity and a flat tolerance
 * is added.
 */
public record AttackSnapshot(World world,
                             double eyeX,
                             double eyeZ,
                             double[] eyeYs,
                             double yaw,
                             double pitch,
                             List<Box> targetBoxes,
                             double extraTolerance,
                             double distance,
                             boolean playerTarget,
                             long windowMs) {

    /** Upper bound on how far a non-player target may be assumed to have moved. */
    private static final double MAX_MOB_TOLERANCE = 2.5;

    public static AttackSnapshot capture(CheckContext ctx, Player attacker, PlayerData attackerData, Entity target,
                                         long now) {
        CheckSettings reach = ctx.settings(CheckType.REACH);
        int ping = ctx.recentPing(attacker, attackerData, now);
        long windowMs = Math.min(Math.round(ping + reach.num("lag-window-extra-ms")), reach.millis("max-lag-window-ms"));

        Location location = attacker.getLocation();
        double[] heights = ctx.eyeHeights(attacker);
        double[] eyeYs = new double[heights.length];
        for (int i = 0; i < heights.length; i++) {
            eyeYs[i] = location.getY() + heights[i];
        }

        List<Box> boxes = new ArrayList<>();
        double extra = 0.0;
        boolean playerTarget = target instanceof Player;
        if (target instanceof Player targetPlayer) {
            PlayerData targetData = ctx.players().peek(targetPlayer.getUniqueId());
            if (targetData != null) {
                boxes.addAll(targetData.positions.boxesSince(now - windowMs));
            }
        }
        BoundingBox current = target.getBoundingBox();
        Box currentBox = new Box(current.getMinX(), current.getMinY(), current.getMinZ(),
                current.getMaxX(), current.getMaxY(), current.getMaxZ());
        // Always consider the current server position as well (lenient).
        boxes.add(0, currentBox);
        if (!playerTarget) {
            Vector velocity = target.getVelocity();
            double ticks = windowMs / 50.0;
            double dx = velocity.getX() * ticks;
            double dy = velocity.getY() * ticks;
            double dz = velocity.getZ() * ticks;
            boxes.add(new Box(currentBox.minX() - dx, currentBox.minY() - dy, currentBox.minZ() - dz,
                    currentBox.maxX() - dx, currentBox.maxY() - dy, currentBox.maxZ() - dz));
            double speed = Math.sqrt(dx * dx + dy * dy + dz * dz);
            extra = Math.min(MAX_MOB_TOLERANCE, reach.num("mob-leniency") + speed * 0.5);
        }

        double best = Double.MAX_VALUE;
        for (Box box : boxes) {
            for (double eyeY : eyeYs) {
                best = Math.min(best, box.distanceTo(location.getX(), eyeY, location.getZ()));
            }
        }
        double distance = Math.max(0.0, best - extra);
        return new AttackSnapshot(location.getWorld(), location.getX(), location.getZ(), eyeYs,
                location.getYaw(), location.getPitch(), List.copyOf(boxes), extra, distance, playerTarget, windowMs);
    }

    /** Boxes grown by the non-player tolerance, used for angle checks. */
    public List<Box> tolerantBoxes() {
        if (extraTolerance <= 0.0) {
            return targetBoxes;
        }
        List<Box> grown = new ArrayList<>(targetBoxes.size());
        for (Box box : targetBoxes) {
            grown.add(box.expand(extraTolerance));
        }
        return grown;
    }

    /** At most {@code limit} boxes spread evenly over the window (newest first). */
    public List<Box> sampledBoxes(int limit) {
        if (targetBoxes.size() <= limit) {
            return targetBoxes;
        }
        List<Box> result = new ArrayList<>(limit);
        double step = (targetBoxes.size() - 1) / (double) (limit - 1);
        for (int i = 0; i < limit; i++) {
            result.add(targetBoxes.get((int) Math.round(i * step)));
        }
        return result;
    }
}
