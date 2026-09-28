package io.github.drepfy.vigil.listener;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.check.movement.TimerCheck;
import io.github.drepfy.vigil.compat.ServerCompat;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.Plugin;

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

    private final Plugin plugin;
    private final Logger logger;
    private final CheckContext ctx;
    private final LifecycleListener lifecycle;
    private final TimerCheck timer;
    private final List<String> registered = new ArrayList<>();

    public OptionalHooks(Plugin plugin, CheckContext ctx, LifecycleListener lifecycle, TimerCheck timer) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.ctx = ctx;
        this.lifecycle = lifecycle;
        this.timer = timer;
    }

    public void registerAll() {
        if (ctx.compat().hasClientTickEvent()) {
            register(ServerCompat.CLIENT_TICK_EVENT_CLASS, (listener, event) -> onClientTick(event), false);
        }
        for (String name : KNOCKBACK_EVENTS) {
            register(name, (listener, event) -> onKnockback(event), true);
        }
        logger.info("Optional hooks: " + (registered.isEmpty() ? "none" : String.join(", ", registered)));
    }

    private void register(String className, EventExecutor executor, boolean ignoreCancelled) {
        try {
            Class<?> raw = Class.forName(className, false, plugin.getClass().getClassLoader());
            if (!Event.class.isAssignableFrom(raw)) {
                return;
            }
            Class<? extends Event> type = raw.asSubclass(Event.class);
            EventExecutor guarded = (listener, event) -> {
                if (type.isInstance(event)) {
                    executor.execute(listener, event);
                }
            };
            plugin.getServer().getPluginManager().registerEvent(type, this, EventPriority.MONITOR, guarded, plugin,
                    ignoreCancelled);
            registered.add(className.substring(className.lastIndexOf('.') + 1));
        } catch (ClassNotFoundException | LinkageError ignored) {
            // Not available on this server.
        } catch (RuntimeException e) {
            logger.fine("Could not register optional hook " + className + ": " + e.getMessage());
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
