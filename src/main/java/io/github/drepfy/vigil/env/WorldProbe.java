package io.github.drepfy.vigil.env;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.entity.Player;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.VoxelShape;

/**
 * Read-only world queries used by the checks. Never loads chunks: a block in an
 * unloaded chunk is reported as {@link #UNKNOWN}, which every caller treats as a
 * reason not to flag.
 */
public final class WorldProbe {

    /** A collision box is inside the support region (the player could be standing on or against it). */
    public static final int SUPPORT = 1;
    public static final int LIQUID = 1 << 1;
    public static final int CLIMBABLE = 1 << 2;
    public static final int SLOWING = 1 << 3;
    /** The support includes a bouncy block (slime, bed). */
    public static final int BOUNCY = 1 << 4;
    public static final int PISTON = 1 << 5;
    /** Part of the scanned area is not loaded or could not be classified. */
    public static final int UNKNOWN = 1 << 6;

    /** Any of these means the player is not simply "in the air". */
    public static final int NOT_AIRBORNE = SUPPORT | LIQUID | CLIMBABLE | SLOWING | PISTON | UNKNOWN;

    private static final double EPSILON = 1.0E-7;
    private static final double SPECIAL_MARGIN = 0.1;

    private final BlockTraits traits;

    public WorldProbe(BlockTraits traits) {
        this.traits = traits;
    }

    public BlockTraits traits() {
        return traits;
    }

    /** Material at a position, or {@code null} if its chunk is not loaded. Outside the world counts as air. */
    public Material typeAt(World world, int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
            return Material.AIR;
        }
        if (!world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        return world.getType(x, y, z);
    }

    /**
     * Scans around a player hitbox.
     *
     * @param h    horizontal distance beyond the hitbox in which a block counts as support
     * @param down how far below the feet a block counts as support
     * @return a combination of the flags declared in this class
     */
    public int scan(World world, double x, double y, double z, double width, double height, double h, double down) {
        double half = width / 2.0;

        // Fast path: an ordinary full cube in the cell just under the feet centre. Its top is at
        // most 0.01 below the feet, so the player is certainly supported and nothing else matters.
        Material below = typeAt(world, floor(x), floor(y - 0.01), floor(z));
        if (below != null) {
            int belowFlags = traits.of(below);
            if ((belowFlags & BlockTraits.FULL) != 0
                    && (belowFlags & (BlockTraits.BOUNCY | BlockTraits.SLOWING | BlockTraits.PISTON)) == 0) {
                return SUPPORT;
            }
        }

        double sMinX = x - half - h;
        double sMaxX = x + half + h;
        double sMinZ = z - half - h;
        double sMaxZ = z + half + h;
        double sMinY = y - down;
        double sMaxY = y + 0.1;

        double pMinX = x - half - SPECIAL_MARGIN;
        double pMaxX = x + half + SPECIAL_MARGIN;
        double pMinZ = z - half - SPECIAL_MARGIN;
        double pMaxZ = z + half + SPECIAL_MARGIN;
        double pMinY = y - SPECIAL_MARGIN;
        double pMaxY = y + height + SPECIAL_MARGIN;

        int minBX = floor(Math.min(sMinX, pMinX));
        int maxBX = floor(Math.max(sMaxX, pMaxX));
        int minBZ = floor(Math.min(sMinZ, pMinZ));
        int maxBZ = floor(Math.max(sMaxZ, pMaxZ));
        // One extra layer below: fences and walls are 1.5 blocks tall.
        int minBY = floor(Math.min(sMinY, pMinY)) - 1;
        int maxBY = floor(Math.max(sMaxY, pMaxY));

        int result = 0;
        for (int bx = minBX; bx <= maxBX; bx++) {
            for (int bz = minBZ; bz <= maxBZ; bz++) {
                for (int by = minBY; by <= maxBY; by++) {
                    Material material = typeAt(world, bx, by, bz);
                    if (material == null) {
                        result |= UNKNOWN;
                        continue;
                    }
                    int f = traits.of(material);
                    if ((f & BlockTraits.AIR) != 0) {
                        continue;
                    }
                    if (cellOverlaps(bx, by, bz, pMinX, pMinY, pMinZ, pMaxX, pMaxY, pMaxZ)) {
                        if ((f & BlockTraits.LIQUID) != 0
                                || ((f & BlockTraits.WATERLOGGABLE) != 0 && isWaterlogged(world, bx, by, bz))) {
                            result |= LIQUID;
                        }
                        if ((f & BlockTraits.CLIMBABLE) != 0) {
                            result |= CLIMBABLE;
                        }
                        if ((f & BlockTraits.SLOWING) != 0) {
                            result |= SLOWING;
                        }
                    }
                    if ((f & BlockTraits.PISTON) != 0
                            && cellOverlaps(bx, by, bz, sMinX - 1, sMinY - 1, sMinZ - 1, sMaxX + 1, pMaxY + 1, sMaxZ + 1)) {
                        result |= PISTON;
                    }
                    if (collides(world, material, f, bx, by, bz, sMinX, sMinY, sMinZ, sMaxX, sMaxY, sMaxZ)) {
                        result |= SUPPORT;
                        if ((f & BlockTraits.BOUNCY) != 0) {
                            result |= BOUNCY;
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * {@link #LIQUID}, {@link #CLIMBABLE}, {@link #SLOWING} and {@link #UNKNOWN} for the
     * blocks the hitbox occupies. Cheaper than {@link #scan} (no collision shapes).
     */
    public int bodyFlags(World world, double x, double y, double z, double width, double height) {
        double half = width / 2.0;
        int minBX = floor(x - half);
        int maxBX = floor(x + half);
        int minBZ = floor(z - half);
        int maxBZ = floor(z + half);
        int minBY = floor(y + 0.01);
        int maxBY = floor(y + height - 0.01);
        int result = 0;
        for (int bx = minBX; bx <= maxBX; bx++) {
            for (int bz = minBZ; bz <= maxBZ; bz++) {
                for (int by = minBY; by <= maxBY; by++) {
                    Material material = typeAt(world, bx, by, bz);
                    if (material == null) {
                        result |= UNKNOWN;
                        continue;
                    }
                    int f = traits.of(material);
                    if ((f & BlockTraits.AIR) != 0) {
                        continue;
                    }
                    if ((f & BlockTraits.LIQUID) != 0
                            || ((f & BlockTraits.WATERLOGGABLE) != 0 && isWaterlogged(world, bx, by, bz))) {
                        result |= LIQUID;
                    }
                    if ((f & BlockTraits.CLIMBABLE) != 0) {
                        result |= CLIMBABLE;
                    }
                    if ((f & BlockTraits.SLOWING) != 0) {
                        result |= SLOWING;
                    }
                }
            }
        }
        return result;
    }

    /**
     * Trait flags ({@link BlockTraits#ICE}, {@link BlockTraits#SLIME}, {@link BlockTraits#SOUL})
     * of the blocks that affect movement friction under the hitbox corners.
     */
    public int surfaceTraits(World world, double x, double y, double z, double width) {
        double half = width / 2.0;
        int by = floor(y - 0.5);
        int result = 0;
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            int bx = floor(x + ((corner & 1) == 0 ? -half : half));
            int bz = floor(z + ((corner & 2) == 0 ? -half : half));
            if (bx == lastX && bz == lastZ) {
                continue;
            }
            lastX = bx;
            lastZ = bz;
            Material material = typeAt(world, bx, by, bz);
            if (material != null) {
                result |= traits.of(material);
            }
        }
        return result & (BlockTraits.ICE | BlockTraits.SLIME | BlockTraits.SOUL | BlockTraits.BOUNCY);
    }

    /** Whether any non-air block is directly above the head (head-hitter jumps are faster). */
    public boolean hasCeiling(World world, double x, double y, double z, double width, double height) {
        double half = width / 2.0;
        int by = floor(y + height + 0.3);
        for (int corner = 0; corner < 4; corner++) {
            int bx = floor(x + ((corner & 1) == 0 ? -half : half));
            int bz = floor(z + ((corner & 2) == 0 ? -half : half));
            Material material = typeAt(world, bx, by, bz);
            if (material == null) {
                return true;
            }
            int f = traits.of(material);
            if ((f & (BlockTraits.AIR | BlockTraits.LIQUID)) == 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the straight segment between two points passes through a full
     * occluding block (voxel traversal, no chunk loading). The start voxel, the end
     * voxel and an optional ignored voxel never count. Unknown blocks count as clear.
     */
    public boolean segmentBlocked(World world, double x0, double y0, double z0, double x1, double y1, double z1,
                                  int ignoreX, int ignoreY, int ignoreZ, boolean useIgnore) {
        int x = floor(x0);
        int y = floor(y0);
        int z = floor(z0);
        int endX = floor(x1);
        int endY = floor(y1);
        int endZ = floor(z1);
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        int stepX = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int stepY = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int stepZ = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double tDeltaX = stepX != 0 ? 1.0 / Math.abs(dx) : Double.POSITIVE_INFINITY;
        double tDeltaY = stepY != 0 ? 1.0 / Math.abs(dy) : Double.POSITIVE_INFINITY;
        double tDeltaZ = stepZ != 0 ? 1.0 / Math.abs(dz) : Double.POSITIVE_INFINITY;
        double tMaxX = stepX > 0 ? (x + 1 - x0) * tDeltaX : (stepX < 0 ? (x0 - x) * tDeltaX : Double.POSITIVE_INFINITY);
        double tMaxY = stepY > 0 ? (y + 1 - y0) * tDeltaY : (stepY < 0 ? (y0 - y) * tDeltaY : Double.POSITIVE_INFINITY);
        double tMaxZ = stepZ > 0 ? (z + 1 - z0) * tDeltaZ : (stepZ < 0 ? (z0 - z) * tDeltaZ : Double.POSITIVE_INFINITY);

        for (int guard = 0; guard < 512; guard++) {
            if (tMaxX <= tMaxY && tMaxX <= tMaxZ) {
                if (tMaxX > 1.0) {
                    return false;
                }
                x += stepX;
                tMaxX += tDeltaX;
            } else if (tMaxY <= tMaxZ) {
                if (tMaxY > 1.0) {
                    return false;
                }
                y += stepY;
                tMaxY += tDeltaY;
            } else {
                if (tMaxZ > 1.0) {
                    return false;
                }
                z += stepZ;
                tMaxZ += tDeltaZ;
            }
            if (x == endX && y == endY && z == endZ) {
                return false;
            }
            if (useIgnore && x == ignoreX && y == ignoreY && z == ignoreZ) {
                continue;
            }
            Material material = typeAt(world, x, y, z);
            if (material != null && traits.has(material, BlockTraits.FULL)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the voxel containing a point is a full occluding block. */
    public boolean isInsideFullBlock(World world, double x, double y, double z) {
        Material material = typeAt(world, floor(x), floor(y), floor(z));
        return material != null && traits.has(material, BlockTraits.FULL);
    }

    /**
     * Re-sends the real blocks under and around a player. Removes client-side "ghost
     * blocks" (a desync) so a legitimate player standing on one falls normally.
     */
    public void resyncAround(Player player, World world, double x, double y, double z) {
        int cx = floor(x);
        int cy = floor(y);
        int cz = floor(z);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    int bx = cx + dx;
                    int by = cy + dy;
                    int bz = cz + dz;
                    if (by < world.getMinHeight() || by >= world.getMaxHeight()
                            || !world.isChunkLoaded(bx >> 4, bz >> 4)) {
                        continue;
                    }
                    BlockData data = world.getBlockData(bx, by, bz);
                    player.sendBlockChange(new Location(world, bx, by, bz), data);
                }
            }
        }
    }

    private boolean collides(World world, Material material, int f, int bx, int by, int bz,
                             double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
        if ((f & BlockTraits.FULL) != 0) {
            return cellOverlaps(bx, by, bz, minX, minY, minZ, maxX, maxY, maxZ);
        }
        // Only cells that can possibly reach the region need a shape lookup (shapes can be 1.5 tall).
        if (bx + 1 < minX - EPSILON || bx > maxX + EPSILON || bz + 1 < minZ - EPSILON || bz > maxZ + EPSILON
                || by + 1.5 < minY - EPSILON || by > maxY + EPSILON) {
            return false;
        }
        Block block = world.getBlockAt(bx, by, bz);
        VoxelShape shape = block.getCollisionShape();
        for (BoundingBox box : shape.getBoundingBoxes()) {
            if (bx + box.getMinX() <= maxX + EPSILON && bx + box.getMaxX() >= minX - EPSILON
                    && by + box.getMinY() <= maxY + EPSILON && by + box.getMaxY() >= minY - EPSILON
                    && bz + box.getMinZ() <= maxZ + EPSILON && bz + box.getMaxZ() >= minZ - EPSILON) {
                return true;
            }
        }
        return false;
    }

    private boolean isWaterlogged(World world, int x, int y, int z) {
        BlockData data = world.getBlockData(x, y, z);
        return data instanceof Waterlogged waterlogged && waterlogged.isWaterlogged();
    }

    private static boolean cellOverlaps(int bx, int by, int bz, double minX, double minY, double minZ,
                                        double maxX, double maxY, double maxZ) {
        return bx <= maxX + EPSILON && bx + 1 >= minX - EPSILON
                && by <= maxY + EPSILON && by + 1 >= minY - EPSILON
                && bz <= maxZ + EPSILON && bz + 1 >= minZ - EPSILON;
    }

    public static int floor(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }
}
