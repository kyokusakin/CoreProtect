package net.coreprotect.fabric.service;

import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.coreprotect.fabric.db.CoreProtectDatabase;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.language.PhraseService;
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
        long copiedPending = 0L;
        long lastPendingCopiedId = 0L;
        long nextProgressAt = System.currentTimeMillis() + PROGRESS_INTERVAL_MS;

        try {
            targetDatabase = new CoreProtectDatabase(targetConfig, runtime.databaseRootDirectory(), logger);
            targetDatabase.start();

            long targetRows = targetDatabase.countAllEvents();
            long targetPendingRows = targetDatabase.countAllPendingInventoryRollbacks();
            if (targetRows > 0L || targetPendingRows > 0L) {
                throw userFacing(
                    "fabric.migrate.target_not_empty",
                    "Target database is not empty. Wipe the target database before running /co migrate-db again."
                );
            }

            long estimatedRows = sourceDatabase.countAllEvents();
            long estimatedPendingRows = sourceDatabase.countAllPendingInventoryRollbacks();
            send(source, "fabric.migrate.started", "CoreProtect migration started: {0} -> {1}", sourceDatabase.databaseDescription(), targetDatabase.databaseDescription());
            send(source, "fabric.migrate.scanning", "Scanning source database... rows={0}, pending-rollbacks={1}", estimatedRows, estimatedPendingRows);

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

            while (true) {
                var batch = sourceDatabase.fetchPendingInventoryRollbacksAfterId(lastPendingCopiedId, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }

                targetDatabase.importPendingInventoryRollbacks(batch);
                copiedPending += batch.size();
                lastPendingCopiedId = batch.get(batch.size() - 1).id();
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

            while (true) {
                var batch = sourceDatabase.fetchPendingInventoryRollbacksAfterId(lastPendingCopiedId, BATCH_SIZE);
                if (batch.isEmpty()) {
                    break;
                }

                targetDatabase.importPendingInventoryRollbacks(batch);
                copiedPending += batch.size();
                lastPendingCopiedId = batch.get(batch.size() - 1).id();
            }

            long verifiedSourceRows = sourceDatabase.countAllEvents();
            long verifiedSourcePendingRows = sourceDatabase.countAllPendingInventoryRollbacks();
            if (copied != verifiedSourceRows) {
                throw userFacing(
                    "fabric.migrate.verification_failed",
                    "Migration verification failed. Source rows={0}, copied rows={1}.",
                    verifiedSourceRows,
                    copied
                );
            }
            if (copiedPending != verifiedSourcePendingRows) {
                throw userFacing(
                    "fabric.migrate.verification_failed_pending",
                    "Migration verification failed for pending inventory rollbacks. Source rows={0}, copied rows={1}.",
                    verifiedSourcePendingRows,
                    copiedPending
                );
            }

            targetConfig.save(runtime.configPath());
            configSaved = true;

            int bufferedWrites = runtime.swapDatabase(targetConfig, targetDatabase);
            switched = true;
            targetDatabase = null;

            send(
                source,
                "fabric.migrate.complete",
                "CoreProtect migration complete: rows={0}, pending-rollbacks={1}, buffered-cutover-writes={2}, active-db={3}",
                copied,
                copiedPending,
                bufferedWrites,
                runtime.database().databaseDescription()
            );
            return new MigrationResult(copied, copiedPending, bufferedWrites);
        }
        catch (RuntimeException | IOException exception) {
            if (cutoverBuffering && !switched) {
                int replayed = runtime.logger().abortCutoverBuffering();
                sourceDatabase.awaitWriterQuiescence();
                send(
                    source,
                    "fabric.migrate.aborted",
                    "CoreProtect migration aborted. Replayed {0} buffered writes back to the source database.",
                    replayed
                );
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
            send(source, "fabric.migrate.progress.simple", "CoreProtect migration progress: copied={0}", copied);
        }
        else {
            long percent = Math.min(99L, (copied * 100L) / Math.max(estimatedRows, 1L));
            send(
                source,
                "fabric.migrate.progress.percent",
                "CoreProtect migration progress: copied={0}/{1} ({2}%)",
                copied,
                estimatedRows,
                percent
            );
        }
        return now + PROGRESS_INTERVAL_MS;
    }

    private static void send(ServerCommandSource source, String key, String fallback, Object... args) {
        source.sendFeedback(() -> Text.literal(PhraseService.getInstance().phrase(key, fallback, args)), false);
    }

    private static UserFacingMigrationException userFacing(String key, String fallback, Object... args) {
        return new UserFacingMigrationException(PhraseService.getInstance().phrase(key, fallback, args));
    }

    public static final class UserFacingMigrationException extends RuntimeException {
        public UserFacingMigrationException(String message) {
            super(message);
        }
    }

    public record MigrationResult(long copiedRows, long copiedPendingRollbacks, int bufferedWrites) {
    }
}
