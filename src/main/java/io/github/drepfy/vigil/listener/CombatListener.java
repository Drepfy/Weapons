package io.github.drepfy.vigil.listener;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.combat.AttackSnapshot;
import io.github.drepfy.vigil.check.combat.AutoClickerCheck;
import io.github.drepfy.vigil.check.combat.KillAuraCheck;
import io.github.drepfy.vigil.check.combat.MaceCheck;
import io.github.drepfy.vigil.check.combat.NoSwingCheck;
import io.github.drepfy.vigil.check.combat.ReachCheck;
import io.github.drepfy.vigil.check.combat.WallHitCheck;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.enchantments.Enchantment;
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
import org.bukkit.inventory.ItemStack;

import java.util.Map;

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
    /** A Lunge spear's grace opens at most this often. */
    private static final long LUNGE_INTERVAL_MS = 1000;

    private final KillAuraCheck killAura;
    private final WallHitCheck wallHit;
    private final NoSwingCheck noSwing;
    private final AutoClickerCheck autoClicker;
    private final MaceCheck mace;
    private volatile boolean packetAttacks;

    public CombatListener(CheckContext ctx, LifecycleListener lifecycle, ReachCheck reach, KillAuraCheck killAura,
                          WallHitCheck wallHit, NoSwingCheck noSwing, AutoClickerCheck autoClicker, MaceCheck mace) {
        this.ctx = ctx;
        this.lifecycle = lifecycle;
        this.reach = reach;
        this.killAura = killAura;
        this.wallHit = wallHit;
        this.noSwing = noSwing;
        this.autoClicker = autoClicker;
        this.mace = mace;
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
        if (data.frozenUntilMs > now) {
            event.setCancelled(true);
            return;
        }
        if (attacker.getInventory().getItemInMainHand().getType().name().equals("MACE")) {
            if (ctx.settings().general().enabled()) {
                ctx.run(CheckType.MACE, now, () -> mace.onMaceHit(attacker, data, event, now));
            }
            // A mace smash (and Wind Burst) launches the attacker upwards and resets its fall.
            lifecycle.impulse(attacker, data, 1.0, now);
            data.lastMaceHitMs = data.lastImpulseMs;
            if (event.isCancelled()) {
                return;
            }
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
        lunge(player, data, now);
        if (ctx.settings().general().enabled()) {
            ctx.run(CheckType.AUTOCLICKER, now, () -> autoClicker.onSwing(player, data, now));
        }
    }

    /**
     * A jab with a Lunge spear (1.21.11+) throws the player forward. Usually the server sends that
     * push (a velocity event), but in case the client moves itself, a jab with such a spear opens
     * a short grace too: at most once a second, so swinging one cannot keep the checks away.
     */
    private void lunge(Player player, PlayerData data, long now) {
        if (now - data.lastLungeMs < LUNGE_INTERVAL_MS) {
            return;
        }
        int level = lungeLevel(player.getInventory().getItemInMainHand());
        if (level > 0) {
            data.lastLungeMs = now;
            lifecycle.impulse(player, data, 0.6 * level, now);
        }
    }

    /** The Lunge level of a spear (0 for anything else). Looked up by name: older servers have neither. */
    static int lungeLevel(ItemStack item) {
        if (item == null || !item.getType().name().endsWith("_SPEAR") || !item.hasItemMeta()) {
            return 0;
        }
        for (Map.Entry<Enchantment, Integer> enchantment : item.getEnchantments().entrySet()) {
            if (enchantment.getKey().getKey().getKey().equals("lunge")) {
                return enchantment.getValue();
            }
        }
        return 0;
    }
}
