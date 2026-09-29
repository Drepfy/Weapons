package io.github.drepfy.vigil.listener;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.movement.TimerCheck;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/**
 * Events that only exist on some servers are registered by name, so the plugin
 * loads everywhere and simply gains precision where they are available.
 */
public final class OptionalHooks implements Listener {

    /** Knockback events across Paper/Spigot versions (all optional). */
    private static final List<String> KNOCKBACK_EVENTS = List.of(
            "io.papermc.paper.event.entity.EntityKnockbackEvent",
            "io.papermc.paper.event.entity.EntityPushedByEntityAttackEvent",
            "com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent",
            "org.bukkit.event.entity.EntityKnockbackEvent",
            "org.bukkit.event.entity.EntityKnockbackByEntityEvent");

    /** Paper: fired from the attack packet itself, before any damage is dealt. */
    private static final String PRE_ATTACK_EVENT = "io.papermc.paper.event.player.PrePlayerAttackEntityEvent";
    private static final String DAMAGE_ABORT_EVENT = "org.bukkit.event.block.BlockDamageAbortEvent";

    private final Plugin plugin;
    private final Logger logger;
    private final CheckContext ctx;
    private final LifecycleListener lifecycle;
    private final CombatListener combat;
    private final InteractionListener interaction;
    private final TimerCheck timer;
    private final List<String> registered = new ArrayList<>();
    private Method attackedGetter;

    public OptionalHooks(Plugin plugin, CheckContext ctx, LifecycleListener lifecycle, CombatListener combat,
                         InteractionListener interaction, TimerCheck timer) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.ctx = ctx;
        this.lifecycle = lifecycle;
        this.combat = combat;
        this.interaction = interaction;
        this.timer = timer;
    }

    public void registerAll() {
        if (ctx.compat().hasClientTickEvent()) {
            register(ServerCompat.CLIENT_TICK_EVENT_CLASS, (listener, event) -> onClientTick(event), false,
                    EventPriority.MONITOR);
        }
        for (String name : KNOCKBACK_EVENTS) {
            register(name, (listener, event) -> onKnockback(event), true, EventPriority.MONITOR);
        }
        try {
            attackedGetter = Class.forName(PRE_ATTACK_EVENT, false, plugin.getClass().getClassLoader())
                    .getMethod("getAttacked");
            if (register(PRE_ATTACK_EVENT, (listener, event) -> onPreAttack(event), true, EventPriority.HIGH)) {
                combat.usePacketAttacks();
            }
        } catch (ReflectiveOperationException | LinkageError ignored) {
            // Not Paper: attacks are taken from the damage event.
        }
        register(DAMAGE_ABORT_EVENT, (listener, event) -> onDamageAbort(event), false, EventPriority.MONITOR);
        logger.info("Optional hooks: " + (registered.isEmpty() ? "none" : String.join(", ", registered)));
    }

    private boolean register(String className, EventExecutor executor, boolean ignoreCancelled,
                             EventPriority priority) {
        try {
            Class<?> raw = Class.forName(className, false, plugin.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) {
                return false;
            }
            Class<? extends Event> type = raw.asSubclass(Event.class);
            EventExecutor guarded = (listener, event) -> {
                if (type.isInstance(event)) {
                    executor.execute(listener, event);
                }
            };
            plugin.getServer().getPluginManager().registerEvent(type, this, priority, guarded, plugin,
                    ignoreCancelled);
            registered.add(className.substring(className.lastIndexOf('.') + 1));
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Not available on this server.
        } catch (RuntimeException e) {
            logger.fine("Could not register optional hook " + className + ": " + e.getMessage());
        }
        return false;
    }

    private void onPreAttack(Event event) {
        if (!(event instanceof PlayerEvent playerEvent) || !(event instanceof Cancellable cancellable)) {
            return;
        }
        try {
            Object target = attackedGetter.invoke(event);
            if (target instanceof Entity entity) {
                combat.onAttack(playerEvent.getPlayer(), entity, cancellable, true);
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.fine("Pre-attack hook failed: " + e);
        }
    }

    private void onDamageAbort(Event event) {
        if (event instanceof BlockDamageAbortEvent abort) {
            interaction.endDigging(abort.getPlayer());
        }
    }

    private void onClientTick(Event event) {
        if (!(event instanceof PlayerEvent playerEvent) || !ctx.settings().general().useClientTickEvents()) {
            return;
        }
        Player player = playerEvent.getPlayer();
        PlayerData data = ctx.players().peek(player.getUniqueId());
        if (data == null) {
            return;
        }
        long now = Clock.now();
        data.clientTick++;
        data.clientTicksSinceSample++;
        data.lastClientTickMs = now;
        if (!ctx.settings().general().enabled()) {
            return;
        }
        boolean exemptState = ctx.isMovementExemptState(player, data, now);
        ctx.run(CheckType.TIMER, now, () -> timer.onClientTick(player, data, exemptState, now));
    }

    private void onKnockback(Event event) {
        if (event instanceof EntityEvent entityEvent && entityEvent.getEntity() instanceof Player player) {
            PlayerData data = ctx.players().peek(player.getUniqueId());
            if (data != null) {
                long now = Clock.now();
                data.lastVelocityMs = now;
                lifecycle.impulse(player, data, 1.0, now);
            }
        }
    }
}
