package io.github.drepfy.lifesteal.heart;

import io.github.drepfy.lifesteal.config.Settings;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.logging.Logger;

/** The crafting recipe for a Heart (by default 6 diamond blocks, 2 netherite ingots and a nether star). */
public final class HeartRecipe {

    private final NamespacedKey key;
    private final HeartItems items;
    private final Logger logger;
    private boolean registered;

    public HeartRecipe(Plugin plugin, HeartItems items) {
        this.key = new NamespacedKey(plugin, "heart_recipe");
        this.items = items;
        this.logger = plugin.getLogger();
    }

    /** (Re)registers the recipe from the settings, or removes it when switched off. */
    public void apply(Settings settings) {
        unregister();
        Settings.Recipe config = settings.recipe();
        if (!config.enabled()) {
            return;
        }
        try {
            ShapedRecipe recipe = new ShapedRecipe(key, items.create(1));
            recipe.shape(config.shape().toArray(new String[0]));
            for (Map.Entry<Character, Material> ingredient : config.ingredients().entrySet()) {
                if (String.join("", config.shape()).indexOf(ingredient.getKey()) >= 0) {
                    recipe.setIngredient(ingredient.getKey(), ingredient.getValue());
                }
            }
            registered = Bukkit.addRecipe(recipe);
        } catch (RuntimeException e) {
            logger.warning("The Heart recipe could not be added: " + e.getMessage());
        }
    }

    public void unregister() {
        if (registered) {
            Bukkit.removeRecipe(key);
            registered = false;
        }
    }

    /** Shows the recipe in the player's recipe book. */
    public void discover(Player player) {
        if (registered) {
            player.discoverRecipe(key);
        }
    }

    public NamespacedKey key() {
        return key;
    }
}
