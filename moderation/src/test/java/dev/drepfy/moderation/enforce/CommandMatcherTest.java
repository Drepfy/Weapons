package dev.drepfy.moderation.enforce;

import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CommandMatcherTest {

    /** Simulates the server's command map: alias label -> the command's real name and label. */
    private static final Map<String, List<String>> COMMAND_MAP = Map.of(
            "whisper", List.of("msg", "msg"),
            "essentials:whisper", List.of("msg", "msg"),
            "emsg", List.of("msg", "msg"),
            "tpa", List.of("tpa", "tpa"));
    private static final Function<String, Collection<String>> RESOLVER = label -> COMMAND_MAP.getOrDefault(label, List.of());

    private final CommandMatcher matcher = new CommandMatcher(List.of("msg", "/Tell", "r", "me"));

    private Optional<String> match(String line) {
        return matcher.match(line, RESOLVER);
    }

    @Test
    void matchesPlainAndCaseInsensitiveCommands() {
        assertEquals(Optional.of("msg"), match("/msg Steve hi"));
        assertEquals(Optional.of("msg"), match("/MSG Steve hi"));
        assertEquals(Optional.of("tell"), match("/tell Steve hi"));
        assertEquals(Optional.of("r"), match("/r hi"));
        assertEquals(Optional.of("me"), match("/me waves"));
    }

    @Test
    void matchesNamespacedCommands() {
        assertEquals(Optional.of("msg"), match("/minecraft:msg Steve hi"));
        assertEquals(Optional.of("tell"), match("/essentials:tell Steve hi"));
    }

    @Test
    void matchesAliasesThroughTheCommandMap() {
        assertEquals(Optional.of("msg"), match("/whisper Steve hi"));
        assertEquals(Optional.of("msg"), match("/essentials:whisper Steve hi"));
        assertEquals(Optional.of("msg"), match("/emsg Steve hi"));
    }

    @Test
    void matchesCommandsWrappedInExecute() {
        assertEquals(Optional.of("msg"), match("/execute as @s run msg Steve hi"));
        assertEquals(Optional.of("tell"), match("/minecraft:execute at @p run execute as @s run tell @a hi"));
    }

    @Test
    void allowsUnrelatedCommands() {
        assertEquals(Optional.empty(), match("/tpa Steve"));
        assertEquals(Optional.empty(), match("/msgtoggle"));
        assertEquals(Optional.empty(), match("/spawn"));
        assertEquals(Optional.empty(), match("/execute as @s run spawn"));
        assertEquals(Optional.empty(), match("/"));
        assertEquals(Optional.empty(), match(""));
    }

    @Test
    void emptyBlockListMatchesNothing() {
        assertEquals(Optional.empty(), new CommandMatcher(List.of()).match("/msg Steve hi", RESOLVER));
    }

    @Test
    void normalizesNames() {
        assertEquals("msg", CommandMatcher.normalize("/Essentials:MSG "));
        assertEquals("tell", CommandMatcher.normalize("tell"));
    }
}
