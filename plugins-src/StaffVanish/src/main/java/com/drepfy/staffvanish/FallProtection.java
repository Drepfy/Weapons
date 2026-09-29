package com.drepfy.staffvanish;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;

/**
 * Cancels the next fall damage of players who lost their flight mid-air by reappearing, so leaving vanish while
 * flying doesn't hurt or kill them. Protection runs out after {@link #DURATION_MILLIS} in case they never take any.
 */
public final class FallProtection {

    static final long DURATION_MILLIS = 20_000;

    private final Map<UUID, Long> protectedUntil = new HashMap<>();

    public void protect(Player player) {
        protectedUntil.put(player.getUniqueId(), System.currentTimeMillis() + DURATION_MILLIS);
    }

    /**
     * @return whether the player's fall damage should be cancelled; protection is used up either way
     */
    public boolean consume(Player player) {
        Long until = protectedUntil.remove(player.getUniqueId());
        return until != null && until >= System.currentTimeMillis();
    }

    public void clear(UUID id) {
        protectedUntil.remove(id);
    }
}
