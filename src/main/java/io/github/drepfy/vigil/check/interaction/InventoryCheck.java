package io.github.drepfy.vigil.check.interaction;

import io.github.drepfy.vigil.api.CheckType;
import io.github.drepfy.vigil.check.CheckContext;
import io.github.drepfy.vigil.config.CheckSettings;
import io.github.drepfy.vigil.data.PlayerData;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;

import java.util.Set;

/**
 * Inventory hacks and inventory macro mods:
 * <ul>
 *   <li><b>Click bursts</b> (Item Scroller mass moving, Tweakeroo hand restock, chest
 *   stealers, inventory cleaners): a person clicks at most 1-2 times per client tick
 *   and about 20 times a second. Clicks beyond {@code max-clicks-per-tick} are
 *   cancelled, so these mods simply stop working.</li>
 *   <li><b>InvMove</b>: vanilla releases the movement keys while any inventory screen
 *   is open, so a player cannot keep walking while clicking in an inventory that has
 *   been open for a while.</li>
 * </ul>
 * Holding Q or a number key over slots repeats keys legitimately, so drops, number-key
 * swaps and the off-hand key are not counted.
 */
public final class InventoryCheck {

    private static final CheckType TYPE = CheckType.INVENTORY;
    private static final Set<ClickType> COUNTED = Set.of(ClickType.LEFT, ClickType.RIGHT, ClickType.SHIFT_LEFT,
            ClickType.SHIFT_RIGHT, ClickType.MIDDLE, ClickType.DOUBLE_CLICK);
    private static final long FLAG_INTERVAL_MS = 1000;
    /** After a burst, further clicks are refused for this long. */
    private static final long BLOCK_MS = 1000;
    private static final long SESSION_MIN_MS = 400;
    private static final long SPEED_WINDOW_MS = 300;
    private static final long ENVIRONMENT_GRACE_MS = 1500;

    private final CheckContext ctx;

    public InventoryCheck(CheckContext ctx) {
        this.ctx = ctx;
    }

    public void onOpen(PlayerData data, long now) {
        data.inventorySessionMs = now;
    }

    public void onClose(PlayerData data) {
        data.inventorySessionMs = -1;
    }

    public void onClick(Player player, PlayerData data, InventoryClickEvent event, long now) {
        if (data.inventorySessionMs < 0) {
            // The player's own inventory is opened without telling the server: start at the first click.
            data.inventorySessionMs = now;
        }
        if (!COUNTED.contains(event.getClick()) || !ctx.isActive(player, data, TYPE, now)) {
            return;
        }
        CheckSettings settings = ctx.settings(TYPE);
        boolean clientTicks = ctx.settings().general().useClientTickEvents() && now - data.lastClientTickMs < 1000;
        long tick = clientTicks ? data.clientTick : ctx.currentTick();
        if (tick != data.inventoryClickTick) {
            data.inventoryClickTick = tick;
            data.inventoryClicksThisTick = 0;
        }
        data.inventoryClicksThisTick++;
        // Without exact client ticks a lag burst can put two or three client ticks into one server tick.
        double perTick = settings.num("max-clicks-per-tick") * (clientTicks ? 1 : 3);
        int perSecond = data.inventoryClicks.record(now);
        boolean burst = data.inventoryClicksThisTick > perTick;
        if (now < data.inventoryBlockedUntilMs && ctx.mitigationAllowed(settings)) {
            event.setCancelled(true);
        }
        if (burst || perSecond > settings.num("max-clicks-per-second")) {
            if (ctx.mitigationAllowed(settings)) {
                // Stop the macro for a moment instead of letting it through at the limit.
                event.setCancelled(true);
                data.inventoryBlockedUntilMs = now + BLOCK_MS;
            }
            violation(player, data, now, settings, burst
                    ? data.inventoryClicksThisTick + " inventory clicks in one tick (inventory macro / Item Scroller)"
                    : perSecond + " inventory clicks in one second");
            return;
        }
        checkInvMove(player, data, now, settings);
    }

    private void checkInvMove(Player player, PlayerData data, long now, CheckSettings settings) {
        int ping = ctx.recentPing(player, data, now);
        if (now - data.inventorySessionMs < SESSION_MIN_MS + ping) {
            return;
        }
        @SuppressWarnings("deprecation")
        boolean onGround = player.isOnGround();
        if (!onGround || ctx.isMovementExemptState(player, data, now) || ctx.inMovementGrace(data, now)
                || now - data.lastLiquidMs < ENVIRONMENT_GRACE_MS || now - data.lastIceMs < ENVIRONMENT_GRACE_MS
                || now - data.lastSlimeMs < ENVIRONMENT_GRACE_MS || now - data.lastClimbMs < ENVIRONMENT_GRACE_MS
                || now - data.lastBouncyMs < ENVIRONMENT_GRACE_MS) {
            return;
        }
        long end = now - ping;
        double speed = data.positions.averageHorizontalSpeed(end - SPEED_WINDOW_MS, end);
        if (Double.isNaN(speed) || speed <= settings.num("invmove-speed")) {
            return;
        }
        if (ctx.isDisturbed(player.getLocation(), now) || ctx.nearPlatformEntity(player)) {
            return;
        }
        violation(player, data, now, settings, "kept moving at " + Text.num(speed * 20.0)
                + " m/s while clicking in an open inventory (InvMove)");
    }

    private void violation(Player player, PlayerData data, long now, CheckSettings settings, String detail) {
        if (now - data.lastInventoryFlagMs < FLAG_INTERVAL_MS || !ctx.canFlag(player, data, now)) {
            return;
        }
        data.lastInventoryFlagMs = now;
        double buffer = data.buffer(TYPE).add(1.0, now, 0.1);
        if (buffer < settings.bufferThreshold()) {
            return;
        }
        data.buffer(TYPE).reset();
        ctx.flag(player, data, TYPE, detail);
    }
}
