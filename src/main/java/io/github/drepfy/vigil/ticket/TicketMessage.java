package io.github.drepfy.vigil.ticket;

import java.util.Locale;

/**
 * One message in a ticket.
 *
 * @param author display name (a player name or a Discord name)
 * @param staff  written by staff (in game or on Discord)
 * @param origin where it was written
 */
public record TicketMessage(long time, String author, boolean staff, Origin origin, String text) {

    public enum Origin {
        /** Written in game. */
        GAME,
        /** Written in the ticket's Discord channel (already visible there). */
        DISCORD,
        /** Filled in on the Discord ticket form (not visible in a channel yet). */
        FORM;

        static Origin parse(String text) {
            try {
                return valueOf(text.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException | NullPointerException e) {
                return GAME;
            }
        }
    }

    /** Whether the bot still has to post it in the ticket's Discord channel. */
    public boolean needsDiscordPost() {
        return origin != Origin.DISCORD;
    }
}
