package io.github.drepfy.legendary.ability;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An ability that waits for the player's next full-strength hit on a player (Draw, Crush, Reap):
 * who is waiting, and until when. Each weapon shows the wait its own way.
 */
final class Armed {

    private final Map<UUID, Long> until = new HashMap<>();

    void arm(Player player, long until) {
        this.until.put(player.getUniqueId(), until);
    }

    boolean armed(Player player, long now) {
        return left(player, now) > 0;
    }

    /** Ticks left to land the hit, or 0. */
    long left(Player player, long now) {
        Long end = until.get(player.getUniqueId());
        return end == null ? 0 : Math.max(0, end - now);
    }

    /** The hit landed: the ability is used up. */
    void spend(Player player) {
        until.remove(player.getUniqueId());
    }

    /** Lets go of everyone whose time ran out. */
    void expire(long now) {
        until.values().removeIf(end -> now >= end);
    }

    /** The players (online) who are waiting to land their hit. */
    List<Player> waiting() {
        List<Player> out = new ArrayList<>();
        for (Iterator<UUID> it = until.keySet().iterator(); it.hasNext(); ) {
            Player player = Bukkit.getPlayer(it.next());
            if (player != null && player.isOnline()) {
                out.add(player);
            }
        }
        return out;
    }

    void forget(Player player) {
        until.remove(player.getUniqueId());
    }
}
