package io.github.drepfy.vigil.check.interaction;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.model.XrayTracker;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

import java.util.Set;

/**
 * X-ray, ore ESP and ore simulation (seed-based ore finders), recognised by how the
 * player mines. A vein counts as "hidden" when every open side of the ore was dug out
 * by the player: nobody could have seen it without digging. Branch mining finds such
 * a vein every 50-100 blocks or so. A player who keeps finding them after only a
 * handful of blocks is digging straight to ores they could not see.
 *
 * <p>This is statistics, not proof, so it only alerts staff by default
 * ({@code ban-at: 0}). Veins found in caves, with TNT or with beds never count.
 */
public final class XrayCheck {

    private static final CheckType TYPE = CheckType.XRAY;
    private static final int DIAMOND = 0;
    private static final int DEBRIS = 1;
    private static final String[] GROUP_NAMES = {"diamond", "ancient debris"};

    private static final Set<String> FILLER = Set.of("STONE", "DEEPSLATE", "TUFF", "GRANITE", "DIORITE", "ANDESITE",
            "CALCITE", "NETHERRACK", "BASALT", "BLACKSTONE", "SMOOTH_BASALT");

    private final CheckContext ctx;

    public XrayCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    /** Called for every block a player breaks (after other plugins had their say). */
    public void onBreak(Player player, PlayerData data, Block block, long now) {
        if (!ctx.isActive(player, data, TYPE, now)) {
            return;
        }
        XrayTracker tracker = data.xray;
        Material type = block.getType();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        int group = group(type);
        if (group < 0) {
            tracker.recordBroken(x, y, z);
            if (FILLER.contains(type.name())) {
                tracker.recordFiller();
            }
            return;
        }
        boolean hidden = wasHidden(block.getWorld(), tracker, x, y, z);
        boolean newVein = tracker.recordOre(group, x, y, z, now);
        tracker.recordBroken(x, y, z);
        if (!newVein || !hidden) {
            return;
        }
        double average = tracker.recordVein(group);
        CheckSettings settings = ctx.settings(TYPE);
        ctx.debug(player, TYPE, () -> "hidden " + GROUP_NAMES[group] + " vein, average blocks per vein "
                + (Double.isNaN(average) ? "-" : Text.num(average)));
        if (Double.isNaN(average) || average > settings.num("max-blocks-per-vein")) {
            return;
        }
        tracker.clearVeins(group);
        ctx.flag(player, data, TYPE, "found " + XrayTracker.WINDOW + " hidden " + GROUP_NAMES[group]
                + " veins in a row, mining only " + Text.num(Math.round(average * 10) / 10.0)
                + " blocks for each (normal mining needs about 50-100)");
    }

    /** Whether every open side of the ore is a block this player dug out. */
    private boolean wasHidden(World world, XrayTracker tracker, int x, int y, int z) {
        int[][] sides = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] side : sides) {
            int nx = x + side[0];
            int ny = y + side[1];
            int nz = z + side[2];
            Material neighbour = ctx.probe().typeAt(world, nx, ny, nz);
            if (neighbour == null) {
                return false;
            }
            boolean open = neighbour.isAir() || neighbour == Material.WATER || neighbour == Material.LAVA;
            if (open && !tracker.brokeRecently(nx, ny, nz)) {
                return false;
            }
        }
        return true;
    }

    private static int group(Material type) {
        return switch (type.name()) {
            case "DIAMOND_ORE", "DEEPSLATE_DIAMOND_ORE" -> DIAMOND;
            case "ANCIENT_DEBRIS" -> DEBRIS;
            default -> -1;
        };
    }
}
