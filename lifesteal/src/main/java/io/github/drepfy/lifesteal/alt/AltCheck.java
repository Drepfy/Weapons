package io.github.drepfy.lifesteal.alt;

import org.bukkit.entity.Player;

/**
 * One way of telling that a kill is heart farming between accounts of the same person.
 * New checks can be added with {@link AltProtection#register}.
 */
public interface AltCheck {

    /** Short name, e.g. {@code shared-ip}. */
    String id();

    /**
     * @return why these two accounts must not trade hearts (shown to staff), or {@code null}
     *         if this check sees no problem
     */
    String check(Player killer, Player victim);
}
