package io.github.drepfy.vigil.env;

import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.util.Glob;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Anti-ESP and anti-freecam for bases (Paper only). When a chunk is sent to a player,
 * storage blocks (chests, barrels, shulker boxes, beds...) that the player cannot see
 * from where they really are are replaced by stone on that player's screen only. They
 * reappear as soon as the player gets close or has a clear line of sight to them.
 *
 * <p>Storage ESP, "stash finder" and freecam users therefore see stone instead of
 * the chests inside other players' bases. The world itself is never changed: other
 * players, hoppers and redstone see the real blocks, and a revealed block is always
 * read back from the world, so nothing that was changed meanwhile can reappear.
 *
 * <p>Limits: the real chunk reaches the client a moment before the replacement, so a
 * mod that records every chest the instant a chunk arrives can still see it once.
 */
public final class StorageHider implements Listener {

    /** Each player is re-examined every this many ticks. */
    private static final int CHECK_INTERVAL = 5;
    /** Line-of-sight traces (segments) allowed per sent chunk and per examination of a player. */
    private static final int MAX_TRACES = 96;
    private static final double[][] SIGHT_POINTS = {
            {0.5, 0.5, 0.5}, {0.5, 1.02, 0.5}, {0.5, -0.02, 0.5}, {1.02, 0.5, 0.5}, {-0.02, 0.5, 0.5},
            {0.5, 0.5, 1.02}, {0.5, 0.5, -0.02}};

    /** Blocks hidden from one player, by chunk. */
    private static final class Hidden {
        private final UUID world;
        private final Map<Long, List<Long>> byChunk = new HashMap<>();

        Hidden(UUID world) {
            this.world = world;
        }
    }

    /** Finds the positions ({x, y, z}) of the blocks of the given types in a chunk. */
    @FunctionalInterface
    public interface TileSource {
        List<int[]> find(Chunk chunk, Set<Material> types);
    }

    private final Plugin plugin;
    private final Logger logger;
    private TileSource tileSource = StorageHider::blockEntities;
    private final Supplier<Settings> settings;
    private final WorldProbe probe;
    private final Map<UUID, Hidden> hidden = new HashMap<>();
    private Settings.AntiEsp materialsSource;
    private Set<Material> materials = EnumSet.noneOf(Material.class);
    private BukkitTask task;
    private long ticks;
    private boolean errorLogged;

    public StorageHider(Plugin plugin, Supplier<Settings> settings, WorldProbe probe) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.settings = settings;
        this.probe = probe;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Shows every hidden block again (plugin shutdown). */
    public void stop() {
        if (task != null) {
            task.cancel();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            revealAll(player);
        }
        hidden.clear();
    }

    // ---- hiding -------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkSent(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        try {
            if (!isActive(player)) {
                return;
            }
            hideIn(player, event.getChunk());
        } catch (LinkageError | RuntimeException e) {
            logOnce(e);
        }
    }

    private void hideIn(Player player, Chunk chunk) {
        Set<Material> types = materials();
        if (types.isEmpty()) {
            return;
        }
        List<int[]> blocks = tileSource.find(chunk, types);
        if (blocks.isEmpty()) {
            return;
        }
        Settings.AntiEsp config = settings.get().antiEsp();
        World world = chunk.getWorld();
        Location eye = player.getEyeLocation();
        boolean sameWorld = world.equals(eye.getWorld());
        double reveal = config.revealDistance() * config.revealDistance();
        double look = config.lookDistance() * config.lookDistance();
        Map<Position, BlockData> fake = new HashMap<>();
        List<Long> positions = new ArrayList<>();
        int[] traces = {MAX_TRACES};
        for (int[] block : blocks) {
            int x = block[0];
            int y = block[1];
            int z = block[2];
            if (sameWorld) {
                double distance = distanceSquared(eye, x, y, z);
                if (distance <= reveal) {
                    continue;
                }
                if (distance <= look && canSee(world, eye, x, y, z, traces)) {
                    continue;
                }
            }
            fake.put(Position.block(x, y, z), replacement(world, y));
            positions.add(pack(x, y, z));
        }
        if (fake.isEmpty()) {
            return;
        }
        player.sendMultiBlockChange(fake);
        Hidden entry = hidden.get(player.getUniqueId());
        if (entry == null || !entry.world.equals(world.getUID())) {
            entry = new Hidden(world.getUID());
            hidden.put(player.getUniqueId(), entry);
        }
        entry.byChunk.put(chunk.getChunkKey(), positions);
    }

    // ---- revealing ----------------------------------------------------------------------------

    private void tick() {
        ticks++;
        if (hidden.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, Hidden>> iterator = hidden.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Hidden> entry = iterator.next();
            // Spread players over the interval so the work per tick stays flat.
            if ((entry.getKey().hashCode() + ticks) % CHECK_INTERVAL != 0) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            try {
                if (!player.getWorld().getUID().equals(entry.getValue().world)) {
                    iterator.remove(); // The client dropped those chunks when it changed worlds.
                } else if (!isActive(player)) {
                    reveal(player, entry.getValue(), true);
                    iterator.remove();
                } else {
                    reveal(player, entry.getValue(), false);
                    if (entry.getValue().byChunk.isEmpty()) {
                        iterator.remove();
                    }
                }
            } catch (LinkageError | RuntimeException e) {
                iterator.remove();
                logOnce(e);
            }
        }
    }

    /** Reveals the hidden blocks the player is now close to or can see (or all of them). */
    private void reveal(Player player, Hidden entry, boolean all) {
        World world = player.getWorld();
        Settings.AntiEsp config = settings.get().antiEsp();
        Location eye = player.getEyeLocation();
        double reveal = config.revealDistance() * config.revealDistance();
        double look = config.lookDistance() * config.lookDistance();
        int radius = (int) Math.ceil(config.lookDistance() / 16.0) + 1;
        int playerChunkX = eye.getBlockX() >> 4;
        int playerChunkZ = eye.getBlockZ() >> 4;
        Map<Position, BlockData> real = new HashMap<>();
        int[] traces = {MAX_TRACES};
        Iterator<Map.Entry<Long, List<Long>>> chunks = entry.byChunk.entrySet().iterator();
        while (chunks.hasNext()) {
            Map.Entry<Long, List<Long>> chunk = chunks.next();
            long key = chunk.getKey();
            int chunkX = (int) key;
            int chunkZ = (int) (key >> 32);
            if (!all && (Math.abs(chunkX - playerChunkX) > radius || Math.abs(chunkZ - playerChunkZ) > radius)) {
                continue;
            }
            Iterator<Long> positions = chunk.getValue().iterator();
            while (positions.hasNext()) {
                long packed = positions.next();
                int x = unpackX(packed);
                int y = unpackY(packed);
                int z = unpackZ(packed);
                boolean show = all;
                if (!show) {
                    double distance = distanceSquared(eye, x, y, z);
                    show = distance <= reveal
                            || (distance <= look && canSee(world, eye, x, y, z, traces));
                }
                if (show) {
                    positions.remove();
                    if (world.isChunkLoaded(x >> 4, z >> 4)) {
                        real.put(Position.block(x, y, z), world.getBlockData(x, y, z));
                    }
                }
            }
            if (chunk.getValue().isEmpty()) {
                chunks.remove();
            }
        }
        if (!real.isEmpty()) {
            player.sendMultiBlockChange(real);
        }
    }

    private void revealAll(Player player) {
        Hidden entry = hidden.get(player.getUniqueId());
        if (entry != null && player.getWorld().getUID().equals(entry.world)) {
            try {
                reveal(player, entry, true);
            } catch (LinkageError | RuntimeException e) {
                logOnce(e);
            }
        }
        hidden.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnsent(PlayerChunkUnloadEvent event) {
        Hidden entry = hidden.get(event.getPlayer().getUniqueId());
        if (entry != null) {
            entry.byChunk.remove(event.getChunk().getChunkKey());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        hidden.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        hidden.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        GameMode mode = event.getNewGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            // Spectators fly through walls: showing them stone would only confuse staff.
            Bukkit.getScheduler().runTask(plugin, () -> revealAll(event.getPlayer()));
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    /** Storage blocks always have block entities, so only those need to be looked at (cheap). */
    private static List<int[]> blockEntities(Chunk chunk, Set<Material> types) {
        Collection<BlockState> states = chunk.getTileEntities(block -> types.contains(block.getType()), false);
        List<int[]> result = new ArrayList<>(states.size());
        for (BlockState state : states) {
            result.add(new int[] {state.getX(), state.getY(), state.getZ()});
        }
        return result;
    }

    /** Replaces how storage blocks are found (for test servers without block entity support). */
    public void setTileSource(TileSource source) {
        this.tileSource = source;
    }

    private boolean isActive(Player player) {
        Settings config = settings.get();
        if (!config.antiEsp().enabled() || !config.general().enabled()) {
            return false;
        }
        GameMode mode = player.getGameMode();
        if (config.general().exemptCreativeAndSpectator() && (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR)) {
            return false;
        }
        if (config.general().disabledWorlds().contains(player.getWorld().getName())) {
            return false;
        }
        return !(config.general().bypassPermission() && player.hasPermission("vigil.bypass"));
    }

    /**
     * Whether any of a few points on the block can be seen from the eye through non-solid
     * blocks. Each trace uses one unit of {@code budget}; without budget the answer is "not
     * yet" and the block is looked at again on a later tick.
     */
    private boolean canSee(World world, Location eye, int x, int y, int z, int[] budget) {
        for (double[] point : SIGHT_POINTS) {
            if (budget[0]-- <= 0) {
                return false;
            }
            if (!probe.segmentBlocked(world, eye.getX(), eye.getY(), eye.getZ(), x + point[0], y + point[1],
                    z + point[2], x, y, z, true)) {
                return true;
            }
        }
        return false;
    }

    private Set<Material> materials() {
        Settings.AntiEsp config = settings.get().antiEsp();
        if (config != materialsSource) {
            Set<Material> result = EnumSet.noneOf(Material.class);
            for (Material material : Material.values()) {
                try {
                    if (!material.isLegacy() && Glob.matchesAny(config.blocks(), material.name()) && material.isBlock()) {
                        result.add(material);
                    }
                } catch (LinkageError | RuntimeException ignored) {
                    // Materials the server cannot describe are simply not hidden.
                }
            }
            materials = result;
            materialsSource = config;
        }
        return materials;
    }

    private static BlockData replacement(World world, int y) {
        return switch (world.getEnvironment()) {
            case NETHER -> Material.NETHERRACK.createBlockData();
            case THE_END -> Material.END_STONE.createBlockData();
            default -> (y < 0 ? Material.DEEPSLATE : Material.STONE).createBlockData();
        };
    }

    private static double distanceSquared(Location eye, int x, int y, int z) {
        double dx = x + 0.5 - eye.getX();
        double dy = y + 0.5 - eye.getY();
        double dz = z + 0.5 - eye.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFFL);
    }

    private static int unpackX(long packed) {
        return (int) (packed >> 38);
    }

    private static int unpackZ(long packed) {
        return (int) (packed << 26 >> 38);
    }

    private static int unpackY(long packed) {
        return (int) (packed << 52 >> 52);
    }

    private void logOnce(Throwable error) {
        if (!errorLogged) {
            errorLogged = true;
            logger.log(Level.WARNING, "Storage hiding failed (the world is not affected)", error);
        }
    }

    /** Number of blocks currently hidden from a player (tests and diagnostics). */
    public int hiddenCount(UUID player) {
        Hidden entry = hidden.get(player);
        if (entry == null) {
            return 0;
        }
        int count = 0;
        for (List<Long> positions : entry.byChunk.values()) {
            count += positions.size();
        }
        return count;
    }
}
