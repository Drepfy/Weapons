package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.config.Settings;
import io.github.drepfy.legendary.util.Compat;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * Particles and sounds. Particles are sent to each player within {@link #RANGE} blocks, so
 * everyone nearby sees an ability coming; nothing here can fail an ability.
 */
public final class Fx {

    public static final double RANGE = 48.0;

    public static final Particle DUST = Compat.particle("DUST", "REDSTONE");
    public static final Particle CRIT = Compat.particle("CRIT");
    public static final Particle EXPLOSION = Compat.particle("EXPLOSION", "EXPLOSION_LARGE");
    public static final Particle BLOCK = Compat.particle("BLOCK", "BLOCK_CRACK");
    public static final Particle SOUL = Compat.particle("SOUL");
    public static final Particle CLOUD = Compat.particle("CLOUD");

    private final Supplier<Settings> settings;

    public Fx(Supplier<Settings> settings) {
        this.settings = settings;
    }

    /** The players who see an effect at {@code center}. */
    public View view(Location center) {
        List<Player> players = new ArrayList<>();
        World world = center.getWorld();
        if (world != null) {
            for (Player player : world.getPlayers()) {
                if (player.getLocation().distanceSquared(center) <= RANGE * RANGE) {
                    players.add(player);
                }
            }
        }
        return new View(players);
    }

    /** Plays a sound from config.yml ({@code sounds.<key>}) at a place, for everyone nearby. */
    public void sound(Location at, String key) {
        sound(at, key, 0f);
    }

    /** The same, with the pitch raised by {@code pitchShift} (Edge stacks chime higher). */
    public void sound(Location at, String key, float pitchShift) {
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        for (Settings.SoundSpec spec : settings.get().sound(key)) {
            try {
                world.playSound(at, spec.key(), SoundCategory.PLAYERS, spec.volume(),
                        Math.max(0.5f, Math.min(2.0f, spec.pitch() + pitchShift)));
            } catch (RuntimeException | LinkageError ignored) {
                // Sounds are only for show.
            }
        }
    }

    /** A sound only this player hears. */
    public void soundTo(Player player, String key) {
        for (Settings.SoundSpec spec : settings.get().sound(key)) {
            try {
                player.playSound(player.getLocation(), spec.key(), SoundCategory.PLAYERS, spec.volume(), spec.pitch());
            } catch (RuntimeException | LinkageError ignored) {
                // Sounds are only for show.
            }
        }
    }

    /** Everyone who sees one effect. */
    public static final class View {
        private final List<Player> players;

        View(List<Player> players) {
            this.players = players;
        }

        public List<Player> players() {
            return players;
        }

        public void particle(Particle particle, Location at, int count, double spreadX, double spreadY, double spreadZ,
                             double speed) {
            particle(particle, at, count, spreadX, spreadY, spreadZ, speed, null);
        }

        public void particle(Particle particle, Location at, int count, double spreadX, double spreadY, double spreadZ,
                             double speed, Object data) {
            if (particle == null || players.isEmpty()) {
                return;
            }
            Class<?> type = particle.getDataType();
            if (data == null && type != Void.class) {
                if (type == Color.class) {
                    data = Color.WHITE;
                } else if (type == Float.class) {
                    data = 0f;
                } else if (type == Integer.class) {
                    data = 0;
                } else {
                    return;
                }
            }
            if (data != null && !type.isInstance(data)) {
                return;
            }
            for (Player player : players) {
                try {
                    player.spawnParticle(particle, at, count, spreadX, spreadY, spreadZ, speed, data);
                } catch (RuntimeException | LinkageError ignored) {
                    // Particles are only for show.
                }
            }
        }

        public void dust(Location at, Color color, float size, int count, double spread) {
            if (DUST != null) {
                particle(DUST, at, count, spread, spread, spread, 0, new Particle.DustOptions(color, size));
            }
        }

        /** The ground cracking (block particles of the block itself). */
        public void debris(Location at, BlockData block, int count, double spread) {
            if (BLOCK != null && block != null) {
                particle(BLOCK, at, count, spread, 0.1, spread, 0.15, block);
            }
        }

    }
}
