package io.github.drepfy.legendary.util;

import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.potion.PotionEffectType;

import java.util.Locale;

/**
 * Parts of the API that differ between 1.20 and 1.21.x. Looked up at runtime so one jar
 * works on every version from 1.20 on.
 */
public final class Compat {

    /** {@code KNOCKBACK_RESISTANCE} since 1.21.3, {@code GENERIC_KNOCKBACK_RESISTANCE} before. */
    private static final Attribute KNOCKBACK_RESISTANCE = attribute("KNOCKBACK_RESISTANCE",
            "GENERIC_KNOCKBACK_RESISTANCE");

    /** {@code MAX_HEALTH} since 1.21.3, {@code GENERIC_MAX_HEALTH} before. */
    private static final Attribute MAX_HEALTH = attribute("MAX_HEALTH", "GENERIC_MAX_HEALTH");

    private Compat() {
    }

    /** The entity's maximum health (Lifesteal hearts included); 20 if it cannot be read. */
    public static double maxHealth(LivingEntity entity) {
        try {
            AttributeInstance instance = MAX_HEALTH == null ? null : entity.getAttribute(MAX_HEALTH);
            if (instance != null) {
                return instance.getValue();
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Default maximum.
        }
        return 20.0;
    }

    /** Heals up to the entity's maximum health. */
    public static void heal(LivingEntity entity, double amount) {
        if (!entity.isDead()) {
            entity.setHealth(Math.max(0.0, Math.min(maxHealth(entity), entity.getHealth() + amount)));
        }
    }

    /** A particle by its current name or an older one ({@code DUST}/{@code REDSTONE}); null if none exists. */
    public static Particle particle(String... names) {
        for (String name : names) {
            try {
                Object value = Particle.class.getField(name).get(null);
                if (value instanceof Particle particle) {
                    return particle;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Try the next name.
            }
        }
        return null;
    }

    private static Attribute attribute(String... names) {
        for (String name : names) {
            try {
                Object value = Attribute.class.getField(name).get(null);
                if (value instanceof Attribute attribute) {
                    return attribute;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Try the next name.
            }
        }
        return null;
    }

    /**
     * Makes an entity immune to knockback (Iron Bastion), or takes that away again. The modifier is
     * transient on Paper, so it is never saved with the player even if the server stops meanwhile.
     */
    public static void unshakable(LivingEntity entity, org.bukkit.NamespacedKey key, boolean on) {
        try {
            AttributeInstance instance = KNOCKBACK_RESISTANCE == null ? null : entity.getAttribute(KNOCKBACK_RESISTANCE);
            if (instance == null) {
                return;
            }
            instance.removeModifier(key);
            if (on) {
                org.bukkit.attribute.AttributeModifier modifier = new org.bukkit.attribute.AttributeModifier(key, 1.0,
                        org.bukkit.attribute.AttributeModifier.Operation.ADD_NUMBER);
                try {
                    instance.addTransientModifier(modifier);
                } catch (LinkageError | UnsupportedOperationException e) {
                    instance.addModifier(modifier);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Knockback is then not changed.
        }
    }

    /** 0 (pushed fully) to 1 (not pushed at all); netherite armour gives 0.1 a piece. */
    public static double knockbackResistance(LivingEntity entity) {
        if (KNOCKBACK_RESISTANCE == null) {
            return 0.0;
        }
        try {
            AttributeInstance instance = entity.getAttribute(KNOCKBACK_RESISTANCE);
            return instance == null ? 0.0 : Math.max(0.0, Math.min(1.0, instance.getValue()));
        } catch (RuntimeException | LinkageError e) {
            return 0.0;
        }
    }

    /** A potion effect by its vanilla id ({@code slowness}, {@code mining_fatigue}...). */
    public static PotionEffectType effect(String key) {
        NamespacedKey id = NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT));
        try {
            PotionEffectType type = Registry.EFFECT.get(id);
            if (type != null) {
                return type;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Older servers: no effect registry.
        }
        try {
            return PotionEffectType.getByKey(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** An enchantment by its vanilla id ({@code sharpness}...), or null. */
    public static Enchantment enchantment(String key) {
        NamespacedKey id = NamespacedKey.minecraft(key.toLowerCase(Locale.ROOT));
        try {
            Enchantment enchantment = Registry.ENCHANTMENT.get(id);
            if (enchantment != null) {
                return enchantment;
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Fall back below.
        }
        try {
            return Enchantment.getByKey(id);
        } catch (RuntimeException | LinkageError e) {
            return null;
        }
    }

    /** Resource pack item model (1.21.2+). @return false when the server has none */
    public static boolean itemModel(ItemMeta meta, NamespacedKey model) {
        try {
            meta.setItemModel(model);
            return true;
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
    }

    /** Dropped legendaries never despawn. */
    public static void neverDespawn(Item item) {
        try {
            item.setUnlimitedLifetime(true);
        } catch (RuntimeException | LinkageError ignored) {
            // The despawn event is cancelled as well.
        }
    }

    public static boolean classExists(String name) {
        try {
            Class.forName(name, false, Compat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }
}
