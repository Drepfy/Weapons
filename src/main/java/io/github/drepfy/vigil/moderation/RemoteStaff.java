package io.github.drepfy.vigil.moderation;

/**
 * A command sender acting for a staff member outside the game, such as a Discord
 * moderator. Punishments show {@link #staffName()} as the staff member.
 */
public interface RemoteStaff {

    /** For example {@code "Bob (Discord)"}. */
    String staffName();
}
