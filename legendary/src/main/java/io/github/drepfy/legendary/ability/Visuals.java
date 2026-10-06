package io.github.drepfy.legendary.ability;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Timing for the abilities' effects ({@link #later}), and the real blocks some of them show:
 * rocks thrown up by a slam, gravestones rising out of the ground. Those are block displays of
 * vanilla blocks: the server says where one starts and where it ends up, and the players' game
 * moves it smoothly in between. They are never saved with the world and are cleared when the
 * plugin stops. Everything else is drawn with particles ({@link Shapes}).
 */
public final class Visuals {

    /** Lit as if in full daylight, so effects glow at night and in caves. */
    private static final Display.Brightness GLOW = new Display.Brightness(15, 15);

    private final Set<Display> alive = new HashSet<>();
    private final List<Job> jobs = new ArrayList<>();
    private long now;

    private record Job(long at, Runnable action) {
    }

    /** Every tick. */
    public void tick(long tick) {
        now = tick;
        List<Job> due = new ArrayList<>();
        for (Iterator<Job> it = jobs.iterator(); it.hasNext(); ) {
            Job job = it.next();
            if (job.at() <= tick) {
                due.add(job);
                it.remove();
            }
        }
        for (Job job : due) {
            try {
                job.action().run();
            } catch (RuntimeException | LinkageError ignored) {
                // Effects are only for show.
            }
        }
        if (tick % 100 == 0) {
            alive.removeIf(display -> !display.isValid());
        }
    }

    /** Runs something a number of ticks from now (0 = next tick). */
    public void later(int ticks, Runnable action) {
        jobs.add(new Job(now + Math.max(1, ticks), action));
    }

    /** Removes every effect (the plugin is stopping). */
    public void clear() {
        for (Display display : alive) {
            display.remove();
        }
        alive.clear();
        jobs.clear();
    }

    public int count() {
        alive.removeIf(display -> !display.isValid());
        return alive.size();
    }

    /** A block shown as an effect (rock thrown up by a slam). */
    public Effect block(BlockData block, Location at) {
        World world = at.getWorld();
        if (world == null || block == null) {
            return Effect.NONE;
        }
        Location place = at.clone();
        place.setYaw(0f);
        place.setPitch(0f);
        try {
            BlockDisplay display = world.spawn(place, BlockDisplay.class, d -> {
                d.setBlock(block);
                setup(d);
                d.setBrightness(null);
            });
            Effect effect = track(display);
            // A block display's corner is its origin: centre it.
            effect.translation.set(-0.5f, -0.5f, -0.5f);
            return effect.send(0);
        } catch (RuntimeException | LinkageError e) {
            return Effect.NONE;
        }
    }

    private static void setup(Display d) {
        d.setPersistent(false);
        d.setBrightness(GLOW);
        d.setShadowRadius(0f);
        d.setViewRange(1.5f);
        d.setInterpolationDuration(0);
        d.setTeleportDuration(0); // Billboard: FIXED, the default (billboard() changes it).
    }

    private Effect track(Display display) {
        alive.add(display);
        return new Effect(this, display);
    }

    /**
     * One effect on screen. Changes are collected and sent with {@link #animate} (smoothly over a
     * number of ticks) or {@link #apply} (at once).
     */
    public static final class Effect {
        static final Effect NONE = new Effect(null, null);

        private final Visuals visuals;
        private final Display display;
        final Vector3f translation = new Vector3f();
        final Quaternionf rotation = new Quaternionf();
        final Vector3f scale = new Vector3f(1f, 1f, 1f);
        final Quaternionf spin = new Quaternionf();

        Effect(Visuals visuals, Display display) {
            this.visuals = visuals;
            this.display = display;
        }

        public boolean exists() {
            return display != null && display.isValid();
        }

        public Display display() {
            return display;
        }

        /** Faces the viewer all the time (stars, portals, halos). */
        public Effect billboard() {
            if (display != null) {
                try {
                    display.setBillboard(Display.Billboard.CENTER);
                } catch (RuntimeException | LinkageError ignored) {
                    // Effects are only for show.
                }
            }
            return this;
        }

        public Effect size(double s) {
            scale.set((float) s, (float) s, (float) s);
            return this;
        }

        public Effect size(double x, double y, double z) {
            scale.set((float) x, (float) y, (float) z);
            return this;
        }

        /** Turned to face a flat direction (the model's +z points along it). */
        public Effect facing(Vector flat) {
            rotation.identity().rotateY((float) Math.atan2(flat.getX(), flat.getZ()));
            return this;
        }

        /** Extra turn about the vertical axis on top of {@link #facing} (spinning rings). */
        public Effect turn(double degrees) {
            spin.identity().rotateY((float) Math.toRadians(degrees));
            return this;
        }

        /** Tilted about the facing direction (a slash at an angle). */
        public Effect tilt(double degrees) {
            spin.identity().rotateZ((float) Math.toRadians(degrees));
            return this;
        }

        public Effect offset(double x, double y, double z) {
            translation.set((float) x, (float) y, (float) z);
            return this;
        }

        /** Sends the changes now, played smoothly over {@code ticks}. */
        public Effect send(int ticks) {
            if (!exists()) {
                return this;
            }
            try {
                Quaternionf left = new Quaternionf(rotation).mul(spin);
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(Math.max(0, ticks));
                display.setTransformation(new Transformation(new Vector3f(translation), left, new Vector3f(scale),
                        new Quaternionf()));
            } catch (RuntimeException | LinkageError ignored) {
                // Effects are only for show.
            }
            return this;
        }

        /** Changes it {@code delay} ticks from now, played smoothly over {@code ticks}. */
        public Effect animate(int delay, int ticks, Consumer<Effect> change) {
            if (display == null) {
                return this;
            }
            visuals.later(delay, () -> {
                change.accept(this);
                send(ticks);
            });
            return this;
        }

        /** Glides to a place over {@code ticks}. */
        public Effect moveTo(Location to, int ticks) {
            if (exists()) {
                try {
                    display.setTeleportDuration(Math.max(0, Math.min(59, ticks)));
                    Location place = to.clone();
                    place.setYaw(0f);
                    place.setPitch(0f);
                    display.teleport(place);
                } catch (RuntimeException | LinkageError ignored) {
                    // Effects are only for show.
                }
            }
            return this;
        }

        /** Keeps it with an entity (offset in blocks) for {@code ticks}. */
        public Effect follow(Entity entity, Vector offset, int ticks) {
            if (display == null) {
                return this;
            }
            for (int t = 1; t <= ticks; t++) {
                visuals.later(t, () -> {
                    if (exists() && entity.isValid()) {
                        moveTo(entity.getLocation().add(offset), 2);
                    }
                });
            }
            return this;
        }

        /** Removed {@code ticks} from now. */
        public Effect life(int ticks) {
            if (display != null) {
                visuals.later(ticks, this::remove);
            }
            return this;
        }

        /** Shrinks away over {@code ticks}, starting {@code delay} ticks from now, then is removed. */
        public Effect vanish(int delay, int ticks) {
            if (display == null) {
                return this;
            }
            animate(delay, ticks, e -> e.scale.set(0.001f, 0.001f, 0.001f));
            return life(delay + ticks + 1);
        }

        public void remove() {
            if (display != null) {
                display.remove();
                visuals.alive.remove(display);
            }
        }
    }
}
