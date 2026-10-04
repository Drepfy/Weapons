package io.github.drepfy.vigil.ticket;

import io.github.drepfy.vigil.storage.AtomicFiles;
import io.github.drepfy.vigil.storage.IoExecutor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps the tickets in {@code data/tickets.yml} and tells the in-game notifier and the
 * Discord bot about every change. Changes happen on the server thread; the two Discord
 * lookups ({@link #ticketForChannel}, {@link #openTicketOfDiscordUser}) work from any thread.
 */
public final class TicketService {

    /** Longest message kept; longer ones are cut. */
    public static final int MAX_MESSAGE_LENGTH = 1000;
    /** A ticket with this many messages takes no more (a new ticket can be opened). */
    public static final int MAX_MESSAGES = 300;

    /** Told about every change, on the server thread. */
    public interface Observer {
        /** A ticket was opened; its first message is already in it. */
        default void opened(Ticket ticket) {
        }

        default void message(Ticket ticket, TicketMessage message) {
        }

        default void claimed(Ticket ticket) {
        }

        default void closed(Ticket ticket) {
        }
    }

    private final Logger logger;
    private final IoExecutor io;
    private final Path file;
    private final Predicate<UUID> online;
    private final Map<Integer, Ticket> tickets = new TreeMap<>();
    private final Map<String, Integer> byChannel = new ConcurrentHashMap<>();
    private final Map<String, Integer> openByDiscordUser = new ConcurrentHashMap<>();
    private final List<Observer> observers = new CopyOnWriteArrayList<>();
    private int nextId = 1;
    private boolean dirty;
    private boolean readOnly;

    /**
     * @param online       whether a player is online (staff replies to offline players are marked unread)
     * @param keepClosedMs closed tickets older than this are deleted when loading ({@code -1} = never)
     */
    public TicketService(Logger logger, IoExecutor io, Path file, Predicate<UUID> online, long keepClosedMs) {
        this.logger = logger;
        this.io = io;
        this.file = file;
        this.online = online;
        load(keepClosedMs);
    }

    public void addObserver(Observer observer) {
        observers.add(observer);
    }

    // ---- changes (server thread) --------------------------------------------------------------------

    /**
     * Opens a ticket with its first message.
     *
     * @param uuid      the player opening it in game, or {@code null} on Discord
     * @param name      the player's name or the Discord user's name
     * @param discordId the Discord user opening it, or {@code null} in game
     */
    public Ticket open(TicketCategory category, UUID uuid, String name, String discordId, String minecraftName,
                       String about, String location, String text, TicketMessage.Origin origin) {
        long now = System.currentTimeMillis();
        Ticket ticket = new Ticket(nextId++, category, uuid, name, discordId, minecraftName, about, location, now);
        ticket.add(new TicketMessage(now, name, false, origin, cut(text)));
        tickets.put(ticket.id(), ticket);
        if (discordId != null) {
            openByDiscordUser.put(discordId, ticket.id());
        }
        changed();
        for (Observer observer : observers) {
            notify(() -> observer.opened(ticket));
        }
        return ticket;
    }

    /**
     * Adds a message.
     *
     * @return false if the ticket is closed or full
     */
    public boolean reply(Ticket ticket, String author, boolean staff, TicketMessage.Origin origin, String text) {
        if (!ticket.isOpen() || ticket.messages().size() >= MAX_MESSAGES) {
            return false;
        }
        TicketMessage message = new TicketMessage(System.currentTimeMillis(), author, staff, origin, cut(text));
        ticket.add(message);
        if (staff && ticket.openedInGame() && !online.test(ticket.creatorUuid())) {
            ticket.unread(true);
        }
        changed();
        for (Observer observer : observers) {
            notify(() -> observer.message(ticket, message));
        }
        return true;
    }

    /** @return false if the ticket is closed */
    public boolean claim(Ticket ticket, String staff) {
        if (!ticket.isOpen()) {
            return false;
        }
        ticket.claimedBy(staff);
        changed();
        for (Observer observer : observers) {
            notify(() -> observer.claimed(ticket));
        }
        return true;
    }

    /** @return false if it was already closed */
    public boolean close(Ticket ticket, String by, String reason) {
        if (!ticket.isOpen()) {
            return false;
        }
        ticket.close(System.currentTimeMillis(), by, reason);
        if (ticket.channelId() != null) {
            byChannel.remove(ticket.channelId(), ticket.id());
        }
        if (ticket.creatorDiscordId() != null) {
            openByDiscordUser.remove(ticket.creatorDiscordId(), ticket.id());
        }
        if (ticket.openedInGame() && !online.test(ticket.creatorUuid())) {
            ticket.unread(true);
        }
        changed();
        for (Observer observer : observers) {
            notify(() -> observer.closed(ticket));
        }
        return true;
    }

    /** Links (or with {@code null} unlinks) the ticket's Discord channel. */
    public void setChannel(Ticket ticket, String channelId) {
        if (ticket.channelId() != null) {
            byChannel.remove(ticket.channelId(), ticket.id());
        }
        ticket.channelId(channelId);
        if (channelId != null && ticket.isOpen()) {
            byChannel.put(channelId, ticket.id());
        }
        changed();
    }

    /** The in-game creator has read the latest replies. */
    public void markRead(Ticket ticket) {
        if (ticket.unread()) {
            ticket.unread(false);
            changed();
        }
    }

    // ---- lookups ----------------------------------------------------------------------------------

    /** @return the ticket, or {@code null} */
    public Ticket get(int id) {
        return tickets.get(id);
    }

    /** Open tickets, oldest first. */
    public List<Ticket> open() {
        List<Ticket> result = new ArrayList<>();
        for (Ticket ticket : tickets.values()) {
            if (ticket.isOpen()) {
                result.add(ticket);
            }
        }
        return result;
    }

    /** Open tickets the player opened in game, oldest first. */
    public List<Ticket> openOf(UUID uuid) {
        List<Ticket> result = new ArrayList<>();
        for (Ticket ticket : tickets.values()) {
            if (ticket.isOpen() && uuid.equals(ticket.creatorUuid())) {
                result.add(ticket);
            }
        }
        return result;
    }

    /** The player's tickets with replies they have not seen, oldest first. */
    public List<Ticket> unreadOf(UUID uuid) {
        List<Ticket> result = new ArrayList<>();
        for (Ticket ticket : tickets.values()) {
            if (ticket.unread() && uuid.equals(ticket.creatorUuid())) {
                result.add(ticket);
            }
        }
        return result;
    }

    /** The open ticket with this Discord channel, or {@code null}. Any thread. */
    public Integer ticketForChannel(String channelId) {
        return channelId == null ? null : byChannel.get(channelId);
    }

    /** The Discord channel of an open ticket, or {@code null}. Any thread. */
    public String channelOfOpenTicket(int id) {
        for (Map.Entry<String, Integer> entry : byChannel.entrySet()) {
            if (entry.getValue() == id) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The open ticket this Discord user opened, or {@code null}. Any thread. */
    public Integer openTicketOfDiscordUser(String userId) {
        return userId == null ? null : openByDiscordUser.get(userId);
    }

    // ---- storage -------------------------------------------------------------------------------------

    public void saveIfDirty() {
        if (!dirty || readOnly) {
            return;
        }
        String snapshot = serialize();
        dirty = false;
        boolean queued = io.execute("save tickets", () -> {
            try {
                AtomicFiles.write(file, snapshot);
            } catch (IOException e) {
                logger.warning("Could not save tickets: " + e.getMessage());
            }
        });
        if (!queued) {
            dirty = true;
        }
    }

    public void saveNow() {
        if (!dirty || readOnly) {
            return;
        }
        try {
            AtomicFiles.write(file, serialize());
            dirty = false;
        } catch (IOException e) {
            logger.warning("Could not save tickets: " + e.getMessage());
        }
    }

    private void changed() {
        dirty = true;
        saveIfDirty();
    }

    /** One broken listener must not stop the others (or the command) from working. */
    private void notify(Runnable call) {
        try {
            call.run();
        } catch (RuntimeException e) {
            logger.log(Level.WARNING, "Error while handling a ticket change", e);
        }
    }

    private static String cut(String text) {
        String trimmed = text == null ? "" : text.trim();
        return trimmed.length() > MAX_MESSAGE_LENGTH ? trimmed.substring(0, MAX_MESSAGE_LENGTH) : trimmed;
    }

    private String serialize() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("format", 1);
        yaml.set("next-id", nextId);
        for (Ticket ticket : tickets.values()) {
            ticket.save(yaml.createSection("tickets." + ticket.id()));
        }
        return yaml.saveToString();
    }

    private void load(long keepClosedMs) {
        if (!Files.exists(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            Path moved = AtomicFiles.quarantine(file);
            if (moved == null) {
                readOnly = true;
                logger.severe("tickets.yml is unreadable and could not be moved aside (" + e.getMessage()
                        + "). Tickets will NOT be saved this session to avoid overwriting it.");
            } else {
                logger.severe("tickets.yml is unreadable (" + e.getMessage() + "); moved to " + moved.getFileName()
                        + ".");
            }
            return;
        }
        nextId = Math.max(1, yaml.getInt("next-id", 1));
        ConfigurationSection section = yaml.getConfigurationSection("tickets");
        if (section == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int skipped = 0;
        int removed = 0;
        for (String key : section.getKeys(false)) {
            try {
                int id = Integer.parseInt(key);
                ConfigurationSection entry = section.getConfigurationSection(key);
                if (entry == null) {
                    skipped++;
                    continue;
                }
                Ticket ticket = Ticket.load(id, entry);
                nextId = Math.max(nextId, id + 1);
                if (!ticket.isOpen() && keepClosedMs >= 0 && now - ticket.closed() > keepClosedMs) {
                    removed++;
                    continue;
                }
                tickets.put(id, ticket);
                if (ticket.isOpen()) {
                    if (ticket.channelId() != null) {
                        byChannel.put(ticket.channelId(), id);
                    }
                    if (ticket.creatorDiscordId() != null) {
                        openByDiscordUser.put(ticket.creatorDiscordId(), id);
                    }
                }
            } catch (RuntimeException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            logger.warning(skipped + " malformed ticket(s) in tickets.yml were skipped.");
        }
        if (removed > 0) {
            dirty = true;
        }
    }
}
