package io.github.drepfy.vigil.storage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.logging.Logger;

/**
 * Appends flag lines to {@code logs/flags-YYYY-MM-DD.log} on the IO thread.
 */
public final class FlagLogWriter {

    private static final String PREFIX = "flags-";
    private static final String SUFFIX = ".log";

    private final Path directory;
    private final IoExecutor io;
    private final Logger logger;
    // Only touched on the IO thread.
    private BufferedWriter writer;
    private LocalDate writerDate;
    private boolean failed;

    public FlagLogWriter(Path directory, IoExecutor io, Logger logger) {
        this.directory = directory;
        this.io = io;
        this.logger = logger;
    }

    public void append(String line) {
        io.execute("append flag log", () -> {
            try {
                BufferedWriter out = writerFor(LocalDate.now());
                out.write(line);
                out.newLine();
                if (io.queued() == 0) {
                    out.flush();
                }
                failed = false;
            } catch (IOException e) {
                if (!failed) {
                    logger.warning("Could not write flag log: " + e.getMessage());
                    failed = true;
                }
                closeQuietly();
            }
        });
    }

    /** Deletes flag logs older than {@code days} (only when explicitly configured, days > 0). */
    public void applyRetention(int days) {
        if (days <= 0) {
            return;
        }
        io.execute("flag log retention", () -> {
            if (!Files.isDirectory(directory)) {
                return;
            }
            LocalDate cutoff = LocalDate.now().minusDays(days);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, PREFIX + "*" + SUFFIX)) {
                for (Path file : files) {
                    String name = file.getFileName().toString();
                    String date = name.substring(PREFIX.length(), name.length() - SUFFIX.length());
                    try {
                        if (LocalDate.parse(date, DateTimeFormatter.ISO_LOCAL_DATE).isBefore(cutoff)) {
                            Files.deleteIfExists(file);
                        }
                    } catch (DateTimeParseException ignored) {
                        // Not one of our files; leave it alone.
                    }
                }
            } catch (IOException e) {
                logger.warning("Could not apply log retention: " + e.getMessage());
            }
        });
    }

    /** Flushes and closes the current file on the IO thread. */
    public void close() {
        io.execute("close flag log", this::closeQuietly);
    }

    private BufferedWriter writerFor(LocalDate date) throws IOException {
        if (writer == null || !date.equals(writerDate)) {
            closeQuietly();
            Files.createDirectories(directory);
            Path file = directory.resolve(PREFIX + date.format(DateTimeFormatter.ISO_LOCAL_DATE) + SUFFIX);
            writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            writerDate = date;
        }
        return writer;
    }

    private void closeQuietly() {
        if (writer != null) {
            try {
                writer.close();
            } catch (IOException ignored) {
                // Nothing more we can do.
            }
            writer = null;
        }
    }
}
