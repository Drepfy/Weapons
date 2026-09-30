package io.github.drepfy.vigil.storage;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Single background thread for all disk IO. The queue is bounded: if the disk
 * stalls, new work is dropped (and counted) instead of growing memory without limit
 * or ever blocking the server thread.
 */
public final class IoExecutor {

    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();
    private final Logger logger;

    public IoExecutor(Logger logger) {
        this.logger = logger;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(10_000),
                runnable -> {
                    Thread thread = new Thread(runnable, "Vigil-IO");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                },
                (runnable, pool) -> {
                    if (dropped.incrementAndGet() % 1000 == 1) {
                        logger.warning("IO queue full, dropped " + dropped.get() + " write(s) so far.");
                    }
                    throw new RejectedExecutionException("IO queue full");
                });
    }

    /**
     * Runs a task on the IO thread; exceptions are logged, never propagated.
     *
     * @return false if the task was dropped (queue full or shutting down)
     */
    public boolean execute(String description, Runnable task) {
        if (executor.isShutdown()) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "IO task failed: " + description, t);
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    public int queued() {
        return executor.getQueue().size();
    }

    public long dropped() {
        return dropped.get();
    }

    public boolean isIdle() {
        return executor.getQueue().isEmpty() && executor.getActiveCount() == 0;
    }

    /** Stops accepting work and waits up to {@code timeoutMs} for queued writes to finish. */
    public void shutdown(long timeoutMs) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS)) {
                logger.warning("Timed out waiting for pending writes; " + executor.getQueue().size()
                        + " task(s) were not written.");
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
