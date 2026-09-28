package io.github.drepfy.vigil.listener;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.combat.AttackSnapshot;
import io.github.drepfy.vigil.check.combat.HitAngleCheck;
import io.github.drepfy.vigil.check.combat.ReachCheck;
import io.github.drepfy.vigil.check.combat.WallHitCheck;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;

/**
 * Direct melee hits by players. Sweep attacks, projectiles and multi-part entities
 * (the ender dragon) are ignored because their geometry is not a single hitbox.
 */
public final class CombatListener implements Listener {

    private final CheckContext ctx;
    private final LifecycleListener lifecycle;
    private final ReachCheck reach;
    private final HitAngleCheck hitAngle;
    private final WallHitCheck wallHit;

    public CombatListener(CheckContext ctx, LifecycleListener lifecycle, ReachCheck reach, HitAngleCheck hitAngle,
                          WallHitCheck wallHit) {
        this.ctx = ctx;
        this.lifecycle = lifecycle;
        this.reach = reach;
        this.hitAngle = hitAngle;
        this.wallHit = wallHit;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onAttack(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker) || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        Entity target = event.getEntity();
        if (target instanceof ComplexEntityPart || target instanceof ComplexLivingEntity
                || !target.getWorld().equals(attacker.getWorld())) {
            return;
        }
        long now = Clock.now();
        PlayerData data = ctx.players().get(attacker);
        if (!ctx.settings().general().enabled()) {
            return;
        }
        // A mace smash (and Wind Burst) launches the attacker upwards.
        if (attacker.getInventory().getItemInMainHand().getType().name().equals("MACE")) {
            lifecycle.impulse(attacker, data, 1.0, now);
        }
        boolean reachActive = ctx.isActive(attacker, data, CheckType.REACH, now);
        boolean angleActive = ctx.isActive(attacker, data, CheckType.HIT_ANGLE, now);
        boolean wallActive = ctx.isActive(attacker, data, CheckType.WALL_HIT, now);
        if (!reachActive && !angleActive && !wallActive) {
            return;
        }
        AttackSnapshot[] hit = new AttackSnapshot[1];
        ctx.run(CheckType.REACH, now, () -> hit[0] = AttackSnapshot.capture(ctx, attacker, data, target, now));
        if (hit[0] == null) {
            return;
        }
        if (reachActive) {
            ctx.run(CheckType.REACH, now, () -> reach.onAttack(attacker, data, hit[0], event, now));
        }
        if (angleActive) {
            ctx.run(CheckType.HIT_ANGLE, now, () -> hitAngle.onAttack(attacker, data, hit[0], now));
        }
        if (wallActive) {
            ctx.run(CheckType.WALL_HIT, now, () -> wallHit.onAttack(attacker, data, hit[0], event, now));
        }
    }
}
