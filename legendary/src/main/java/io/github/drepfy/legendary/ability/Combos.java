package io.github.drepfy.legendary.ability;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Counts hits in a row on the same target, for passives that go off every few hits. Hits closer
 * together than the minimum gap (spam clicks) keep the chain but do not count; switching target
 * or waiting too long starts again.
 */
final class Combos {

    private final Map<UUID, State> states = new HashMap<>();

    private static final class State {
        UUID target;
        int count;
        long last;
    }

    /** @return true when this hit completes the combo (the count then starts again) */
    boolean hit(UUID attacker, UUID target, long now, int hits, int window, int gap) {
        State state = states.computeIfAbsent(attacker, id -> new State());
        if (!target.equals(state.target) || now - state.last > window) {
            state.target = target;
            state.count = 0;
            state.last = Long.MIN_VALUE / 2;
        }
        if (now - state.last < gap) {
            return false;
        }
        state.last = now;
        state.count++;
        if (state.count >= hits) {
            state.count = 0;
            return true;
        }
        return false;
    }

    /** Hits counted so far towards the next one. */
    int count(UUID attacker) {
        State state = states.get(attacker);
        return state == null ? 0 : state.count;
    }

    void forget(UUID attacker) {
        states.remove(attacker);
    }

    void prune(long now, int window) {
        states.values().removeIf(state -> now - state.last > window);
    }
}
