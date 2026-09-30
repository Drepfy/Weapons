package io.github.drepfy.vigil.moderation;

import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.util.Text;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * {@code /punish <player>}: a chest menu with the preset reasons of every punishment
 * the staff member may give, each showing its time per offence and what the next
 * offence of this player would get. A click asks for confirmation; the second click
 * runs the normal command (so permissions, messages and escalation all apply).
 */
public final class PunishMenu implements Listener, CommandExecutor, TabCompleter {

    private static final int SIZE = 54;
    private static final int PER_PAGE = 36;
    private static final int FIRST_PRESET_SLOT = 9;
    private static final int HEAD_SLOT = 4;
    private static final int HISTORY_SLOT = 48;
    private static final int CLOSE_SLOT = 49;
    private static final int PREVIOUS_SLOT = 45;
    private static final int NEXT_SLOT = 53;
    private static final int LIFT_SLOT = 50;
    private static final long CONFIRM_MS = 5000;

    /** Tabs, in order: the punishment type, its tab slot, command and look. */
    private enum Tab {
        BAN(PunishmentType.BAN, 1, "ban", "vigil.ban", Material.RED_WOOL, Material.RED_CONCRETE, "&c"),
        MUTE(PunishmentType.MUTE, 2, "mute", "vigil.mute", Material.ORANGE_WOOL, Material.ORANGE_CONCRETE, "&6"),
        WARN(PunishmentType.WARN, 6, "warn", "vigil.warn", Material.YELLOW_WOOL, Material.YELLOW_CONCRETE, "&e"),
        KICK(PunishmentType.KICK, 7, "kick", "vigil.kick", Material.LIGHT_BLUE_WOOL, Material.LIGHT_BLUE_CONCRETE, "&b");

        final PunishmentType type;
        final int slot;
        final String command;
        final String permission;
        final Material icon;
        final Material item;
        final String color;

        Tab(PunishmentType type, int slot, String command, String permission, Material icon, Material item,
            String color) {
            this.type = type;
            this.slot = slot;
            this.command = command;
            this.permission = permission;
            this.icon = icon;
            this.item = item;
            this.color = color;
        }
    }

    /** One open menu. */
    private static final class Menu implements InventoryHolder {
        final UUID target;
        final String targetName;
        Tab tab;
        int page;
        int pendingSlot = -1;
        long pendingSinceMs;
        Inventory inventory;

        Menu(UUID target, String targetName, Tab tab) {
            this.target = target;
            this.targetName = targetName;
            this.tab = tab;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private final Plugin plugin;
    private final Supplier<Settings> settings;
    private final ModerationService service;

    public PunishMenu(Plugin plugin, Supplier<Settings> settings, ModerationService service) {
        this.plugin = plugin;
        this.settings = settings;
        this.service = service;
    }

    // ---- command ------------------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        Settings.Messages messages = settings.get().messages();
        if (!(sender instanceof Player staff)) {
            sender.sendMessage(Text.color(messages.get("prefix") + "&7The menu is for players; use /ban, /mute..."));
            return true;
        }
        Tab first = firstAllowed(staff);
        if (first == null) {
            staff.sendMessage(Text.color(messages.get("prefix") + messages.get("no-permission")));
            return true;
        }
        if (args.length < 1) {
            staff.sendMessage(Text.color(messages.get("prefix") + "&cUsage: /" + label + " <player>"));
            return true;
        }
        OfflinePlayer target = find(args[0]);
        if (target == null) {
            staff.sendMessage(Text.color(messages.get("prefix")
                    + Text.replace(messages.get("player-not-found"), "player", args[0])));
            return true;
        }
        String name = target.getName() != null ? target.getName() : args[0];
        Menu menu = new Menu(target.getUniqueId(), name, first);
        menu.inventory = Bukkit.createInventory(menu, SIZE, Text.color("&8Punish &0" + name));
        render(staff, menu);
        staff.openInventory(menu.inventory);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> names = new ArrayList<>();
        if (args.length == 1 && firstAllowed(sender) != null) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            for (Player player : Bukkit.getOnlinePlayers()) {
                if (player.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    names.add(player.getName());
                }
            }
        }
        return names;
    }

    // ---- clicks -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player staff) || event.getRawSlot() < 0 || event.getRawSlot() >= SIZE) {
            return;
        }
        int slot = event.getRawSlot();
        for (Tab tab : Tab.values()) {
            if (slot == tab.slot && staff.hasPermission(tab.permission)) {
                menu.tab = tab;
                menu.page = 0;
                menu.pendingSlot = -1;
                render(staff, menu);
                return;
            }
        }
        List<ReasonPreset> presets = settings.get().moderation().reasons(menu.tab.type);
        if (slot == CLOSE_SLOT) {
            staff.closeInventory();
        } else if (slot == PREVIOUS_SLOT && menu.page > 0) {
            menu.page--;
            menu.pendingSlot = -1;
            render(staff, menu);
        } else if (slot == NEXT_SLOT && (menu.page + 1) * PER_PAGE < presets.size()) {
            menu.page++;
            menu.pendingSlot = -1;
            render(staff, menu);
        } else if (slot == LIFT_SLOT) {
            lift(staff, menu);
        } else if (slot >= FIRST_PRESET_SLOT && slot < FIRST_PRESET_SLOT + PER_PAGE) {
            int index = menu.page * PER_PAGE + slot - FIRST_PRESET_SLOT;
            if (index < presets.size()) {
                choose(staff, menu, slot, presets.get(index));
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    private void choose(Player staff, Menu menu, int slot, ReasonPreset preset) {
        long now = System.currentTimeMillis();
        if (menu.pendingSlot != slot || now - menu.pendingSinceMs > CONFIRM_MS) {
            menu.pendingSlot = slot;
            menu.pendingSinceMs = now;
            render(staff, menu);
            return;
        }
        menu.pendingSlot = -1;
        staff.closeInventory();
        String command = menu.tab.command + " " + menu.targetName + " " + preset.name();
        // Next tick: never run commands from inside an inventory event.
        Bukkit.getScheduler().runTask(plugin, () -> staff.performCommand(command));
    }

    private void lift(Player staff, Menu menu) {
        boolean ban = menu.tab == Tab.BAN;
        boolean mute = menu.tab == Tab.MUTE;
        if ((ban && service.activeBan(menu.target) != null) || (mute && service.activeMute(menu.target) != null)) {
            staff.closeInventory();
            String command = (ban ? "unban " : "unmute ") + menu.targetName;
            Bukkit.getScheduler().runTask(plugin, () -> staff.performCommand(command));
        }
    }

    // ---- drawing ------------------------------------------------------------------------------

    private void render(Player staff, Menu menu) {
        Inventory inventory = menu.inventory;
        inventory.clear();
        Settings config = settings.get();
        String permanent = config.messages().get("permanent");
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            if (slot < FIRST_PRESET_SLOT || slot >= FIRST_PRESET_SLOT + PER_PAGE) {
                inventory.setItem(slot, filler);
            }
        }
        inventory.setItem(HEAD_SLOT, head(menu));
        for (Tab tab : Tab.values()) {
            if (staff.hasPermission(tab.permission)) {
                String name = (tab == menu.tab ? tab.color + "&l▶ " : tab.color) + tab.name();
                inventory.setItem(tab.slot, item(tab.icon, name, List.of(tab == menu.tab ? "&7Selected"
                        : "&eClick to show " + tab.name().toLowerCase(Locale.ROOT) + " reasons")));
            }
        }

        List<ReasonPreset> presets = config.moderation().reasons(menu.tab.type);
        boolean online = Bukkit.getPlayer(menu.target) != null;
        for (int i = 0; i < PER_PAGE; i++) {
            int index = menu.page * PER_PAGE + i;
            if (index >= presets.size()) {
                break;
            }
            ReasonPreset preset = presets.get(index);
            int slot = FIRST_PRESET_SLOT + i;
            inventory.setItem(slot, presetItem(menu, preset, slot, permanent, online));
        }

        if (menu.page > 0) {
            inventory.setItem(PREVIOUS_SLOT, item(Material.ARROW, "&fPrevious page", List.of()));
        }
        if ((menu.page + 1) * PER_PAGE < presets.size()) {
            inventory.setItem(NEXT_SLOT, item(Material.ARROW, "&fNext page", List.of()));
        }
        inventory.setItem(CLOSE_SLOT, item(Material.BARRIER, "&cClose", List.of()));
        inventory.setItem(HISTORY_SLOT, history(menu, permanent));
        if (menu.tab == Tab.BAN && service.activeBan(menu.target) != null) {
            inventory.setItem(LIFT_SLOT, item(Material.LIME_DYE, "&aUnban " + menu.targetName,
                    List.of("&7Lift the current ban", "&eClick to unban")));
        } else if (menu.tab == Tab.MUTE && service.activeMute(menu.target) != null) {
            inventory.setItem(LIFT_SLOT, item(Material.LIME_DYE, "&aUnmute " + menu.targetName,
                    List.of("&7Lift the current mute", "&eClick to unmute")));
        }
    }

    private ItemStack presetItem(Menu menu, ReasonPreset preset, int slot, String permanent, boolean online) {
        Tab tab = menu.tab;
        String verb = tab.name().toLowerCase(Locale.ROOT);
        List<String> lore = new ArrayList<>();
        String next = "";
        if (tab == Tab.BAN || tab == Tab.MUTE) {
            if (preset.durations().isEmpty()) {
                lore.add("&7Time: &fdefault");
            } else {
                lore.add("&7Times: &f" + preset.ladderText(permanent).replace("→", "&8→&f"));
                int previous = service.previousOffences(menu.target, tab.type, preset.display(), Integer.MAX_VALUE);
                Long duration = preset.durationFor(previous);
                next = Durations.format(duration, permanent);
                lore.add("&7Next for " + menu.targetName + ": &c" + next + " &8("
                        + ReasonPreset.ordinal(previous + 1) + " offence)");
            }
        }
        if (tab == Tab.KICK && !online) {
            return item(Material.GRAY_DYE, "&7" + preset.display(), List.of("&c" + menu.targetName + " is offline"));
        }
        if (menu.pendingSlot == slot) {
            List<String> confirm = new ArrayList<>();
            confirm.add("&7" + capitalize(verb) + " &f" + menu.targetName + " &7for &f" + preset.display()
                    + (next.isEmpty() ? "" : " &7for &f" + next));
            confirm.add("");
            confirm.add("&c&lClick again to confirm");
            return item(Material.TNT, "&c&lCONFIRM " + verb.toUpperCase(Locale.ROOT), confirm);
        }
        lore.add("");
        lore.add("&eClick to " + verb + " " + menu.targetName);
        return item(tab.item, tab.color + preset.display(), lore);
    }

    private ItemStack head(Menu menu) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = head.getItemMeta();
        if (meta instanceof SkullMeta skull) {
            try {
                skull.setOwningPlayer(Bukkit.getOfflinePlayer(menu.target));
            } catch (RuntimeException ignored) {
                // Only the skin; the menu works without it.
            }
        }
        List<String> lore = new ArrayList<>();
        Punishment ban = service.activeBan(menu.target);
        Punishment mute = service.activeMute(menu.target);
        lore.add("&7Online: " + (Bukkit.getPlayer(menu.target) != null ? "&ayes" : "&cno"));
        lore.add("&7Banned: " + (ban != null ? "&cyes &8(" + ban.reason() + ")" : "&ano"));
        lore.add("&7Muted: " + (mute != null ? "&cyes &8(" + mute.reason() + ")" : "&ano"));
        lore.add("&7Warnings: &f" + service.warningCount(menu.target));
        if (meta != null) {
            meta.setDisplayName(Text.color("&f&l" + menu.targetName));
            meta.setLore(color(lore));
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack history(Menu menu, String permanent) {
        List<Punishment> history = service.history(menu.target);
        List<String> lore = new ArrayList<>();
        if (history.isEmpty()) {
            lore.add("&aNo punishments");
        }
        long now = System.currentTimeMillis();
        for (Punishment p : history.subList(0, Math.min(8, history.size()))) {
            String length = p.type() == PunishmentType.BAN || p.type() == PunishmentType.MUTE
                    ? " &8(" + Durations.format(p.durationMs(), permanent) + ")" : "";
            lore.add("&8#" + p.id() + " &e" + p.type().name().toLowerCase(Locale.ROOT) + " &f" + p.reason() + length
                    + " &8" + Text.duration(now - p.createdEpochMs()) + " ago");
        }
        if (history.size() > 8) {
            lore.add("&7... and " + (history.size() - 8) + " more (/ac check " + menu.targetName + ")");
        }
        return item(Material.BOOK, "&fHistory of " + menu.targetName, lore);
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(Text.color(name));
            meta.setLore(color(lore));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private static List<String> color(List<String> lines) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(Text.color(line));
        }
        return result;
    }

    private static String capitalize(String text) {
        return text.isEmpty() ? text : Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static Tab firstAllowed(CommandSender sender) {
        for (Tab tab : Tab.values()) {
            if (sender.hasPermission(tab.permission)) {
                return tab;
            }
        }
        return null;
    }

    /** An online player, a UUID, or a player who joined before (no web lookup). */
    private static OfflinePlayer find(String input) {
        Player online = Bukkit.getPlayerExact(input);
        if (online != null) {
            return online;
        }
        try {
            return Bukkit.getOfflinePlayer(UUID.fromString(input));
        } catch (IllegalArgumentException ignored) {
            // Not a UUID.
        }
        for (OfflinePlayer offline : Bukkit.getOfflinePlayers()) {
            if (offline.getName() != null && offline.getName().equalsIgnoreCase(input)) {
                return offline;
            }
        }
        return null;
    }
}
