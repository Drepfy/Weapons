package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.Text;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F (the swap-offhand key) uses a weapon's first ability and Shift + F its second; or
 * right-click and sneak + right-click, as config.yml's controls say. Also passes sword and axe
 * hits on to the passives.
 */
public final class Abilities implements Listener {

    /** Right-clicking a block can fire two events; one use per this many ticks. */
    private static final long DOUBLE_CLICK_TICKS = 4;

    private final LegendaryPlugin plugin;
    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<WeaponType, Kit> kits = new EnumMap<>(WeaponType.class);
    private final Map<UUID, Long> lastUse = new HashMap<>();

    public Abilities(LegendaryPlugin plugin) {
        this.plugin = plugin;
        for (Kit kit : List.of(new Kurogane(plugin), new Sugarcrash(plugin), new Riftblade(plugin),
                new Gravebreaker(plugin), new Starforged(plugin))) {
            kits.put(kit.type(), kit);
        }
    }

    public Cooldowns cooldowns() {
        return cooldowns;
    }

    Kit kit(WeaponType type) {
        return kits.get(type);
    }

    /** Listeners some kits need of their own. */
    public List<Listener> extraListeners() {
        return List.of((Listener) kits.get(WeaponType.STARFORGED));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (event.getHand() != EquipmentSlot.HAND
                || (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
                || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        if (!plugin.settings().controls().rightClick()) {
            return;
        }
        Player player = event.getPlayer();
        WeaponItems.Tag tag = plugin.items().read(player.getInventory().getItemInMainHand());
        if (tag == null || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (usesOffhand(offhand)) {
            return; // Eating a golden apple, drinking, throwing a pearl... comes first.
        }
        if (action == Action.RIGHT_CLICK_BLOCK) {
            Block block = event.getClickedBlock();
            if (block != null && !player.isSneaking() && block.getType().isInteractable()) {
                return; // Doors, chests, buttons... work as usual.
            }
            if (offhand.getType().isBlock() && !offhand.getType().isAir()) {
                return; // Placing a block from the offhand.
            }
        }
        // Axes would strip logs or scrape copper.
        event.setUseItemInHand(Event.Result.DENY);
        long now = plugin.tick();
        Long last = lastUse.get(player.getUniqueId());
        if (last != null && now - last < DOUBLE_CLICK_TICKS && now >= last) {
            return;
        }
        lastUse.put(player.getUniqueId(), now);
        if (!plugin.tracker().verify(player, tag)) {
            return;
        }
        Ability ability = player.isSneaking() ? tag.type().secondary() : tag.type().primary();
        // With a shield, right-click also blocks: no complaint while the ability recharges.
        use(player, tag, ability, offhand.getType() != Material.SHIELD);
    }

    /**
     * F with a legendary in the main hand: its first ability (Shift + F the second). The weapon is
     * not swapped into the offhand. A legendary held in the offhand swaps back as usual.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (!plugin.settings().controls().offhand()) {
            return;
        }
        Player player = event.getPlayer();
        WeaponItems.Tag tag = plugin.items().read(player.getInventory().getItemInMainHand());
        if (tag == null || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        event.setCancelled(true);
        if (!plugin.tracker().verify(player, tag)) {
            return;
        }
        use(player, tag, player.isSneaking() ? tag.type().secondary() : tag.type().primary(), true);
    }

    /** Offhand items that right-click uses instead of the weapon. */
    static boolean usesOffhand(ItemStack offhand) {
        Material type = offhand.getType();
        String name = type.name();
        return type.isEdible() || name.endsWith("POTION") || name.endsWith("BUCKET") || name.equals("BOW")
                || name.equals("CROSSBOW") || name.equals("TRIDENT") || name.equals("ENDER_PEARL")
                || name.equals("SNOWBALL") || name.equals("EGG") || name.equals("WIND_CHARGE")
                || name.equals("FISHING_ROD") || name.equals("FIREWORK_ROCKET") || name.equals("EXPERIENCE_BOTTLE")
                || name.equals("ENDER_EYE") || name.equals("GOAT_HORN");
    }

    /** Uses an ability now, if it is ready. */
    public void use(Player player, WeaponItems.Tag tag, Ability ability) {
        use(player, tag, ability, true);
    }

    private void use(Player player, WeaponItems.Tag tag, Ability ability, boolean complain) {
        long now = plugin.tick();
        String name = plugin.settings().ability(ability).name();
        long left = cooldowns.remaining(tag.id(), ability, now);
        if (left > 0) {
            if (!complain) {
                return;
            }
            plugin.hud().flash(player, Text.format(plugin.settings().message("on-cooldown"), "ability", name,
                    "time", Text.countdown(left)));
            plugin.fx().soundTo(player, "cooldown");
            return;
        }
        Kit.Result result = kits.get(tag.type()).use(player, tag, ability);
        if (result == Kit.Result.FIRED) {
            cooldowns.start(tag.id(), ability, now, plugin.settings().ability(ability).ticks("cooldown"));
        }
    }

    /** Ticks left on an ability's cooldown (0 = ready). */
    public long cooldown(WeaponItems.Tag tag, Ability ability, long now) {
        return cooldowns.remaining(tag.id(), ability, now);
    }

    /** Ticks an ability is still running for (Sugar Rush, a rift mark, a gravity well). */
    public long active(Player player, WeaponItems.Tag tag, Ability ability, long now) {
        return kits.get(tag.type()).active(player, tag, ability, now);
    }

    public void hudParts(Player player, WeaponItems.Tag tag, List<String> parts, long now) {
        kits.get(tag.type()).hud(player, tag, parts, now);
    }

    public void tick(long now) {
        for (Kit kit : kits.values()) {
            kit.tick(now);
        }
        if (now % 1200 == 0) {
            cooldowns.prune(now);
            lastUse.values().removeIf(at -> now - at > DOUBLE_CLICK_TICKS);
        }
    }

    // ---- sword and axe hits (passives) -----------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        WeaponItems.Tag tag = melee(event);
        if (tag != null) {
            kits.get(tag.type()).melee(event, (Player) event.getDamager(), (LivingEntity) event.getEntity(), tag);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLanded(EntityDamageByEntityEvent event) {
        WeaponItems.Tag tag = melee(event);
        if (tag != null && event.getFinalDamage() > 0) {
            kits.get(tag.type()).landed((Player) event.getDamager(), (LivingEntity) event.getEntity(), tag);
        }
    }

    /** The legendary used for this melee hit, or null when it is not one (or is ability damage). */
    private WeaponItems.Tag melee(EntityDamageByEntityEvent event) {
        if (plugin.hits().inAbility() || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity)) {
            return null;
        }
        return plugin.items().read(attacker.getInventory().getItemInMainHand());
    }

    // ---- leaving --------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer());
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        forget(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        forget(event.getPlayer());
    }

    private void forget(Player player) {
        for (Kit kit : kits.values()) {
            kit.forget(player);
        }
    }
}
