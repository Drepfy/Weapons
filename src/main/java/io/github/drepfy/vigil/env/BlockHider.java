package io.github.drepfy.vigil.env;

import io.github.drepfy.vigil.config.Settings;
import io.github.drepfy.vigil.util.Glob;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Anti-ESP, anti-freecam and cave anti-xray (Paper only). Blocks a player cannot see
 * from where they really are are shown to that player as stone (deepslate below y 0,
 * netherrack in the nether) and only appear once the player is close or has a clear
 * line of sight to them. When they are out of sight again they are hidden again.
 *
 * <ul>
 *   <li><b>Storage</b> (chests, barrels, shulker boxes, beds...): storage ESP, stash
 *   finders and freecam show stone instead of the insides of bases.</li>
 *   <li><b>Valuable ores</b> (diamonds, ancient debris): also the ones in caves, which
 *   Paper's anti-xray cannot hide. X-ray only shows them once the player is really
 *   down there and can see them.</li>
 * </ul>
 *
 * The world itself is never changed. A revealed block is always read back from the
 * world, and a block is only hidden again if it is still of a hidden type.
 *
 * <p>Limits: the real chunk reaches the client a moment before the replacement, and
 * ores in freshly sent chunks are hidden a tick or two later (they are found off the
 * main thread), so a mod that records blocks the instant a chunk arrives can still
 * see them once.
 */
public final class BlockHider implements Listener {

    /** Each player is re-examined every this many ticks. */
    private static final int CHECK_INTERVAL = 5;
    /** Line-of-sight traces (segments) per sent chunk and per examination of a player. */
    private static final int MAX_TRACES = 160;
    /** Hysteresis: shown blocks are hidden again only this much further out. */
    private static final double REHIDE_MARGIN = 6.0;
    /** Examinations in a row without line of sight before a shown block is hidden again. */
    private static final int REHIDE_MISSES = 2;
    private static final int MAX_CACHED_CHUNKS = 8192;
    private static final double[][] SIGHT_POINTS = {
            {0.5, 0.5, 0.5}, {0.5, 1.02, 0.5}, {0.5, -0.02, 0.5}, {1.02, 0.5, 0.5}, {-0.02, 0.5, 0.5},
            {0.5, 0.5, 1.02}, {0.5, 0.5, -0.02}};

    private enum Kind { STORAGE, ORE }

    private enum Sight { VISIBLE, HIDDEN, UNKNOWN }

    /** Finds storage blocks (they always have block entities, which is cheap). */
    @FunctionalInterface
    public interface TileSource {
        List<int[]> find(Chunk chunk, Set<Material> types);
    }

    /** Finds ore blocks and hands them to {@code result} on the server thread (possibly later). */
    @FunctionalInterface
    public interface OreSource {
        void find(Chunk chunk, Set<Material> types, int minY, int maxY, Consumer<List<int[]>> result);
    }

    private static final class Tracked {
        final long position;
        final Kind kind;
        boolean hidden;
        int misses;

        Tracked(long position, Kind kind, boolean hidden) {
            this.position = position;
            this.kind = kind;
            this.hidden = hidden;
        }
    }

    /** Everything tracked for one player, by chunk. */
    private static final class View {
        final UUID world;
        final Map<Long, List<Tracked>> byChunk = new HashMap<>();

        View(UUID world) {
            this.world = world;
        }
    }

    private final Plugin plugin;
    private final Logger logger;
    private final Supplier<Settings> settings;
    private final WorldProbe probe;
    private final Map<UUID, View> views = new HashMap<>();
    /** Ore positions per world and chunk, valid until a block in the chunk changes. */
    private final Map<UUID, LinkedHashMap<Long, List<int[]>>> oreCache = new HashMap<>();
    /** Players waiting for the ores of a chunk that is being scanned. */
    private final Map<String, Set<UUID>> pendingScans = new HashMap<>();
    private final ThreadPoolExecutor scanner;
    private TileSource tileSource = BlockHider::blockEntities;
    private OreSource oreSource;
    private Settings.AntiEsp typesSource;
    private Set<Material> storageTypes = EnumSet.noneOf(Material.class);
    private Set<Material> oreTypes = EnumSet.noneOf(Material.class);
    private BukkitTask task;
    private long ticks;
    private boolean errorLogged;

    public BlockHider(Plugin plugin, Supplier<Settings> settings, WorldProbe probe) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.settings = settings;
        this.probe = probe;
        this.scanner = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4096), runnable -> {
            Thread thread = new Thread(runnable, "Vigil-OreScanner");
            thread.setDaemon(true);
            return thread;
        });
        this.scanner.allowCoreThreadTimeOut(true);
        this.oreSource = this::scanSnapshot;
    }

    public void start() {
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    /** Shows every hidden block again (plugin shutdown). */
    public void stop() {
        if (task != null) {
            task.cancel();
        }
        scanner.shutdownNow();
        for (Player player : Bukkit.getOnlinePlayers()) {
            revealAll(player);
        }
        views.clear();
        oreCache.clear();
        pendingScans.clear();
    }

    // ---- chunk sending --------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkSent(PlayerChunkLoadEvent event) {
        Player player = event.getPlayer();
        Chunk chunk = event.getChunk();
        try {
            View view = views.get(player.getUniqueId());
            if (view != null) {
                // The client received the real chunk again: start over for it.
                view.byChunk.remove(chunk.getChunkKey());
            }
            if (!isActive(player)) {
                return;
            }
            Settings.AntiEsp config = settings.get().antiEsp();
            refreshTypes(config);
            if (config.hideStorage() && !storageTypes.isEmpty()) {
                track(player, chunk.getWorld(), chunk.getChunkKey(), tileSource.find(chunk, storageTypes), Kind.STORAGE);
            }
            int[] range = oreRange(chunk.getWorld());
            if (config.hideOres() && range != null && !oreTypes.isEmpty()) {
                requestOres(player, chunk, range);
            }
        } catch (LinkageError | RuntimeException e) {
            logOnce(e);
        }
    }

    private void requestOres(Player player, Chunk chunk, int[] range) {
        World world = chunk.getWorld();
        long key = chunk.getChunkKey();
        List<int[]> cached = cache(world).get(key);
        if (cached != null) {
            track(player, world, key, cached, Kind.ORE);
            return;
        }
        String pendingKey = world.getUID() + ":" + key;
        Set<UUID> waiting = pendingScans.get(pendingKey);
        if (waiting != null) {
            waiting.add(player.getUniqueId());
            return;
        }
        waiting = new HashSet<>();
        waiting.add(player.getUniqueId());
        pendingScans.put(pendingKey, waiting);
        UUID worldId = world.getUID();
        oreSource.find(chunk, oreTypes, range[0], range[1], ores -> {
            Set<UUID> players = pendingScans.remove(pendingKey);
            World current = Bukkit.getWorld(worldId);
            if (current == null || ores == null) {
                return;
            }
            LinkedHashMap<Long, List<int[]>> worldCache = cache(current);
            worldCache.put(key, ores);
            if (worldCache.size() > MAX_CACHED_CHUNKS) {
                Iterator<Long> oldest = worldCache.keySet().iterator();
                oldest.next();
                oldest.remove();
            }
            if (players == null || ores.isEmpty()) {
                return;
            }
            for (UUID id : players) {
                Player waitingPlayer = Bukkit.getPlayer(id);
                if (waitingPlayer != null && waitingPlayer.getWorld().getUID().equals(worldId) && isActive(waitingPlayer)) {
                    try {
                        track(waitingPlayer, current, key, ores, Kind.ORE);
                    } catch (LinkageError | RuntimeException e) {
                        logOnce(e);
                    }
                }
            }
        });
    }

    /** Starts tracking blocks for a player; hides the ones the player cannot see now. */
    private void track(Player player, World world, long chunkKey, List<int[]> blocks, Kind kind) {
        if (blocks.isEmpty()) {
            return;
        }
        View view = views.get(player.getUniqueId());
        if (view == null || !view.world.equals(world.getUID())) {
            view = new View(world.getUID());
            views.put(player.getUniqueId(), view);
        }
        List<Tracked> entries = view.byChunk.computeIfAbsent(chunkKey, key -> new ArrayList<>());
        Settings.AntiEsp config = settings.get().antiEsp();
        Location eye = player.getEyeLocation();
        boolean sameWorld = world.equals(eye.getWorld());
        double reveal = square(config.revealDistance());
        double look = square(config.lookDistance());
        int[] budget = {MAX_TRACES};
        Map<Position, BlockData> fake = new HashMap<>();
        for (int[] block : blocks) {
            int x = block[0];
            int y = block[1];
            int z = block[2];
            boolean show = false;
            if (sameWorld) {
                double distance = distanceSquared(eye, x, y, z);
                show = distance <= reveal || (distance <= look && sight(world, eye, x, y, z, budget) == Sight.VISIBLE);
            }
            entries.add(new Tracked(pack(x, y, z), kind, !show));
            if (!show) {
                fake.put(Position.block(x, y, z), replacement(world, y));
            }
        }
        if (!fake.isEmpty()) {
            player.sendMultiBlockChange(fake);
        }
    }

    // ---- revealing and hiding again ---------------------------------------------------------

    private void tick() {
        ticks++;
        if (views.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<UUID, View>> iterator = views.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, View> entry = iterator.next();
            // Spread players over the interval so the work per tick stays flat.
            if (Math.floorMod(entry.getKey().hashCode() + ticks, CHECK_INTERVAL) != 0) {
                continue;
            }
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.getWorld().getUID().equals(entry.getValue().world)) {
                iterator.remove(); // Gone, or the client dropped those chunks when it changed worlds.
                continue;
            }
            try {
                if (!isActive(player)) {
                    update(player, entry.getValue(), true);
                    iterator.remove();
                } else {
                    update(player, entry.getValue(), false);
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

    /** Reveals what the player can now see and hides again what went out of sight. */
    private void update(Player player, View view, boolean revealAll) {
        World world = player.getWorld();
        Settings.AntiEsp config = settings.get().antiEsp();
        refreshTypes(config);
        Location eye = player.getEyeLocation();
        double reveal = square(config.revealDistance());
        double look = square(config.lookDistance());
        double rehideNear = square(config.revealDistance() + REHIDE_MARGIN);
        double rehideFar = square(config.lookDistance() + REHIDE_MARGIN);
        int[] budget = {MAX_TRACES};
        Map<Position, BlockData> changes = new HashMap<>();
        Iterator<Map.Entry<Long, List<Tracked>>> chunks = view.byChunk.entrySet().iterator();
        while (chunks.hasNext()) {
            List<Tracked> entries = chunks.next().getValue();
            Iterator<Tracked> blocks = entries.iterator();
            while (blocks.hasNext()) {
                Tracked tracked = blocks.next();
                int x = unpackX(tracked.position);
                int y = unpackY(tracked.position);
                int z = unpackZ(tracked.position);
                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }
                double distance = distanceSquared(eye, x, y, z);
                if (tracked.hidden) {
                    boolean show = revealAll || distance <= reveal
                            || (distance <= look && sight(world, eye, x, y, z, budget) == Sight.VISIBLE);
                    if (show) {
                        tracked.hidden = false;
                        tracked.misses = 0;
                        changes.put(Position.block(x, y, z), world.getBlockData(x, y, z));
                    }
                    continue;
                }
                if (revealAll || distance <= rehideNear) {
                    tracked.misses = 0;
                    continue;
                }
                boolean hideAgain = distance > rehideFar;
                if (!hideAgain) {
                    Sight sight = sight(world, eye, x, y, z, budget);
                    if (sight == Sight.VISIBLE) {
                        tracked.misses = 0;
                    } else if (sight == Sight.HIDDEN && ++tracked.misses >= REHIDE_MISSES) {
                        hideAgain = true;
                    }
                }
                if (hideAgain) {
                    Material now = world.getType(x, y, z);
                    Set<Material> types = tracked.kind == Kind.STORAGE ? storageTypes : oreTypes;
                    if (types.contains(now)) {
                        tracked.hidden = true;
                        tracked.misses = 0;
                        changes.put(Position.block(x, y, z), replacement(world, y));
                    } else {
                        blocks.remove(); // The block changed; nothing to hide there any more.
                    }
                }
            }
            if (entries.isEmpty()) {
                chunks.remove();
            }
        }
        if (!changes.isEmpty()) {
            player.sendMultiBlockChange(changes);
        }
    }

    private void revealAll(Player player) {
        View view = views.get(player.getUniqueId());
        if (view != null && player.getWorld().getUID().equals(view.world)) {
            try {
                update(player, view, true);
            } catch (LinkageError | RuntimeException e) {
                logOnce(e);
            }
        }
        views.remove(player.getUniqueId());
    }

    // ---- bookkeeping ----------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnsent(PlayerChunkUnloadEvent event) {
        View view = views.get(event.getPlayer().getUniqueId());
        if (view != null) {
            view.byChunk.remove(event.getChunk().getChunkKey());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        views.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        views.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        GameMode mode = event.getNewGameMode();
        if (mode == GameMode.CREATIVE || mode == GameMode.SPECTATOR) {
            // Spectators fly through walls: showing them stone would only confuse staff.
            Bukkit.getScheduler().runTask(plugin, () -> revealAll(event.getPlayer()));
        }
    }

    // Block changes make cached ore positions stale.

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        invalidate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        invalidate(event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onExplode(EntityExplodeEvent event) {
        event.blockList().forEach(this::invalidate);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(this::invalidate);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        event.getBlocks().forEach(this::invalidate);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        event.getBlocks().forEach(this::invalidate);
    }

    private void invalidate(Block block) {
        LinkedHashMap<Long, List<int[]>> worldCache = oreCache.get(block.getWorld().getUID());
        if (worldCache != null) {
            worldCache.remove(Chunk.getChunkKey(block.getX() >> 4, block.getZ() >> 4));
        }
    }

    // ---- finding blocks -----------------------------------------------------------------------

    private static List<int[]> blockEntities(Chunk chunk, Set<Material> types) {
        Collection<BlockState> states = chunk.getTileEntities(block -> types.contains(block.getType()), false);
        List<int[]> result = new ArrayList<>(states.size());
        for (BlockState state : states) {
            result.add(new int[] {state.getX(), state.getY(), state.getZ()});
        }
        return result;
    }

    /** Copies the chunk on the server thread and scans the copy on a background thread. */
    private void scanSnapshot(Chunk chunk, Set<Material> types, int minY, int maxY, Consumer<List<int[]>> result) {
        ChunkSnapshot snapshot = chunk.getChunkSnapshot(false, false, false);
        int baseX = chunk.getX() << 4;
        int baseZ = chunk.getZ() << 4;
        Set<Material> wanted = EnumSet.copyOf(types);
        try {
            scanner.execute(() -> {
                List<int[]> found = new ArrayList<>();
                for (int y = minY; y <= maxY; y++) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            if (wanted.contains(snapshot.getBlockType(x, y, z))) {
                                found.add(new int[] {baseX + x, y, baseZ + z});
                            }
                        }
                    }
                }
                if (plugin.isEnabled()) {
                    Bukkit.getScheduler().runTask(plugin, () -> result.accept(found));
                }
            });
        } catch (RejectedExecutionException e) {
            // Overloaded or shutting down: this chunk simply is not protected.
            Bukkit.getScheduler().runTask(plugin, () -> result.accept(null));
        }
    }

    /** Y range to scan for ores in a world, or {@code null} when ores are not hidden there. */
    private static int[] oreRange(World world) {
        return switch (world.getEnvironment()) {
            case NORMAL -> new int[] {world.getMinHeight(), Math.min(16, world.getMaxHeight() - 1)};
            case NETHER -> new int[] {Math.max(world.getMinHeight(), 0), Math.min(127, world.getMaxHeight() - 1)};
            default -> null;
        };
    }

    // ---- helpers ------------------------------------------------------------------------------

    private boolean isActive(Player player) {
        Settings config = settings.get();
        Settings.AntiEsp antiEsp = config.antiEsp();
        if ((!antiEsp.hideStorage() && !antiEsp.hideOres()) || !config.general().enabled()) {
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
     * blocks. Each trace uses one unit of {@code budget}; without budget the answer is unknown
     * and the block is looked at again on a later examination.
     */
    private Sight sight(World world, Location eye, int x, int y, int z, int[] budget) {
        for (double[] point : SIGHT_POINTS) {
            if (budget[0]-- <= 0) {
                return Sight.UNKNOWN;
            }
            if (!probe.segmentBlocked(world, eye.getX(), eye.getY(), eye.getZ(), x + point[0], y + point[1],
                    z + point[2], x, y, z, true)) {
                return Sight.VISIBLE;
            }
        }
        return Sight.HIDDEN;
    }

    private void refreshTypes(Settings.AntiEsp config) {
        if (config == typesSource) {
            return;
        }
        storageTypes = materials(config.blocks());
        oreTypes = materials(config.ores());
        typesSource = config;
    }

    private static Set<Material> materials(List<String> patterns) {
        Set<Material> result = EnumSet.noneOf(Material.class);
        for (Material material : Material.values()) {
            try {
                if (!material.isLegacy() && Glob.matchesAny(patterns, material.name()) && material.isBlock()) {
                    result.add(material);
                }
            } catch (LinkageError | RuntimeException ignored) {
                // Materials the server cannot describe are simply not hidden.
            }
        }
        return result;
    }

    private LinkedHashMap<Long, List<int[]>> cache(World world) {
        return oreCache.computeIfAbsent(world.getUID(), id -> new LinkedHashMap<>(256, 0.75f, true));
    }

    private static BlockData replacement(World world, int y) {
        return switch (world.getEnvironment()) {
            case NETHER -> Material.NETHERRACK.createBlockData();
            case THE_END -> Material.END_STONE.createBlockData();
            default -> (y < 0 ? Material.DEEPSLATE : Material.STONE).createBlockData();
        };
    }

    private static double square(double value) {
        return value * value;
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
            logger.log(Level.WARNING, "Hiding blocks failed (the world is not affected)", error);
        }
    }

    // ---- test hooks and diagnostics -------------------------------------------------------------

    /** Replaces how storage blocks are found (test servers without block entity support). */
    public void setTileSource(TileSource source) {
        this.tileSource = source;
    }

    /** Replaces how ores are found (test servers without chunk snapshots). */
    public void setOreSource(OreSource source) {
        this.oreSource = source;
    }

    /** Number of blocks currently hidden from a player. */
    public int hiddenCount(UUID player) {
        View view = views.get(player);
        if (view == null) {
            return 0;
        }
        int count = 0;
        for (List<Tracked> entries : view.byChunk.values()) {
            for (Tracked tracked : entries) {
                if (tracked.hidden) {
                    count++;
                }
            }
        }
        return count;
    }
}
