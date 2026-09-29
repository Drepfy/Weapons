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

    /** Open the vanish settings menu. */
    public static final String SETTINGS = "staffvanish.settings";
    /** Look at another player's inventory with /invsee or the Vanish Stick. */
    public static final String INVSEE = "staffvanish.invsee";
    /** Look at another player's ender chest with /endersee. */
    public static final String ENDERSEE = "staffvanish.endersee";
    /** Vanish level N (1-10): staff only see vanished players whose level is the same or lower. */
    public static final String LEVEL_PREFIX = "staffvanish.level.";
    public static final int MAX_LEVEL = 10;

    private VanishPermissions() {
    }
}
