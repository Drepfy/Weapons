package io.github.drepfy.vigil.check.combat;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.env.WorldProbe;
import io.github.drepfy.vigil.model.Physics;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * Mace smash exploits ("MaceKill"). A mace deals extra damage for every block the
 * attacker fell. Cheats fake that fall: they send a move several blocks straight up
 * and back down within one tick, so the server counts a big fall and the hit one-shots
 * the target. Two things give it away:
 * <ul>
 *   <li>a single move rose further than any vanilla movement can ({@code min-rise}),
 *   just before the smash, without riptide, elytra or an explosion;</li>
 *   <li>the smash lands while the attacker stands exactly on a block, which vanilla
 *   never allows (landing resets the fall).</li>
 * </ul>
 * The hit is cancelled, so the fake fall never deals damage.
 */
public final class MaceCheck {

    private static final CheckType TYPE = CheckType.MACE;
    /** Vanilla smash attacks need a fall of more than this. */
    private static final double SMASH_FALL = 1.5;
    private static final long RISE_WINDOW_MS = 1000;

    private final CheckContext ctx;

    public MaceCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onMaceHit(Player attacker, PlayerData data, EntityDamageByEntityEvent event, long now) {
        if (!ctx.isActive(attacker, data, TYPE, now)) {
            return;
        }
        float fall = attacker.getFallDistance();
        if (fall <= SMASH_FALL || attacker.isGliding() || attacker.isInsideVehicle()) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        String detail = null;
        double jump = Physics.jumpVelocity(ctx.compat().attributeOr(attacker, ServerCompat.Attr.JUMP_STRENGTH,
                Physics.DEFAULT_JUMP_STRENGTH), ctx.compat().amplifier(attacker, ServerCompat.Effect.JUMP_BOOST));
        double minRise = Math.max(settings.num("min-rise"), jump + 1.0);
        if (now - data.lastBigRiseMs < RISE_WINDOW_MS && data.lastBigRise >= minRise
                && now - data.lastRiptideMs > 3000 && now - data.lastGlideMs > 3000 && (now - data.lastImpulseMs > 1500 || data.lastImpulseMs == data.lastMaceHitMs)
                && now - data.lastTeleportMs > 1000) {
            detail = "jumped " + Text.num(data.lastBigRise) + " blocks up in one move, then smashed with a "
                    + Text.num(fall) + "-block fall";
        } else if (standingOnBlock(attacker)) {
            detail = "smashed with a " + Text.num(fall) + "-block fall while standing on the ground";
        }
        if (detail == null) {
            return;
        }
        if (ctx.mitigationAllowed(settings)) {
            event.setCancelled(true);
        }
        if (!ctx.canFlag(attacker, data, now)) {
            return;
        }
        ctx.flag(attacker, data, TYPE, detail);
    }

    /**
     * Feet exactly on top of a full block. Landing resets the fall in vanilla, so a
     * smash from there is impossible; an airborne player is essentially never exactly
     * level with a block top (distances are not rounded).
     */
    private boolean standingOnBlock(Player player) {
        Location at = player.getLocation();
        double y = at.getY();
        double level = Math.rint(y);
        if (Math.abs(y - level) > 1.0E-6) {
            return false;
        }
        double half = player.getWidth() / 2.0;
        int by = (int) level - 1;
        for (int corner = 0; corner < 4; corner++) {
            int bx = WorldProbe.floor(at.getX() + ((corner & 1) == 0 ? -half + 0.001 : half - 0.001));
            int bz = WorldProbe.floor(at.getZ() + ((corner & 2) == 0 ? -half + 0.001 : half - 0.001));
            org.bukkit.Material below = ctx.probe().typeAt(at.getWorld(), bx, by, bz);
            if (below != null && ctx.probe().traits().has(below, io.github.drepfy.vigil.env.BlockTraits.FULL)) {
                return true;
            }
        }
        return false;
    }
}
