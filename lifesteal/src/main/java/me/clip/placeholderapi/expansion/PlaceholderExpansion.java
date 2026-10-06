package me.clip.placeholderapi.expansion;

import me.clip.placeholderapi.PlaceholderHook;

/**
 * Compile-time stand-in for PlaceholderAPI's class of the same name (same signatures as
 * PlaceholderAPI 2.11 and newer). It is left out of the plugin jar: on the server the real one,
 * from PlaceholderAPI, is used.
 */
public abstract class PlaceholderExpansion extends PlaceholderHook {

    public abstract String getIdentifier();

    public abstract String getAuthor();

    public abstract String getVersion();

    public boolean persist() {
        return false;
    }

    public boolean canRegister() {
        return true;
    }

    public boolean register() {
        return false;
    }
}
