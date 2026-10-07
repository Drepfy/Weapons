package io.github.drepfy.legendary.ability;

import io.github.drepfy.legendary.Ability;
import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.util.Compat;
import org.bukkit.GameMode;
import org.bukkit.Material;
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
 * Shift + F (the swap-offhand key) uses the weapon's ability, or sneak + right-click, as
 * config.yml's controls say; plain F swaps hands as usual. Also passes sword and axe hits on
 * other players to the weapon, with the legendary hit bonus ({@code melee-damage}).
 */
public final class Abilities implements Listener {

    /** Right-clicking a block can fire two events; one use per this many ticks. */
    private static final long DOUBLE_CLICK_TICKS = 4;
    /** The attack bar (0 to 1) a hit needs to count as full strength. */
    static final double FULL_STRENGTH = 0.9;

    private final LegendaryPlugin plugin;
    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<WeaponType, Kit> kits = new EnumMap<>(WeaponType.class);
    private final Map<UUID, Long> lastUse = new HashMap<>();
    /** Players who died or changed world: their abilities are stopped at the start of the next tick. */
    private final java.util.Set<Player> toForget = new java.util.LinkedHashSet<>();
    /** The sword or axe hit being dealt right now, from the moment it is counted until it lands. */
    private Swing swing;

    public Abilities(LegendaryPlugin plugin) {
        this.plugin = plugin;
        for (Kit kit : List.of(new Katana(plugin), new CandyCane(plugin), new Crush(plugin), new Reaper(plugin))) {
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
        List<Listener> listeners = new java.util.ArrayList<>();
        for (Kit kit : kits.values()) {
            if (kit instanceof Listener listener) {
                listeners.add(listener);
            }
        }
        return listeners;
    }

    // ---- the keys -------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        Action action = event.getAction();
        if (event.getHand() != EquipmentSlot.HAND
                || (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK)
                || event.useItemInHand() == Event.Result.DENY) {
            return;
        }
        Player player = event.getPlayer();
        if (!plugin.settings().controls().rightClick() || !player.isSneaking()) {
            return;
        }
        WeaponItems.Tag tag = plugin.items().read(player.getInventory().getItemInMainHand());
        if (tag == null || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (usesOffhand(offhand)) {
            return; // Eating a golden apple, drinking, throwing a pearl... comes first.
        }
        if (action == Action.RIGHT_CLICK_BLOCK && offhand.getType().isBlock() && !offhand.getType().isAir()) {
            return; // Placing a block from the offhand.
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
        // With a shield, right-click also blocks: no complaint while the ability recharges.
        use(player, tag, offhand.getType() != Material.SHIELD);
    }

    /**
     * Shift + F with a legendary in the main hand: its ability (the weapon is not swapped into
     * the offhand). Plain F swaps hands as usual.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        if (!plugin.settings().controls().offhand() || !player.isSneaking()) {
            return;
        }
        WeaponItems.Tag tag = plugin.items().read(player.getInventory().getItemInMainHand());
        if (tag == null || player.getGameMode() == GameMode.SPECTATOR) {
            return;
        }
        event.setCancelled(true);
        if (!plugin.tracker().verify(player, tag)) {
            return;
        }
        use(player, tag, true);
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

    /** Uses the weapon's ability now, if it is ready. */
    public void use(Player player, WeaponItems.Tag tag) {
        use(player, tag, true);
    }

    private void use(Player player, WeaponItems.Tag tag, boolean complain) {
        long now = plugin.tick();
        Ability ability = tag.type().active();
        if (cooldowns.remaining(tag.id(), ability, now) > 0) {
            if (complain) {
                plugin.hud().shake(player, ability); // The boss bar shows how long is left.
            }
            return;
        }
        if (kits.get(tag.type()).use(player, tag) == Kit.Result.FIRED) {
            cooldowns.start(tag.id(), ability, now, plugin.settings().ability(ability).ticks("cooldown"));
        }
    }

    /** Ticks left on an ability's cooldown (0 = ready). */
    public long cooldown(WeaponItems.Tag tag, Ability ability, long now) {
        return cooldowns.remaining(tag.id(), ability, now);
    }

    /** Ticks an ability is still waiting for its hit (or its traps are still out). */
    public long active(Player player, WeaponItems.Tag tag, Ability ability, long now) {
        return ability == tag.type().active() ? kits.get(tag.type()).active(player, now) : 0;
    }

    /** How long an ability runs in all (ticks), for the boss bar; 0 when it is instant. */
    public long activeLength(WeaponItems.Tag tag, Ability ability) {
        return ability == tag.type().active() ? kits.get(tag.type()).activeLength() : 0;
    }

    public void tick(long now) {
        // Deaths and world changes can happen in the middle of a tick: what the player had
        // going is let go of here, between ticks.
        List<Player> leaving = new java.util.ArrayList<>(toForget);
        toForget.clear();
        leaving.forEach(this::forget);
        for (Kit kit : kits.values()) {
            try {
                kit.tick(now);
            } catch (RuntimeException e) {
                // One weapon's bug must not stop the others (or the rest of the plugin).
                plugin.reportError(kit.type().key() + " abilities", e);
            }
        }
        if (now % 1200 == 0) {
            cooldowns.prune(now);
            lastUse.values().removeIf(at -> now - at > DOUBLE_CLICK_TICKS);
        }
    }

    // ---- sword and axe hits ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMelee(EntityDamageByEntityEvent event) {
        swing = null;
        WeaponItems.Tag tag = melee(event);
        if (tag == null) {
            return;
        }
        Player attacker = (Player) event.getDamager();
        boolean charged = !plugin.settings().fullStrengthHits() || fullStrength(attacker, tag, event.getDamage());
        // Legendary hits are stronger than the bare netherite weapon (melee-damage in config.yml).
        double multiplier = plugin.settings().meleeDamage();
        if (multiplier != 1.0) {
            event.setDamage(event.getDamage() * multiplier);
        }
        if (event.getEntity() instanceof Player target && plugin.hits().canTarget(attacker, target)) {
            swing = new Swing(event, attacker, target, tag, charged);
            kits.get(tag.type()).melee(swing);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLanded(EntityDamageByEntityEvent event) {
        Swing hit = swing;
        swing = null;
        if (hit != null && hit.event() == event && event.getFinalDamage() > 0) {
            kits.get(hit.weapon().type()).landed(hit);
        }
    }

    /** The legendary used for this melee hit, or null when it is not one (or is extra damage). */
    private WeaponItems.Tag melee(EntityDamageByEntityEvent event) {
        if (plugin.hits().inAbility() || event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || !(event.getDamager() instanceof Player attacker)) {
            return null;
        }
        return plugin.items().read(attacker.getInventory().getItemInMainHand());
    }

    /**
     * Whether a hit was at full strength (the attack bar at 90% or more). The game has already
     * reset the bar when the hit is dealt, so it is worked out from the damage: a hit at charge
     * c does attack × (0.2 + 0.8 c²) + Sharpness × c (critical hits more).
     */
    private boolean fullStrength(Player attacker, WeaponItems.Tag tag, double damage) {
        io.github.drepfy.legendary.config.Settings.Look look = plugin.settings().look(tag.type());
        double attack = Compat.attackDamage(attacker);
        if (attack <= 0) {
            attack = tag.type().attackDamage();
        }
        double sharpness = WeaponItems.sharpnessDamage(look);
        double c = FULL_STRENGTH;
        return damage >= attack * (0.2 + 0.8 * c * c) + sharpness * c - 1.0E-6;
    }

    // ---- leaving --------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        forget(event.getPlayer());
        lastUse.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        toForget.add(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        toForget.add(event.getPlayer());
    }

    private void forget(Player player) {
        for (Kit kit : kits.values()) {
            kit.forget(player);
        }
    }
}
