package io.github.drepfy.vigil.check.interaction;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.env.WorldProbe;
import io.github.drepfy.vigil.util.Glob;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerInteractEvent;

/**
 * Opening containers the player cannot see (chest-aura/stealer cheats). Suspicious
 * only when every line from every plausible eye position to a grid of points on the
 * clicked face passes through another full, opaque block.
 */
public final class ChestAuraCheck {

    private static final CheckType TYPE = CheckType.CHESTAURA;
    private static final double INSET = 0.05;
    private static final double OFFSET = 0.01;

    private final CheckContext ctx;

    public ChestAuraCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onInteract(Player player, PlayerData data, PlayerInteractEvent event, long now) {
        Block block = event.getClickedBlock();
        BlockFace face = event.getBlockFace();
        if (block == null || !ctx.isActive(player, data, TYPE, now) || ctx.recentlyRelocated(data, now)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        if (!Glob.matchesAny(settings.list("blocks"), block.getType().name())) {
            return;
        }
        if (face == null || face == BlockFace.SELF || !face.isCartesian()) {
            return;
        }
        WorldProbe probe = ctx.probe();
        World world = block.getWorld();
        Location location = player.getLocation();
        double[] heights = ctx.eyeHeights(player);
        for (double height : heights) {
            if (probe.isInsideFullBlock(world, location.getX(), location.getY() + height, location.getZ())) {
                return;
            }
        }

        if (hasClearLine(probe, world, location, heights, block, face)) {
            data.buffer(TYPE).reduce(0.5);
            return;
        }
        if (!ctx.canFlag(player, data, now)) {
            return;
        }
        double buffer = data.buffer(TYPE).add(1.0, now, 0.02);
        ctx.debug(player, TYPE, () -> "no line of sight to " + block.getType() + " " + face + ", buffer="
                + Text.num(buffer));
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        if (ctx.mitigationAllowed(settings)) {
            event.setCancelled(true);
        }
        data.buffer(TYPE).reset();
        ctx.flag(player, data, TYPE, "opened " + block.getType().name() + " without line of sight");
    }

    private static boolean hasClearLine(WorldProbe probe, World world, Location eye, double[] heights, Block block,
                                        BlockFace face) {
        int modX = face.getModX();
        int modY = face.getModY();
        int modZ = face.getModZ();
        for (int u = 0; u < 3; u++) {
            for (int v = 0; v < 3; v++) {
                double a = INSET + (1 - 2 * INSET) * u / 2.0;
                double b = INSET + (1 - 2 * INSET) * v / 2.0;
                double px;
                double py;
                double pz;
                if (modX != 0) {
                    px = block.getX() + (modX > 0 ? 1 + OFFSET : -OFFSET);
                    py = block.getY() + a;
                    pz = block.getZ() + b;
                } else if (modY != 0) {
                    px = block.getX() + a;
                    py = block.getY() + (modY > 0 ? 1 + OFFSET : -OFFSET);
                    pz = block.getZ() + b;
                } else {
                    px = block.getX() + a;
                    py = block.getY() + b;
                    pz = block.getZ() + (modZ > 0 ? 1 + OFFSET : -OFFSET);
                }
                for (double height : heights) {
                    if (!probe.segmentBlocked(world, eye.getX(), eye.getY() + height, eye.getZ(), px, py, pz,
                            block.getX(), block.getY(), block.getZ(), true)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
