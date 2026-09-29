package io.github.drepfy.vigil.check.interaction;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.env.BlockTraits;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Locale;

/**
 * Placing a block against a face the player cannot see (scaffold and similar cheats).
 *
 * <p>A face is only visible from its outer side: to click the top of a block the eyes
 * must be above it, to click its north side they must be north of it, and so on. The
 * eye position at the moment of the click is exactly the player's last position, so
 * the only uncertainty is the pose (all possible eye heights are tried). For blocks
 * that are not full cubes the far side of the block is used instead, which is always
 * safe.
 */
public final class InteractCheck {

    private static final CheckType TYPE = CheckType.INTERACT;
    private static final long SETBACK_GRACE_MS = 1000;

    private final CheckContext ctx;

    public InteractCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onPlace(Player player, PlayerData data, BlockPlaceEvent event, long now) {
        Block placed = event.getBlockPlaced();
        Block against = event.getBlockAgainst();
        if (!ctx.isActive(player, data, TYPE, now) || player.isInsideVehicle() || ctx.recentlyRelocated(data, now)
                || now - data.lastSetbackMs < SETBACK_GRACE_MS) {
            return;
        }
        if (placed.equals(against) || !placed.getWorld().equals(player.getWorld())) {
            return;
        }
        BlockFace face = against.getFace(placed);
        if (face == null || face == BlockFace.SELF || !face.isCartesian()) {
            return;
        }
        Material againstType = against.getType();
        if (isSpecial(placed.getType()) || isSpecial(againstType)) {
            return;
        }
        boolean full = ctx.probe().traits().has(againstType, BlockTraits.FULL);
        Location location = player.getLocation();
        double[] heights = ctx.eyeHeights(player);
        double minEye = Double.MAX_VALUE;
        double maxEye = -Double.MAX_VALUE;
        for (double height : heights) {
            minEye = Math.min(minEye, location.getY() + height);
            maxEye = Math.max(maxEye, location.getY() + height);
        }
        CheckSettings settings = ctx.settings(TYPE);
        double margin = settings.num("margin");
        int bx = against.getX();
        int by = against.getY();
        int bz = against.getZ();
        double eyeX = location.getX();
        double eyeZ = location.getZ();
        // Distance by which the eyes are on the hidden side of the clicked face (> 0 = hidden).
        double behind = switch (face) {
            case UP -> (full ? by + 1 : by) - maxEye;
            case DOWN -> minEye - (full ? by : by + 1);
            case EAST -> (full ? bx + 1 : bx) - eyeX;
            case WEST -> eyeX - (full ? bx : bx + 1);
            case SOUTH -> (full ? bz + 1 : bz) - eyeZ;
            case NORTH -> eyeZ - (full ? bz : bz + 1);
            default -> -1.0;
        };
        if (behind <= margin) {
            data.buffer(TYPE).reduce(0.25);
            return;
        }
        if (!ctx.canFlag(player, data, now)) {
            return;
        }
        double buffer = data.buffer(TYPE).add(1.0, now, 0.05);
        ctx.debug(player, TYPE, () -> "clicked the " + face + " face from " + Text.num(behind)
                + " blocks behind it, buffer " + Text.num(buffer));
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        if (ctx.mitigationAllowed(settings)) {
            event.setCancelled(true);
        }
        data.buffer(TYPE).reset();
        ctx.flag(player, data, TYPE, "placed " + placed.getType().name().toLowerCase(Locale.ROOT)
                + " against the hidden " + face.name().toLowerCase(Locale.ROOT) + " side of "
                + againstType.name().toLowerCase(Locale.ROOT) + " (" + Text.num(behind) + " blocks behind it)");
    }

    /** Blocks with unusual placement rules (scaffolding climbs, liquids flow...). */
    private static boolean isSpecial(Material material) {
        String name = material.name();
        return name.equals("SCAFFOLDING") || name.equals("WATER") || name.equals("LAVA")
                || name.endsWith("_BUCKET") || material.isAir();
    }
}
