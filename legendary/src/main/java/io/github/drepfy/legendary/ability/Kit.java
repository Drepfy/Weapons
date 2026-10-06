package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.item.WeaponItems;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/** One weapon's abilities. */
interface Kit {

    enum Result {
        /** It went off: its cooldown starts now. */
        FIRED,
        /** The kit looked after the cooldown itself (or there is none yet). */
        HANDLED,
        /** Nothing happened: no cooldown. */
        FAILED
    }

    WeaponType type();

    Result use(Player player, WeaponItems.Tag weapon, Ability ability);

    /**
     * The key pressed again while an ability is still going: another charge, a return, a dive.
     * Asked before the cooldown is checked.
     *
     * @return whether it was a recast (then nothing else happens)
     */
    default boolean recast(Player player, WeaponItems.Tag weapon, Ability ability) {
        return false;
    }

    /** A fully counted sword or axe hit, before damage is applied (may change it). */
    default void melee(EntityDamageByEntityEvent event, Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
    }

    /** The same hit after it landed. */
    default void landed(Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
    }

    /** Ticks an ability is still running for (Blood Moon, a cyclone, a falling star...), or 0. */
    default long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        return 0;
    }

    /** How long an ability runs in all, in ticks (for the boss bar), or 0. */
    default long activeLength(Ability ability) {
        return 0;
    }

    default void tick(long now) {
    }

    /** The player left, died or changed world. */
    default void forget(Player player) {
    }
}
