package io.github.drepfy.lifesteal.heart;

import io.github.drepfy.lifesteal.config.Settings;
import io.github.drepfy.lifesteal.util.Text;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.function.Supplier;

/**
 * The title, sound and floating hearts when a player wins or loses hearts (effects in
 * config.yml). Only for the player concerned; the floating hearts are seen by everyone near.
 */
public final class HeartEffects {

    private final Supplier<Settings> settings;

    public HeartEffects(Supplier<Settings> settings) {
        this.settings = settings;
    }

    /**
     * @param victim the player they were stolen from, or null (a Heart item was used)
     * @param hearts how many the player has now
     */
    public void gained(Player player, int count, String victim, int hearts) {
        Settings config = settings.get();
        Settings.Effects effects = config.effects();
        title(player, config, "title-gain", victim != null ? "subtitle-stolen" : "subtitle-hearts", count, hearts,
                victim, null);
        play(player, effects.gain());
        if (effects.particles()) {
            try {
                Location above = player.getLocation().add(0, player.getHeight() + 0.3, 0);
                player.getWorld().spawnParticle(Particle.HEART, above, 5 + 2 * Math.min(count, 5), 0.45, 0.25, 0.45, 0);
            } catch (RuntimeException | LinkageError ignored) {
                // Only a decoration.
            }
        }
    }

    /**
     * @param killer the player who took them, or null (a death without a killer)
     * @param hearts how many the player has now
     */
    public void lost(Player player, int count, String killer, int hearts) {
        Settings config = settings.get();
        title(player, config, "title-lose", killer != null ? "subtitle-taken" : "subtitle-hearts", count, hearts,
                null, killer);
        play(player, config.effects().lose());
    }

    @SuppressWarnings("deprecation") // sendTitle(String...): works on Spigot and Paper alike.
    private static void title(Player player, Settings config, String titleKey, String subtitleKey, int count,
                              int hearts, String victim, String killer) {
        if (!config.effects().titles()) {
            return;
        }
        Object[] values = {"count", count, "hearts", hearts, "victim", victim, "killer", killer};
        String title = Text.format(config.messages().get(titleKey), values);
        String subtitle = Text.format(config.messages().get(subtitleKey), values);
        if (!title.isEmpty() || !subtitle.isEmpty()) {
            player.sendTitle(title, subtitle, 4, 30, 10); // About 2 seconds: never long in the way of a fight.
        }
    }

    private static void play(Player player, Settings.SoundSpec sound) {
        if (sound == null) {
            return;
        }
        try {
            player.playSound(player.getLocation(), sound.key(), sound.volume(), sound.pitch());
        } catch (RuntimeException | LinkageError ignored) {
            // Only a sound.
        }
    }
}
