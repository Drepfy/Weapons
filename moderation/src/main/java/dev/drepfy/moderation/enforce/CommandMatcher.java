package dev.drepfy.moderation.enforce;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * Decides whether a command line typed by a muted player runs one of the blocked commands.
 *
 * <p>Matching is done on the command's real identity rather than the literal text, so these all
 * count as {@code msg}: {@code /MSG}, {@code /minecraft:msg}, {@code /essentials:msg}, an alias
 * such as {@code /whisper} that resolves to a command named {@code msg}, and
 * {@code /execute as @s run msg ...}.
 */
public final class CommandMatcher {

    private static final int MAX_EXECUTE_DEPTH = 8;

    private final Set<String> blocked;

    public CommandMatcher(Collection<String> blockedCommands) {
        Set<String> normalized = new HashSet<>();
        for (String command : blockedCommands) {
            String name = normalize(command);
            if (!name.isEmpty()) {
                normalized.add(name);
            }
        }
        this.blocked = Set.copyOf(normalized);
    }

    /**
     * @param commandLine the full message from the command event, e.g. {@code "/msg Steve hi"}
     * @param resolver    maps a typed label to the names of the command it runs (its real name and
     *                    label); may return an empty collection for unknown commands
     * @return the blocked command that matched, if any
     */
    public Optional<String> match(String commandLine, Function<String, Collection<String>> resolver) {
        return match(commandLine, resolver, 0);
    }

    private Optional<String> match(String commandLine, Function<String, Collection<String>> resolver, int depth) {
        if (blocked.isEmpty() || depth > MAX_EXECUTE_DEPTH) {
            return Optional.empty();
        }
        String line = commandLine.strip();
        if (line.startsWith("/")) {
            line = line.substring(1);
        }
        List<String> tokens = List.of(line.split("\\s+"));
        if (tokens.isEmpty() || tokens.getFirst().isEmpty()) {
            return Optional.empty();
        }
        String label = tokens.getFirst().toLowerCase(Locale.ROOT);
        String name = normalize(label);
        if (blocked.contains(name)) {
            return Optional.of(name);
        }
        for (String resolved : resolver.apply(label)) {
            String resolvedName = normalize(resolved);
            if (blocked.contains(resolvedName)) {
                return Optional.of(resolvedName);
            }
        }
        if (name.equals("execute")) {
            int run = tokens.indexOf("run");
            if (run >= 0 && run + 1 < tokens.size()) {
                return match(String.join(" ", tokens.subList(run + 1, tokens.size())), resolver, depth + 1);
            }
        }
        return Optional.empty();
    }

    /** Lower-cases a command name and strips any {@code namespace:} prefix and leading slash. */
    static String normalize(String command) {
        String name = command.strip().toLowerCase(Locale.ROOT);
        while (name.startsWith("/")) {
            name = name.substring(1);
        }
        int colon = name.lastIndexOf(':');
        return colon >= 0 ? name.substring(colon + 1) : name;
    }
}
