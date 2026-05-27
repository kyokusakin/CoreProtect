package net.coreprotect.fabric.service;

import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.command.PurgeCommandParser;
import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import org.slf4j.Logger;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class AutoPurgeService {
    private static final long INITIAL_DELAY_MS = 60_000L;
    private static final long INTERVAL_MS = 24L * 60L * 60L * 1000L;

    private final FabricRuntime runtime;
    private final Logger logger;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "coreprotect-auto-purge");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong rowsPurgedSinceRestart = new AtomicLong(0L);
    private volatile long nextRunAt;

    public AutoPurgeService(FabricRuntime runtime, Logger logger) {
        this.runtime = runtime;
        this.logger = logger;
        this.nextRunAt = System.currentTimeMillis() + INITIAL_DELAY_MS;
    }

    public boolean isEnabled() {
        return configuredSeconds() > 0;
    }

    public long rowsPurgedSinceRestart() {
        return rowsPurgedSinceRestart.get();
    }

    public void tick() {
        int seconds = configuredSeconds();
        if (seconds <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextRunAt) {
            return;
        }
        nextRunAt = now + INTERVAL_MS;
        if (running.compareAndSet(false, true)) {
            executor.execute(() -> runPurge(seconds));
        }
    }

    private void runPurge(int seconds) {
        try {
            if (runtime.database() == null || runtime.database().writesPaused()) {
                // No database yet, or another purge already holds the consumer paused — skip this cycle.
                return;
            }
            runtime.database().setWritesPaused(true);
            try {
                int deleted = runtime.database().purgeOlderThan(seconds, null, null);
                if (deleted > 0) {
                    rowsPurgedSinceRestart.addAndGet(deleted);
                    logger.info("CoreProtect auto purge removed {} rows older than {} seconds", deleted, seconds);
                }
            }
            finally {
                runtime.database().setWritesPaused(false);
            }
        }
        catch (RuntimeException exception) {
            logger.error("CoreProtect auto purge failed", exception);
        }
        finally {
            running.set(false);
        }
    }

    private int configuredSeconds() {
        CoreProtectFabricConfig config = runtime.config();
        return config == null ? 0 : PurgeCommandParser.autoPurgeSeconds(config.autoPurge());
    }

    public void shutdown() {
        executor.shutdownNow();
    }
}
