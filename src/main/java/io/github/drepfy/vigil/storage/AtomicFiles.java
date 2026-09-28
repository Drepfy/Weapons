package io.github.drepfy.vigil.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * File helpers that never leave a half-written file behind and never delete data.
 */
public final class AtomicFiles {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private AtomicFiles() {
    }

    /** Writes to a temporary file in the same directory, then moves it over the target. */
    public static void write(Path target, String content) throws IOException {
        Path parent = target.toAbsolutePath().getParent();
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temp, content, StandardCharsets.UTF_8);
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Moves an unreadable file aside (never deletes it) so it can be inspected and a
     * fresh file can be written in its place.
     *
     * @return the new location, or {@code null} if the move failed
     */
    public static Path quarantine(Path file) {
        try {
            Path target = file.resolveSibling(file.getFileName() + ".corrupt-" + LocalDateTime.now().format(STAMP));
            return Files.move(file, target);
        } catch (IOException e) {
            return null;
        }
    }
}
