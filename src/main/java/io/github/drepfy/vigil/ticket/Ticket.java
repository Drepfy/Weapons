package io.github.drepfy.vigil.ticket;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A support ticket: a conversation between a player (in game or on Discord) and staff.
 * Only changed through {@link TicketService}, on the server thread.
 */
public final class Ticket {

    private final int id;
    private final TicketCategory category;
    private final UUID creatorUuid;
    private final String creatorName;
    private final String creatorDiscordId;
    private final String minecraftName;
    private final String about;
    private final String location;
    private final long created;
    private final List<TicketMessage> messages = new ArrayList<>();
    private String channelId;
    private String claimedBy;
    private long closed;
    private String closedBy;
    private String closeReason;
    private boolean unread;

    /**
     * @param creatorUuid      the player who opened it in game, or {@code null} when opened on Discord
     * @param creatorDiscordId the Discord user who opened it, or {@code null} when opened in game
     * @param minecraftName    the Minecraft name given on the Discord form ({@code null} if none)
     * @param about            the reported or banned player, if any
     * @param location         where it was opened in game ({@code world;x;y;z;yaw;pitch}), if anywhere
     */
    Ticket(int id, TicketCategory category, UUID creatorUuid, String creatorName, String creatorDiscordId,
           String minecraftName, String about, String location, long created) {
        this.id = id;
        this.category = category;
        this.creatorUuid = creatorUuid;
        this.creatorName = creatorName;
        this.creatorDiscordId = creatorDiscordId;
        this.minecraftName = minecraftName;
        this.about = about;
        this.location = location;
        this.created = created;
    }

    public int id() {
        return id;
    }

    public TicketCategory category() {
        return category;
    }

    /** The player who opened it in game, or {@code null} when it was opened on Discord. */
    public UUID creatorUuid() {
        return creatorUuid;
    }

    public String creatorName() {
        return creatorName;
    }

    /** The Discord user who opened it, or {@code null} when it was opened in game. */
    public String creatorDiscordId() {
        return creatorDiscordId;
    }

    /** The Minecraft name: the player's own for in-game tickets, the one given on the Discord form otherwise. */
    public String minecraftName() {
        return creatorUuid != null ? creatorName : minecraftName;
    }

    /** The reported or banned player, or {@code null}. */
    public String about() {
        return about;
    }

    /** {@code world;x;y;z;yaw;pitch} where it was opened in game, or {@code null}. */
    public String location() {
        return location;
    }

    public long created() {
        return created;
    }

    public List<TicketMessage> messages() {
        return Collections.unmodifiableList(messages);
    }

    /** The ticket's Discord channel, or {@code null}. */
    public String channelId() {
        return channelId;
    }

    /** The staff member handling it, or {@code null}. */
    public String claimedBy() {
        return claimedBy;
    }

    public boolean isOpen() {
        return closed == 0L;
    }

    public long closed() {
        return closed;
    }

    public String closedBy() {
        return closedBy;
    }

    public String closeReason() {
        return closeReason;
    }

    /** Staff replied while the (in-game) creator was offline. */
    public boolean unread() {
        return unread;
    }

    public long lastActivity() {
        return messages.isEmpty() ? created : messages.get(messages.size() - 1).time();
    }

    public boolean openedInGame() {
        return creatorUuid != null;
    }

    // ---- changes (TicketService only) ------------------------------------------------------------

    void add(TicketMessage message) {
        messages.add(message);
    }

    void channelId(String channelId) {
        this.channelId = channelId;
    }

    void claimedBy(String staff) {
        this.claimedBy = staff;
    }

    void close(long time, String by, String reason) {
        this.closed = time;
        this.closedBy = by;
        this.closeReason = reason;
    }

    void unread(boolean unread) {
        this.unread = unread;
    }

    // ---- storage ---------------------------------------------------------------------------------

    void save(ConfigurationSection section) {
        section.set("category", category.key());
        if (creatorUuid != null) {
            section.set("creator-uuid", creatorUuid.toString());
        }
        section.set("creator", creatorName);
        section.set("creator-discord", creatorDiscordId);
        section.set("minecraft-name", minecraftName);
        section.set("about", about);
        section.set("location", location);
        section.set("created", created);
        section.set("channel", channelId);
        section.set("claimed-by", claimedBy);
        if (closed != 0L) {
            section.set("closed", closed);
            section.set("closed-by", closedBy);
            section.set("close-reason", closeReason);
        }
        if (unread) {
            section.set("unread", true);
        }
        List<Map<String, Object>> lines = new ArrayList<>();
        for (TicketMessage message : messages) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("time", message.time());
            line.put("author", message.author());
            line.put("staff", message.staff());
            line.put("origin", message.origin().name().toLowerCase(java.util.Locale.ROOT));
            line.put("text", message.text());
            lines.add(line);
        }
        section.set("messages", lines);
    }

    static Ticket load(int id, ConfigurationSection section) {
        TicketCategory category = TicketCategory.fromKey(section.getString("category", "support"));
        String rawUuid = section.getString("creator-uuid");
        Ticket ticket = new Ticket(id, category != null ? category : TicketCategory.SUPPORT,
                rawUuid != null ? UUID.fromString(rawUuid) : null, section.getString("creator", "unknown"),
                section.getString("creator-discord"), section.getString("minecraft-name"), section.getString("about"),
                section.getString("location"), section.getLong("created"));
        ticket.channelId = section.getString("channel");
        ticket.claimedBy = section.getString("claimed-by");
        ticket.closed = section.getLong("closed", 0L);
        ticket.closedBy = section.getString("closed-by");
        ticket.closeReason = section.getString("close-reason");
        ticket.unread = section.getBoolean("unread", false);
        for (Map<?, ?> line : section.getMapList("messages")) {
            Object time = line.get("time");
            Object author = line.get("author");
            Object text = line.get("text");
            if (author == null || text == null) {
                continue;
            }
            ticket.messages.add(new TicketMessage(time instanceof Number number ? number.longValue() : 0L,
                    author.toString(), Boolean.TRUE.equals(line.get("staff")),
                    TicketMessage.Origin.parse(String.valueOf(line.get("origin"))), text.toString()));
        }
        return ticket;
    }
}
