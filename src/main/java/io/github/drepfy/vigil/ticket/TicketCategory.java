package io.github.drepfy.vigil.ticket;

import java.util.Locale;

/** What a ticket is about. Each one is a button on the Discord ticket panel. */
public enum TicketCategory {
    SUPPORT("Support"),
    REPORT("Report a player"),
    APPEAL("Ban appeal");

    private final String display;

    TicketCategory(String display) {
        this.display = display;
    }

    public String display() {
        return display;
    }

    /** Stored in tickets.yml and in Discord button IDs, e.g. {@code support}. */
    public String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** @return the category with this key, or {@code null} */
    public static TicketCategory fromKey(String key) {
        for (TicketCategory category : values()) {
            if (category.key().equalsIgnoreCase(key)) {
                return category;
            }
        }
        return null;
    }
}
