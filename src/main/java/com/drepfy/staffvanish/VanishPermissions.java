package com.drepfy.staffvanish;

public final class VanishPermissions {

    /** Vanish yourself. Grants {@link #SEE} as a child permission. */
    public static final String USE = "staffvanish.use";
    /** See vanished players and receive vanish notifications. */
    public static final String SEE = "staffvanish.see";
    /** Toggle vanish for other players. */
    public static final String OTHERS = "staffvanish.others";
    /** List vanished players. */
    public static final String LIST = "staffvanish.list";
    /** Teleport while vanished, with the command or the staff selector. */
    public static final String TELEPORT = "staffvanish.teleport";
    /** Reload the configuration. */
    public static final String RELOAD = "staffvanish.reload";

    private VanishPermissions() {
    }
}
