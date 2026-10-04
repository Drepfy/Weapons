package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.item.WeaponItems;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

import java.util.List;

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

    /** A fully counted sword or axe hit, before damage is applied (may change it). */
    default void melee(EntityDamageByEntityEvent event, Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
    }

    /** The same hit after it landed. */
    default void landed(Player attacker, LivingEntity target, WeaponItems.Tag weapon) {
    }

    /** Ticks an ability is still running for (Sugar Rush, a rift mark...), or 0. */
    default long active(Player player, WeaponItems.Tag weapon, Ability ability, long now) {
        return 0;
    }

    /** Extra parts for the action bar (Edge stacks, a mark). */
    default void hud(Player player, WeaponItems.Tag weapon, List<String> parts, long now) {
    }

    default void tick(long now) {
    }

    /** The player left, died or changed world. */
    default void forget(Player player) {
    }
}
