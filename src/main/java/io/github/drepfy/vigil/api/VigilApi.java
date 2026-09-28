package io.github.drepfy.vigil.api;

import org.bukkit.entity.Player;

/**
 * Public API for other plugins, obtained through Bukkit's services manager:
 *
 * <pre>{@code
 * RegisteredServiceProvider<VigilApi> rsp =
 *         Bukkit.getServicesManager().getRegistration(VigilApi.class);
 * if (rsp != null) {
 *     rsp.getProvider().exempt(player, CheckCategory.MOVEMENT, 2000);
 * }
 * }</pre>
 *
 * Plugins that move players in ways Bukkit cannot observe (custom packets, dashes,
 * grappling hooks, launch pads implemented through NMS) should call
 * {@link #notifyImpulse(Player)} or {@link #exempt(Player, CheckCategory, long)} to
 * avoid false positives. All methods must be called from the server thread.
 */
public interface VigilApi {

    /** Exempts a player from one check for the given duration. */
    void exempt(Player player, CheckType check, long durationMillis);

    /** Exempts a player from every check in a category for the given duration. */
    void exempt(Player player, CheckCategory category, long durationMillis);

    /** Exempts a player from all checks for the given duration. */
    void exemptAll(Player player, long durationMillis);

    /**
     * Tells Vigil that the player was just pushed/launched by something it cannot
     * see. Movement checks treat this like received knockback.
     */
    void notifyImpulse(Player player);

    /** Current (decayed) violation level for a check, 0 if none. */
    double getViolationLevel(Player player, CheckType check);

    /** Whether checks are globally enabled in the configuration. */
    boolean isEnabled();
}
