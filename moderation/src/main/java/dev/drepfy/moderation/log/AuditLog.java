package dev.drepfy.moderation.log;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Append-only record of every moderation action, written to one file per day
 * ({@code logs/moderation-2026-01-31.log}) and optionally mirrored to the console.
 *
 * <p>Writes happen on a dedicated thread so callers (including chat and login threads) never
 * block on disk I/O. Messages are stripped of control characters so a crafted reason can't forge
 * extra log lines.
 */
public final class AuditLog implements AutoCloseable {

    public record Settings(boolean file, boolean console, int retentionDays) {
        public static final Settings DEFAULT = new Settings(true, true, 90);
    }

    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern FILE_NAME = Pattern.compile("moderation-(\\d{4}-\\d{2}-\\d{2})\\.log");
    private static final int MAX_MESSAGE_LENGTH = 4000;

    private final Path directory;
    private final Clock clock;
    private final Logger console;
    private final ExecutorService writer;
    private volatile Settings settings;

    // Only touched on the writer thread.
    private BufferedWriter out;
    private LocalDate outDate;

    public AuditLog(Path directory, Clock clock, Logger console, Settings settings) {
        this.directory = directory;
        this.clock = clock;
        this.console = console;
        this.settings = settings;
        this.writer = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "StaffModeration-AuditLog");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void configure(Settings settings) {
        this.settings = settings;
    }

    /** Records a moderation event, e.g. {@code record("BAN", "#12 Steve by Alex ...")}. */
    public void record(String category, String message) {
        write(Level.INFO, category, message);
    }

    /** Records something staff or admins should look into, such as a blocked bypass attempt. */
    public void warn(String category, String message) {
        write(Level.WARNING, category, message);
    }

    /**
     * Records an error in the file only; the caller logs it to the console itself (usually with a
     * stack trace).
     */
    public void error(String category, String message) {
        write(Level.SEVERE, category, message, false);
    }

    private void write(Level level, String category, String message) {
        write(level, category, message, true);
    }

    private void write(Level level, String category, String message, boolean toConsole) {
        String line = "[" + category + "] " + sanitize(message);
        Settings current = settings;
        if (toConsole && (current.console() || level.intValue() >= Level.WARNING.intValue())) {
            console.log(level, line);
        }
        if (!current.file()) {
            return;
        }
        ZonedDateTime time = ZonedDateTime.now(clock);
        String prefix = level == Level.INFO ? "" : level.getName() + " ";
        submit(() -> append(time, prefix + line));
    }

    /** Deletes log files older than the configured retention period. */
    public void purgeOldFiles() {
        submit(() -> {
            int days = settings.retentionDays();
            if (days <= 0 || !Files.isDirectory(directory)) {
                return;
            }
            LocalDate cutoff = LocalDate.now(clock).minusDays(days);
            try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "moderation-*.log")) {
                for (Path file : files) {
                    Matcher matcher = FILE_NAME.matcher(file.getFileName().toString());
                    if (matcher.matches() && LocalDate.parse(matcher.group(1)).isBefore(cutoff)) {
                        Files.deleteIfExists(file);
                    }
                }
            } catch (IOException | DateTimeParseException e) {
                console.log(Level.WARNING, "Could not clean up old moderation logs", e);
            }
        });
    }

    /** Flushes pending entries and closes the current file. */
    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                console.warning("Timed out writing the moderation log; some entries may be missing");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        closeFile();
    }

    private void submit(Runnable task) {
        try {
            writer.execute(task);
        } catch (RejectedExecutionException ignored) {
            // Shutting down; the console copy (if enabled) is all we can keep.
        }
    }

    private void append(ZonedDateTime time, String line) {
        try {
            LocalDate date = time.toLocalDate();
            if (out == null || !date.equals(outDate)) {
                closeFile();
                Files.createDirectories(directory);
                Path file = directory.resolve("moderation-" + date + ".log");
                out = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND, StandardOpenOption.WRITE);
                outDate = date;
            }
            out.write("[" + LINE_TIME.format(time) + "] " + line);
            out.newLine();
            out.flush();
        } catch (IOException e) {
            console.log(Level.WARNING, "Could not write to the moderation log: " + line, e);
            closeFile();
        }
    }

    private void closeFile() {
        if (out != null) {
            try {
                out.close();
            } catch (IOException ignored) {
                // Nothing useful left to do with a broken log file.
            }
            out = null;
            outDate = null;
        }
    }

    /** Replaces control characters (including line breaks) and trims overly long messages. */
    public static String sanitize(String message) {
        StringBuilder builder = new StringBuilder(Math.min(message.length(), MAX_MESSAGE_LENGTH));
        for (int i = 0; i < message.length() && builder.length() < MAX_MESSAGE_LENGTH; i++) {
            char c = message.charAt(i);
            int type = Character.getType(c);
            boolean lineBreak = type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR;
            builder.append(Character.isISOControl(c) || lineBreak ? ' ' : c);
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            builder.append("...");
        }
        return builder.toString();
    }
}
