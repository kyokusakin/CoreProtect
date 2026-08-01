package net.coreprotect.fabric.db;

import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.util.LoggedSignState;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

public final class CoreProtectDatabase implements AutoCloseable {
    private static final long QUIESCENCE_TIMEOUT_MS = 5_000L;
    private static final long CLOSE_DRAIN_TIMEOUT_MS = 15_000L;
    private static final int WRITE_BATCH_SIZE = 128;
    private static final int PURGE_ORPHAN_ENTITY_BATCH_SIZE = 1_000;
    private static final int PURGE_ORPHAN_ENTITY_MAX_BATCHES = 64;
    private static final int PURGE_ORPHAN_MAPPING_BATCH_SIZE = 1_000;
    private static final int PURGE_ORPHAN_MAPPING_MAX_BATCHES = 64;
    private static final int MAPPING_BACKFILL_BATCH_SIZE = 500;
    private static final int MAX_PENDING_INVENTORY_ATTEMPTS = 20;
    private static final String PENDING_INVENTORY_STATUS_PENDING = "PENDING";
    private static final String PENDING_INVENTORY_STATUS_APPLIED = "APPLIED";
    private static final String PENDING_INVENTORY_STATUS_FAILED = "FAILED";
    private static final int MAX_PENDING_ERROR_LENGTH = 512;

    private final CoreProtectFabricConfig config;
    private final Path rootDirectory;
    private final Path databasePath;
    private final CoreProtectFabricConfig.DatabaseType databaseType;
    private final Logger logger;
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private final AtomicBoolean writesPaused = new AtomicBoolean(false);
    private final AtomicBoolean flushScheduled = new AtomicBoolean(false);
    private final Queue<EventRecord> pendingEventWrites = new ConcurrentLinkedQueue<>();
    private final Object quiescenceMonitor = new Object();
    private final Object pauseMonitor = new Object();
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "coreprotect-fabric-writer");
        thread.setDaemon(true);
        return thread;
    });

    private Connection writeConnection;

    public CoreProtectDatabase(CoreProtectFabricConfig config, Path rootDirectory, Logger logger) {
        this.config = config;
        this.rootDirectory = rootDirectory;
        this.databaseType = config.databaseType();
        this.databasePath = databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? rootDirectory.resolve(config.databaseFile()) : null;
        this.logger = logger;
    }

    public void start() {
        try {
            Class.forName(databaseType == CoreProtectFabricConfig.DatabaseType.MYSQL ? "com.mysql.cj.jdbc.Driver" : "org.sqlite.JDBC");
            writeConnection = openConnection();
            initializeSchema(writeConnection);
        }
        catch (ClassNotFoundException | SQLException | RuntimeException exception) {
            writer.shutdownNow();
            closeWriteConnection();
            throw new IllegalStateException("Unable to start CoreProtect database", exception);
        }
    }

    boolean hasOpenWriteConnection() {
        if (writeConnection == null) {
            return false;
        }
        try {
            return !writeConnection.isClosed();
        }
        catch (SQLException exception) {
            return false;
        }
    }

    public void write(EventRecord record) {
        if (record == null) {
            return;
        }

        pendingWrites.incrementAndGet();
        pendingEventWrites.offer(record);
        scheduleWriteFlush();
    }

    public int pendingWrites() {
        return pendingWrites.get();
    }

    private void decrementPendingWrites(int count) {
        if (count <= 0) {
            return;
        }

        int remaining = pendingWrites.addAndGet(-count);
        if (remaining <= 0) {
            synchronized (quiescenceMonitor) {
                quiescenceMonitor.notifyAll();
            }
        }
    }

    private void scheduleWriteFlush() {
        if (!flushScheduled.compareAndSet(false, true)) {
            return;
        }

        try {
            writer.execute(this::flushPendingWrites);
        }
        catch (RejectedExecutionException exception) {
            flushScheduled.set(false);
            int dropped = 0;
            while (pendingEventWrites.poll() != null) {
                dropped++;
            }
            if (dropped > 0) {
                decrementPendingWrites(dropped);
                logger.error("Dropped {} pending CoreProtect event writes because the writer is shutting down", dropped, exception);
            }
        }
    }

    private void flushPendingWrites() {
        try {
            while (true) {
                awaitWritesResumed();
                List<EventRecord> batch = drainWriteBatch();
                if (batch.isEmpty()) {
                    return;
                }

                try {
                    insertBatch(batch);
                }
                catch (SQLException exception) {
                    insertBatchWithFallback(batch, exception);
                }
                finally {
                    decrementPendingWrites(batch.size());
                }
            }
        }
        finally {
            flushScheduled.set(false);
            if (!pendingEventWrites.isEmpty()) {
                scheduleWriteFlush();
            }
        }
    }

    private List<EventRecord> drainWriteBatch() {
        List<EventRecord> batch = new ArrayList<>(WRITE_BATCH_SIZE);
        while (batch.size() < WRITE_BATCH_SIZE) {
            EventRecord record = pendingEventWrites.poll();
            if (record == null) {
                break;
            }
            batch.add(record);
        }
        return batch;
    }

    private void insertBatchWithFallback(List<EventRecord> batch, SQLException batchException) {
        if (batch == null || batch.isEmpty()) {
            return;
        }

        logger.error(
            "Failed to persist {} Fabric audit event(s) in one batch, retrying individually",
            batch.size(),
            batchException
        );

        int dropped = 0;
        for (EventRecord record : batch) {
            try {
                insertBatch(List.of(record));
            }
            catch (SQLException exception) {
                dropped++;
                logger.error("Failed to persist Fabric audit event {}", record.type(), exception);
            }
        }

        if (dropped > 0) {
            logger.error("Dropped {} Fabric audit event(s) after single-row retry fallback", dropped);
        }
    }

    public boolean writesPaused() {
        return writesPaused.get();
    }

    public void setWritesPaused(boolean paused) {
        writesPaused.set(paused);
        if (!paused) {
            synchronized (pauseMonitor) {
                pauseMonitor.notifyAll();
            }
        }
    }

    public Path databasePath() {
        return databasePath;
    }

    public CoreProtectFabricConfig.DatabaseType databaseType() {
        return databaseType;
    }

    public String databaseDescription() {
        if (databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE) {
            return "sqlite:" + databasePath.toAbsolutePath();
        }
        return "mysql:" + config.mysqlHost() + ":" + config.mysqlPort() + "/" + config.mysqlDatabase();
    }

    public long countAllEvents() {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) AS total FROM cp_events");
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong("total") : 0L;
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to count Fabric audit events", exception);
        }
    }

    public long countAllPendingInventoryRollbacks() {
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT COUNT(*) AS total FROM cp_pending_inventory_rollbacks");
             ResultSet resultSet = statement.executeQuery()) {
            return resultSet.next() ? resultSet.getLong("total") : 0L;
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to count pending inventory rollbacks", exception);
        }
    }

    public List<StoredEventRecord> fetchEventsAfterId(long afterId, int limit) {
        String sql = eventSelectSql() + "WHERE e.id > ? ORDER BY e.id ASC LIMIT ?";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, afterId);
            statement.setInt(2, limit);
            return readRows(statement);
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to fetch Fabric audit events for migration", exception);
        }
    }

    public String lookupLatestActorName(String actorUuid) {
        if (actorUuid == null || actorUuid.isBlank()) {
            return null;
        }

        awaitWriterQuiescence();
        String sql =
            "SELECT actor_map.actor_name AS actor_name "
                + "FROM cp_events e "
                + "JOIN cp_actor actor_map ON actor_map.id = e.actor_id "
                + "WHERE actor_map.actor_uuid = ? "
                + "AND actor_map.actor_name IS NOT NULL "
                + "AND actor_map.actor_name <> '' "
                + "ORDER BY e.id DESC LIMIT 1";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, actorUuid);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("actor_name") : null;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to look up the latest actor name", exception);
        }
    }

    public String lookupLatestBlockPlaceActor(String worldKey, BlockPos pos) {
        if (worldKey == null || worldKey.isBlank() || pos == null) {
            return null;
        }

        awaitWriterQuiescence();
        try (Connection connection = openConnection()) {
            Long worldId = findWorldId(connection, worldKey);
            if (worldId == null) {
                return null;
            }

            String sql =
                "SELECT actor_map.actor_name AS actor_name "
                    + "FROM cp_events e "
                    + "JOIN cp_actor actor_map ON actor_map.id = e.actor_id "
                    + "WHERE e.world_id = ? "
                    + "AND e.x = ? "
                    + "AND e.y = ? "
                    + "AND e.z = ? "
                    + "AND e.event_type = 'BLOCK_PLACE' "
                    + "AND e.actor_id IS NOT NULL "
                    + "ORDER BY e.id DESC LIMIT 1";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, worldId);
                statement.setInt(2, pos.getX());
                statement.setInt(3, pos.getY());
                statement.setInt(4, pos.getZ());
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getString("actor_name") : null;
                }
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to look up the latest block place actor", exception);
        }
    }

    public List<PendingInventoryRollbackRecord> fetchPendingInventoryRollbacksAfterId(long afterId, int limit) {
        String sql =
            "SELECT p.id, p.event_id, actor_map.actor_uuid AS actor_uuid, "
                + "actor_map.actor_name AS actor_name, "
                + "world_map.world_key AS world_key, "
                + "p.restore, p.add_items, "
                + "target_map.target AS target, "
                + "p.payload, p.status, p.attempts, p.last_error, p.created_ts, p.updated_ts "
                + "FROM cp_pending_inventory_rollbacks p "
                + "LEFT JOIN cp_actor actor_map ON actor_map.id = p.actor_id "
                + "LEFT JOIN cp_world world_map ON world_map.id = p.world_id "
                + "LEFT JOIN cp_target target_map ON target_map.id = p.target_id "
                + "WHERE p.id > ? ORDER BY p.id ASC LIMIT ?";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, afterId);
            statement.setInt(2, limit);
            List<PendingInventoryRollbackRecord> rows = new ArrayList<>();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    rows.add(readPendingInventoryRollbackRow(resultSet));
                }
            }
            return rows;
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to fetch pending inventory rollbacks for migration", exception);
        }
    }

    public void importEvents(List<StoredEventRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        String sql =
            "INSERT INTO cp_events ("
                + "id, "
                + "ts, "
                + "event_type, "
                + "actor_id, "
                + "world_id, "
                + "x, "
                + "y, "
                + "z, "
                + "target_id, "
                + "entity_key, "
                + "payload, "
                + "rolled_back"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                MappingContext context = new MappingContext(new HashMap<>(), new HashMap<>(), new HashMap<>());
                for (StoredEventRecord record : records) {
                    TargetIndex targetIndex = buildTargetIndex(record.target());
                    Long actorId = ensureActorId(connection, context, record.actorUuid(), record.actorName());
                    Long worldId = ensureWorldId(connection, context, record.worldKey());
                    Long targetId = ensureTargetId(connection, context, record.target(), targetIndex);
                    statement.setLong(1, record.id());
                    statement.setLong(2, record.timestamp());
                    statement.setString(3, record.type().name());
                    bindNullableLong(statement, 4, actorId);
                    bindNullableLong(statement, 5, worldId);
                    bindNullableInt(statement, 6, record.x());
                    bindNullableInt(statement, 7, record.y());
                    bindNullableInt(statement, 8, record.z());
                    bindNullableLong(statement, 9, targetId);
                    long entityKey = record.type() == CoreProtectEventType.ENTITY_KILL ? parseEntityKey(record.payload()) : -1L;
                    bindNullableLong(statement, 10, entityKey <= 0L ? null : entityKey);
                    statement.setString(11, record.payload());
                    statement.setInt(12, record.rolledBack() ? 1 : 0);
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            }
            catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
            finally {
                if (originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to import Fabric audit events", exception);
        }
    }

    public void importPendingInventoryRollbacks(List<PendingInventoryRollbackRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }

        String sql =
            "INSERT INTO cp_pending_inventory_rollbacks ("
                + "id, event_id, actor_id, world_id, restore, add_items, target_id, payload, status, attempts, last_error, created_ts, updated_ts"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            boolean originalAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                MappingContext context = new MappingContext(new HashMap<>(), new HashMap<>(), new HashMap<>());
                for (PendingInventoryRollbackRecord record : records) {
                    Long actorId = ensureActorId(connection, context, record.actorUuid(), record.actorName());
                    Long worldId = ensureWorldId(connection, context, record.worldKey());
                    Long targetId = ensureTargetId(connection, context, record.target(), buildTargetIndex(record.target()));
                    statement.setLong(1, record.id());
                    statement.setLong(2, record.eventId());
                    bindNullableLong(statement, 3, actorId);
                    bindNullableLong(statement, 4, worldId);
                    statement.setInt(5, record.restore() ? 1 : 0);
                    statement.setInt(6, record.addItems() ? 1 : 0);
                    bindNullableLong(statement, 7, targetId);
                    statement.setString(8, record.payload());
                    statement.setString(9, record.status());
                    statement.setInt(10, record.attempts());
                    statement.setString(11, record.lastError());
                    statement.setLong(12, record.createdAt());
                    statement.setLong(13, record.updatedAt());
                    statement.addBatch();
                }
                statement.executeBatch();
                connection.commit();
            }
            catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
            finally {
                if (originalAutoCommit) {
                    connection.setAutoCommit(true);
                }
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to import pending inventory rollbacks", exception);
        }
    }

    public List<StoredEventRecord> lookupBlockHistory(String worldKey, BlockPos pos, int limit) {
        return lookupBlockHistory(
            worldKey,
            pos,
            limit,
            Arrays.asList(
                CoreProtectEventType.BLOCK_BREAK,
                CoreProtectEventType.BLOCK_PLACE,
                CoreProtectEventType.BLOCK_USE,
                CoreProtectEventType.ENTITY_PLACE,
                CoreProtectEventType.ENTITY_BREAK,
                CoreProtectEventType.ENTITY_USE,
                CoreProtectEventType.ENTITY_KILL,
                CoreProtectEventType.SIGN_CHANGE,
                CoreProtectEventType.CONTAINER_TRANSACTION
            )
        );
    }

    public List<StoredEventRecord> lookupBlockHistory(String worldKey, BlockPos pos, int limit, List<CoreProtectEventType> eventTypes) {
        awaitWriterQuiescence();
        try (Connection connection = openConnection()) {
            Long worldId = findWorldId(connection, worldKey);
            if (worldId == null) {
                return List.of();
            }

            StringBuilder sql = new StringBuilder(
                eventSelectSql()
                    + "WHERE e.world_id = ? "
                    + "AND e.x = ? "
                    + "AND e.y = ? "
                    + "AND e.z = ?"
            );
            if (eventTypes != null && !eventTypes.isEmpty()) {
                sql.append(" AND event_type IN (");
                for (int i = 0; i < eventTypes.size(); i++) {
                    if (i > 0) {
                        sql.append(", ");
                    }
                    sql.append("?");
                }
                sql.append(")");
            }
            sql.append(" ORDER BY e.id DESC LIMIT ?");

            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                statement.setLong(index++, worldId);
                statement.setInt(index++, pos.getX());
                statement.setInt(index++, pos.getY());
                statement.setInt(index++, pos.getZ());
                if (eventTypes != null && !eventTypes.isEmpty()) {
                    for (CoreProtectEventType eventType : eventTypes) {
                        statement.setString(index++, eventType.name());
                    }
                }
                statement.setInt(index, limit);
                return readRows(statement);
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to run block history lookup", exception);
        }
    }

    public List<StoredEventRecord> lookupNearby(String worldKey, BlockPos center, int radius, int seconds, int limit, String actorName) {
        return lookupNearby(worldKey, center, radius, seconds, limit, actorName, null);
    }

    public List<StoredEventRecord> lookupNearby(String worldKey, BlockPos center, int radius, int seconds, int limit, String actorName, List<CoreProtectEventType> eventTypes) {
        return lookupNearby(worldKey, center, radius, 0, seconds, limit, actorName, eventTypes);
    }

    public List<StoredEventRecord> lookupNearby(String worldKey, BlockPos center, int radius, int minimumSeconds, int maximumSeconds, int limit, String actorName, List<CoreProtectEventType> eventTypes) {
        return lookupHistory(
            worldKey,
            center,
            radius,
            minimumSeconds,
            maximumSeconds,
            limit,
            actorName == null || actorName.isBlank() ? null : List.of(actorName),
            eventTypes,
            null,
            null
        );
    }

    public List<StoredEventRecord> lookupHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        List<String> actorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return lookupHistory(worldKey, center, radius, minimumSeconds, maximumSeconds, limit, 0, actorNames, null, eventTypes, includeTargets, excludeTargets);
    }

    public List<StoredEventRecord> lookupHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return lookupHistory(worldKey, center, radius, minimumSeconds, maximumSeconds, limit, 0, actorNames, excludeActorNames, eventTypes, includeTargets, excludeTargets);
    }

    public List<StoredEventRecord> lookupHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        int offset,
        List<String> actorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return lookupHistory(
            worldKey,
            center,
            radius,
            minimumSeconds,
            maximumSeconds,
            limit,
            offset,
            actorNames,
            null,
            eventTypes,
            includeTargets,
            excludeTargets
        );
    }

    public List<StoredEventRecord> lookupHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        int offset,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return lookupHistory(
            worldKey,
            minimumBound(center, radius),
            maximumBound(center, radius),
            minimumSeconds,
            maximumSeconds,
            limit,
            offset,
            actorNames,
            excludeActorNames,
            eventTypes,
            includeTargets,
            excludeTargets
        );
    }

    public List<StoredEventRecord> lookupHistory(
        String worldKey,
        BlockPos minimum,
        BlockPos maximum,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        int offset,
        List<String> actorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return lookupHistory(
            worldKey,
            minimum,
            maximum,
            minimumSeconds,
            maximumSeconds,
            limit,
            offset,
            actorNames,
            null,
            eventTypes,
            includeTargets,
            excludeTargets
        );
    }

    public List<StoredEventRecord> lookupHistory(
        String worldKey,
        BlockPos minimum,
        BlockPos maximum,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        int offset,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        awaitWriterQuiescence();
        long now = System.currentTimeMillis();
        long notBefore = now - (Math.max(minimumSeconds, maximumSeconds) * 1000L);
        long notAfter = now - (Math.min(minimumSeconds, maximumSeconds) * 1000L);

        try (Connection connection = openConnection()) {
            Long worldId = findWorldId(connection, worldKey);
            if (worldKey != null && !worldKey.isBlank() && worldId == null) {
                return List.of();
            }

            List<String> normalizedActorNames = normalizeFilterValues(actorNames);
            List<Long> actorIds = findActorIds(connection, normalizedActorNames);
            if (!normalizedActorNames.isEmpty() && actorIds.isEmpty()) {
                return List.of();
            }

            List<String> normalizedExcludeActorNames = normalizeFilterValues(excludeActorNames);
            List<Long> excludeActorIds = findActorIds(connection, normalizedExcludeActorNames);
            ResolvedTargetFilter includeTargetFilter = resolveTargetFilter(connection, includeTargets);
            if (includeTargetFilter.requested() && includeTargetFilter.targetIds().isEmpty() && includeTargetFilter.textClauses().isEmpty()) {
                return List.of();
            }
            ResolvedTargetFilter excludeTargetFilter = resolveTargetFilter(connection, excludeTargets);

            StringBuilder sql = new StringBuilder(eventSelectSql() + "WHERE e.ts >= ? AND e.ts <= ?");
            if (worldId != null) {
                sql.append(" AND e.world_id = ?");
            }
            if (minimum != null && maximum != null) {
                sql.append(" AND e.x BETWEEN ? AND ?");
                sql.append(" AND e.y BETWEEN ? AND ?");
                sql.append(" AND e.z BETWEEN ? AND ?");
            }
            if (eventTypes != null && !eventTypes.isEmpty()) {
                sql.append(" AND event_type IN (");
                for (int i = 0; i < eventTypes.size(); i++) {
                    if (i > 0) {
                        sql.append(", ");
                    }
                    sql.append("?");
                }
                sql.append(")");
            }
            appendAnyLongPredicate(sql, "e.actor_id", actorIds, false);
            appendAnyLongPredicate(sql, "e.actor_id", excludeActorIds, true);
            appendResolvedTargetPredicate(sql, includeTargetFilter, false, "e.target_id", "target_map.target", "e.payload");
            appendResolvedTargetPredicate(sql, excludeTargetFilter, true, "e.target_id", "target_map.target", "e.payload");
            sql.append(" ORDER BY e.id DESC LIMIT ? OFFSET ?");

            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                statement.setLong(index++, notBefore);
                statement.setLong(index++, notAfter);
                if (worldId != null) {
                    statement.setLong(index++, worldId);
                }
                if (minimum != null && maximum != null) {
                    statement.setInt(index++, minimum.getX());
                    statement.setInt(index++, maximum.getX());
                    statement.setInt(index++, minimum.getY());
                    statement.setInt(index++, maximum.getY());
                    statement.setInt(index++, minimum.getZ());
                    statement.setInt(index++, maximum.getZ());
                }
                if (eventTypes != null && !eventTypes.isEmpty()) {
                    for (CoreProtectEventType eventType : eventTypes) {
                        statement.setString(index++, eventType.name());
                    }
                }
                index = bindLongValues(statement, index, actorIds);
                index = bindLongValues(statement, index, excludeActorIds);
                index = bindResolvedTargetValues(statement, index, includeTargetFilter);
                index = bindResolvedTargetValues(statement, index, excludeTargetFilter);
                statement.setInt(index++, limit);
                statement.setInt(index, Math.max(0, offset));
                return readRows(statement);
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to run scoped history lookup", exception);
        }
    }

    public int countHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        List<String> actorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return countHistory(
            worldKey,
            center,
            radius,
            minimumSeconds,
            maximumSeconds,
            actorNames,
            null,
            eventTypes,
            includeTargets,
            excludeTargets
        );
    }

    public int countHistory(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return countHistory(
            worldKey,
            minimumBound(center, radius),
            maximumBound(center, radius),
            minimumSeconds,
            maximumSeconds,
            actorNames,
            excludeActorNames,
            eventTypes,
            includeTargets,
            excludeTargets
        );
    }

    public int countHistory(
        String worldKey,
        BlockPos minimum,
        BlockPos maximum,
        int minimumSeconds,
        int maximumSeconds,
        List<String> actorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return countHistory(
            worldKey,
            minimum,
            maximum,
            minimumSeconds,
            maximumSeconds,
            actorNames,
            null,
            eventTypes,
            includeTargets,
            excludeTargets
        );
    }

    public int countHistory(
        String worldKey,
        BlockPos minimum,
        BlockPos maximum,
        int minimumSeconds,
        int maximumSeconds,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        awaitWriterQuiescence();
        long now = System.currentTimeMillis();
        long notBefore = now - (Math.max(minimumSeconds, maximumSeconds) * 1000L);
        long notAfter = now - (Math.min(minimumSeconds, maximumSeconds) * 1000L);

        try (Connection connection = openConnection()) {
            Long worldId = findWorldId(connection, worldKey);
            if (worldKey != null && !worldKey.isBlank() && worldId == null) {
                return 0;
            }

            List<String> normalizedActorNames = normalizeFilterValues(actorNames);
            List<Long> actorIds = findActorIds(connection, normalizedActorNames);
            if (!normalizedActorNames.isEmpty() && actorIds.isEmpty()) {
                return 0;
            }

            List<String> normalizedExcludeActorNames = normalizeFilterValues(excludeActorNames);
            List<Long> excludeActorIds = findActorIds(connection, normalizedExcludeActorNames);
            ResolvedTargetFilter includeTargetFilter = resolveTargetFilter(connection, includeTargets);
            if (includeTargetFilter.requested() && includeTargetFilter.targetIds().isEmpty() && includeTargetFilter.textClauses().isEmpty()) {
                return 0;
            }
            ResolvedTargetFilter excludeTargetFilter = resolveTargetFilter(connection, excludeTargets);

            StringBuilder sql = new StringBuilder(
                "SELECT COUNT(*) AS total "
                    + "FROM cp_events "
                    + "WHERE ts >= ? "
                    + "AND ts <= ?"
            );
            if (worldId != null) {
                sql.append(" AND world_id = ?");
            }
            if (minimum != null && maximum != null) {
                sql.append(" AND x BETWEEN ? AND ?");
                sql.append(" AND y BETWEEN ? AND ?");
                sql.append(" AND z BETWEEN ? AND ?");
            }
            if (eventTypes != null && !eventTypes.isEmpty()) {
                sql.append(" AND event_type IN (");
                for (int index = 0; index < eventTypes.size(); index++) {
                    if (index > 0) {
                        sql.append(", ");
                    }
                    sql.append("?");
                }
                sql.append(")");
            }
            appendAnyLongPredicate(sql, "actor_id", actorIds, false);
            appendAnyLongPredicate(sql, "actor_id", excludeActorIds, true);
            appendResolvedTargetPredicate(sql, includeTargetFilter, false, "target_id", "(SELECT target FROM cp_target WHERE id = target_id)", "payload");
            appendResolvedTargetPredicate(sql, excludeTargetFilter, true, "target_id", "(SELECT target FROM cp_target WHERE id = target_id)", "payload");

            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                statement.setLong(index++, notBefore);
                statement.setLong(index++, notAfter);
                if (worldId != null) {
                    statement.setLong(index++, worldId);
                }
                if (minimum != null && maximum != null) {
                    statement.setInt(index++, minimum.getX());
                    statement.setInt(index++, maximum.getX());
                    statement.setInt(index++, minimum.getY());
                    statement.setInt(index++, maximum.getY());
                    statement.setInt(index++, minimum.getZ());
                    statement.setInt(index++, maximum.getZ());
                }
                if (eventTypes != null && !eventTypes.isEmpty()) {
                    for (CoreProtectEventType eventType : eventTypes) {
                        statement.setString(index++, eventType.name());
                    }
                }
                index = bindLongValues(statement, index, actorIds);
                index = bindLongValues(statement, index, excludeActorIds);
                index = bindResolvedTargetValues(statement, index, includeTargetFilter);
                bindResolvedTargetValues(statement, index, excludeTargetFilter);
                try (ResultSet resultSet = statement.executeQuery()) {
                    return resultSet.next() ? resultSet.getInt("total") : 0;
                }
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to count scoped history rows", exception);
        }
    }

    public List<StoredEventRecord> lookupRollbackCandidates(String worldKey, BlockPos center, int radius, int seconds, String actorName, boolean rolledBack, boolean ascending) {
        return lookupRollbackCandidates(worldKey, center, radius, seconds, actorName, rolledBack, ascending, null);
    }

    public List<StoredEventRecord> lookupRollbackCandidates(String worldKey, BlockPos center, int radius, int seconds, String actorName, boolean rolledBack, boolean ascending, List<CoreProtectEventType> eventTypes) {
        return lookupRollbackCandidates(
            worldKey,
            center,
            radius,
            0,
            seconds,
            actorName == null || actorName.isBlank() ? null : List.of(actorName),
            null,
            rolledBack,
            ascending,
            eventTypes,
            null,
            null
        );
    }

    public List<StoredEventRecord> lookupRollbackCandidates(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        List<String> actorNames,
        boolean rolledBack,
        boolean ascending,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return lookupRollbackCandidates(
            worldKey,
            center,
            radius,
            minimumSeconds,
            maximumSeconds,
            actorNames,
            null,
            rolledBack,
            ascending,
            eventTypes,
            includeTargets,
            excludeTargets
        );
    }

    public List<StoredEventRecord> lookupRollbackCandidates(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        List<String> actorNames,
        List<String> excludeActorNames,
        boolean rolledBack,
        boolean ascending,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return lookupRollbackCandidates(
            worldKey,
            minimumBound(center, radius),
            maximumBound(center, radius),
            minimumSeconds,
            maximumSeconds,
            actorNames,
            excludeActorNames,
            rolledBack,
            ascending,
            eventTypes,
            includeTargets,
            excludeTargets
        );
    }

    public List<StoredEventRecord> lookupRollbackCandidates(
        String worldKey,
        BlockPos minimum,
        BlockPos maximum,
        int minimumSeconds,
        int maximumSeconds,
        List<String> actorNames,
        boolean rolledBack,
        boolean ascending,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return lookupRollbackCandidates(
            worldKey,
            minimum,
            maximum,
            minimumSeconds,
            maximumSeconds,
            actorNames,
            null,
            rolledBack,
            ascending,
            eventTypes,
            includeTargets,
            excludeTargets
        );
    }

    public List<StoredEventRecord> lookupRollbackCandidates(
        String worldKey,
        BlockPos minimum,
        BlockPos maximum,
        int minimumSeconds,
        int maximumSeconds,
        List<String> actorNames,
        List<String> excludeActorNames,
        boolean rolledBack,
        boolean ascending,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        long now = System.currentTimeMillis();
        long notBefore = now - (Math.max(minimumSeconds, maximumSeconds) * 1000L);
        long notAfter = now - (Math.min(minimumSeconds, maximumSeconds) * 1000L);
        return lookupRollbackCandidatesBetween(
            worldKey,
            minimum,
            maximum,
            notBefore,
            notAfter,
            actorNames,
            excludeActorNames,
            rolledBack,
            ascending,
            eventTypes,
            includeTargets,
            excludeTargets
        );
    }

    public List<StoredEventRecord> lookupRollbackCandidatesBetween(
        String worldKey,
        BlockPos minimum,
        BlockPos maximum,
        long notBefore,
        long notAfter,
        List<String> actorNames,
        boolean rolledBack,
        boolean ascending,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return lookupRollbackCandidatesBetween(
            worldKey,
            minimum,
            maximum,
            notBefore,
            notAfter,
            actorNames,
            null,
            rolledBack,
            ascending,
            eventTypes,
            includeTargets,
            excludeTargets
        );
    }

    public List<StoredEventRecord> lookupRollbackCandidatesBetween(
        String worldKey,
        BlockPos minimum,
        BlockPos maximum,
        long notBefore,
        long notAfter,
        List<String> actorNames,
        List<String> excludeActorNames,
        boolean rolledBack,
        boolean ascending,
        List<CoreProtectEventType> eventTypes,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        awaitWriterQuiescence();
        if (eventTypes != null && eventTypes.isEmpty()) {
            return List.of();
        }
        List<CoreProtectEventType> rollbackTypes = eventTypes == null
            ? List.of(CoreProtectEventType.BLOCK_BREAK, CoreProtectEventType.BLOCK_PLACE, CoreProtectEventType.SIGN_CHANGE)
            : eventTypes;

        try (Connection connection = openConnection()) {
            Long worldId = findWorldId(connection, worldKey);
            if (worldKey != null && !worldKey.isBlank() && worldId == null) {
                return List.of();
            }

            List<String> normalizedActorNames = normalizeFilterValues(actorNames);
            List<Long> actorIds = findActorIds(connection, normalizedActorNames);
            if (!normalizedActorNames.isEmpty() && actorIds.isEmpty()) {
                return List.of();
            }

            List<String> normalizedExcludeActorNames = normalizeFilterValues(excludeActorNames);
            List<Long> excludeActorIds = findActorIds(connection, normalizedExcludeActorNames);
            ResolvedTargetFilter includeTargetFilter = resolveTargetFilter(connection, includeTargets);
            if (includeTargetFilter.requested() && includeTargetFilter.targetIds().isEmpty() && includeTargetFilter.textClauses().isEmpty()) {
                return List.of();
            }
            ResolvedTargetFilter excludeTargetFilter = resolveTargetFilter(connection, excludeTargets);

            StringBuilder sql = new StringBuilder(eventSelectSql() + "WHERE e.ts >= ? AND e.ts <= ? AND e.rolled_back = ?");
            if (worldId != null) {
                sql.append(" AND e.world_id = ?");
            }
            if (minimum != null && maximum != null) {
                sql.append(" AND e.x BETWEEN ? AND ?");
                sql.append(" AND e.y BETWEEN ? AND ?");
                sql.append(" AND e.z BETWEEN ? AND ?");
            }
            if (!rollbackTypes.isEmpty()) {
                sql.append(" AND event_type IN (");
                for (int i = 0; i < rollbackTypes.size(); i++) {
                    if (i > 0) {
                        sql.append(", ");
                    }
                    sql.append("?");
                }
                sql.append(")");
            }
            appendAnyLongPredicate(sql, "e.actor_id", actorIds, false);
            appendAnyLongPredicate(sql, "e.actor_id", excludeActorIds, true);
            appendResolvedTargetPredicate(sql, includeTargetFilter, false, "e.target_id", "target_map.target", "e.payload");
            appendResolvedTargetPredicate(sql, excludeTargetFilter, true, "e.target_id", "target_map.target", "e.payload");
            sql.append(ascending ? " ORDER BY e.id ASC" : " ORDER BY e.id DESC");

            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                statement.setLong(index++, notBefore);
                statement.setLong(index++, notAfter);
                statement.setInt(index++, rolledBack ? 1 : 0);
                if (worldId != null) {
                    statement.setLong(index++, worldId);
                }
                if (minimum != null && maximum != null) {
                    statement.setInt(index++, minimum.getX());
                    statement.setInt(index++, maximum.getX());
                    statement.setInt(index++, minimum.getY());
                    statement.setInt(index++, maximum.getY());
                    statement.setInt(index++, minimum.getZ());
                    statement.setInt(index++, maximum.getZ());
                }
                for (CoreProtectEventType rollbackType : rollbackTypes) {
                    statement.setString(index++, rollbackType.name());
                }
                index = bindLongValues(statement, index, actorIds);
                index = bindLongValues(statement, index, excludeActorIds);
                index = bindResolvedTargetValues(statement, index, includeTargetFilter);
                bindResolvedTargetValues(statement, index, excludeTargetFilter);
                return readRows(statement);
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to run rollback lookup", exception);
        }
    }

    public LoggedSignState lookupPreviousSignState(String worldKey, BlockPos pos, long beforeId, boolean front) {
        awaitWriterQuiescence();
        try (Connection connection = openConnection()) {
            Long worldId = findWorldId(connection, worldKey);
            if (worldId == null) {
                return LoggedSignState.blank(front);
            }

            String sql =
                "SELECT event_type, payload "
                    + "FROM cp_events "
                    + "WHERE world_id = ? "
                    + "AND x = ? "
                    + "AND y = ? "
                    + "AND z = ? "
                    + "AND id < ? "
                    + "AND event_type IN ('SIGN_CHANGE', 'BLOCK_BREAK', 'BLOCK_PLACE') "
                    + "ORDER BY id DESC";

            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setLong(1, worldId);
                statement.setInt(2, pos.getX());
                statement.setInt(3, pos.getY());
                statement.setInt(4, pos.getZ());
                statement.setLong(5, beforeId);

                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        CoreProtectEventType eventType = CoreProtectEventType.valueOf(resultSet.getString("event_type"));
                        if (eventType == CoreProtectEventType.SIGN_CHANGE) {
                            LoggedSignState signState = parseSignPayload(resultSet.getString("payload"), front);
                            if (signState != null) {
                                return signState;
                            }
                            continue;
                        }

                        if (eventType == CoreProtectEventType.BLOCK_BREAK || eventType == CoreProtectEventType.BLOCK_PLACE) {
                            return LoggedSignState.blank(front);
                        }
                    }
                }
            }
            return LoggedSignState.blank(front);
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to look up previous sign state", exception);
        }
    }

    public int updateRolledBack(List<Long> ids, boolean rolledBack) {
        if (ids.isEmpty()) {
            return 0;
        }

        String sql = "UPDATE cp_events SET rolled_back = ? WHERE id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (Long id : ids) {
                statement.setInt(1, rolledBack ? 1 : 0);
                statement.setLong(2, id);
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
            return ids.size();
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to update rollback state", exception);
        }
    }

    public boolean actorExists(String actorName) {
        if (actorName == null || actorName.isBlank()) {
            return false;
        }

        awaitWriterQuiescence();
        String sql =
            "SELECT 1 "
                + "FROM cp_actor "
                + "WHERE " + caseInsensitiveEquality("actor_name") + " "
                + "LIMIT 1";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, actorName);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to validate actor existence", exception);
        }
    }

    public boolean queuePendingInventoryRollback(
        long eventId,
        String actorUuid,
        String actorName,
        String worldKey,
        boolean restore,
        boolean addItems,
        String target,
        String payload
    ) {
        if (eventId <= 0L || actorUuid == null || actorUuid.isBlank()) {
            return false;
        }

        awaitWriterQuiescence();
        long now = System.currentTimeMillis();
        String selectSql = "SELECT id FROM cp_pending_inventory_rollbacks WHERE event_id = ? AND restore = ? LIMIT 1";
        String insertSql =
            "INSERT INTO cp_pending_inventory_rollbacks ("
                + "event_id, actor_id, world_id, restore, add_items, target_id, payload, status, attempts, created_ts, updated_ts"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?)";
        String updateSql =
            "UPDATE cp_pending_inventory_rollbacks "
                + "SET actor_id = ?, world_id = ?, add_items = ?, target_id = ?, payload = ?, status = ?, attempts = 0, last_error = NULL, updated_ts = ? "
                + "WHERE id = ?";

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                MappingContext context = new MappingContext(new HashMap<>(), new HashMap<>(), new HashMap<>());
                Long actorId = ensureActorId(connection, context, actorUuid, actorName);
                Long worldId = ensureWorldId(connection, context, worldKey);
                Long targetId = ensureTargetId(connection, context, target, buildTargetIndex(target));
                try (PreparedStatement selectStatement = connection.prepareStatement(selectSql)) {
                    selectStatement.setLong(1, eventId);
                    selectStatement.setInt(2, restore ? 1 : 0);
                    try (ResultSet resultSet = selectStatement.executeQuery()) {
                        if (resultSet.next()) {
                            long existingId = resultSet.getLong("id");
                            try (PreparedStatement updateStatement = connection.prepareStatement(updateSql)) {
                                bindNullableLong(updateStatement, 1, actorId);
                                bindNullableLong(updateStatement, 2, worldId);
                                updateStatement.setInt(3, addItems ? 1 : 0);
                                bindNullableLong(updateStatement, 4, targetId);
                                updateStatement.setString(5, payload);
                                updateStatement.setString(6, PENDING_INVENTORY_STATUS_PENDING);
                                updateStatement.setLong(7, now);
                                updateStatement.setLong(8, existingId);
                                updateStatement.executeUpdate();
                            }
                            connection.commit();
                            return true;
                        }
                    }
                }

                try (PreparedStatement insertStatement = connection.prepareStatement(insertSql)) {
                    insertStatement.setLong(1, eventId);
                    bindNullableLong(insertStatement, 2, actorId);
                    bindNullableLong(insertStatement, 3, worldId);
                    insertStatement.setInt(4, restore ? 1 : 0);
                    insertStatement.setInt(5, addItems ? 1 : 0);
                    bindNullableLong(insertStatement, 6, targetId);
                    insertStatement.setString(7, payload);
                    insertStatement.setString(8, PENDING_INVENTORY_STATUS_PENDING);
                    insertStatement.setLong(9, now);
                    insertStatement.setLong(10, now);
                    insertStatement.executeUpdate();
                }
                connection.commit();
                return true;
            }
            catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to queue pending offline inventory rollback", exception);
        }
    }

    public List<PendingInventoryRollbackRecord> lookupPendingInventoryRollbacks(String actorUuid, String actorName, int limit) {
        if ((actorUuid == null || actorUuid.isBlank()) && (actorName == null || actorName.isBlank())) {
            return List.of();
        }

        awaitWriterQuiescence();
        try (Connection connection = openConnection()) {
            List<Long> actorIds = resolvePendingActorIds(connection, actorUuid, actorName);
            if (actorIds.isEmpty()) {
                return List.of();
            }
            StringBuilder sql = new StringBuilder(
                "SELECT p.id, p.event_id, actor_map.actor_uuid AS actor_uuid, "
                    + "actor_map.actor_name AS actor_name, "
                    + "world_map.world_key AS world_key, "
                    + "p.restore, p.add_items, "
                    + "target_map.target AS target, "
                    + "p.payload, p.status, p.attempts, p.last_error, p.created_ts, p.updated_ts "
                    + "FROM cp_pending_inventory_rollbacks p "
                    + "LEFT JOIN cp_actor actor_map ON actor_map.id = p.actor_id "
                    + "LEFT JOIN cp_world world_map ON world_map.id = p.world_id "
                    + "LEFT JOIN cp_target target_map ON target_map.id = p.target_id "
                    + "WHERE p.status IN (?, ?) "
                    + "AND p.attempts < ?"
            );
            sql.append(buildPendingActorPredicate(actorIds));
            sql.append(" ORDER BY p.attempts ASC, p.updated_ts ASC, p.id ASC LIMIT ?");

            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                int index = 1;
                statement.setString(index++, PENDING_INVENTORY_STATUS_PENDING);
                statement.setString(index++, PENDING_INVENTORY_STATUS_FAILED);
                statement.setInt(index++, MAX_PENDING_INVENTORY_ATTEMPTS);
                index = bindLongValues(statement, index, actorIds);
                statement.setInt(index, Math.max(1, limit));

                List<PendingInventoryRollbackRecord> rows = new ArrayList<>();
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        rows.add(readPendingInventoryRollbackRow(resultSet));
                    }
                }
                return rows;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to look up pending inventory rollbacks", exception);
        }
    }

    public void markPendingInventoryRollbackApplied(long queueId, long eventId, boolean restore) {
        if (queueId <= 0L) {
            return;
        }

        awaitWriterQuiescence();
        long now = System.currentTimeMillis();
        String queueSql =
            "UPDATE cp_pending_inventory_rollbacks "
                + "SET status = ?, attempts = attempts + 1, last_error = NULL, updated_ts = ? "
                + "WHERE id = ?";
        String eventSql = "UPDATE cp_events SET rolled_back = ? WHERE id = ?";

        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement queueStatement = connection.prepareStatement(queueSql)) {
                    queueStatement.setString(1, PENDING_INVENTORY_STATUS_APPLIED);
                    queueStatement.setLong(2, now);
                    queueStatement.setLong(3, queueId);
                    queueStatement.executeUpdate();
                }

                if (eventId > 0L) {
                    try (PreparedStatement eventStatement = connection.prepareStatement(eventSql)) {
                        eventStatement.setInt(1, restore ? 0 : 1);
                        eventStatement.setLong(2, eventId);
                        eventStatement.executeUpdate();
                    }
                }

                connection.commit();
            }
            catch (SQLException exception) {
                connection.rollback();
                throw exception;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to mark pending inventory rollback as applied", exception);
        }
    }

    public void markPendingInventoryRollbackFailed(long queueId, String error) {
        if (queueId <= 0L) {
            return;
        }

        awaitWriterQuiescence();
        String sql =
            "UPDATE cp_pending_inventory_rollbacks "
                + "SET status = ?, attempts = attempts + 1, last_error = ?, updated_ts = ? "
                + "WHERE id = ?";
        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, PENDING_INVENTORY_STATUS_FAILED);
            statement.setString(2, truncatePendingError(error));
            statement.setLong(3, System.currentTimeMillis());
            statement.setLong(4, queueId);
            statement.executeUpdate();
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to mark pending inventory rollback as failed", exception);
        }
    }

    public int purgeOlderThan(int seconds, String worldKey, List<String> includeTargets) {
        boolean previouslyPaused = writesPaused();
        if (!previouslyPaused) {
            setWritesPaused(true);
        }

        try {
            awaitWriterQuiescence();
            long before = System.currentTimeMillis() - (seconds * 1000L);
            boolean globalScope = worldKey == null || worldKey.isBlank();
            boolean targetFiltered = includeTargets != null && !includeTargets.isEmpty();

            try (Connection connection = openConnection()) {
                Long worldId = findWorldId(connection, worldKey);
                if (!globalScope && worldId == null) {
                    return 0;
                }
                ResolvedTargetFilter includeTargetFilter = resolveTargetFilter(connection, includeTargets);
                if (targetFiltered && includeTargetFilter.requested()
                    && includeTargetFilter.targetIds().isEmpty()
                    && includeTargetFilter.textClauses().isEmpty()) {
                    return 0;
                }

                StringBuilder sql = new StringBuilder("DELETE FROM cp_events WHERE ts < ?");
                if (worldId != null) {
                    sql.append(" AND world_id = ?");
                }
                appendResolvedTargetPredicate(sql, includeTargetFilter, false, "target_id", "(SELECT target FROM cp_target WHERE id = target_id)", "payload");

                connection.setAutoCommit(false);
                try {
                    int deletedEvents;
                    try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                        int index = 1;
                        statement.setLong(index++, before);
                        if (worldId != null) {
                            statement.setLong(index++, worldId);
                        }
                        bindResolvedTargetValues(statement, index, includeTargetFilter);
                        deletedEvents = statement.executeUpdate();
                    }

                    if (globalScope && !targetFiltered) {
                        try (PreparedStatement purgeEntity = connection.prepareStatement("DELETE FROM cp_entity WHERE ts < ?")) {
                            purgeEntity.setLong(1, before);
                            purgeEntity.executeUpdate();
                        }
                    }
                    purgeOrphanPendingInventoryRows(connection);
                    purgeOrphanEntityRows(connection);
                    purgeOrphanMappingRows(connection);

                    try (PreparedStatement purgeTerminalQueue = connection.prepareStatement(
                        "DELETE FROM cp_pending_inventory_rollbacks "
                            + "WHERE (status = ? AND updated_ts < ?) "
                            + "OR (status = ? AND attempts >= ? AND updated_ts < ?)"
                    )) {
                        purgeTerminalQueue.setString(1, PENDING_INVENTORY_STATUS_APPLIED);
                        purgeTerminalQueue.setLong(2, before);
                        purgeTerminalQueue.setString(3, PENDING_INVENTORY_STATUS_FAILED);
                        purgeTerminalQueue.setInt(4, MAX_PENDING_INVENTORY_ATTEMPTS);
                        purgeTerminalQueue.setLong(5, before);
                        purgeTerminalQueue.executeUpdate();
                    }

                    connection.commit();
                    return deletedEvents;
                }
                catch (SQLException exception) {
                    connection.rollback();
                    throw exception;
                }
            }
            catch (SQLException exception) {
                throw new IllegalStateException("Unable to purge old Fabric audit data", exception);
            }
        }
        finally {
            if (!previouslyPaused) {
                setWritesPaused(false);
            }
        }
    }

    public boolean optimizeStorage() {
        boolean previouslyPaused = writesPaused();
        if (!previouslyPaused) {
            setWritesPaused(true);
        }

        try {
            awaitWriterQuiescence();
            try (Connection connection = openConnection();
                 Statement statement = connection.createStatement()) {
                if (databaseType == CoreProtectFabricConfig.DatabaseType.MYSQL) {
                    statement.execute("OPTIMIZE TABLE cp_events");
                    statement.execute("OPTIMIZE TABLE cp_entity");
                    statement.execute("OPTIMIZE TABLE cp_world");
                    statement.execute("OPTIMIZE TABLE cp_actor");
                    statement.execute("OPTIMIZE TABLE cp_target");
                    statement.execute("OPTIMIZE TABLE cp_pending_inventory_rollbacks");
                }
                else {
                    statement.execute("VACUUM");
                    statement.execute("ANALYZE");
                }
                return true;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to optimize Fabric audit tables", exception);
        }
        finally {
            if (!previouslyPaused) {
                setWritesPaused(false);
            }
        }
    }

    private void initializeSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE) {
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_world ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "world_key TEXT NOT NULL UNIQUE"
                        + ")"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_actor ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "actor_uuid TEXT, "
                        + "actor_name TEXT, "
                        + "lookup_key TEXT NOT NULL UNIQUE"
                        + ")"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_target ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "target_hash INTEGER NOT NULL, "
                        + "target TEXT NOT NULL, "
                        + "target_key TEXT, "
                        + "target_path TEXT, "
                        + "lookup_key TEXT NOT NULL UNIQUE"
                        + ")"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_entity ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "ts INTEGER NOT NULL, "
                        + "data TEXT"
                        + ")"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_events ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "ts INTEGER NOT NULL, "
                        + "event_type TEXT NOT NULL, "
                        + "actor_id INTEGER, "
                        + "world_id INTEGER, "
                        + "x INTEGER, "
                        + "y INTEGER, "
                        + "z INTEGER, "
                        + "target_id INTEGER, "
                        + "entity_key INTEGER, "
                        + "payload TEXT, "
                        + "rolled_back INTEGER NOT NULL DEFAULT 0, "
                        + "FOREIGN KEY (actor_id) REFERENCES cp_actor (id), "
                        + "FOREIGN KEY (world_id) REFERENCES cp_world (id), "
                        + "FOREIGN KEY (target_id) REFERENCES cp_target (id), "
                        + "FOREIGN KEY (entity_key) REFERENCES cp_entity (id)"
                        + ")"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_pending_inventory_rollbacks ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "event_id INTEGER NOT NULL, "
                        + "actor_id INTEGER, "
                        + "world_id INTEGER, "
                        + "restore INTEGER NOT NULL DEFAULT 0, "
                        + "add_items INTEGER NOT NULL DEFAULT 0, "
                        + "target_id INTEGER, "
                        + "payload TEXT, "
                        + "status TEXT NOT NULL DEFAULT 'PENDING', "
                        + "attempts INTEGER NOT NULL DEFAULT 0, "
                        + "last_error TEXT, "
                        + "created_ts INTEGER NOT NULL, "
                        + "updated_ts INTEGER NOT NULL, "
                        + "FOREIGN KEY (event_id) REFERENCES cp_events (id) ON DELETE CASCADE, "
                        + "FOREIGN KEY (actor_id) REFERENCES cp_actor (id), "
                        + "FOREIGN KEY (world_id) REFERENCES cp_world (id), "
                        + "FOREIGN KEY (target_id) REFERENCES cp_target (id)"
                        + ")"
                );
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS cp_events_ts_idx ON cp_events (ts)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS cp_entity_ts_idx ON cp_entity (ts)");
                statement.executeUpdate("CREATE UNIQUE INDEX IF NOT EXISTS cp_pending_inventory_rollbacks_event_restore_idx ON cp_pending_inventory_rollbacks (event_id, restore)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS cp_pending_inventory_rollbacks_status_idx ON cp_pending_inventory_rollbacks (status, created_ts)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS cp_pending_inventory_rollbacks_status_attempts_updated_idx ON cp_pending_inventory_rollbacks (status, attempts, updated_ts)");
            }
            else {
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_world ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, "
                        + "world_key VARCHAR(255) NOT NULL, "
                        + "PRIMARY KEY (id), "
                        + "UNIQUE KEY cp_world_key_idx (world_key)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_actor ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, "
                        + "actor_uuid VARCHAR(64), "
                        + "actor_name VARCHAR(255), "
                        + "lookup_key CHAR(64) NOT NULL, "
                        + "PRIMARY KEY (id), "
                        + "UNIQUE KEY cp_actor_lookup_key_idx (lookup_key), "
                        + "KEY cp_actor_uuid_name_idx (actor_uuid, actor_name), "
                        + "KEY cp_actor_name_idx (actor_name)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_target ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, "
                        + "target_hash BIGINT NOT NULL, "
                        + "target TEXT NOT NULL, "
                        + "target_key VARCHAR(255), "
                        + "target_path VARCHAR(255), "
                        + "lookup_key CHAR(64) NOT NULL, "
                        + "PRIMARY KEY (id), "
                        + "UNIQUE KEY cp_target_lookup_key_idx (lookup_key), "
                        + "KEY cp_target_hash_idx (target_hash), "
                        + "KEY cp_target_key_idx (target_key), "
                        + "KEY cp_target_path_idx (target_path)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_entity ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, "
                        + "ts BIGINT NOT NULL, "
                        + "data LONGTEXT, "
                        + "PRIMARY KEY (id), "
                        + "KEY cp_entity_ts_idx (ts)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_events ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, "
                        + "ts BIGINT NOT NULL, "
                        + "event_type VARCHAR(64) NOT NULL, "
                        + "actor_id BIGINT, "
                        + "world_id BIGINT, "
                        + "x INT, "
                        + "y INT, "
                        + "z INT, "
                        + "target_id BIGINT, "
                        + "entity_key BIGINT, "
                        + "payload LONGTEXT, "
                        + "rolled_back TINYINT(1) NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (id), "
                        + "KEY cp_events_ts_idx (ts), "
                        + "CONSTRAINT cp_events_actor_fk FOREIGN KEY (actor_id) REFERENCES cp_actor (id), "
                        + "CONSTRAINT cp_events_world_fk FOREIGN KEY (world_id) REFERENCES cp_world (id), "
                        + "CONSTRAINT cp_events_target_fk FOREIGN KEY (target_id) REFERENCES cp_target (id), "
                        + "CONSTRAINT cp_events_entity_fk FOREIGN KEY (entity_key) REFERENCES cp_entity (id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                );
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_pending_inventory_rollbacks ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, "
                        + "event_id BIGINT NOT NULL, "
                        + "actor_id BIGINT, "
                        + "world_id BIGINT, "
                        + "restore TINYINT(1) NOT NULL DEFAULT 0, "
                        + "add_items TINYINT(1) NOT NULL DEFAULT 0, "
                        + "target_id BIGINT, "
                        + "payload LONGTEXT, "
                        + "status VARCHAR(16) NOT NULL DEFAULT 'PENDING', "
                        + "attempts INT NOT NULL DEFAULT 0, "
                        + "last_error TEXT, "
                        + "created_ts BIGINT NOT NULL, "
                        + "updated_ts BIGINT NOT NULL, "
                        + "PRIMARY KEY (id), "
                        + "UNIQUE KEY cp_pending_inventory_rollbacks_event_restore_idx (event_id, restore), "
                        + "KEY cp_pending_inventory_rollbacks_status_idx (status, created_ts), "
                        + "KEY cp_pending_inventory_rollbacks_status_attempts_updated_idx (status, attempts, updated_ts), "
                        + "CONSTRAINT cp_pending_inventory_rollbacks_event_fk FOREIGN KEY (event_id) REFERENCES cp_events (id) ON DELETE CASCADE, "
                        + "CONSTRAINT cp_pending_inventory_rollbacks_actor_fk FOREIGN KEY (actor_id) REFERENCES cp_actor (id), "
                        + "CONSTRAINT cp_pending_inventory_rollbacks_world_fk FOREIGN KEY (world_id) REFERENCES cp_world (id), "
                        + "CONSTRAINT cp_pending_inventory_rollbacks_target_fk FOREIGN KEY (target_id) REFERENCES cp_target (id)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                );
            }
        }

        ensureColumn(connection, "cp_events", "rolled_back", databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? "INTEGER NOT NULL DEFAULT 0" : "TINYINT(1) NOT NULL DEFAULT 0");
        ensureColumn(connection, "cp_events", "actor_id", databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? "INTEGER" : "BIGINT");
        ensureColumn(connection, "cp_events", "world_id", databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? "INTEGER" : "BIGINT");
        ensureColumn(connection, "cp_events", "target_id", databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? "INTEGER" : "BIGINT");
        ensureColumn(connection, "cp_events", "entity_key", databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? "INTEGER" : "BIGINT");
        ensureColumn(connection, "cp_actor", "lookup_key", databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? "TEXT" : "CHAR(64)");
        ensureColumn(connection, "cp_target", "lookup_key", databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? "TEXT" : "CHAR(64)");
        ensureColumn(connection, "cp_pending_inventory_rollbacks", "actor_id", databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? "INTEGER" : "BIGINT");
        ensureColumn(connection, "cp_pending_inventory_rollbacks", "world_id", databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? "INTEGER" : "BIGINT");
        ensureColumn(connection, "cp_pending_inventory_rollbacks", "target_id", databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? "INTEGER" : "BIGINT");
        ensureReferenceIndexes(connection);
        normalizeMappingTables(connection);
        repairReferenceIntegrity(connection);
        ensureForeignKeys(connection);
        ensureSchemaIndexes(connection);
        cleanupObsoleteIndexes(connection);
    }

    void ensureReferenceIndexes(Connection connection) throws SQLException {
        ensureIndex(connection, "cp_events", "cp_events_actor_id_idx", "CREATE INDEX cp_events_actor_id_idx ON cp_events (actor_id)");
        ensureIndex(connection, "cp_events", "cp_events_world_id_idx", "CREATE INDEX cp_events_world_id_idx ON cp_events (world_id)");
        ensureIndex(connection, "cp_events", "cp_events_target_id_idx", "CREATE INDEX cp_events_target_id_idx ON cp_events (target_id)");
        ensureIndex(connection, "cp_events", "cp_events_entity_key_idx", "CREATE INDEX cp_events_entity_key_idx ON cp_events (entity_key)");
        ensureIndex(connection, "cp_pending_inventory_rollbacks", "cp_pending_inventory_rollbacks_actor_id_status_idx",
            "CREATE INDEX cp_pending_inventory_rollbacks_actor_id_status_idx ON cp_pending_inventory_rollbacks (actor_id, status)");
        ensureIndex(connection, "cp_pending_inventory_rollbacks", "cp_pending_inventory_rollbacks_world_id_status_idx",
            "CREATE INDEX cp_pending_inventory_rollbacks_world_id_status_idx ON cp_pending_inventory_rollbacks (world_id, status)");
        ensureIndex(connection, "cp_pending_inventory_rollbacks", "cp_pending_inventory_rollbacks_target_id_status_idx",
            "CREATE INDEX cp_pending_inventory_rollbacks_target_id_status_idx ON cp_pending_inventory_rollbacks (target_id, status)");
    }

    private void ensureSchemaIndexes(Connection connection) throws SQLException {
        if (databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE) {
            ensureIndex(connection, "cp_events", "cp_events_ts_idx", "CREATE INDEX cp_events_ts_idx ON cp_events (ts)");
            ensureIndex(connection, "cp_events", "cp_events_world_id_ts_idx", "CREATE INDEX cp_events_world_id_ts_idx ON cp_events (world_id, ts)");
            ensureIndex(connection, "cp_events", "cp_events_world_id_xyz_idx", "CREATE INDEX cp_events_world_id_xyz_idx ON cp_events (world_id, x, y, z)");
            ensureIndex(connection, "cp_events", "cp_events_actor_id_ts_idx", "CREATE INDEX cp_events_actor_id_ts_idx ON cp_events (actor_id, ts)");
            ensureIndex(connection, "cp_events", "cp_events_world_id_actor_id_ts_idx", "CREATE INDEX cp_events_world_id_actor_id_ts_idx ON cp_events (world_id, actor_id, ts)");
            ensureIndex(connection, "cp_events", "cp_events_world_id_event_ts_idx", "CREATE INDEX cp_events_world_id_event_ts_idx ON cp_events (world_id, event_type, ts)");
            ensureIndex(connection, "cp_events", "cp_events_world_id_rolled_event_ts_idx", "CREATE INDEX cp_events_world_id_rolled_event_ts_idx ON cp_events (world_id, rolled_back, event_type, ts)");
            ensureIndex(connection, "cp_events", "cp_events_world_id_target_id_ts_idx", "CREATE INDEX cp_events_world_id_target_id_ts_idx ON cp_events (world_id, target_id, ts)");
            ensureIndex(connection, "cp_events", "cp_events_target_id_ts_idx", "CREATE INDEX cp_events_target_id_ts_idx ON cp_events (target_id, ts)");
            ensureIndex(connection, "cp_events", "cp_events_rolled_back_ts_idx", "CREATE INDEX cp_events_rolled_back_ts_idx ON cp_events (rolled_back, ts)");

            ensureIndex(connection, "cp_world", "cp_world_key_idx", "CREATE UNIQUE INDEX cp_world_key_idx ON cp_world (world_key)");
            ensureIndex(connection, "cp_actor", "cp_actor_lookup_key_idx", "CREATE UNIQUE INDEX cp_actor_lookup_key_idx ON cp_actor (lookup_key)");
            ensureIndex(connection, "cp_actor", "cp_actor_uuid_name_idx", "CREATE INDEX cp_actor_uuid_name_idx ON cp_actor (actor_uuid, actor_name COLLATE NOCASE)");
            ensureIndex(connection, "cp_actor", "cp_actor_name_idx", "CREATE INDEX cp_actor_name_idx ON cp_actor (actor_name COLLATE NOCASE)");
            ensureIndex(connection, "cp_target", "cp_target_lookup_key_idx", "CREATE UNIQUE INDEX cp_target_lookup_key_idx ON cp_target (lookup_key)");
            ensureIndex(connection, "cp_target", "cp_target_hash_idx", "CREATE INDEX cp_target_hash_idx ON cp_target (target_hash)");
            ensureIndex(connection, "cp_target", "cp_target_key_idx", "CREATE INDEX cp_target_key_idx ON cp_target (target_key)");
            ensureIndex(connection, "cp_target", "cp_target_path_idx", "CREATE INDEX cp_target_path_idx ON cp_target (target_path)");

            ensureIndex(connection, "cp_entity", "cp_entity_ts_idx", "CREATE INDEX cp_entity_ts_idx ON cp_entity (ts)");

            ensureIndex(connection, "cp_pending_inventory_rollbacks", "cp_pending_inventory_rollbacks_event_restore_idx",
                "CREATE UNIQUE INDEX cp_pending_inventory_rollbacks_event_restore_idx ON cp_pending_inventory_rollbacks (event_id, restore)");
            ensureIndex(connection, "cp_pending_inventory_rollbacks", "cp_pending_inventory_rollbacks_status_idx",
                "CREATE INDEX cp_pending_inventory_rollbacks_status_idx ON cp_pending_inventory_rollbacks (status, created_ts)");
            ensureIndex(connection, "cp_pending_inventory_rollbacks", "cp_pending_inventory_rollbacks_status_attempts_updated_idx",
                "CREATE INDEX cp_pending_inventory_rollbacks_status_attempts_updated_idx ON cp_pending_inventory_rollbacks (status, attempts, updated_ts)");
            return;
        }

        ensureIndex(connection, "cp_events", "cp_events_ts_idx", "CREATE INDEX cp_events_ts_idx ON cp_events (ts)");
        ensureIndex(connection, "cp_events", "cp_events_world_id_ts_idx", "CREATE INDEX cp_events_world_id_ts_idx ON cp_events (world_id, ts)");
        ensureIndex(connection, "cp_events", "cp_events_world_id_xyz_idx", "CREATE INDEX cp_events_world_id_xyz_idx ON cp_events (world_id, x, y, z)");
        ensureIndex(connection, "cp_events", "cp_events_actor_id_ts_idx", "CREATE INDEX cp_events_actor_id_ts_idx ON cp_events (actor_id, ts)");
        ensureIndex(connection, "cp_events", "cp_events_world_id_actor_id_ts_idx", "CREATE INDEX cp_events_world_id_actor_id_ts_idx ON cp_events (world_id, actor_id, ts)");
        ensureIndex(connection, "cp_events", "cp_events_world_id_event_ts_idx", "CREATE INDEX cp_events_world_id_event_ts_idx ON cp_events (world_id, event_type, ts)");
        ensureIndex(connection, "cp_events", "cp_events_world_id_rolled_event_ts_idx", "CREATE INDEX cp_events_world_id_rolled_event_ts_idx ON cp_events (world_id, rolled_back, event_type, ts)");
        ensureIndex(connection, "cp_events", "cp_events_world_id_target_id_ts_idx", "CREATE INDEX cp_events_world_id_target_id_ts_idx ON cp_events (world_id, target_id, ts)");
        ensureIndex(connection, "cp_events", "cp_events_target_id_ts_idx", "CREATE INDEX cp_events_target_id_ts_idx ON cp_events (target_id, ts)");
        ensureIndex(connection, "cp_events", "cp_events_rolled_back_ts_idx", "CREATE INDEX cp_events_rolled_back_ts_idx ON cp_events (rolled_back, ts)");

        ensureIndex(connection, "cp_world", "cp_world_key_idx", "CREATE UNIQUE INDEX cp_world_key_idx ON cp_world (world_key)");
        ensureIndex(connection, "cp_actor", "cp_actor_lookup_key_idx", "CREATE UNIQUE INDEX cp_actor_lookup_key_idx ON cp_actor (lookup_key)");
        ensureIndex(connection, "cp_actor", "cp_actor_uuid_name_idx", "CREATE INDEX cp_actor_uuid_name_idx ON cp_actor (actor_uuid, actor_name)");
        ensureIndex(connection, "cp_actor", "cp_actor_name_idx", "CREATE INDEX cp_actor_name_idx ON cp_actor (actor_name)");
        ensureIndex(connection, "cp_target", "cp_target_lookup_key_idx", "CREATE UNIQUE INDEX cp_target_lookup_key_idx ON cp_target (lookup_key)");
        ensureIndex(connection, "cp_target", "cp_target_hash_idx", "CREATE INDEX cp_target_hash_idx ON cp_target (target_hash)");
        ensureIndex(connection, "cp_target", "cp_target_key_idx", "CREATE INDEX cp_target_key_idx ON cp_target (target_key)");
        ensureIndex(connection, "cp_target", "cp_target_path_idx", "CREATE INDEX cp_target_path_idx ON cp_target (target_path)");

        ensureIndex(connection, "cp_entity", "cp_entity_ts_idx", "CREATE INDEX cp_entity_ts_idx ON cp_entity (ts)");

        ensureIndex(connection, "cp_pending_inventory_rollbacks", "cp_pending_inventory_rollbacks_event_restore_idx",
            "CREATE UNIQUE INDEX cp_pending_inventory_rollbacks_event_restore_idx ON cp_pending_inventory_rollbacks (event_id, restore)");
        ensureIndex(connection, "cp_pending_inventory_rollbacks", "cp_pending_inventory_rollbacks_status_idx",
            "CREATE INDEX cp_pending_inventory_rollbacks_status_idx ON cp_pending_inventory_rollbacks (status, created_ts)");
        ensureIndex(connection, "cp_pending_inventory_rollbacks", "cp_pending_inventory_rollbacks_status_attempts_updated_idx",
            "CREATE INDEX cp_pending_inventory_rollbacks_status_attempts_updated_idx ON cp_pending_inventory_rollbacks (status, attempts, updated_ts)");
    }

    private void ensureColumn(Connection connection, String tableName, String columnName, String definition) throws SQLException {
        if (hasColumn(connection, tableName, columnName)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("ALTER TABLE " + tableName + " ADD COLUMN " + columnName + " " + definition);
        }
    }

    private boolean hasColumn(Connection connection, String tableName, String columnName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getColumns(connection.getCatalog(), null, tableName, columnName)) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("COLUMN_NAME"))) {
                    return true;
                }
            }
            return false;
        }
    }

    private void ensureIndex(Connection connection, String tableName, String indexName, String createSql) throws SQLException {
        if (hasIndex(connection, tableName, indexName)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(createSql);
        }
    }

    private boolean hasIndex(Connection connection, String tableName, String indexName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getIndexInfo(connection.getCatalog(), null, tableName, false, false)) {
            while (resultSet.next()) {
                String name = resultSet.getString("INDEX_NAME");
                if (name != null && indexName.equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        }
    }

    private void normalizeMappingTables(Connection connection) throws SQLException {
        backfillActorLookupKeys(connection);
        backfillTargetLookupKeys(connection);
        deduplicateActorMappings(connection);
        deduplicateTargetMappings(connection);
    }

    void repairReferenceIntegrity(Connection connection) throws SQLException {
        if (!hasForeignKeyReference(connection, "cp_pending_inventory_rollbacks", "event_id", "cp_events")) {
            purgeOrphanPendingInventoryRows(connection);
        }
        repairOrphanReference(connection, "cp_events", "actor_id", "cp_actor");
        repairOrphanReference(connection, "cp_events", "world_id", "cp_world");
        repairOrphanReference(connection, "cp_events", "target_id", "cp_target");
        repairOrphanReference(connection, "cp_events", "entity_key", "cp_entity");
        repairOrphanReference(connection, "cp_pending_inventory_rollbacks", "actor_id", "cp_actor");
        repairOrphanReference(connection, "cp_pending_inventory_rollbacks", "world_id", "cp_world");
        repairOrphanReference(connection, "cp_pending_inventory_rollbacks", "target_id", "cp_target");
    }

    private void repairOrphanReference(Connection connection, String tableName, String columnName, String referenceTable) throws SQLException {
        if (!hasForeignKeyReference(connection, tableName, columnName, referenceTable)) {
            nullOrphanReference(connection, tableName, columnName, referenceTable);
        }
    }

    private boolean hasForeignKeyReference(Connection connection, String tableName, String columnName, String referenceTable) throws SQLException {
        if (databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE) {
            return hasSqliteForeignKey(connection, tableName, columnName, referenceTable);
        }

        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getImportedKeys(connection.getCatalog(), null, tableName)) {
            while (resultSet.next()) {
                if (columnName.equalsIgnoreCase(resultSet.getString("FKCOLUMN_NAME"))
                    && referenceTable.equalsIgnoreCase(resultSet.getString("PKTABLE_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureForeignKeys(Connection connection) throws SQLException {
        if (databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE) {
            ensureSqliteForeignKeys(connection);
            return;
        }

        ensureForeignKey(
            connection,
            "cp_events",
            "cp_events_actor_fk",
            "ALTER TABLE cp_events ADD CONSTRAINT cp_events_actor_fk FOREIGN KEY (actor_id) REFERENCES cp_actor (id)"
        );
        ensureForeignKey(
            connection,
            "cp_events",
            "cp_events_world_fk",
            "ALTER TABLE cp_events ADD CONSTRAINT cp_events_world_fk FOREIGN KEY (world_id) REFERENCES cp_world (id)"
        );
        ensureForeignKey(
            connection,
            "cp_events",
            "cp_events_target_fk",
            "ALTER TABLE cp_events ADD CONSTRAINT cp_events_target_fk FOREIGN KEY (target_id) REFERENCES cp_target (id)"
        );
        ensureForeignKey(
            connection,
            "cp_events",
            "cp_events_entity_fk",
            "ALTER TABLE cp_events ADD CONSTRAINT cp_events_entity_fk FOREIGN KEY (entity_key) REFERENCES cp_entity (id)"
        );
        ensureForeignKey(
            connection,
            "cp_pending_inventory_rollbacks",
            "cp_pending_inventory_rollbacks_event_fk",
            "ALTER TABLE cp_pending_inventory_rollbacks ADD CONSTRAINT cp_pending_inventory_rollbacks_event_fk FOREIGN KEY (event_id) REFERENCES cp_events (id) ON DELETE CASCADE"
        );
        ensureForeignKey(
            connection,
            "cp_pending_inventory_rollbacks",
            "cp_pending_inventory_rollbacks_actor_fk",
            "ALTER TABLE cp_pending_inventory_rollbacks ADD CONSTRAINT cp_pending_inventory_rollbacks_actor_fk FOREIGN KEY (actor_id) REFERENCES cp_actor (id)"
        );
        ensureForeignKey(
            connection,
            "cp_pending_inventory_rollbacks",
            "cp_pending_inventory_rollbacks_world_fk",
            "ALTER TABLE cp_pending_inventory_rollbacks ADD CONSTRAINT cp_pending_inventory_rollbacks_world_fk FOREIGN KEY (world_id) REFERENCES cp_world (id)"
        );
        ensureForeignKey(
            connection,
            "cp_pending_inventory_rollbacks",
            "cp_pending_inventory_rollbacks_target_fk",
            "ALTER TABLE cp_pending_inventory_rollbacks ADD CONSTRAINT cp_pending_inventory_rollbacks_target_fk FOREIGN KEY (target_id) REFERENCES cp_target (id)"
        );
    }

    private void ensureForeignKey(Connection connection, String tableName, String foreignKeyName, String alterSql) throws SQLException {
        if (hasForeignKey(connection, tableName, foreignKeyName)) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(alterSql);
        }
    }

    private boolean hasForeignKey(Connection connection, String tableName, String foreignKeyName) throws SQLException {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet resultSet = metadata.getImportedKeys(connection.getCatalog(), null, tableName)) {
            while (resultSet.next()) {
                String fkName = resultSet.getString("FK_NAME");
                if (fkName != null && foreignKeyName.equalsIgnoreCase(fkName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void ensureSqliteForeignKeys(Connection connection) throws SQLException {
        boolean rebuildEvents = !hasSqliteForeignKey(connection, "cp_events", "actor_id", "cp_actor")
            || !hasSqliteForeignKey(connection, "cp_events", "world_id", "cp_world")
            || !hasSqliteForeignKey(connection, "cp_events", "target_id", "cp_target")
            || !hasSqliteForeignKey(connection, "cp_events", "entity_key", "cp_entity");
        boolean rebuildPending = !hasSqliteForeignKey(connection, "cp_pending_inventory_rollbacks", "event_id", "cp_events")
            || !hasSqliteForeignKey(connection, "cp_pending_inventory_rollbacks", "actor_id", "cp_actor")
            || !hasSqliteForeignKey(connection, "cp_pending_inventory_rollbacks", "world_id", "cp_world")
            || !hasSqliteForeignKey(connection, "cp_pending_inventory_rollbacks", "target_id", "cp_target");

        if (!rebuildEvents && !rebuildPending) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
        }

        boolean originalAutoCommit = connection.getAutoCommit();
        if (originalAutoCommit) {
            connection.setAutoCommit(false);
        }

        try {
            if (rebuildEvents) {
                rebuildSqliteEventsTable(connection);
            }
            if (rebuildPending) {
                rebuildSqlitePendingInventoryTable(connection);
            }
            connection.commit();
        }
        catch (SQLException exception) {
            connection.rollback();
            throw exception;
        }
        finally {
            if (originalAutoCommit) {
                connection.setAutoCommit(true);
            }
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
            }
        }
    }

    private boolean hasSqliteForeignKey(Connection connection, String tableName, String fromColumn, String referenceTable) throws SQLException {
        String pragma = "PRAGMA foreign_key_list(" + tableName + ")";
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(pragma)) {
            while (resultSet.next()) {
                if (fromColumn.equalsIgnoreCase(resultSet.getString("from"))
                    && referenceTable.equalsIgnoreCase(resultSet.getString("table"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private void rebuildSqliteEventsTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cp_events_new");
            statement.executeUpdate(
                "CREATE TABLE cp_events_new ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "ts INTEGER NOT NULL, "
                    + "event_type TEXT NOT NULL, "
                    + "actor_id INTEGER, "
                    + "world_id INTEGER, "
                    + "x INTEGER, "
                    + "y INTEGER, "
                    + "z INTEGER, "
                    + "target_id INTEGER, "
                    + "entity_key INTEGER, "
                    + "payload TEXT, "
                    + "rolled_back INTEGER NOT NULL DEFAULT 0, "
                    + "FOREIGN KEY (actor_id) REFERENCES cp_actor (id), "
                    + "FOREIGN KEY (world_id) REFERENCES cp_world (id), "
                    + "FOREIGN KEY (target_id) REFERENCES cp_target (id), "
                    + "FOREIGN KEY (entity_key) REFERENCES cp_entity (id)"
                    + ")"
            );
            statement.executeUpdate(
                "INSERT INTO cp_events_new (id, ts, event_type, actor_id, world_id, x, y, z, target_id, entity_key, payload, rolled_back) "
                    + "SELECT id, ts, event_type, actor_id, world_id, x, y, z, target_id, entity_key, payload, rolled_back FROM cp_events"
            );
            statement.executeUpdate("DROP TABLE cp_events");
            statement.executeUpdate("ALTER TABLE cp_events_new RENAME TO cp_events");
        }
    }

    private void rebuildSqlitePendingInventoryTable(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS cp_pending_inventory_rollbacks_new");
            statement.executeUpdate(
                "CREATE TABLE cp_pending_inventory_rollbacks_new ("
                    + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + "event_id INTEGER NOT NULL, "
                    + "actor_id INTEGER, "
                    + "world_id INTEGER, "
                    + "restore INTEGER NOT NULL DEFAULT 0, "
                    + "add_items INTEGER NOT NULL DEFAULT 0, "
                    + "target_id INTEGER, "
                    + "payload TEXT, "
                    + "status TEXT NOT NULL DEFAULT 'PENDING', "
                    + "attempts INTEGER NOT NULL DEFAULT 0, "
                    + "last_error TEXT, "
                    + "created_ts INTEGER NOT NULL, "
                    + "updated_ts INTEGER NOT NULL, "
                    + "FOREIGN KEY (event_id) REFERENCES cp_events (id) ON DELETE CASCADE, "
                    + "FOREIGN KEY (actor_id) REFERENCES cp_actor (id), "
                    + "FOREIGN KEY (world_id) REFERENCES cp_world (id), "
                    + "FOREIGN KEY (target_id) REFERENCES cp_target (id)"
                    + ")"
            );
            statement.executeUpdate(
                "INSERT INTO cp_pending_inventory_rollbacks_new (id, event_id, actor_id, world_id, restore, add_items, target_id, payload, status, attempts, last_error, created_ts, updated_ts) "
                    + "SELECT id, event_id, actor_id, world_id, restore, add_items, target_id, payload, status, attempts, last_error, created_ts, updated_ts FROM cp_pending_inventory_rollbacks"
            );
            statement.executeUpdate("DROP TABLE cp_pending_inventory_rollbacks");
            statement.executeUpdate("ALTER TABLE cp_pending_inventory_rollbacks_new RENAME TO cp_pending_inventory_rollbacks");
        }
    }

    private void cleanupObsoleteIndexes(Connection connection) throws SQLException {
        dropIndex(connection, "cp_events", "cp_events_actor_idx");
        dropIndex(connection, "cp_events", "cp_events_actor_name_idx");
        dropIndex(connection, "cp_events", "cp_events_actor_name_ts_idx");
        dropIndex(connection, "cp_events", "cp_events_target_key_idx");
        dropIndex(connection, "cp_events", "cp_events_target_path_idx");
        dropIndex(connection, "cp_events", "cp_events_world_xyz_idx");
        dropIndex(connection, "cp_events", "cp_events_world_ts_idx");
        dropIndex(connection, "cp_events", "cp_events_world_actor_ts_idx");
        dropIndex(connection, "cp_events", "cp_events_world_event_ts_idx");
        dropIndex(connection, "cp_events", "cp_events_world_rolled_ts_idx");
        dropIndex(connection, "cp_events", "cp_events_world_rolled_event_ts_idx");
        dropIndex(connection, "cp_events", "cp_events_world_target_key_ts_idx");
        dropIndex(connection, "cp_events", "cp_events_world_target_path_ts_idx");
        dropIndex(connection, "cp_pending_inventory_rollbacks", "cp_pending_inventory_rollbacks_actor_status_idx");
        dropIndex(connection, "cp_pending_inventory_rollbacks", "cp_pending_inventory_rollbacks_actor_name_status_idx");
    }

    private void dropIndex(Connection connection, String tableName, String indexName) throws SQLException {
        if (!hasIndex(connection, tableName, indexName)) {
            return;
        }

        String dropSql = databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE
            ? "DROP INDEX IF EXISTS " + indexName
            : "DROP INDEX " + indexName + " ON " + tableName;
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(dropSql);
        }
    }

    private void backfillActorLookupKeys(Connection connection) throws SQLException {
        String selectSql =
            "SELECT id, actor_uuid, actor_name "
                + "FROM cp_actor "
                + "WHERE lookup_key IS NULL OR lookup_key = ''";
        String updateSql = "UPDATE cp_actor SET lookup_key = ? WHERE id = ?";
        runLookupKeyBackfill(connection, selectSql, updateSql, resultSet ->
            new LookupKeyRow(actorLookupKey(resultSet.getString("actor_uuid"), resultSet.getString("actor_name")), resultSet.getLong("id")));
    }

    private void backfillTargetLookupKeys(Connection connection) throws SQLException {
        String selectSql =
            "SELECT id, target "
                + "FROM cp_target "
                + "WHERE lookup_key IS NULL OR lookup_key = ''";
        String updateSql = "UPDATE cp_target SET lookup_key = ? WHERE id = ?";
        runLookupKeyBackfill(connection, selectSql, updateSql, resultSet ->
            new LookupKeyRow(targetLookupKey(resultSet.getString("target")), resultSet.getLong("id")));
    }

    private void runLookupKeyBackfill(
        Connection connection,
        String selectSql,
        String updateSql,
        LookupKeyResolver resolver
    ) throws SQLException {
        boolean originalAutoCommit = connection.getAutoCommit();
        if (originalAutoCommit) {
            connection.setAutoCommit(false);
        }

        try (PreparedStatement select = connection.prepareStatement(selectSql);
             ResultSet rows = select.executeQuery();
             PreparedStatement update = connection.prepareStatement(updateSql)) {
            int updates = 0;
            while (rows.next()) {
                LookupKeyRow row = resolver.resolve(rows);
                if (row == null || row.lookupKey() == null || row.lookupKey().isBlank()) {
                    continue;
                }
                update.setString(1, row.lookupKey());
                update.setLong(2, row.id());
                update.addBatch();
                updates++;
                if (updates % MAPPING_BACKFILL_BATCH_SIZE == 0) {
                    update.executeBatch();
                    if (originalAutoCommit) {
                        connection.commit();
                    }
                }
            }

            if (updates % MAPPING_BACKFILL_BATCH_SIZE != 0) {
                update.executeBatch();
            }

            if (originalAutoCommit) {
                connection.commit();
            }
        }
        catch (SQLException exception) {
            if (originalAutoCommit) {
                connection.rollback();
            }
            throw exception;
        }
        finally {
            if (originalAutoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    private void deduplicateActorMappings(Connection connection) throws SQLException {
        deduplicateMappings(connection, "cp_actor", "lookup_key", "actor_id");
    }

    private void deduplicateTargetMappings(Connection connection) throws SQLException {
        deduplicateMappings(connection, "cp_target", "lookup_key", "target_id");
    }

    private void deduplicateMappings(Connection connection, String mappingTable, String lookupColumn, String referenceColumn) throws SQLException {
        String duplicateSql =
            "SELECT " + lookupColumn + ", MIN(id) AS canonical_id "
                + "FROM " + mappingTable + " "
                + "WHERE " + lookupColumn + " IS NOT NULL "
                + "AND " + lookupColumn + " <> '' "
                + "GROUP BY " + lookupColumn + " "
                + "HAVING COUNT(*) > 1";
        boolean originalAutoCommit = connection.getAutoCommit();
        if (originalAutoCommit) {
            connection.setAutoCommit(false);
        }

        try (PreparedStatement duplicates = connection.prepareStatement(duplicateSql);
             ResultSet duplicateRows = duplicates.executeQuery()) {
            while (duplicateRows.next()) {
                String lookupKey = duplicateRows.getString(lookupColumn);
                long canonicalId = duplicateRows.getLong("canonical_id");
                mergeDuplicateMappingGroup(connection, mappingTable, lookupColumn, referenceColumn, lookupKey, canonicalId);
            }
            if (originalAutoCommit) {
                connection.commit();
            }
        }
        catch (SQLException exception) {
            if (originalAutoCommit) {
                connection.rollback();
            }
            throw exception;
        }
        finally {
            if (originalAutoCommit) {
                connection.setAutoCommit(true);
            }
        }
    }

    private void mergeDuplicateMappingGroup(
        Connection connection,
        String mappingTable,
        String lookupColumn,
        String referenceColumn,
        String lookupKey,
        long canonicalId
    ) throws SQLException {
        List<Long> duplicateIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM " + mappingTable + " WHERE " + lookupColumn + " = ? AND id <> ? ORDER BY id ASC"
        )) {
            statement.setString(1, lookupKey);
            statement.setLong(2, canonicalId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    duplicateIds.add(resultSet.getLong("id"));
                }
            }
        }

        for (Long duplicateId : duplicateIds) {
            try (PreparedStatement updateEvents = connection.prepareStatement("UPDATE cp_events SET " + referenceColumn + " = ? WHERE " + referenceColumn + " = ?");
                 PreparedStatement updatePending = connection.prepareStatement("UPDATE cp_pending_inventory_rollbacks SET " + referenceColumn + " = ? WHERE " + referenceColumn + " = ?");
                 PreparedStatement delete = connection.prepareStatement("DELETE FROM " + mappingTable + " WHERE id = ?")) {
                updateEvents.setLong(1, canonicalId);
                updateEvents.setLong(2, duplicateId);
                updateEvents.executeUpdate();

                updatePending.setLong(1, canonicalId);
                updatePending.setLong(2, duplicateId);
                updatePending.executeUpdate();

                delete.setLong(1, duplicateId);
                delete.executeUpdate();
            }
        }
    }

    private void nullOrphanReference(Connection connection, String tableName, String columnName, String referenceTable) throws SQLException {
        String sql =
            "UPDATE " + tableName + " "
                + "SET " + columnName + " = NULL "
                + "WHERE " + columnName + " IS NOT NULL "
                + "AND NOT EXISTS (SELECT 1 FROM " + referenceTable + " mapping WHERE mapping.id = " + tableName + "." + columnName + ")";
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }

    private Long ensureWorldId(Connection connection, MappingContext context, String worldKey) throws SQLException {
        if (worldKey == null || worldKey.isBlank()) {
            return null;
        }
        Long cached = context.worldIds().get(worldKey);
        if (cached != null) {
            return cached;
        }

        String selectSql = "SELECT id FROM cp_world WHERE world_key = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setString(1, worldKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    long id = resultSet.getLong("id");
                    context.worldIds().put(worldKey, id);
                    return id;
                }
            }
        }

        String insertSql = "INSERT INTO cp_world (world_key) VALUES (?)";
        try {
            long id = insertAndReturnId(connection, insertSql, statement -> statement.setString(1, worldKey));
            context.worldIds().put(worldKey, id);
            return id;
        }
        catch (SQLException exception) {
            if (!isUniqueConstraintViolation(exception)) {
                throw exception;
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setString(1, worldKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    long id = resultSet.getLong("id");
                    context.worldIds().put(worldKey, id);
                    return id;
                }
            }
        }
        throw new SQLException("Unable to resolve world mapping row");
    }

    private Long ensureActorId(Connection connection, MappingContext context, String actorUuid, String actorName) throws SQLException {
        if ((actorUuid == null || actorUuid.isBlank()) && (actorName == null || actorName.isBlank())) {
            return null;
        }

        String cacheKey = actorLookupKey(actorUuid, actorName);
        Long cached = context.actorIds().get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM cp_actor WHERE lookup_key = ? LIMIT 1")) {
            statement.setString(1, cacheKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    long id = resultSet.getLong("id");
                    context.actorIds().put(cacheKey, id);
                    return id;
                }
            }
        }

        String insertSql = "INSERT INTO cp_actor (actor_uuid, actor_name, lookup_key) VALUES (?, ?, ?)";
        try {
            long id = insertAndReturnId(connection, insertSql, statement -> {
                statement.setString(1, actorUuid);
                statement.setString(2, actorName);
                statement.setString(3, cacheKey);
            });
            context.actorIds().put(cacheKey, id);
            return id;
        }
        catch (SQLException exception) {
            if (!isUniqueConstraintViolation(exception)) {
                throw exception;
            }
        }

        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM cp_actor WHERE lookup_key = ? LIMIT 1")) {
            statement.setString(1, cacheKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    long id = resultSet.getLong("id");
                    context.actorIds().put(cacheKey, id);
                    return id;
                }
            }
        }
        throw new SQLException("Unable to resolve actor mapping row");
    }

    private Long ensureTargetId(Connection connection, MappingContext context, String target, TargetIndex targetIndex) throws SQLException {
        if (target == null || target.isBlank()) {
            return null;
        }

        String lookupKey = targetLookupKey(target);
        Long cached = context.targetIds().get(lookupKey);
        if (cached != null) {
            return cached;
        }

        long targetHash = targetHash(target);
        String selectSql = "SELECT id FROM cp_target WHERE lookup_key = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setString(1, lookupKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    long id = resultSet.getLong("id");
                    context.targetIds().put(lookupKey, id);
                    return id;
                }
            }
        }

        String insertSql = "INSERT INTO cp_target (target_hash, target, target_key, target_path, lookup_key) VALUES (?, ?, ?, ?, ?)";
        try {
            long id = insertAndReturnId(connection, insertSql, statement -> {
                statement.setLong(1, targetHash);
                statement.setString(2, target);
                statement.setString(3, targetIndex == null ? null : targetIndex.key());
                statement.setString(4, targetIndex == null ? null : targetIndex.path());
                statement.setString(5, lookupKey);
            });
            context.targetIds().put(lookupKey, id);
            return id;
        }
        catch (SQLException exception) {
            if (!isUniqueConstraintViolation(exception)) {
                throw exception;
            }
        }

        try (PreparedStatement statement = connection.prepareStatement(selectSql)) {
            statement.setString(1, lookupKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    long id = resultSet.getLong("id");
                    context.targetIds().put(lookupKey, id);
                    return id;
                }
            }
        }
        throw new SQLException("Unable to resolve target mapping row");
    }

    private void purgeOrphanEntityRows(Connection connection) throws SQLException {
        String sql =
            "DELETE FROM cp_entity WHERE id IN ("
                + "SELECT id FROM ("
                + "SELECT e.id "
                + "FROM cp_entity e "
                + "LEFT JOIN cp_events c ON c.entity_key = e.id "
                + "WHERE c.id IS NULL "
                + "LIMIT ?"
                + ") orphan_entity"
                + ")";

        int totalBatches = 0;
        while (totalBatches < PURGE_ORPHAN_ENTITY_MAX_BATCHES) {
            int deleted;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, PURGE_ORPHAN_ENTITY_BATCH_SIZE);
                deleted = statement.executeUpdate();
            }

            if (deleted < PURGE_ORPHAN_ENTITY_BATCH_SIZE) {
                return;
            }
            totalBatches++;
        }
    }

    private void purgeOrphanMappingRows(Connection connection) throws SQLException {
        purgeOrphanRows(connection, "cp_target", "target_id", PURGE_ORPHAN_MAPPING_BATCH_SIZE, PURGE_ORPHAN_MAPPING_MAX_BATCHES);
        purgeOrphanRows(connection, "cp_actor", "actor_id", PURGE_ORPHAN_MAPPING_BATCH_SIZE, PURGE_ORPHAN_MAPPING_MAX_BATCHES);
        purgeOrphanRows(connection, "cp_world", "world_id", PURGE_ORPHAN_MAPPING_BATCH_SIZE, PURGE_ORPHAN_MAPPING_MAX_BATCHES);
    }

    private void purgeOrphanPendingInventoryRows(Connection connection) throws SQLException {
        String sql =
            "DELETE FROM cp_pending_inventory_rollbacks WHERE id IN ("
                + "SELECT id FROM ("
                + "SELECT queue.id "
                + "FROM cp_pending_inventory_rollbacks queue "
                + "LEFT JOIN cp_events event_row ON event_row.id = queue.event_id "
                + "WHERE event_row.id IS NULL "
                + "LIMIT ?"
                + ") orphan_queue"
                + ")";

        int totalBatches = 0;
        while (totalBatches < PURGE_ORPHAN_MAPPING_MAX_BATCHES) {
            int deleted;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, PURGE_ORPHAN_MAPPING_BATCH_SIZE);
                deleted = statement.executeUpdate();
            }

            if (deleted < PURGE_ORPHAN_MAPPING_BATCH_SIZE) {
                return;
            }
            totalBatches++;
        }
    }

    private void purgeOrphanRows(
        Connection connection,
        String tableName,
        String referenceColumn,
        int batchSize,
        int maxBatches
    ) throws SQLException {
        String sql =
            "DELETE FROM " + tableName + " WHERE id IN ("
                + "SELECT id FROM ("
                + "SELECT mapping.id "
                + "FROM " + tableName + " mapping "
                + "LEFT JOIN cp_events c ON c." + referenceColumn + " = mapping.id "
                + "WHERE c.id IS NULL "
                + "LIMIT ?"
                + ") orphan_mapping"
                + ")";

        int totalBatches = 0;
        while (totalBatches < maxBatches) {
            int deleted;
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setInt(1, batchSize);
                deleted = statement.executeUpdate();
            }

            if (deleted < batchSize) {
                return;
            }
            totalBatches++;
        }
    }

    private void insertBatch(List<EventRecord> records) throws SQLException {
        if (records == null || records.isEmpty()) {
            return;
        }

        boolean originalAutoCommit = writeConnection.getAutoCommit();
        if (originalAutoCommit) {
            writeConnection.setAutoCommit(false);
        }

        try (PreparedStatement statement = writeConnection.prepareStatement(
            "INSERT INTO cp_events ("
                + "ts, "
                + "event_type, "
                + "actor_id, "
                + "world_id, "
                + "x, "
                + "y, "
                + "z, "
                + "target_id, "
                + "entity_key, "
                + "payload, "
                + "rolled_back"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
        )) {
            MappingContext context = new MappingContext(new HashMap<>(), new HashMap<>(), new HashMap<>());
            for (EventRecord record : records) {
                fillEventInsertStatement(statement, record, context);
                statement.addBatch();
            }
            statement.executeBatch();
            writeConnection.commit();
        }
        catch (SQLException exception) {
            writeConnection.rollback();
            throw exception;
        }
        finally {
            if (originalAutoCommit) {
                writeConnection.setAutoCommit(true);
            }
        }
    }

    private void fillEventInsertStatement(PreparedStatement statement, EventRecord record, MappingContext context) throws SQLException {
        PreparedEventPayload eventPayload = prepareEventPayload(record);
        TargetIndex targetIndex = buildTargetIndex(record.target());
        Long actorId = ensureActorId(writeConnection, context, record.actorUuid(), record.actorName());
        Long worldId = ensureWorldId(writeConnection, context, record.worldKey());
        Long targetId = ensureTargetId(writeConnection, context, record.target(), targetIndex);
        statement.setLong(1, record.timestamp());
        statement.setString(2, record.type().name());
        bindNullableLong(statement, 3, actorId);
        bindNullableLong(statement, 4, worldId);
        bindNullableInt(statement, 5, record.x());
        bindNullableInt(statement, 6, record.y());
        bindNullableInt(statement, 7, record.z());
        bindNullableLong(statement, 8, targetId);
        bindNullableLong(statement, 9, eventPayload.entityKey());
        statement.setString(10, eventPayload.payload());
    }

    private PreparedEventPayload prepareEventPayload(EventRecord record) throws SQLException {
        String payload = record.payload();
        if (record.type() == CoreProtectEventType.ENTITY_KILL && payload != null && !payload.isBlank()) {
            long existingEntityKey = parseEntityKey(payload);
            if (existingEntityKey > 0L) {
                return new PreparedEventPayload("entity_key=" + existingEntityKey, existingEntityKey);
            }
            long entityKey = insertEntityPayload(payload, record.timestamp());
            return new PreparedEventPayload("entity_key=" + entityKey, entityKey);
        }
        return new PreparedEventPayload(payload, null);
    }

    private long insertEntityPayload(String payload, long timestamp) throws SQLException {
        String sql = "INSERT INTO cp_entity (ts, data) VALUES (?, ?)";
        try (PreparedStatement statement = writeConnection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, timestamp);
            statement.setString(2, payload);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to store entity payload");
    }

    private void bindNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
            return;
        }
        statement.setInt(index, value);
    }

    private void bindNullableLong(PreparedStatement statement, int index, Long value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BIGINT);
            return;
        }
        statement.setLong(index, value);
    }

    private long insertAndReturnId(Connection connection, String sql, StatementBinder binder) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            binder.bind(statement);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (keys.next()) {
                    return keys.getLong(1);
                }
            }
        }
        throw new SQLException("Unable to create mapping row");
    }

    private boolean isUniqueConstraintViolation(SQLException exception) {
        String sqlState = exception.getSQLState();
        if ("23505".equals(sqlState) || "23000".equals(sqlState)) {
            return true;
        }
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        return normalized.contains("unique constraint")
            || normalized.contains("duplicate entry")
            || normalized.contains("unique index");
    }

    private void awaitWritesResumed() {
        while (writesPaused.get()) {
            synchronized (pauseMonitor) {
                if (!writesPaused.get()) {
                    return;
                }
                try {
                    pauseMonitor.wait(250L);
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private Long findWorldId(Connection connection, String worldKey) throws SQLException {
        if (worldKey == null || worldKey.isBlank()) {
            return null;
        }

        String sql = "SELECT id FROM cp_world WHERE world_key = ? LIMIT 1";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, worldKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getLong("id") : null;
            }
        }
    }

    private List<Long> findActorIds(Connection connection, List<String> actorNames) throws SQLException {
        if (actorNames == null || actorNames.isEmpty()) {
            return List.of();
        }

        String sql = "SELECT id FROM cp_actor WHERE " + caseInsensitiveEquality("actor_name");
        List<Long> actorIds = new ArrayList<>();
        for (String actorName : actorNames) {
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, actorName);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        Long actorId = resultSet.getLong("id");
                        if (!actorIds.contains(actorId)) {
                            actorIds.add(actorId);
                        }
                    }
                }
            }
        }
        return actorIds;
    }

    private List<Long> resolvePendingActorIds(Connection connection, String actorUuid, String actorName) throws SQLException {
        List<Long> actorIds = new ArrayList<>();
        if (actorUuid != null && !actorUuid.isBlank()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM cp_actor WHERE actor_uuid = ?")) {
                statement.setString(1, actorUuid);
                try (ResultSet resultSet = statement.executeQuery()) {
                    while (resultSet.next()) {
                        addUnique(actorIds, resultSet.getLong("id"));
                    }
                }
            }
        }

        if (actorName != null && !actorName.isBlank()) {
            for (Long actorId : findActorIds(connection, List.of(actorName))) {
                addUnique(actorIds, actorId);
            }
        }
        return actorIds;
    }

    private String buildPendingActorPredicate(List<Long> actorIds) {
        StringBuilder sql = new StringBuilder(" AND (");
        for (int index = 0; index < actorIds.size(); index++) {
            if (index > 0) {
                sql.append(" OR ");
            }
            sql.append("p.actor_id = ?");
        }
        sql.append(")");
        return sql.toString();
    }

    private List<String> normalizeFilterValues(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null) {
                continue;
            }
            String trimmed = value.trim();
            if (!trimmed.isBlank() && !normalized.contains(trimmed)) {
                normalized.add(trimmed);
            }
        }
        return normalized;
    }

    private ResolvedTargetFilter resolveTargetFilter(Connection connection, List<String> values) throws SQLException {
        List<TargetSearchClause> clauses = buildTargetSearchClauses(values);
        if (clauses.isEmpty()) {
            return new ResolvedTargetFilter(false, List.of(), List.of());
        }

        List<Long> targetIds = new ArrayList<>();
        List<TargetSearchClause> textClauses = new ArrayList<>();
        for (TargetSearchClause clause : clauses) {
            if (clause.simpleToken()) {
                collectTargetIds(connection, clause, targetIds);
            }
            else {
                textClauses.add(clause);
            }
        }

        return new ResolvedTargetFilter(true, targetIds, textClauses);
    }

    private void collectTargetIds(Connection connection, TargetSearchClause clause, List<Long> targetIds) throws SQLException {
        for (String keyCandidate : clause.keyCandidates()) {
            collectTargetIds(
                connection,
                "SELECT id FROM cp_target WHERE " + caseInsensitiveEquality("target_key"),
                keyCandidate,
                targetIds
            );
        }
        for (String pathCandidate : clause.pathCandidates()) {
            collectTargetIds(
                connection,
                "SELECT id FROM cp_target WHERE " + caseInsensitiveEquality("target_path"),
                pathCandidate,
                targetIds
            );
        }
    }

    private void collectTargetIds(Connection connection, String sql, String value, List<Long> targetIds) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    addUnique(targetIds, resultSet.getLong("id"));
                }
            }
        }
    }

    private void appendAnyLongPredicate(StringBuilder sql, String column, List<Long> values, boolean negated) {
        if (values == null || values.isEmpty()) {
            return;
        }

        sql.append(" AND (");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sql.append(negated ? " AND " : " OR ");
            }
            if (negated) {
                sql.append("(").append(column).append(" IS NULL OR ").append(column).append(" <> ?)");
            }
            else {
                sql.append(column).append(" = ?");
            }
        }
        sql.append(")");
    }

    private String caseInsensitiveEquality(String column) {
        if (databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE) {
            return column + " = ? COLLATE NOCASE";
        }
        return column + " = ?";
    }

    private String caseInsensitiveLike(String column) {
        if (databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE) {
            return column + " LIKE ? COLLATE NOCASE";
        }
        return column + " LIKE ?";
    }

    private void appendResolvedTargetPredicate(
        StringBuilder sql,
        ResolvedTargetFilter filter,
        boolean negated,
        String targetIdColumn,
        String targetTextColumn,
        String payloadColumn
    ) {
        if (filter == null || (!filter.requested() && filter.targetIds().isEmpty() && filter.textClauses().isEmpty())) {
            return;
        }

        if (negated) {
            appendAnyLongPredicate(sql, targetIdColumn, filter.targetIds(), true);
            if (!filter.textClauses().isEmpty()) {
                sql.append(" AND (");
                for (int index = 0; index < filter.textClauses().size(); index++) {
                    if (index > 0) {
                        sql.append(" AND ");
                    }
                    sql.append("NOT ");
                    appendTargetTextMatchClause(sql, filter.textClauses().get(index), targetTextColumn, payloadColumn);
                }
                sql.append(")");
            }
            return;
        }

        if (filter.targetIds().isEmpty() && filter.textClauses().isEmpty()) {
            return;
        }

        sql.append(" AND (");
        boolean appended = false;
        if (!filter.targetIds().isEmpty()) {
            sql.append("(");
            for (int index = 0; index < filter.targetIds().size(); index++) {
                if (index > 0) {
                    sql.append(" OR ");
                }
                sql.append(targetIdColumn).append(" = ?");
            }
            sql.append(")");
            appended = true;
        }
        for (TargetSearchClause clause : filter.textClauses()) {
            if (appended) {
                sql.append(" OR ");
            }
            appendTargetTextMatchClause(sql, clause, targetTextColumn, payloadColumn);
            appended = true;
        }
        sql.append(")");
    }

    private int bindLongValues(PreparedStatement statement, int index, List<Long> values) throws SQLException {
        if (values == null || values.isEmpty()) {
            return index;
        }

        for (Long value : values) {
            statement.setLong(index++, value);
        }
        return index;
    }

    private int bindResolvedTargetValues(PreparedStatement statement, int index, ResolvedTargetFilter filter) throws SQLException {
        if (filter == null) {
            return index;
        }

        index = bindLongValues(statement, index, filter.targetIds());
        return bindTargetTextValues(statement, index, filter.textClauses());
    }

    private int bindTargetTextValues(PreparedStatement statement, int index, List<TargetSearchClause> clauses) throws SQLException {
        if (clauses == null || clauses.isEmpty()) {
            return index;
        }

        for (TargetSearchClause clause : clauses) {
            if (clause.simpleToken()) {
                statement.setString(index++, "%" + clause.likeValue() + "%");
            }
            else {
                statement.setString(index++, "%" + clause.likeValue() + "%");
                statement.setString(index++, "%" + clause.likeValue() + "%");
            }
        }
        return index;
    }

    private void appendTargetTextMatchClause(StringBuilder sql, TargetSearchClause clause, String targetTextColumn, String payloadColumn) {
        sql.append("(");
        if (clause.simpleToken()) {
            sql.append("(").append(targetTextColumn).append(" IS NOT NULL AND ").append(caseInsensitiveLike(targetTextColumn)).append(")");
        }
        else {
            sql.append("(").append(targetTextColumn).append(" IS NOT NULL AND ").append(caseInsensitiveLike(targetTextColumn)).append(")");
            sql.append(" OR (").append(payloadColumn).append(" IS NOT NULL AND ").append(caseInsensitiveLike(payloadColumn)).append(")");
        }
        sql.append(")");
    }

    private List<TargetSearchClause> buildTargetSearchClauses(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }

        List<TargetSearchClause> clauses = new ArrayList<>();
        for (String rawValue : values) {
            String normalized = normalizeTargetToken(rawValue);
            if (normalized.isBlank()) {
                continue;
            }

            if (isSimpleTargetToken(normalized)) {
                List<String> keyCandidates = new ArrayList<>();
                List<String> pathCandidates = new ArrayList<>();

                if (normalized.startsWith("#")) {
                    addUnique(keyCandidates, normalized);
                    if (normalized.length() > 1) {
                        addUnique(pathCandidates, normalized.substring(1));
                    }
                }
                else if (normalized.contains(":")) {
                    addUnique(keyCandidates, normalized);
                    addUnique(pathCandidates, extractTargetPath(normalized));
                }
                else {
                    addUnique(keyCandidates, normalized);
                    addUnique(keyCandidates, "minecraft:" + normalized);
                    addUnique(pathCandidates, normalized);
                }
                clauses.add(new TargetSearchClause(keyCandidates, pathCandidates, normalized, true));
            }
            else {
                clauses.add(new TargetSearchClause(List.of(), List.of(), normalized, false));
            }
        }

        return clauses;
    }

    private TargetIndex buildTargetIndex(String target) {
        String normalized = normalizeTargetToken(target);
        if (normalized.isBlank()) {
            return new TargetIndex(null, null);
        }

        String key = extractPrimaryTargetToken(normalized);
        if (key == null || key.isBlank()) {
            return new TargetIndex(null, null);
        }

        String path = extractTargetPath(key);
        return new TargetIndex(limitIndexValue(key), limitIndexValue(path));
    }

    private String normalizeTargetToken(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private String extractPrimaryTargetToken(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        int boundary = value.length();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isWhitespace(character)
                || character == ','
                || character == ';'
                || character == '|'
                || character == '['
                || character == '{'
                || character == '('
                || character == '\n'
                || character == '\r') {
                boundary = index;
                break;
            }
        }
        if (boundary <= 0) {
            return null;
        }
        return value.substring(0, boundary);
    }

    private String extractTargetPath(String key) {
        if (key == null || key.isBlank()) {
            return null;
        }
        if (key.startsWith("#")) {
            return key.length() > 1 ? key.substring(1) : null;
        }

        int separator = key.indexOf(':');
        if (separator >= 0 && separator + 1 < key.length()) {
            return key.substring(separator + 1);
        }
        return key;
    }

    private String limitIndexValue(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 255) {
            return value;
        }
        return value.substring(0, 255);
    }

    private boolean isSimpleTargetToken(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!Character.isLetterOrDigit(character)
                && character != '_'
                && character != ':'
                && character != '#'
                && character != '.'
                && character != '-'
                && character != '/') {
                return false;
            }
        }
        return true;
    }

    private void addUnique(List<String> values, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return;
        }
        if (!values.contains(candidate)) {
            values.add(candidate);
        }
    }

    private void addUnique(List<Long> values, Long candidate) {
        if (candidate == null || candidate <= 0L) {
            return;
        }
        if (!values.contains(candidate)) {
            values.add(candidate);
        }
    }

    private String eventSelectSql() {
        return "SELECT e.id AS id, e.ts AS ts, e.event_type AS event_type, actor_map.actor_uuid AS actor_uuid, "
            + "actor_map.actor_name AS actor_name, "
            + "world_map.world_key AS world_key, "
            + "e.x AS x, e.y AS y, e.z AS z, "
            + "target_map.target AS target, "
            + "e.entity_key AS entity_key, e.payload AS payload, e.rolled_back AS rolled_back "
            + "FROM cp_events e "
            + "LEFT JOIN cp_actor actor_map ON actor_map.id = e.actor_id "
            + "LEFT JOIN cp_world world_map ON world_map.id = e.world_id "
            + "LEFT JOIN cp_target target_map ON target_map.id = e.target_id ";
    }

    private String actorLookupKey(String actorUuid, String actorName) {
        return sha256Hex("actor\u0000" + (actorUuid == null ? "" : actorUuid) + "\u0000" + (actorName == null ? "" : actorName));
    }

    private long targetHash(String target) {
        CRC32 crc32 = new CRC32();
        crc32.update(target.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return crc32.getValue();
    }

    private String targetLookupKey(String target) {
        return sha256Hex("target\u0000" + target);
    }

    private String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder output = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                output.append(String.format("%02x", current));
            }
            return output.toString();
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private record TargetIndex(String key, String path) {
    }

    private record TargetSearchClause(List<String> keyCandidates, List<String> pathCandidates, String likeValue, boolean simpleToken) {
    }

    private record ResolvedTargetFilter(boolean requested, List<Long> targetIds, List<TargetSearchClause> textClauses) {
    }

    private record PreparedEventPayload(String payload, Long entityKey) {
    }

    private record MappingContext(Map<String, Long> worldIds, Map<String, Long> actorIds, Map<String, Long> targetIds) {
    }

    private record LookupKeyRow(String lookupKey, long id) {
    }

    @FunctionalInterface
    private interface StatementBinder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface LookupKeyResolver {
        LookupKeyRow resolve(ResultSet resultSet) throws SQLException;
    }

    private BlockPos minimumBound(BlockPos center, Integer radius) {
        if (center == null || radius == null) {
            return null;
        }
        return new BlockPos(center.getX() - radius, center.getY() - radius, center.getZ() - radius);
    }

    private BlockPos maximumBound(BlockPos center, Integer radius) {
        if (center == null || radius == null) {
            return null;
        }
        return new BlockPos(center.getX() + radius, center.getY() + radius, center.getZ() + radius);
    }

    private List<StoredEventRecord> readRows(PreparedStatement statement) throws SQLException {
        List<StoredEventRecord> rows = new ArrayList<>();
        Map<Long, String> entityPayloadCache = new HashMap<>();
        Connection connection = statement.getConnection();
        try (ResultSet resultSet = statement.executeQuery()) {
            boolean hasEntityKeyColumn = hasResultSetColumn(resultSet, "entity_key");
            while (resultSet.next()) {
                rows.add(readRow(connection, resultSet, entityPayloadCache, hasEntityKeyColumn));
            }
        }
        return rows;
    }

    private PendingInventoryRollbackRecord readPendingInventoryRollbackRow(ResultSet resultSet) throws SQLException {
        return new PendingInventoryRollbackRecord(
            resultSet.getLong("id"),
            resultSet.getLong("event_id"),
            resultSet.getString("actor_uuid"),
            resultSet.getString("actor_name"),
            resultSet.getString("world_key"),
            resultSet.getInt("restore") != 0,
            resultSet.getInt("add_items") != 0,
            resultSet.getString("target"),
            resultSet.getString("payload"),
            resultSet.getString("status"),
            resultSet.getInt("attempts"),
            resultSet.getString("last_error"),
            resultSet.getLong("created_ts"),
            resultSet.getLong("updated_ts")
        );
    }

    private boolean hasResultSetColumn(ResultSet resultSet, String columnName) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        int columnCount = metadata.getColumnCount();
        for (int index = 1; index <= columnCount; index++) {
            String label = metadata.getColumnLabel(index);
            if (label != null && columnName.equalsIgnoreCase(label)) {
                return true;
            }
            String name = metadata.getColumnName(index);
            if (name != null && columnName.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private StoredEventRecord readRow(
        Connection connection,
        ResultSet resultSet,
        Map<Long, String> entityPayloadCache,
        boolean hasEntityKeyColumn
    ) throws SQLException {
        Integer x = resultSet.getObject("x") == null ? null : resultSet.getInt("x");
        Integer y = resultSet.getObject("y") == null ? null : resultSet.getInt("y");
        Integer z = resultSet.getObject("z") == null ? null : resultSet.getInt("z");
        CoreProtectEventType type = CoreProtectEventType.valueOf(resultSet.getString("event_type"));
        String payload = resultSet.getString("payload");
        if (type == CoreProtectEventType.ENTITY_KILL) {
            Long entityKey = null;
            if (hasEntityKeyColumn && resultSet.getObject("entity_key") != null) {
                entityKey = resultSet.getLong("entity_key");
            }
            payload = resolveEntityKillPayload(connection, payload, entityKey, entityPayloadCache);
        }

        return new StoredEventRecord(
            resultSet.getLong("id"),
            resultSet.getLong("ts"),
            type,
            resultSet.getString("actor_uuid"),
            resultSet.getString("actor_name"),
            resultSet.getString("world_key"),
            x,
            y,
            z,
            resultSet.getString("target"),
            payload,
            resultSet.getInt("rolled_back") != 0
        );
    }

    private String resolveEntityKillPayload(
        Connection connection,
        String payload,
        Long entityKeyFromColumn,
        Map<Long, String> entityPayloadCache
    ) throws SQLException {
        long entityKey = entityKeyFromColumn != null && entityKeyFromColumn > 0L
            ? entityKeyFromColumn
            : parseEntityKey(payload);
        if (entityKey <= 0L) {
            return payload;
        }

        String inlinePayload = payloadAfterEntityKey(payload);

        String cached = entityPayloadCache.get(entityKey);
        if (cached != null) {
            return cached;
        }

        String sql = "SELECT data FROM cp_entity WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, entityKey);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    String resolved = resultSet.getString("data");
                    if (resolved != null) {
                        entityPayloadCache.put(entityKey, resolved);
                        return resolved;
                    }
                }
            }
        }

        return inlinePayload.isBlank() ? payload : inlinePayload;
    }

    private long parseEntityKey(String payload) {
        if (payload == null || payload.isBlank()) {
            return -1L;
        }
        String firstLine = payload;
        int newline = payload.indexOf('\n');
        if (newline >= 0) {
            firstLine = payload.substring(0, newline);
        }
        if (!firstLine.startsWith("entity_key=")) {
            return -1L;
        }
        String raw = firstLine.substring("entity_key=".length()).trim();
        if (raw.isEmpty()) {
            return -1L;
        }
        try {
            return Long.parseLong(raw);
        }
        catch (NumberFormatException exception) {
            return -1L;
        }
    }

    private String payloadAfterEntityKey(String payload) {
        if (payload == null || payload.isBlank()) {
            return "";
        }
        int newline = payload.indexOf('\n');
        if (newline < 0 || newline + 1 >= payload.length()) {
            return "";
        }
        return payload.substring(newline + 1);
    }

    private String truncatePendingError(String error) {
        if (error == null) {
            return null;
        }
        String normalized = error.trim();
        if (normalized.length() <= MAX_PENDING_ERROR_LENGTH) {
            return normalized;
        }
        return normalized.substring(0, MAX_PENDING_ERROR_LENGTH);
    }

    private Connection openConnection() throws SQLException {
        if (databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE) {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("PRAGMA foreign_keys = ON");
                    statement.execute("PRAGMA busy_timeout = 5000");
                    statement.execute("PRAGMA journal_mode = WAL");
                    statement.execute("PRAGMA synchronous = NORMAL");
                }
                return connection;
            }
            catch (SQLException | RuntimeException exception) {
                try {
                    connection.close();
                }
                catch (SQLException closeException) {
                    exception.addSuppressed(closeException);
                }
                throw exception;
            }
        }

        String url = "jdbc:mysql://" + config.mysqlHost() + ":" + config.mysqlPort() + "/" + config.mysqlDatabase()
            + "?useSSL=" + config.mysqlUseSsl()
            + "&allowPublicKeyRetrieval=true"
            + "&characterEncoding=utf8"
            + "&connectionTimeZone=UTC";
        try {
            return DriverManager.getConnection(url, config.mysqlUsername(), config.mysqlPassword());
        }
        catch (SQLException exception) {
            if (!isUnknownMySqlDatabase(exception)) {
                throw exception;
            }

            ensureMySqlDatabaseExists();
            return DriverManager.getConnection(url, config.mysqlUsername(), config.mysqlPassword());
        }
    }

    private void ensureMySqlDatabaseExists() throws SQLException {
        if (databaseType != CoreProtectFabricConfig.DatabaseType.MYSQL) {
            return;
        }

        String bootstrapUrl = "jdbc:mysql://" + config.mysqlHost() + ":" + config.mysqlPort()
            + "?useSSL=" + config.mysqlUseSsl()
            + "&allowPublicKeyRetrieval=true"
            + "&characterEncoding=utf8"
            + "&connectionTimeZone=UTC";
        String escapedDatabase = config.mysqlDatabase().replace("`", "``");
        try (Connection connection = DriverManager.getConnection(bootstrapUrl, config.mysqlUsername(), config.mysqlPassword());
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("CREATE DATABASE IF NOT EXISTS `" + escapedDatabase + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }
    }

    private boolean isUnknownMySqlDatabase(SQLException exception) {
        return exception.getErrorCode() == 1049
            || (exception.getMessage() != null && exception.getMessage().toLowerCase().contains("unknown database"));
    }

    public void awaitWriterQuiescence() {
        long deadline = System.currentTimeMillis() + QUIESCENCE_TIMEOUT_MS;
        while (pendingWrites.get() > 0) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }
            synchronized (quiescenceMonitor) {
                if (pendingWrites.get() <= 0) {
                    return;
                }
                try {
                    quiescenceMonitor.wait(Math.min(remaining, 100L));
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private void awaitWriterDrain(long timeoutMs) {
        long deadline = System.currentTimeMillis() + Math.max(0L, timeoutMs);
        while (pendingWrites.get() > 0) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0) {
                return;
            }
            synchronized (quiescenceMonitor) {
                if (pendingWrites.get() <= 0) {
                    return;
                }
                try {
                    quiescenceMonitor.wait(Math.min(remaining, 200L));
                }
                catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private LoggedSignState parseSignPayload(String payload, boolean front) {
        LoggedSignState signState = LoggedSignState.parse(payload);
        if (signState == null || signState.front() != front) {
            return null;
        }
        return signState;
    }

    @Override
    public void close() {
        setWritesPaused(false);
        scheduleWriteFlush();
        awaitWriterDrain(CLOSE_DRAIN_TIMEOUT_MS);
        writer.shutdown();
        try {
            if (!writer.awaitTermination(10, TimeUnit.SECONDS)) {
                writer.shutdownNow();
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            writer.shutdownNow();
        }

        int outstanding = pendingWrites.get();
        if (outstanding > 0) {
            logger.warn("Closing CoreProtect database with {} pending write(s) still not flushed", outstanding);
        }

        closeWriteConnection();
    }

    private void closeWriteConnection() {
        if (writeConnection == null) {
            return;
        }
        try {
            writeConnection.close();
        }
        catch (SQLException exception) {
            logger.warn("Failed to close CoreProtect database cleanly", exception);
        }
        finally {
            writeConnection = null;
        }
    }
}
