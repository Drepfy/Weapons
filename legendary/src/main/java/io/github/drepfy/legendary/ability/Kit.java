package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.item.WeaponItems;
import org.bukkit.entity.Player;

/** One weapon: its ability (Shift + F) and its passive. */
interface Kit {

    enum Result {
        /** It went off: its cooldown starts now. */
        FIRED,
        /** Nothing happened: no cooldown. */
        FAILED
    }

    WeaponType type();

    /** Shift + F (or sneak + right-click) with the weapon in hand, when the ability is ready. */
    Result use(Player player, WeaponItems.Tag weapon);

    /** A sword or axe hit on a player that the weapon's passive and ability may act on, before it lands. */
    default void melee(Swing swing) {
    }

    /** The same hit, after it landed (not cancelled, and not fully blocked by a shield). */
    default void landed(Swing swing) {
    }

    /** Ticks the ability is still waiting for its hit (Draw, Crush, Reap) or still out (traps), or 0. */
    default long active(Player player, long now) {
        return 0;
    }

    /** How long the ability runs in all, in ticks (for the boss bar), or 0. */
    default long activeLength() {
        return 0;
    }

    default void tick(long now) {
    }

    /** The player left, died or changed world. */
    default void forget(Player player) {
    }
}
