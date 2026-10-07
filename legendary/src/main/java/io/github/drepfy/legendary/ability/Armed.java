package io.github.drepfy.legendary.ability;

import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * An ability that waits for the player's next full-strength hit on a player (Draw, Crush, Reap):
 * who is waiting until when, and the ring round their feet that shows it.
 */
final class Armed {

    private record State(long until, Visuals.Effect ring) {
    }

    private final Map<UUID, State> states = new HashMap<>();

    void arm(Player player, long until, Visuals.Effect ring) {
        State old = states.put(player.getUniqueId(), new State(until, ring));
        if (old != null && old.ring() != null) {
            old.ring().remove();
        }
    }

    boolean armed(Player player, long now) {
        return left(player, now) > 0;
    }

    /** Ticks left to land the hit, or 0. */
    long left(Player player, long now) {
        State state = states.get(player.getUniqueId());
        return state == null ? 0 : Math.max(0, state.until() - now);
    }

    /** The hit landed: the ability is used up and its ring flares out. */
    void spend(Player player) {
        State state = states.remove(player.getUniqueId());
        if (state != null && state.ring() != null) {
            state.ring().animate(1, 3, e -> e.size(4.2)).vanish(4, 4);
        }
    }

    /** Lets go of everyone whose time ran out (their ring fades). */
    void expire(long now) {
        for (Iterator<State> it = states.values().iterator(); it.hasNext(); ) {
            State state = it.next();
            if (now >= state.until()) {
                if (state.ring() != null) {
                    state.ring().vanish(0, 5);
                }
                it.remove();
            }
        }
    }

    void forget(Player player) {
        State state = states.remove(player.getUniqueId());
        if (state != null && state.ring() != null) {
            state.ring().remove();
        }
    }
}
