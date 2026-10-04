package io.github.drepfy.lifesteal.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/**
 * Fired when a kill is about to move hearts (after the alt account and cooldown checks).
 * Cancel it to stop the steal; the death itself is not affected.
 */
public final class HeartStealEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();
    private final Player killer;
    private final Player victim;
    private final int hearts;
    private boolean cancelled;

    public HeartStealEvent(Player killer, Player victim, int hearts) {
        this.killer = killer;
        this.victim = victim;
        this.hearts = hearts;
    }

    public Player getKiller() {
        return killer;
    }

    public Player getVictim() {
        return victim;
    }

    /** Hearts the victim is about to lose. */
    public int getHearts() {
        return hearts;
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
