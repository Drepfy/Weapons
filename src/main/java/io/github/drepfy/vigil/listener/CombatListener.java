package io.github.drepfy.vigil.listener;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.combat.AttackSnapshot;
import io.github.drepfy.vigil.check.combat.AutoClickerCheck;
import io.github.drepfy.vigil.check.combat.KillAuraCheck;
import io.github.drepfy.vigil.check.combat.NoSwingCheck;
import io.github.drepfy.vigil.check.combat.ReachCheck;
import io.github.drepfy.vigil.check.combat.WallHitCheck;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.ComplexLivingEntity;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;

/**
 * Melee attacks and arm swings.
 *
 * <p>On Paper, attacks come from the packet-driven pre-attack event (see
 * {@link OptionalHooks}), so damage that other plugins deal in the player's name
 * (area abilities, custom enchantments) is never mistaken for a hit. Elsewhere the
 * damage event is used, and a second target in the same server tick is ignored for
 * the same reason. Only single-hitbox living targets are checked.
 */
public final class CombatListener implements Listener {

    private final CheckContext ctx;
    private final LifecycleListener lifecycle;
    private final ReachCheck reach;
    private final KillAuraCheck killAura;
    private final WallHitCheck wallHit;
    private final NoSwingCheck noSwing;
    private final AutoClickerCheck autoClicker;
    private volatile boolean packetAttacks;

    public CombatListener(CheckContext ctx, LifecycleListener lifecycle, ReachCheck reach, KillAuraCheck killAura,
                          WallHitCheck wallHit, NoSwingCheck noSwing, AutoClickerCheck autoClicker) {
        this.ctx = ctx;
        this.lifecycle = lifecycle;
        this.reach = reach;
        this.killAura = killAura;
        this.wallHit = wallHit;
        this.noSwing = noSwing;
        this.autoClicker = autoClicker;
    }

    /** Switches attack detection to the packet-driven pre-attack event. */
    public void usePacketAttacks() {
        packetAttacks = true;
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)
                || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return;
        }
        long now = Clock.now();
        PlayerData data = ctx.players().get(attacker);
        // A mace smash (and Wind Burst) launches the attacker upwards and resets its fall.
        if (attacker.getInventory().getItemInMainHand().getType().name().equals("MACE")) {
            lifecycle.impulse(attacker, data, 1.0, now);
        }
        if (!packetAttacks) {
            onAttack(attacker, event.getEntity(), event, false);
        }
    }

    /** A melee attack by a player, from the pre-attack event or the damage event. */
    public void onAttack(Player attacker, Entity target, Cancellable event, boolean fromPacket) {
        if (!(target instanceof LivingEntity) || target instanceof ComplexLivingEntity
                || target instanceof ComplexEntityPart || !target.getWorld().equals(attacker.getWorld())
                || !ctx.settings().general().enabled()) {
            return;
        }
        if (attacker.getInventory().getItemInMainHand().getType().name().endsWith("_SPEAR")) {
            // Spears (1.21.11+) have their own reach and can hit several entities at once.
            return;
        }
        long now = Clock.now();
        PlayerData data = ctx.players().get(attacker);
        long tick = ctx.currentTick();
        if (!fromPacket && data.lastAttackServerTick == tick && data.lastAttackServerTarget != target.getEntityId()) {
            // Probably another plugin damaging nearby entities in the player's name.
            return;
        }
        data.lastAttackServerTick = tick;
        data.lastAttackServerTarget = target.getEntityId();

        ctx.run(CheckType.NOSWING, now, () -> noSwing.onAttack(attacker, data, now));
        boolean reachActive = ctx.isActive(attacker, data, CheckType.REACH, now);
        boolean auraActive = ctx.isActive(attacker, data, CheckType.KILLAURA, now);
        boolean wallActive = ctx.isActive(attacker, data, CheckType.WALLHIT, now);
        if (!reachActive && !auraActive && !wallActive) {
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
        if (auraActive) {
            ctx.run(CheckType.KILLAURA, now, () -> killAura.onAttack(attacker, data, hit[0], target.getEntityId(), now));
        }
        if (wallActive) {
            ctx.run(CheckType.WALLHIT, now, () -> wallHit.onAttack(attacker, data, hit[0], event, now));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnimation(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        long now = Clock.now();
        PlayerData data = ctx.players().get(player);
        data.lastSwingMs = now;
        if (ctx.settings().general().enabled()) {
            ctx.run(CheckType.AUTOCLICKER, now, () -> autoClicker.onSwing(player, data, now));
        }
    }
}
