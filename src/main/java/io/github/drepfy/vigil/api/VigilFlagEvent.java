package io.github.drepfy.vigil.api;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.bukkit.event.player.PlayerEvent;

/**
 * Called on the server thread right before a violation is recorded. Cancelling it
 * discards the flag entirely (no VL, alert, log entry or automatic ban).
 */
public class VigilFlagEvent extends PlayerEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final CheckType check;
    private final double vlAdded;
    private final double currentVl;
    private final String detail;
    private boolean cancelled;

    public VigilFlagEvent(Player player, CheckType check, double vlAdded, double currentVl, String detail) {
        super(player);
        this.check = check;
        this.vlAdded = vlAdded;
        this.currentVl = currentVl;
        this.detail = detail;
    }

    public CheckType getCheck() {
        return check;
    }

    /** VL this flag would add. */
    public double getVlAdded() {
        return vlAdded;
    }

    /** VL before this flag is applied (after decay). */
    public double getCurrentVl() {
        return currentVl;
    }

    /** Short human readable evidence string. */
    public String getDetail() {
        return detail;
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
