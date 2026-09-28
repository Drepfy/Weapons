package com.drepfy.staffvanish;

import com.drepfy.staffvanish.selector.SelectorAction;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.jspecify.annotations.Nullable;

/** The vanish configuration, parsed and validated once per (re)load. */
public record VanishSettings(
        boolean flight,
        boolean fakeJoinLeaveMessages,
        boolean showInTabForStaff,
        String tabListFormat,
        boolean hideFromServerList,
        boolean actionBarReminder,
        int refreshIntervalTicks,
        Protection protection,
        Selector selector
) {

    public record Protection(
            boolean invulnerable,
            boolean noHunger,
            boolean noItemPickup,
            boolean noPhysicalInteraction,
            boolean noMobTargeting,
            boolean noSculkDetection,
            boolean projectilesPassThrough,
            boolean blockChat,
            Set<String> privateMessageCommands
    ) {
    }

    public record Selector(
            boolean enabled,
            int slot,
            Material material,
            String name,
            List<String> lore,
            boolean glint,
            @Nullable NamespacedKey itemModel,
            Actions actions,
            int inspectRange,
            Menu menu
    ) {
    }

    public record Actions(
            SelectorAction rightClick,
            SelectorAction sneakRightClick,
            SelectorAction leftClick,
            SelectorAction sneakLeftClick,
            SelectorAction clickPlayer
    ) {
    }

    public record Menu(
            String title,
            int rows,
            boolean showVanished,
            String headName,
            String vanishedTag,
            List<String> headLore,
            String previousPage,
            String nextPage,
            String close,
            String pageInfo
    ) {
    }

    /**
     * Reads the settings from the root of the plugin config. Missing keys fall back to the bundled defaults and
     * invalid values are replaced with safe ones, with a warning.
     */
    public static VanishSettings load(ConfigurationSection config, Logger logger) {
        return new VanishSettings(
                config.getBoolean("vanish.flight"),
                config.getBoolean("vanish.fake-join-leave-messages"),
                config.getBoolean("vanish.show-in-tab-for-staff"),
                string(config, "vanish.tab-list-format"),
                config.getBoolean("vanish.hide-from-server-list"),
                config.getBoolean("vanish.action-bar-reminder"),
                Math.max(0, config.getInt("vanish.refresh-interval-ticks")),
                new Protection(
                        config.getBoolean("vanish.protection.invulnerable"),
                        config.getBoolean("vanish.protection.no-hunger"),
                        config.getBoolean("vanish.protection.no-item-pickup"),
                        config.getBoolean("vanish.protection.no-physical-interaction"),
                        config.getBoolean("vanish.protection.no-mob-targeting"),
                        config.getBoolean("vanish.protection.no-sculk-detection"),
                        config.getBoolean("vanish.protection.projectiles-pass-through"),
                        config.getBoolean("vanish.protection.block-chat"),
                        config.getStringList("vanish.protection.private-message-commands").stream()
                                .map(command -> command.trim().toLowerCase(Locale.ROOT))
                                .collect(Collectors.toUnmodifiableSet())
                ),
                loadSelector(config, logger)
        );
    }

    private static Selector loadSelector(ConfigurationSection config, Logger logger) {
        int slot = config.getInt("selector.slot");
        if (slot < -1 || slot > 8) {
            logger.warning("selector.slot must be between -1 and 8, but is " + slot + ". Using no preferred slot.");
            slot = -1;
        }
        return new Selector(
                config.getBoolean("selector.enabled"),
                slot,
                material(string(config, "selector.item.material"), logger),
                string(config, "selector.item.name"),
                config.getStringList("selector.item.lore"),
                config.getBoolean("selector.item.glint"),
                itemModel(string(config, "selector.item.item-model"), logger),
                new Actions(
                        action(config, "selector.actions.right-click", SelectorAction.OPEN_MENU, logger),
                        action(config, "selector.actions.sneak-right-click", SelectorAction.OPEN_MENU, logger),
                        action(config, "selector.actions.left-click", SelectorAction.NEXT_PLAYER, logger),
                        action(config, "selector.actions.sneak-left-click", SelectorAction.RANDOM_PLAYER, logger),
                        action(config, "selector.actions.click-player", SelectorAction.INSPECT, logger)
                ),
                Math.max(1, config.getInt("selector.inspect-range")),
                new Menu(
                        string(config, "selector.menu.title"),
                        Math.clamp(config.getInt("selector.menu.rows"), 2, 6),
                        config.getBoolean("selector.menu.show-vanished"),
                        string(config, "selector.menu.head.name"),
                        string(config, "selector.menu.head.vanished-tag"),
                        config.getStringList("selector.menu.head.lore"),
                        string(config, "selector.menu.previous-page"),
                        string(config, "selector.menu.next-page"),
                        string(config, "selector.menu.close"),
                        string(config, "selector.menu.page-info")
                )
        );
    }

    private static Material material(String raw, Logger logger) {
        Material material = Material.matchMaterial(raw);
        if (material == null || !material.isItem() || material.isAir()) {
            logger.warning("selector.item.material '" + raw + "' is not an item. Using BREEZE_ROD.");
            return Material.BREEZE_ROD;
        }
        return material;
    }

    private static @Nullable NamespacedKey itemModel(String raw, Logger logger) {
        if (raw.isBlank()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(raw.trim());
        if (key == null) {
            logger.warning("selector.item.item-model '" + raw + "' is not a valid namespaced key. Ignoring it.");
        }
        return key;
    }

    /**
     * Reads a string, falling back to the bundled default. (Passing a default to {@code getString} would skip the
     * bundled one, so options added in an update would come out empty for existing configs.)
     */
    private static String string(ConfigurationSection config, String path) {
        return Objects.requireNonNullElse(config.getString(path), "");
    }

    private static SelectorAction action(ConfigurationSection config, String path, SelectorAction fallback,
            Logger logger) {
        String raw = Objects.requireNonNullElse(config.getString(path), fallback.name());
        return SelectorAction.parse(raw, fallback, logger, path);
    }
}
