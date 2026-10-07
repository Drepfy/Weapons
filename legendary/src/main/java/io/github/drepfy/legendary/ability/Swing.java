package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.item.WeaponItems;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;

/**
 * A sword or axe hit with a legendary on another player.
 *
 * @param charged whether it was a full-strength hit (the attack bar at 90% or more), which the
 *                abilities wait for; any hit counts when {@code full-strength-hits} is off
 */
record Swing(EntityDamageByEntityEvent event, Player attacker, Player target, WeaponItems.Tag weapon,
             boolean charged) {
}
