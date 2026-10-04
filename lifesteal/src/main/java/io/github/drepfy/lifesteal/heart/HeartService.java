package io.github.drepfy.lifesteal.heart;

import io.github.drepfy.lifesteal.config.Settings;
import io.github.drepfy.lifesteal.data.LifestealStore;
import io.github.drepfy.lifesteal.util.Compat;
import org.bukkit.Location;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Hearts of every player. The stored number is the truth; a player's max health
 * (2 health per heart) is set from it whenever it changes and when they join or respawn.
 */
public final class HeartService {

    private final Supplier<Settings> settings;
    private final LifestealStore store;
    private final HeartItems items;

    public HeartService(Supplier<Settings> settings, LifestealStore store, HeartItems items) {
        this.settings = settings;
        this.store = store;
        this.items = items;
    }

    /** The player's hearts (a new player gets the starting hearts). */
    public int hearts(Player player) {
        return store.entry(player.getUniqueId(), player.getName(), settings.get().startHearts()).hearts();
    }

    /** Hearts of a player who may be offline, or {@code -1} if they never joined. */
    public int hearts(UUID uuid) {
        LifestealStore.Entry entry = store.entry(uuid);
        return entry == null ? -1 : entry.hearts();
    }

    /** Within the configured minimum and maximum. */
    public int clamp(int hearts) {
        Settings config = settings.get();
        return Math.max(config.minHearts(), Math.min(config.maxHearts(), hearts));
    }

    /**
     * Sets an online player's hearts (clamped) and their max health.
     *
     * @param heal also fill the hearts gained
     * @return the new number of hearts
     */
    public int set(Player player, int hearts, boolean heal) {
        LifestealStore.Entry entry = store.entry(player.getUniqueId(), player.getName(), settings.get().startHearts());
        int before = entry.hearts();
        int after = clamp(hearts);
        store.setHearts(entry, after);
        apply(player);
        if (heal && after > before && !player.isDead()) {
            AttributeInstance max = player.getAttribute(Compat.MAX_HEALTH);
            double limit = max != null ? max.getValue() : after * 2.0;
            player.setHealth(Math.min(limit, player.getHealth() + (after - before) * 2.0));
        }
        return after;
    }

    /** Sets a player's hearts whether online or not (offline players get them when they join). */
    public int set(UUID uuid, String name, Player online, int hearts) {
        if (online != null) {
            return set(online, hearts, false);
        }
        LifestealStore.Entry entry = store.entry(uuid, name, settings.get().startHearts());
        int after = clamp(hearts);
        store.setHearts(entry, after);
        return after;
    }

    /** Max health = 2 per heart; current health never above it. */
    public void apply(Player player) {
        AttributeInstance max = player.getAttribute(Compat.MAX_HEALTH);
        if (max == null) {
            return;
        }
        int hearts = hearts(player);
        int clamped = clamp(hearts);
        if (clamped != hearts) {
            // The limits changed in config.yml.
            store.setHearts(store.entry(player.getUniqueId()), clamped);
        }
        double health = clamped * 2.0;
        if (max.getBaseValue() != health) {
            max.setBaseValue(health);
        }
        if (!player.isDead() && player.getHealth() > max.getValue()) {
            player.setHealth(Math.max(0.0, max.getValue()));
        }
    }

    /**
     * Gives Heart items; what does not fit is dropped at the player's feet.
     *
     * @return how many were dropped
     */
    public int give(Player player, int amount) {
        int dropped = 0;
        for (ItemStack stack : items.createStacks(amount)) {
            Map<Integer, ItemStack> left = player.getInventory().addItem(stack);
            for (ItemStack rest : left.values()) {
                dropped += rest.getAmount();
                player.getWorld().dropItem(player.getLocation(), rest);
            }
        }
        return dropped;
    }

    /** Drops Heart items on the ground (a heart nobody could hold). */
    public void drop(Location location, int amount) {
        if (location.getWorld() == null) {
            return;
        }
        for (ItemStack stack : items.createStacks(amount)) {
            location.getWorld().dropItemNaturally(location, stack);
        }
    }
}
