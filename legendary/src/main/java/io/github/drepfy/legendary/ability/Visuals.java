package io.github.drepfy.legendary.ability;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
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
 * The abilities' 3D effects: the Katana's cuts, the Candy Cane's sticky puddles, Crush's
 * cracked ground and flying shards, the Reaper's spectral scythe... Each is a model from the
 * resource pack ({@code legendary:fx/<name>}) or a piece of a real block, shown by a display
 * entity. The
 * server only says where it starts and where it ends up; the players' game animates it smoothly
 * in between. Effects are never saved with the world and are cleared when the plugin stops.
 *
 * <p>Models are flat (lying on the ground, facing up) or upright (standing, facing the viewer
 * along +z), one block across at scale 1. Without the resource pack they show as paper.
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

    /** A model effect at a place. */
    public Effect spawn(String model, Location at) {
        return model("legendary:fx/" + model, at);
    }

    /** Any item model at a place, e.g. {@code legendary:sugarcrash} for a thrown weapon. */
    public Effect model(String itemModel, Location at) {
        World world = at.getWorld();
        if (world == null) {
            return Effect.NONE;
        }
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            try {
                meta.setItemModel(NamespacedKey.fromString(itemModel));
            } catch (RuntimeException | LinkageError ignored) {
                // Before 1.21.2 there are no item models: the effect shows as paper.
            }
            item.setItemMeta(meta);
        }
        Location place = at.clone();
        place.setYaw(0f);
        place.setPitch(0f);
        try {
            ItemDisplay display = world.spawn(place, ItemDisplay.class, d -> {
                d.setItemStack(item);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                setup(d);
            });
            return track(display);
        } catch (RuntimeException | LinkageError e) {
            return Effect.NONE;
        }
    }

    /**
     * A small piece of a real block (a shard of the ground thrown up by an impact). Shown by a
     * block display: no block in the world is ever placed or changed.
     */
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
                d.setBrightness(null);                    // lit like the world round it
            });
            Effect effect = track(display);
            effect.centred = true;
            return effect;
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
        /** A block display: its corner is at its position, so it is moved to turn round its middle. */
        boolean centred;
        /** Set by {@link #stretch}: the flat direction its size is stretched along. */
        private Quaternionf along;
        /** Bumped by {@link #stop}: what was planned before it is dropped. */
        private int plan;

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

        /** Moved off its position by this much (blocks; for a billboard, across the screen). */
        public Effect shift(double x, double y, double z) {
            translation.set((float) x, (float) y, (float) z);
            return this;
        }

        /**
         * From now on its size stretches it along a flat direction in the world (size z along
         * it, x across it) instead of along its own sides: a puddle squashed out the way it was
         * stepped in, whichever way the puddle itself is turned.
         */
        public Effect stretch(Vector flat) {
            along = new Quaternionf().rotateY((float) Math.atan2(flat.getX(), flat.getZ()));
            return this;
        }

        /**
         * Drops everything planned for it so far: animations, glides and its removal (so plan a
         * new one, e.g. with {@link #vanish}).
         */
        public Effect stop() {
            plan++;
            return this;
        }

        /** Tumbled freely (a shard flying through the air): turns about all three axes. */
        public Effect tumble(double x, double y, double z) {
            spin.identity().rotateXYZ((float) Math.toRadians(x), (float) Math.toRadians(y), (float) Math.toRadians(z));
            return this;
        }

        /** Sends the changes now, played smoothly over {@code ticks}. */
        public Effect send(int ticks) {
            if (!exists()) {
                return this;
            }
            try {
                Quaternionf turned = new Quaternionf(rotation).mul(spin);
                Vector3f move = new Vector3f(translation);
                if (centred) {
                    // turn and scale a block round its middle, not its corner
                    move.sub(turned.transform(new Vector3f(scale).mul(0.5f)));
                }
                // the game draws translation * left * scale * right
                Quaternionf left = along == null ? turned : new Quaternionf(along);
                Quaternionf right = along == null ? new Quaternionf() : new Quaternionf(along).conjugate().mul(turned);
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(Math.max(0, ticks));
                display.setTransformation(new Transformation(move, left, new Vector3f(scale), right));
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
            int planned = plan;
            visuals.later(delay, () -> {
                if (planned == plan) {
                    change.accept(this);
                    send(ticks);
                }
            });
            return this;
        }

        /** Glides to a place over {@code ticks}. */
        public Effect moveTo(Location to, int ticks) {
            if (exists()) {
                try {
                    display.setTeleportDuration(Math.max(0, Math.min(59, ticks)));
                } catch (RuntimeException | LinkageError ignored) {
                    // Without smooth teleports it jumps there instead.
                }
                try {
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

        /** Glides to a place over {@code ticks}, starting {@code delay} ticks from now (at least 1). */
        public Effect glide(int delay, Location to, int ticks) {
            if (display != null) {
                Location target = to.clone();
                int planned = plan;
                visuals.later(delay, () -> {
                    if (planned == plan) {
                        moveTo(target, ticks);
                    }
                });
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
                int planned = plan;
                visuals.later(ticks, () -> {
                    if (planned == plan) {
                        remove();
                    }
                });
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
