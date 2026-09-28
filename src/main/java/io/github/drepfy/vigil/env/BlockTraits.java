package io.github.drepfy.vigil.env;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Waterlogged;

import java.util.Set;
import java.util.logging.Logger;

/**
 * Precomputed per-material flags so the hot movement path never does string work.
 * Material names are used instead of enum constants so renamed/missing materials
 * on other server versions simply do not match (fail safe).
 */
public final class BlockTraits {

    public static final int AIR = 1;
    /** Assumed to be a full collision cube (occluding and solid). */
    public static final int FULL = 1 << 1;
    public static final int LIQUID = 1 << 2;
    public static final int WATERLOGGABLE = 1 << 3;
    public static final int CLIMBABLE = 1 << 4;
    /** Blocks that slow or hold players (cobweb, berry bush, powder snow, honey). */
    public static final int SLOWING = 1 << 5;
    public static final int ICE = 1 << 6;
    public static final int SLIME = 1 << 7;
    /** Blocks that launch players upwards (slime, beds). */
    public static final int BOUNCY = 1 << 8;
    public static final int SOUL = 1 << 9;
    public static final int PISTON = 1 << 10;

    private static final Set<String> LIQUIDS = Set.of("WATER", "LAVA", "BUBBLE_COLUMN", "KELP", "KELP_PLANT",
            "SEAGRASS", "TALL_SEAGRASS");
    private static final Set<String> CLIMBABLES = Set.of("LADDER", "VINE", "SCAFFOLDING", "TWISTING_VINES",
            "TWISTING_VINES_PLANT", "WEEPING_VINES", "WEEPING_VINES_PLANT", "CAVE_VINES", "CAVE_VINES_PLANT",
            "POWDER_SNOW");
    private static final Set<String> SLOWING_BLOCKS = Set.of("COBWEB", "SWEET_BERRY_BUSH", "POWDER_SNOW",
            "HONEY_BLOCK", "SOUL_SAND");
    private static final Set<String> ICES = Set.of("ICE", "PACKED_ICE", "BLUE_ICE", "FROSTED_ICE");
    private static final Set<String> SOULS = Set.of("SOUL_SAND", "SOUL_SOIL");
    private static final Set<String> PISTONS = Set.of("PISTON", "STICKY_PISTON", "PISTON_HEAD", "MOVING_PISTON");

    private final int[] flags;

    public BlockTraits(Logger logger) {
        Material[] materials = Material.values();
        flags = new int[materials.length];
        Tag<Material> climbableTag = null;
        Tag<Material> bedTag = null;
        try {
            climbableTag = Tag.CLIMBABLE;
            bedTag = Tag.BEDS;
        } catch (LinkageError | RuntimeException e) {
            logger.warning("Block tags unavailable, using name matching only: " + e);
        }
        int failures = 0;
        for (Material material : materials) {
            try {
                flags[material.ordinal()] = compute(material, climbableTag, bedTag);
            } catch (LinkageError | RuntimeException e) {
                failures++;
            }
        }
        if (failures > 0) {
            logger.fine("Could not classify " + failures + " materials (treated as unknown shapes).");
        }
    }

    @SuppressWarnings("deprecation")
    private static int compute(Material material, Tag<Material> climbableTag, Tag<Material> bedTag) {
        if (material.isLegacy() || !material.isBlock()) {
            return 0;
        }
        String name = material.name();
        int result = 0;
        if (material.isAir()) {
            return AIR;
        }
        if (material.isOccluding() && material.isSolid()) {
            result |= FULL;
        }
        if (LIQUIDS.contains(name)) {
            result |= LIQUID;
        }
        if (CLIMBABLES.contains(name) || (climbableTag != null && climbableTag.isTagged(material))) {
            result |= CLIMBABLE;
        }
        if (SLOWING_BLOCKS.contains(name)) {
            result |= SLOWING;
        }
        if (ICES.contains(name)) {
            result |= ICE;
        }
        if (name.equals("SLIME_BLOCK")) {
            result |= SLIME | BOUNCY;
        }
        if (name.endsWith("_BED") || (bedTag != null && bedTag.isTagged(material))) {
            result |= BOUNCY;
        }
        if (SOULS.contains(name)) {
            result |= SOUL;
        }
        if (PISTONS.contains(name)) {
            result |= PISTON;
        }
        try {
            BlockData data = material.createBlockData();
            if (data instanceof Waterlogged) {
                result |= WATERLOGGABLE;
            }
        } catch (LinkageError | RuntimeException ignored) {
            // Some technical blocks cannot create block data; they are simply not waterloggable.
        }
        return result;
    }

    public int of(Material material) {
        int ordinal = material.ordinal();
        return ordinal < flags.length ? flags[ordinal] : 0;
    }

    public boolean has(Material material, int flag) {
        return (of(material) & flag) != 0;
    }
}
