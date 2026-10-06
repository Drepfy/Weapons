package io.github.drepfy.legendary.guard;

import io.github.drepfy.legendary.LegendaryPlugin;
import io.github.drepfy.legendary.WeaponType;
import io.github.drepfy.legendary.item.WeaponItems;
import io.github.drepfy.legendary.registry.WeaponRecord;
import io.github.drepfy.legendary.registry.WeaponRegistry;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemDespawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.util.Vector;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Knows where every legendary is and makes sure there is only ever one of each copy.
 *
 * <p>Whenever a legendary is seen (in an inventory, on the ground, being picked up), it is
 * compared with the registry. Two copies of the same weapon that can be seen at the same time
 * are a duplicate: the one the registry does not expect is deleted. A copy seen somewhere new
 * is only accepted when its old place can be checked and is empty, or cannot be checked at all
 * (an offline player, an unloaded chunk); the other copy is then deleted as soon as it can be
 * seen. Nothing is ever deleted on a guess.
 */
public final class Tracker implements Listener {

    private final LegendaryPlugin plugin;
    /** Who was refused a pickup recently (pickups are retried every tick). */
    private final Map<String, Long> recentRefusals = new HashMap<>();
    /** Weapons not seen where the registry expects them, and since when (ms). */
    private final Map<UUID, Long> missingSince = new HashMap<>();
    /** How long a weapon may be out of sight before it counts as lost (a creative cursor, entities loading). */
    private static final long LOST_AFTER_MS = 5000;

    public Tracker(LegendaryPlugin plugin) {
        this.plugin = plugin;
    }

    private WeaponItems items() {
        return plugin.items();
    }

    private WeaponRegistry registry() {
        return plugin.registry();
    }

    // ---- where a legendary can be inside a player's reach ----------------------------------------------

    /** One place a legendary sits in: an inventory slot or the cursor. */
    private record Spot(Player player, Inventory inventory, int slot, ItemStack item, WeaponItems.Tag tag) {
        void clear() {
            if (inventory == null) {
                player.setItemOnCursor(null);
            } else {
                inventory.setItem(slot, null);
            }
        }
    }

    /** Every legendary in the player's inventory, cursor and own crafting grid. */
    private List<Spot> spots(Player player) {
        List<Spot> spots = new ArrayList<>();
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            add(spots, player, inventory, slot, inventory.getItem(slot));
        }
        add(spots, player, null, -1, player.getItemOnCursor());
        Inventory top = topInventory(player);
        if (top != null && top.getType() == InventoryType.CRAFTING) {
            for (int slot = 0; slot < top.getSize(); slot++) {
                add(spots, player, top, slot, top.getItem(slot));
            }
        }
        return spots;
    }

    private void add(List<Spot> spots, Player player, Inventory inventory, int slot, ItemStack item) {
        WeaponItems.Tag tag = items().read(item);
        if (tag != null) {
            spots.add(new Spot(player, inventory, slot, item, tag));
        }
    }

    static Inventory topInventory(Player player) {
        try {
            InventoryView view = player.getOpenInventory();
            return view == null ? null : view.getTopInventory();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether the player carries this copy anywhere (inventory, cursor, crafting grid). */
    public boolean carries(Player player, UUID id) {
        for (Spot spot : spots(player)) {
            if (spot.tag().id().equals(id)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the player carries any legendary. */
    public boolean carriesAny(Player player) {
        return !spots(player).isEmpty();
    }

    /**
     * Deletes the matching legendaries from a player's inventory, cursor and crafting grid.
     *
     * @return what was taken
     */
    public List<WeaponItems.Tag> takeFrom(Player player, java.util.function.Predicate<WeaponItems.Tag> which) {
        List<WeaponItems.Tag> taken = new ArrayList<>();
        for (Spot spot : spots(player)) {
            if (which.test(spot.tag())) {
                spot.clear();
                taken.add(spot.tag());
            }
        }
        return taken;
    }

    /** The legendaries in a player's inventory (for /legendary inspect). */
    public List<WeaponItems.Tag> carried(Player player) {
        List<WeaponItems.Tag> tags = new ArrayList<>();
        for (Spot spot : spots(player)) {
            tags.add(spot.tag());
        }
        return tags;
    }

    // ---- the check --------------------------------------------------------------------------------------

    /** Looks at every online player and puts the registry and the world in agreement. */
    public void scan() {
        Map<UUID, List<Spot>> seen = new LinkedHashMap<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Inventory top = topInventory(player);
            if (top != null && isForeign(player, top)) {
                takeOut(top, player);
            }
            for (Spot spot : spots(player)) {
                seen.computeIfAbsent(spot.tag().id(), key -> new ArrayList<>()).add(spot);
            }
        }
        for (Map.Entry<UUID, List<Spot>> copies : seen.entrySet()) {
            settle(copies.getKey(), copies.getValue());
        }
        for (WeaponRecord record : new ArrayList<>(registry().all())) {
            if (record.state() != WeaponRecord.State.HELD || record.holder() == null) {
                continue;
            }
            Player holder = Bukkit.getPlayer(record.holder());
            if (seen.containsKey(record.id()) || holder == null || !holder.isOnline()) {
                missingSince.remove(record.id());
                continue;
            }
            if (!plugin.settings().markLost()) {
                continue; // Off: it stays registered to its holder until staff remove it.
            }
            if (missingFor(record.id()) >= LOST_AFTER_MS) {
                missingSince.remove(record.id());
                registry().lost(record, "not in " + holder.getName() + "'s inventory");
                plugin.alert(plugin.items().displayName(record.type()) + " #" + WeaponItems.shortId(record.id())
                        + " is no longer in " + holder.getName() + "'s inventory and is marked as lost. "
                        + "It can be given out again.");
            }
        }
    }

    /** Milliseconds since this weapon was first missed (0 the first time). */
    private long missingFor(UUID id) {
        long now = plugin.now();
        Long since = missingSince.putIfAbsent(id, now);
        return since == null ? 0 : now - since;
    }

    /** All copies of one weapon that are in players' hands right now. */
    private void settle(UUID id, List<Spot> copies) {
        WeaponItems.Tag tag = copies.get(0).tag();
        WeaponRecord record = registry().get(id);
        if (record == null) {
            if (!canAdopt(tag.type())) {
                for (Spot spot : copies) {
                    delete(spot, "copy-removed", "a copy of " + name(tag) + " that was never given out (another "
                            + tag.type().key() + " already exists)");
                }
                return;
            }
            record = registry().adopt(id, tag.type());
            registry().log("FOUND " + tag.type().key() + " #" + tag.shortId() + " with " + copies.get(0).player().getName()
                    + " (not in data.yml; registered)");
        }
        if (record.state() == WeaponRecord.State.REMOVED || record.type() != tag.type()) {
            for (Spot spot : copies) {
                delete(spot, "revoked", null);
                registry().log("DELETED " + tag.type().key() + " #" + tag.shortId() + " from " + spot.player().getName()
                        + " (" + (record.note() == null ? "removed" : record.note()) + ")");
            }
            return;
        }
        Spot keep = copies.get(0);
        for (Spot spot : copies) {
            if (spot.item().getType() != tag.type().material()) {
                continue;
            }
            if (record.state() == WeaponRecord.State.HELD && spot.player().getUniqueId().equals(record.holder())) {
                keep = spot;
                break;
            }
        }
        for (Spot spot : copies) {
            if (spot != keep) {
                delete(spot, "copy-removed", "a duplicate of " + name(tag) + " from " + spot.player().getName());
            }
        }
        if (keep.item().getType() != tag.type().material()) {
            delete(keep, "copy-removed", "a forged " + name(tag) + " from " + keep.player().getName());
            return;
        }
        Player player = keep.player();
        boolean moved = record.state() != WeaponRecord.State.HELD || !player.getUniqueId().equals(record.holder());
        if (moved && stillAtOldPlace(record, player)) {
            delete(keep, "copy-removed", "a duplicate of " + name(tag) + " from " + player.getName()
                    + " (the original is " + where(record) + ")");
            return;
        }
        if (moved) {
            registry().log("MOVED " + tag.type().key() + " #" + tag.shortId() + " to " + player.getName()
                    + " (was " + where(record) + ")");
        }
        registry().held(record, player.getUniqueId(), player.getName(), player.getLocation());
        if (items().refresh(keep.item()) && keep.inventory() != null) {
            keep.inventory().setItem(keep.slot(), keep.item());
        }
    }

    /**
     * Whether the copy the registry knows about can be seen where it should be. Only places
     * that can be checked count: an offline holder or an unloaded item cannot be seen.
     */
    private boolean stillAtOldPlace(WeaponRecord record, Player newHolder) {
        if (record.state() == WeaponRecord.State.HELD && record.holder() != null
                && !record.holder().equals(newHolder.getUniqueId())) {
            Player old = Bukkit.getPlayer(record.holder());
            return old != null && old.isOnline() && carries(old, record.id());
        }
        if (record.state() == WeaponRecord.State.GROUND && record.entity() != null) {
            Entity entity = Bukkit.getEntity(record.entity());
            if (entity instanceof Item item && item.isValid()) {
                WeaponItems.Tag tag = items().read(item.getItemStack());
                return tag != null && tag.id().equals(record.id());
            }
        }
        return false;
    }

    /** A weapon nobody registered may be adopted unless that would make a second one of its kind. */
    private boolean canAdopt(WeaponType type) {
        return !plugin.settings().oneOfEach() || registry().existing(type).isEmpty();
    }

    /**
     * Quick check before an ability is used: is this the real copy?
     *
     * @return false when it is not (it has then been dealt with)
     */
    public boolean verify(Player player, WeaponItems.Tag tag) {
        WeaponRecord record = registry().get(tag.id());
        if (record != null && record.state() == WeaponRecord.State.HELD
                && player.getUniqueId().equals(record.holder()) && record.type() == tag.type()) {
            return true;
        }
        scan();
        record = registry().get(tag.id());
        return record != null && record.state() == WeaponRecord.State.HELD
                && player.getUniqueId().equals(record.holder()) && carries(player, tag.id());
    }

    private void delete(Spot spot, String messageKey, String alert) {
        spot.clear();
        plugin.send(spot.player(), messageKey, "weapon", name(spot.tag()));
        if (alert != null) {
            plugin.alert("Deleted " + alert + ".");
        }
    }

    private String name(WeaponItems.Tag tag) {
        return items().displayName(tag.type());
    }

    /** "held by Steve (online)", "on the ground at world 10 64 -3". */
    public String where(WeaponRecord record) {
        return switch (record.state()) {
            case HELD -> {
                Player holder = record.holder() == null ? null : Bukkit.getPlayer(record.holder());
                yield "held by " + record.holderName() + (holder != null && holder.isOnline() ? " (online)" : " (offline)");
            }
            case GROUND -> "on the ground at " + record.world() + " " + Math.round(record.x()) + " "
                    + Math.round(record.y()) + " " + Math.round(record.z());
            case LOST -> "lost" + (record.note() == null ? "" : " (" + record.note() + ")");
            case REMOVED -> "removed" + (record.note() == null ? "" : " (" + record.note() + ")");
        };
    }

    // ---- storage --------------------------------------------------------------------------------------

    /** Not the player's own inventory or crafting grid. */
    static boolean isForeign(Player player, Inventory inventory) {
        if (inventory == null) {
            return false;
        }
        InventoryType type = inventory.getType();
        if (type == InventoryType.CRAFTING || type == InventoryType.CREATIVE) {
            return false;
        }
        return !(inventory instanceof PlayerInventory own) || !player.equals(own.getHolder());
    }

    /**
     * Legendaries found in a container (put there before this plugin was installed, or through
     * a bug) are taken out and given to the player who opened it. Another player's inventory or
     * ender chest (staff using /invsee) is left alone.
     */
    public void takeOut(Inventory inventory, Player player) {
        if (inventory.getHolder() instanceof org.bukkit.entity.HumanEntity owner && !owner.equals(player)) {
            return;
        }
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            WeaponItems.Tag tag = items().read(item);
            if (tag == null) {
                continue;
            }
            ItemStack copy = item.clone();
            inventory.setItem(slot, null);
            give(player, copy);
            plugin.send(player, "container-returned", "weapon", name(tag));
            plugin.alert(name(tag) + " #" + tag.shortId() + " was found in a " + inventory.getType().name()
                    .toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + " opened by " + player.getName()
                    + " and moved to their inventory.");
        }
    }

    /** Into the inventory, or on the ground at the player's feet when it is full. */
    public void give(Player player, ItemStack item) {
        Map<Integer, ItemStack> left = player.getInventory().addItem(item);
        for (ItemStack rest : left.values()) {
            WeaponItems.Tag tag = items().read(rest);
            Item dropped = player.getWorld().dropItem(player.getLocation(), rest);
            protect(dropped);
            if (tag != null) {
                WeaponRecord record = registry().get(tag.id());
                if (record != null) {
                    registry().ground(record, dropped.getUniqueId(), dropped.getLocation());
                }
                plugin.send(player, "dropped-at-feet", "weapon", name(tag));
            }
        }
    }

    // ---- items on the ground ------------------------------------------------------------------------------

    /** A legendary on the ground never despawns, burns or gets destroyed, and glows so it is easy to find. */
    public void protect(Item item) {
        item.setInvulnerable(true);
        io.github.drepfy.legendary.util.Compat.neverDespawn(item);
        try {
            item.setGlowing(true);
        } catch (RuntimeException ignored) {
            // Cosmetic.
        }
    }

    /** Every second: follow weapons on the ground, rescue them from the void, notice destroyed ones. */
    public void checkGround() {
        for (WeaponRecord record : new ArrayList<>(registry().all())) {
            if (record.state() != WeaponRecord.State.GROUND || record.entity() == null) {
                continue;
            }
            Entity entity = Bukkit.getEntity(record.entity());
            if (entity instanceof Item item && item.isValid()) {
                missingSince.remove(record.id());
                Location at = item.getLocation();
                World world = at.getWorld();
                if (world != null && at.getY() < world.getMinHeight()) {
                    Location safe = safeSpot(world);
                    item.teleport(safe);
                    item.setVelocity(new Vector());
                    at = safe;
                    plugin.alert(plugin.items().displayName(record.type()) + " #" + WeaponItems.shortId(record.id())
                            + " fell into the void and was moved to " + world.getName() + " spawn.");
                }
                registry().at(record, at);
                continue;
            }
            World world = record.world() == null ? null : Bukkit.getWorld(record.world());
            if (world == null || !entitiesLoaded(world, (int) Math.floor(record.x()) >> 4, (int) Math.floor(record.z()) >> 4)) {
                missingSince.remove(record.id()); // Unloaded: it is simply not here to see.
                continue;
            }
            if (!plugin.settings().markLost()) {
                continue;
            }
            if (missingFor(record.id()) >= LOST_AFTER_MS) {
                missingSince.remove(record.id());
                registry().lost(record, "the item on the ground was destroyed");
                plugin.alert(plugin.items().displayName(record.type()) + " #" + WeaponItems.shortId(record.id())
                        + " disappeared from the ground and is marked as lost. It can be given out again.");
            }
        }
    }

    /** The chunk and its entities are loaded (Paper loads entities a little after the chunk). */
    private static boolean entitiesLoaded(World world, int chunkX, int chunkZ) {
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            return false;
        }
        try {
            return world.getChunkAt(chunkX, chunkZ).isEntitiesLoaded();
        } catch (RuntimeException | LinkageError e) {
            return true;
        }
    }

    /** The highest block at the world's spawn. */
    static Location safeSpot(World world) {
        Location spawn = world.getSpawnLocation();
        int y = world.getHighestBlockYAt(spawn.getBlockX(), spawn.getBlockZ());
        if (y < world.getMinHeight()) {
            y = spawn.getBlockY();
        }
        return new Location(world, spawn.getBlockX() + 0.5, y + 1.0, spawn.getBlockZ() + 0.5);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        WeaponItems.Tag tag = items().read(event.getEntity().getItemStack());
        if (tag == null) {
            return;
        }
        WeaponRecord record = registry().get(tag.id());
        if (record == null ? !canAdopt(tag.type()) : record.state() == WeaponRecord.State.REMOVED) {
            event.setCancelled(true);
            return;
        }
        if (record != null && record.state() == WeaponRecord.State.HELD && record.holder() != null) {
            Player holder = Bukkit.getPlayer(record.holder());
            if (holder != null && holder.isOnline() && carries(holder, tag.id())) {
                event.setCancelled(true);
                plugin.alert("Stopped a duplicate of " + name(tag) + " #" + tag.shortId() + " appearing on the ground near "
                        + holder.getName() + ".");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawned(ItemSpawnEvent event) {
        Item item = event.getEntity();
        WeaponItems.Tag tag = items().read(item.getItemStack());
        if (tag == null) {
            return;
        }
        protect(item);
        WeaponRecord record = registry().get(tag.id());
        if (record == null) {
            record = registry().adopt(tag.id(), tag.type());
        }
        if (record.state() != WeaponRecord.State.GROUND || !item.getUniqueId().equals(record.entity())) {
            registry().ground(record, item.getUniqueId(), item.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Item item = event.getItemDrop();
        WeaponItems.Tag tag = items().read(item.getItemStack());
        if (tag == null) {
            return;
        }
        protect(item);
        WeaponRecord record = registry().get(tag.id());
        if (record == null || record.state() == WeaponRecord.State.REMOVED) {
            return; // Deleted when someone tries to pick it up.
        }
        registry().ground(record, item.getUniqueId(), item.getLocation());
        registry().log("DROPPED " + tag.type().key() + " #" + tag.shortId() + " by " + event.getPlayer().getName()
                + " at " + where(record).replace("on the ground at ", ""));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Item item = event.getItem();
        WeaponItems.Tag tag = items().read(item.getItemStack());
        if (tag == null) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            event.setCancelled(true); // Zombies, foxes, allays... would carry it off or despawn with it.
            return;
        }
        WeaponRecord record = registry().get(tag.id());
        if (record == null ? !canAdopt(tag.type()) : record.state() == WeaponRecord.State.REMOVED) {
            event.setCancelled(true);
            item.remove();
            plugin.alert("Deleted a " + (record == null ? "copy of " : "removed ") + name(tag) + " #" + tag.shortId()
                    + " that " + player.getName() + " tried to pick up.");
            return;
        }
        if (record == null) {
            return;
        }
        if (stillAtOldPlace(record, player) && !item.getUniqueId().equals(record.entity())) {
            event.setCancelled(true);
            item.remove();
            plugin.alert("Deleted a duplicate of " + name(tag) + " #" + tag.shortId() + " on the ground near "
                    + player.getName() + " (the original is " + where(record) + ").");
            return;
        }
        UUID last = record.state() == WeaponRecord.State.HELD ? record.holder() : record.previousHolder();
        if (last != null && !last.equals(player.getUniqueId()) && sameOwner(last, player)) {
            event.setCancelled(true);
            if (refusedRecently(player.getUniqueId() + ":" + tag.id(), 3000)) {
                return;
            }
            plugin.send(player, "alt-blocked");
            String lastName = record.state() == WeaponRecord.State.HELD ? record.holderName() : record.previousHolderName();
            if (!refusedRecently("alert:" + player.getUniqueId() + ":" + last, 60_000)) {
                plugin.alert(player.getName() + " tried to pick up " + name(tag) + " #" + tag.shortId() + " dropped by "
                        + lastName + ", an account on the same IP address. Refused.");
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickedUp(EntityPickupItemEvent event) {
        WeaponItems.Tag tag = items().read(event.getItem().getItemStack());
        if (tag == null || !(event.getEntity() instanceof Player player)) {
            return;
        }
        WeaponRecord record = registry().get(tag.id());
        if (record == null) {
            record = registry().adopt(tag.id(), tag.type());
        }
        registry().held(record, player.getUniqueId(), player.getName(), player.getLocation());
        registry().log("PICKED UP " + tag.type().key() + " #" + tag.shortId() + " by " + player.getName()
                + (record.previousHolderName() == null ? "" : " (dropped by " + record.previousHolderName() + ")"));
    }

    /** Two accounts of one person: same recent IP address, unless staff allowed it. */
    boolean sameOwner(UUID previous, Player player) {
        var alts = plugin.settings().alts();
        if (!alts.enabled() || player.hasPermission("legendary.bypass.alts")) {
            return false;
        }
        return registry().shareIp(previous, player.getUniqueId(), plugin.now() - alts.rememberMs(),
                alts.maxAccountsPerIp());
    }

    private boolean refusedRecently(String key, long millis) {
        long now = plugin.now();
        Long last = recentRefusals.get(key);
        if (last != null && now - last < millis && now >= last) {
            return true;
        }
        recentRefusals.put(key, now);
        if (recentRefusals.size() > 500) {
            recentRefusals.values().removeIf(at -> now - at > 60_000);
        }
        return false;
    }

    /** Hoppers and hopper minecarts cannot pick legendaries up. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHopper(InventoryPickupItemEvent event) {
        if (items().isLegendary(event.getItem().getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDespawn(ItemDespawnEvent event) {
        if (items().isLegendary(event.getEntity().getItemStack())) {
            event.setCancelled(true);
        }
    }

    /** Lava, fire, cactus, explosions: a dropped legendary survives them all. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Item item && event.getCause() != EntityDamageEvent.DamageCause.VOID
                && items().isLegendary(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onItemBurn(EntityCombustEvent event) {
        if (event.getEntity() instanceof Item item && items().isLegendary(item.getItemStack())) {
            event.setCancelled(true);
        }
    }

    // ---- death ----------------------------------------------------------------------------------------------

    /** Legendaries taken off players who are dying, until the death is final. */
    private final Map<UUID, List<ItemStack>> dying = new HashMap<>();

    /**
     * First, before any grave or death-chest plugin looks: the legendaries leave the drops and
     * the inventory.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDeathFirst(PlayerDeathEvent event) {
        event.getDrops().removeIf(items()::isLegendary);
        try {
            event.getItemsToKeep().removeIf(items()::isLegendary);
        } catch (RuntimeException | LinkageError ignored) {
            // Only on Paper.
        }
        List<ItemStack> taken = new ArrayList<>();
        for (Spot spot : spots(event.getEntity())) {
            taken.add(spot.item().clone());
            spot.clear();
        }
        if (!taken.isEmpty()) {
            dying.put(event.getEntity().getUniqueId(), taken);
        }
    }

    /** Last: dropped on the ground where the player died (or given back if they did not die after all). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        List<ItemStack> taken = dying.remove(player.getUniqueId());
        if (taken == null) {
            return;
        }
        if (event.isCancelled() || (event.getKeepInventory() && !plugin.settings().dropOnDeath())) {
            for (ItemStack item : taken) {
                player.getInventory().addItem(item).values().forEach(rest -> give(player, rest));
            }
            return;
        }
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) {
            return;
        }
        if (at.getY() < world.getMinHeight()) {
            at = safeSpot(world); // Died in the void: the weapon would fall out of the world.
        }
        for (ItemStack stack : taken) {
            WeaponItems.Tag tag = items().read(stack);
            WeaponRecord record = registry().get(tag.id());
            if (record == null || record.state() == WeaponRecord.State.REMOVED) {
                continue;
            }
            Item item = world.dropItemNaturally(at, stack);
            protect(item);
            registry().ground(record, item.getUniqueId(), item.getLocation());
            registry().log("DEATH DROP " + tag.type().key() + " #" + tag.shortId() + " from " + player.getName()
                    + (player.getKiller() != null ? " (killed by " + player.getKiller().getName() + ")" : "")
                    + " at " + where(record).replace("on the ground at ", ""));
        }
    }

    // ---- joining and leaving ------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        recordAddress(player);
        takeOut(player.getEnderChest(), player);
        Bukkit.getScheduler().runTask(plugin, this::scan);
    }

    void recordAddress(Player player) {
        InetSocketAddress address = player.getAddress();
        InetAddress ip = address == null ? null : address.getAddress();
        // Local addresses: a proxy without IP forwarding would make every account look like one person.
        if (ip != null && !ip.isLoopbackAddress() && !ip.isAnyLocalAddress()) {
            registry().recordIp(player.getUniqueId(), ip.getHostAddress());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        for (Spot spot : spots(player)) {
            WeaponRecord record = registry().get(spot.tag().id());
            if (record != null && record.state() == WeaponRecord.State.HELD
                    && player.getUniqueId().equals(record.holder())) {
                registry().at(record, player.getLocation());
            }
        }
    }

    /** Opening a container with a legendary inside (from before the plugin) gives it back. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isForeign(player, event.getInventory())) {
            takeOut(event.getInventory(), player);
        }
    }
}
