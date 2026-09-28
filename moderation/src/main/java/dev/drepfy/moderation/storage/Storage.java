package dev.drepfy.moderation.storage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Runs {@link PunishmentStore} calls on dedicated database threads.
 *
 * <p>If the database couldn't be opened, every call fails. Callers treat failures as "unknown", so
 * logins are refused (when configured) instead of banned players being let in.
 */
public final class Storage implements AutoCloseable {

    @FunctionalInterface
    public interface Task<T> {
        T run(PunishmentStore store) throws Exception;
    }

    private final Database database;
    private final PunishmentStore store;
    private final ExecutorService executor;
    private final String failure;
    private final Logger logger;

    private Storage(Database database, int threads, String failure, Logger logger) {
        this.database = database;
        this.logger = logger;
        this.store = database == null ? null : new PunishmentStore(database);
        this.failure = failure;
        AtomicInteger counter = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "StaffModeration-DB-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    public static Storage of(Database database, int threads, Logger logger) {
        return new Storage(database, threads, null, logger);
    }

    /** A storage whose every call fails, used when the database couldn't be opened. */
    public static Storage unavailable(String reason, Logger logger) {
        return new Storage(null, 1, reason, logger);
    }

    public boolean available() {
        return database != null;
    }

    public String describe() {
        return database != null ? database.description() : "unavailable (" + failure + ")";
    }

    public <T> CompletableFuture<T> submit(Task<T> task) {
        if (store == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("Database unavailable: " + failure));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.run(store);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.failedFuture(new IllegalStateException("Plugin is shutting down", e));
        }
    }

    /** Lets queued writes finish, then closes the connection pool. */
    @Override
    public void close() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
                logger.warning("Timed out waiting for database writes to finish");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (database != null) {
            database.close();
        }
    }
}
