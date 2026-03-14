package net.coreprotect.fabric.service;

import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.coreprotect.fabric.db.CoreProtectDatabase;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;

public final class DatabaseMigrationService {
    private static final int BATCH_SIZE = 1_000;
    private static final long PROGRESS_INTERVAL_MS = 2_000L;

    private DatabaseMigrationService() {
    }

    public static MigrationResult migrate(ServerCommandSource source, FabricRuntime runtime, CoreProtectFabricConfig.DatabaseType targetType, Logger logger) {
        CoreProtectDatabase sourceDatabase = runtime.database();
        CoreProtectFabricConfig currentConfig = runtime.config();
        CoreProtectFabricConfig targetConfig = currentConfig.withDatabaseType(targetType);
        CoreProtectDatabase targetDatabase = null;
        boolean cutoverBuffering = false;
        boolean configSaved = false;
        boolean switched = false;
        long copied = 0L;
        long lastCopiedId = 0L;
        long nextProgressAt = System.currentTimeMillis() + PROGRESS_INTERVAL_MS;

        try {
            targetDatabase = new CoreProtectDatabase(targetConfig, runtime.rootDirectory(), logger);
            targetDatabase.start();

            long targetRows = targetDatabase.countAllEvents();
            if (targetRows > 0L) {
                throw new IllegalStateException("Target database is not empty. Wipe the target database before running /co migrate-db again.");
            }

            long estimatedRows = sourceDatabase.countAllEvents();
            send(source, "CoreProtect migration started: " + sourceDatabase.databaseDescription() + " -> " + targetDatabase.databaseDescription());
            send(source, "Scanning source database... rows=" + estimatedRows);

            while (true) {
                List<StoredEventRecord> batch = sourceDatabase.fetchEventsAfterId(lastCopiedId, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }

                targetDatabase.importEvents(batch);
                copied += batch.size();
                lastCopiedId = batch.get(batch.size() - 1).id();
                nextProgressAt = maybeReportProgress(source, copied, estimatedRows, nextProgressAt);
            }

            runtime.logger().beginCutoverBuffering();
            cutoverBuffering = true;
            sourceDatabase.awaitWriterQuiescence();

            while (true) {
                List<StoredEventRecord> batch = sourceDatabase.fetchEventsAfterId(lastCopiedId, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }

                targetDatabase.importEvents(batch);
                copied += batch.size();
                lastCopiedId = batch.get(batch.size() - 1).id();
            }

            long verifiedSourceRows = sourceDatabase.countAllEvents();
            if (copied != verifiedSourceRows) {
                throw new IllegalStateException("Migration verification failed. Source rows=" + verifiedSourceRows + ", copied rows=" + copied + ".");
            }

            targetConfig.save(runtime.configPath());
            configSaved = true;

            int bufferedWrites = runtime.swapDatabase(targetConfig, targetDatabase);
            switched = true;
            targetDatabase = null;

            send(source, "CoreProtect migration complete: rows=" + copied + ", buffered-cutover-writes=" + bufferedWrites + ", active-db=" + runtime.database().databaseDescription());
            return new MigrationResult(copied, bufferedWrites);
        }
        catch (RuntimeException | IOException exception) {
            if (cutoverBuffering && !switched) {
                int replayed = runtime.logger().abortCutoverBuffering();
                sourceDatabase.awaitWriterQuiescence();
                send(source, "CoreProtect migration aborted. Replayed " + replayed + " buffered writes back to the source database.");
            }

            if (configSaved && !switched) {
                try {
                    currentConfig.save(runtime.configPath());
                }
                catch (IOException restoreException) {
                    logger.error("Failed to restore CoreProtect Fabric configuration after migration failure", restoreException);
                }
            }

            if (targetDatabase != null) {
                targetDatabase.close();
            }

            throw exception instanceof RuntimeException ? (RuntimeException) exception : new IllegalStateException(exception);
        }
    }

    private static long maybeReportProgress(ServerCommandSource source, long copied, long estimatedRows, long nextProgressAt) {
        long now = System.currentTimeMillis();
        if (now < nextProgressAt) {
            return nextProgressAt;
        }

        if (estimatedRows <= 0L) {
            send(source, "CoreProtect migration progress: copied=" + copied);
        }
        else {
            long percent = Math.min(99L, (copied * 100L) / Math.max(estimatedRows, 1L));
            send(source, "CoreProtect migration progress: copied=" + copied + "/" + estimatedRows + " (" + percent + "%)");
        }
        return now + PROGRESS_INTERVAL_MS;
    }

    private static void send(ServerCommandSource source, String message) {
        source.sendFeedback(() -> Text.literal(message), false);
    }

    public record MigrationResult(long copiedRows, int bufferedWrites) {
    }
}
