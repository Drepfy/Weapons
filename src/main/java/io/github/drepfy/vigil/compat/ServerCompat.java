package io.github.drepfy.vigil.compat;

import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Isolates every API member that differs between server versions or between
 * Spigot and Paper. Everything is resolved once at startup; anything that cannot be
 * resolved degrades to a safe default (which always makes checks more lenient).
 *
 * <p>Notable differences handled here:
 * <ul>
 *   <li>{@code Attribute} was an enum before 1.21.3 and an interface afterwards, and
 *   its constants lost the {@code GENERIC_}/{@code PLAYER_} prefixes.</li>
 *   <li>Potion effect constants were renamed in 1.20.5 (JUMP to JUMP_BOOST, ...).</li>
 *   <li>Paper-only features: per-player chunk-sent state and the client tick event.</li>
 * </ul>
 */
public final class ServerCompat {

    public enum Attr {
        MOVEMENT_SPEED("MOVEMENT_SPEED", "GENERIC_MOVEMENT_SPEED"),
        JUMP_STRENGTH("JUMP_STRENGTH", "GENERIC_JUMP_STRENGTH"),
        STEP_HEIGHT("STEP_HEIGHT", "GENERIC_STEP_HEIGHT"),
        GRAVITY("GRAVITY", "GENERIC_GRAVITY"),
        ENTITY_INTERACTION_RANGE("ENTITY_INTERACTION_RANGE", "PLAYER_ENTITY_INTERACTION_RANGE"),
        BLOCK_INTERACTION_RANGE("BLOCK_INTERACTION_RANGE", "PLAYER_BLOCK_INTERACTION_RANGE"),
        SAFE_FALL_DISTANCE("SAFE_FALL_DISTANCE", "GENERIC_SAFE_FALL_DISTANCE"),
        FALL_DAMAGE_MULTIPLIER("FALL_DAMAGE_MULTIPLIER", "GENERIC_FALL_DAMAGE_MULTIPLIER");

        private final String[] fieldNames;

        Attr(String... fieldNames) {
            this.fieldNames = fieldNames;
        }
    }

    public enum Effect {
        SPEED("SPEED"),
        JUMP_BOOST("JUMP_BOOST", "JUMP"),
        LEVITATION("LEVITATION"),
        SLOW_FALLING("SLOW_FALLING"),
        DOLPHINS_GRACE("DOLPHINS_GRACE"),
        RESISTANCE("RESISTANCE", "DAMAGE_RESISTANCE");

        private final String[] fieldNames;

        Effect(String... fieldNames) {
            this.fieldNames = fieldNames;
        }
    }

    public static final String CLIENT_TICK_EVENT_CLASS = "io.papermc.paper.event.packet.ClientTickEndEvent";

    private final Map<Attr, Object> attributes = new EnumMap<>(Attr.class);
    private final Map<Effect, PotionEffectType> effects = new EnumMap<>(Effect.class);
    private final Method isChunkSent;
    private final boolean clientTickEventAvailable;
    private volatile boolean pingAvailable = true;
    private volatile boolean tickManagerAvailable = true;
    private volatile boolean attributesWorking = true;

    public ServerCompat(Logger logger) {
        for (Attr attr : Attr.values()) {
            Object handle = staticField(Attribute.class, attr.fieldNames);
            if (handle != null) {
                attributes.put(attr, handle);
            }
        }
        for (Effect effect : Effect.values()) {
            Object handle = staticField(PotionEffectType.class, effect.fieldNames);
            if (handle instanceof PotionEffectType type) {
                effects.put(effect, type);
            }
        }
        Method chunkSent = null;
        try {
            chunkSent = Player.class.getMethod("isChunkSent", long.class);
        } catch (NoSuchMethodException | SecurityException ignored) {
            // Spigot: not available.
        }
        this.isChunkSent = chunkSent;
        this.clientTickEventAvailable = classExists(CLIENT_TICK_EVENT_CLASS);

        logger.info("Compatibility: attributes " + attributes.size() + "/" + Attr.values().length
                + ", potion effects " + effects.size() + "/" + Effect.values().length
                + ", chunk-sent API " + (isChunkSent != null ? "yes" : "no")
                + ", client tick event " + (clientTickEventAvailable ? "yes" : "no"));
    }

    /** Current attribute value, or {@link Double#NaN} when it cannot be determined. */
    public double attribute(Player player, Attr attr) {
        if (!attributesWorking) {
            return Double.NaN;
        }
        Object handle = attributes.get(attr);
        if (handle == null) {
            return Double.NaN;
        }
        try {
            AttributeInstance instance = player.getAttribute((Attribute) handle);
            return instance == null ? Double.NaN : instance.getValue();
        } catch (LinkageError | RuntimeException e) {
            attributesWorking = false;
            return Double.NaN;
        }
    }

    /** Attribute value, or {@code fallback} when unknown. */
    public double attributeOr(Player player, Attr attr, double fallback) {
        double value = attribute(player, attr);
        return Double.isNaN(value) ? fallback : value;
    }

    /** Potion amplifier (0 = level I), or -1 when the effect is absent or unknown. */
    public int amplifier(Player player, Effect effect) {
        PotionEffectType type = effects.get(effect);
        if (type == null) {
            return -1;
        }
        try {
            PotionEffect active = player.getPotionEffect(type);
            return active == null ? -1 : active.getAmplifier();
        } catch (LinkageError | RuntimeException e) {
            return -1;
        }
    }

    public boolean hasEffect(Player player, Effect effect) {
        return amplifier(player, effect) >= 0;
    }

    /** Whether the effect type exists on this server (unknown effects cannot be checked). */
    public boolean isEffectKnown(Effect effect) {
        return effects.containsKey(effect);
    }

    /** Smoothed ping in ms, or -1 when unavailable. Only ever used for leniency. */
    public int ping(Player player) {
        if (!pingAvailable) {
            return -1;
        }
        try {
            return player.getPing();
        } catch (LinkageError e) {
            pingAvailable = false;
            return -1;
        }
    }

    /**
     * False while the server tick rate was changed with /tick (clients then tick at
     * a different speed and time-based checks would be wrong).
     */
    public boolean isTickRateNormal() {
        if (!tickManagerAvailable) {
            return true;
        }
        try {
            org.bukkit.ServerTickManager manager = Bukkit.getServerTickManager();
            return manager.isRunningNormally() && Math.abs(manager.getTickRate() - 20.0F) < 0.01F;
        } catch (LinkageError | RuntimeException e) {
            tickManagerAvailable = false;
            return true;
        }
    }

    /** Paper only: whether the client has received the chunk. Unknown = true. */
    public boolean isChunkSent(Player player, int chunkX, int chunkZ) {
        if (isChunkSent == null) {
            return true;
        }
        try {
            long key = (long) chunkX & 0xffffffffL | ((long) chunkZ & 0xffffffffL) << 32;
            return (Boolean) isChunkSent.invoke(player, key);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            return true;
        }
    }

    public boolean hasClientTickEvent() {
        return clientTickEventAvailable;
    }

    private static Object staticField(Class<?> owner, String... names) {
        for (String name : names) {
            try {
                Field field = owner.getField(name);
                Object value = field.get(null);
                if (value != null) {
                    return value;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Try the next historical name.
            }
        }
        return null;
    }

    public static boolean classExists(String name) {
        try {
            Class.forName(name, false, ServerCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
