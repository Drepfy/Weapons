package io.github.drepfy.vigil;

import io.github.drepfy.vigil.api.CheckCategory;
import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.api.VigilApi;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Clock;
import org.bukkit.entity.Player;

/**
 * Implementation of the public API registered with Bukkit's services manager.
 */
final class VigilApiImpl implements VigilApi {

    private final VigilPlugin plugin;

    VigilApiImpl(VigilPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void exempt(Player player, CheckType check, long durationMillis) {
        data(player).exempt(check, Clock.now() + Math.max(0L, durationMillis));
    }

    @Override
    public void exempt(Player player, CheckCategory category, long durationMillis) {
        data(player).exempt(category, Clock.now() + Math.max(0L, durationMillis));
    }

    @Override
    public void exemptAll(Player player, long durationMillis) {
        data(player).exemptAll(Clock.now() + Math.max(0L, durationMillis));
    }

    @Override
    public void notifyImpulse(Player player) {
        long now = Clock.now();
        PlayerData data = data(player);
        data.lastVelocityMs = now;
        plugin.lifecycle().impulse(player, data, 1.0, now);
    }

    @Override
    public double getViolationLevel(Player player, CheckType check) {
        PlayerData data = plugin.players().peek(player.getUniqueId());
        if (data == null) {
            return 0.0;
        }
        return data.violation(check).get(Clock.now(), plugin.settings().check(check).decayPerMinute());
    }

    @Override
    public boolean isEnabled() {
        return plugin.settings().general().enabled();
    }

    private PlayerData data(Player player) {
        return plugin.players().get(player);
    }
}
