package io.github.drepfy.legendary.registry;

import io.github.drepfy.legendary.WeaponType;
import org.bukkit.Location;

import java.util.UUID;

/** Where one legendary weapon is, and who had it. */
public final class WeaponRecord {

    public enum State {
        /** In a player's inventory. */
        HELD,
        /** An item on the ground. */
        GROUND,
        /** Gone without anyone seeing how (can be given out again; comes back if it turns up). */
        LOST,
        /** Taken away or replaced by staff: deleted wherever it turns up. */
        REMOVED
    }

    private final UUID id;
    private final WeaponType type;
    private final long created;
    private final String createdBy;
    private State state = State.HELD;
    private UUID holder;
    private String holderName;
    /** The holder before the current one (for a weapon on the ground: who dropped it). */
    private UUID previousHolder;
    private String previousHolderName;
    private UUID entity;
    private String world;
    private double x;
    private double y;
    private double z;
    private long updated;
    private String note;

    public WeaponRecord(UUID id, WeaponType type, long created, String createdBy) {
        this.id = id;
        this.type = type;
        this.created = created;
        this.createdBy = createdBy;
        this.updated = created;
    }

    public UUID id() {
        return id;
    }

    public WeaponType type() {
        return type;
    }

    public long created() {
        return created;
    }

    public String createdBy() {
        return createdBy;
    }

    public State state() {
        return state;
    }

    /** Held or on the ground: it is somewhere in the world. */
    public boolean exists() {
        return state == State.HELD || state == State.GROUND;
    }

    public UUID holder() {
        return holder;
    }

    public String holderName() {
        return holderName;
    }

    public UUID previousHolder() {
        return previousHolder;
    }

    public String previousHolderName() {
        return previousHolderName;
    }

    public UUID entity() {
        return entity;
    }

    public String world() {
        return world;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public double z() {
        return z;
    }

    public long updated() {
        return updated;
    }

    public String note() {
        return note;
    }

    /** Now in this player's inventory. */
    void held(UUID player, String name, Location at, long now) {
        if (holder != null && !holder.equals(player)) {
            previousHolder = holder;
            previousHolderName = holderName;
        }
        state = State.HELD;
        holder = player;
        holderName = name;
        entity = null;
        note = null;
        at(at, now);
    }

    /** Dropped (or spawned) as an item on the ground. */
    void ground(UUID item, Location at, long now) {
        if (holder != null) {
            previousHolder = holder;
            previousHolderName = holderName;
        }
        state = State.GROUND;
        holder = null;
        holderName = null;
        entity = item;
        note = null;
        at(at, now);
    }

    void lost(String why, long now) {
        state = State.LOST;
        entity = null;
        note = why;
        updated = now;
    }

    void removed(String why, long now) {
        state = State.REMOVED;
        entity = null;
        note = why;
        updated = now;
    }

    void at(Location at, long now) {
        if (at != null && at.getWorld() != null) {
            world = at.getWorld().getName();
            x = at.getX();
            y = at.getY();
            z = at.getZ();
        }
        updated = now;
    }

    void restore(State state, UUID holder, String holderName, UUID previousHolder, String previousHolderName,
                 UUID entity, String world, double x, double y, double z, long updated, String note) {
        this.state = state;
        this.holder = holder;
        this.holderName = holderName;
        this.previousHolder = previousHolder;
        this.previousHolderName = previousHolderName;
        this.entity = entity;
        this.world = world;
        this.x = x;
        this.y = y;
        this.z = z;
        this.updated = updated;
        this.note = note;
    }
}
