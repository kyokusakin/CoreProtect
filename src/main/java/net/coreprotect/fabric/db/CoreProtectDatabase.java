package net.coreprotect.fabric.db;

import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.minecraft.util.math.BlockPos;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CoreProtectDatabase implements AutoCloseable {
    private static final long QUIESCENCE_TIMEOUT_MS = 5_000L;

    private final CoreProtectFabricConfig config;
    private final Path rootDirectory;
    private final Path databasePath;
    private final CoreProtectFabricConfig.DatabaseType databaseType;
    private final Logger logger;
    private final AtomicInteger pendingWrites = new AtomicInteger();
    private final AtomicBoolean writesPaused = new AtomicBoolean(false);
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
        catch (ClassNotFoundException | SQLException exception) {
            throw new IllegalStateException("Unable to start CoreProtect Fabric database", exception);
        }
    }

    public void write(EventRecord record) {
        pendingWrites.incrementAndGet();
        writer.execute(() -> {
            try {
                insert(record);
            }
            catch (SQLException exception) {
                logger.error("Failed to persist Fabric audit event {}", record.type(), exception);
            }
            finally {
                pendingWrites.decrementAndGet();
            }
        });
    }

    public int pendingWrites() {
        return pendingWrites.get();
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

    public List<StoredEventRecord> fetchEventsAfterId(long afterId, int limit) {
        String sql =
            "SELECT id, ts, event_type, actor_uuid, actor_name, world_key, x, y, z, target, payload, rolled_back "
                + "FROM cp_events "
                + "WHERE id > ? "
                + "ORDER BY id ASC LIMIT ?";

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
            "SELECT actor_name "
                + "FROM cp_events "
                + "WHERE actor_uuid = ? "
                + "AND actor_name IS NOT NULL "
                + "AND actor_name <> '' "
                + "ORDER BY id DESC LIMIT 1";

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
        String sql =
            "SELECT actor_name "
                + "FROM cp_events "
                + "WHERE world_key = ? "
                + "AND x = ? "
                + "AND y = ? "
                + "AND z = ? "
                + "AND event_type = 'BLOCK_PLACE' "
                + "AND actor_name IS NOT NULL "
                + "AND actor_name <> '' "
                + "ORDER BY id DESC LIMIT 1";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, worldKey);
            statement.setInt(2, pos.getX());
            statement.setInt(3, pos.getY());
            statement.setInt(4, pos.getZ());
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getString("actor_name") : null;
            }
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to look up the latest block place actor", exception);
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
                + "actor_uuid, "
                + "actor_name, "
                + "world_key, "
                + "x, "
                + "y, "
                + "z, "
                + "target, "
                + "payload, "
                + "rolled_back"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            connection.setAutoCommit(false);
            for (StoredEventRecord record : records) {
                statement.setLong(1, record.id());
                statement.setLong(2, record.timestamp());
                statement.setString(3, record.type().name());
                statement.setString(4, record.actorUuid());
                statement.setString(5, record.actorName());
                statement.setString(6, record.worldKey());
                bindNullableInt(statement, 7, record.x());
                bindNullableInt(statement, 8, record.y());
                bindNullableInt(statement, 9, record.z());
                statement.setString(10, record.target());
                statement.setString(11, record.payload());
                statement.setInt(12, record.rolledBack() ? 1 : 0);
                statement.addBatch();
            }
            statement.executeBatch();
            connection.commit();
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to import Fabric audit events", exception);
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
        StringBuilder sql = new StringBuilder(
            "SELECT id, ts, event_type, actor_uuid, actor_name, world_key, x, y, z, target, payload, rolled_back "
                + "FROM cp_events "
                + "WHERE world_key = ? "
                + "AND x = ? "
                + "AND y = ? "
                + "AND z = ?"
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
        sql.append(" ORDER BY id DESC LIMIT ?");

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, worldKey);
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

        StringBuilder sql = new StringBuilder(
            "SELECT id, ts, event_type, actor_uuid, actor_name, world_key, x, y, z, target, payload, rolled_back "
                + "FROM cp_events "
                + "WHERE ts >= ? "
                + "AND ts <= ?"
        );
        if (worldKey != null && !worldKey.isBlank()) {
            sql.append(" AND world_key = ?");
        }
        if (minimum != null && maximum != null) {
            sql.append(" AND x BETWEEN ? AND ?");
            sql.append(" AND y BETWEEN ? AND ?");
            sql.append(" AND z BETWEEN ? AND ?");
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
        appendAnyValuePredicate(sql, "actor_name", actorNames, false);
        appendAnyValuePredicate(sql, "actor_name", excludeActorNames, true);
        appendLikePredicate(sql, includeTargets, false, "target", "payload");
        appendLikePredicate(sql, excludeTargets, true, "target", "payload");
        sql.append(" ORDER BY id DESC LIMIT ? OFFSET ?");

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setLong(index++, notBefore);
            statement.setLong(index++, notAfter);
            if (worldKey != null && !worldKey.isBlank()) {
                statement.setString(index++, worldKey);
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
            index = bindValues(statement, index, actorNames);
            index = bindValues(statement, index, excludeActorNames);
            index = bindLikeValues(statement, index, includeTargets, 2);
            index = bindLikeValues(statement, index, excludeTargets, 2);
            statement.setInt(index++, limit);
            statement.setInt(index, Math.max(0, offset));
            return readRows(statement);
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

        StringBuilder sql = new StringBuilder(
            "SELECT COUNT(*) AS total "
                + "FROM cp_events "
                + "WHERE ts >= ? "
                + "AND ts <= ?"
        );
        if (worldKey != null && !worldKey.isBlank()) {
            sql.append(" AND world_key = ?");
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
        appendAnyValuePredicate(sql, "actor_name", actorNames, false);
        appendAnyValuePredicate(sql, "actor_name", excludeActorNames, true);
        appendLikePredicate(sql, includeTargets, false, "target", "payload");
        appendLikePredicate(sql, excludeTargets, true, "target", "payload");

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setLong(index++, notBefore);
            statement.setLong(index++, notAfter);
            if (worldKey != null && !worldKey.isBlank()) {
                statement.setString(index++, worldKey);
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
            index = bindValues(statement, index, actorNames);
            index = bindValues(statement, index, excludeActorNames);
            index = bindLikeValues(statement, index, includeTargets, 2);
            bindLikeValues(statement, index, excludeTargets, 2);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? resultSet.getInt("total") : 0;
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
        List<CoreProtectEventType> rollbackTypes = eventTypes == null || eventTypes.isEmpty()
            ? List.of(CoreProtectEventType.BLOCK_BREAK, CoreProtectEventType.BLOCK_PLACE, CoreProtectEventType.SIGN_CHANGE)
            : eventTypes;

        StringBuilder sql = new StringBuilder(
            "SELECT id, ts, event_type, actor_uuid, actor_name, world_key, x, y, z, target, payload, rolled_back "
                + "FROM cp_events "
                + "WHERE ts >= ? "
                + "AND ts <= ? "
                + "AND rolled_back = ?"
        );
        if (worldKey != null && !worldKey.isBlank()) {
            sql.append(" AND world_key = ?");
        }
        if (minimum != null && maximum != null) {
            sql.append(" AND x BETWEEN ? AND ?");
            sql.append(" AND y BETWEEN ? AND ?");
            sql.append(" AND z BETWEEN ? AND ?");
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
        appendAnyValuePredicate(sql, "actor_name", actorNames, false);
        appendAnyValuePredicate(sql, "actor_name", excludeActorNames, true);
        appendLikePredicate(sql, includeTargets, false, "target", "payload");
        appendLikePredicate(sql, excludeTargets, true, "target", "payload");
        sql.append(ascending ? " ORDER BY id ASC" : " ORDER BY id DESC");

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setLong(index++, notBefore);
            statement.setLong(index++, notAfter);
            statement.setInt(index++, rolledBack ? 1 : 0);
            if (worldKey != null && !worldKey.isBlank()) {
                statement.setString(index++, worldKey);
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
            index = bindValues(statement, index, actorNames);
            index = bindValues(statement, index, excludeActorNames);
            index = bindLikeValues(statement, index, includeTargets, 2);
            bindLikeValues(statement, index, excludeTargets, 2);
            return readRows(statement);
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to run rollback lookup", exception);
        }
    }

    public String[] lookupPreviousSignState(String worldKey, BlockPos pos, long beforeId, boolean front) {
        awaitWriterQuiescence();
        String sql =
            "SELECT event_type, payload "
                + "FROM cp_events "
                + "WHERE world_key = ? "
                + "AND x = ? "
                + "AND y = ? "
                + "AND z = ? "
                + "AND id < ? "
                + "AND event_type IN ('SIGN_CHANGE', 'BLOCK_BREAK', 'BLOCK_PLACE') "
                + "ORDER BY id DESC";

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, worldKey);
            statement.setInt(2, pos.getX());
            statement.setInt(3, pos.getY());
            statement.setInt(4, pos.getZ());
            statement.setLong(5, beforeId);

            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    CoreProtectEventType eventType = CoreProtectEventType.valueOf(resultSet.getString("event_type"));
                    if (eventType == CoreProtectEventType.SIGN_CHANGE) {
                        String[] lines = parseSignPayload(resultSet.getString("payload"), front);
                        if (lines != null) {
                            return lines;
                        }
                        continue;
                    }

                    if (eventType == CoreProtectEventType.BLOCK_BREAK || eventType == CoreProtectEventType.BLOCK_PLACE) {
                        return blankSignLines();
                    }
                }
            }

            return blankSignLines();
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

    public int purgeOlderThan(int seconds, String worldKey, List<String> includeTargets) {
        awaitWriterQuiescence();
        long before = System.currentTimeMillis() - (seconds * 1000L);
        StringBuilder sql = new StringBuilder("DELETE FROM cp_events WHERE ts < ?");
        if (worldKey != null && !worldKey.isBlank()) {
            sql.append(" AND world_key = ?");
        }
        appendLikePredicate(sql, includeTargets, false, "target", "payload");

        try (Connection connection = openConnection();
             PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setLong(index++, before);
            if (worldKey != null && !worldKey.isBlank()) {
                statement.setString(index++, worldKey);
            }
            bindLikeValues(statement, index, includeTargets, 2);
            return statement.executeUpdate();
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to purge old Fabric audit events", exception);
        }
    }

    public boolean optimizeStorage() {
        if (databaseType != CoreProtectFabricConfig.DatabaseType.MYSQL) {
            return false;
        }

        try (Connection connection = openConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("OPTIMIZE TABLE cp_events");
            return true;
        }
        catch (SQLException exception) {
            throw new IllegalStateException("Unable to optimize Fabric audit tables", exception);
        }
    }

    private void initializeSchema(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE) {
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_events ("
                        + "id INTEGER PRIMARY KEY AUTOINCREMENT, "
                        + "ts INTEGER NOT NULL, "
                        + "event_type TEXT NOT NULL, "
                        + "actor_uuid TEXT, "
                        + "actor_name TEXT, "
                        + "world_key TEXT, "
                        + "x INTEGER, "
                        + "y INTEGER, "
                        + "z INTEGER, "
                        + "target TEXT, "
                        + "payload TEXT, "
                        + "rolled_back INTEGER NOT NULL DEFAULT 0"
                        + ")"
                );
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS cp_events_ts_idx ON cp_events (ts)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS cp_events_actor_idx ON cp_events (actor_uuid)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS cp_events_world_xyz_idx ON cp_events (world_key, x, y, z)");
                statement.executeUpdate("CREATE INDEX IF NOT EXISTS cp_events_world_ts_idx ON cp_events (world_key, ts)");
            }
            else {
                statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS cp_events ("
                        + "id BIGINT NOT NULL AUTO_INCREMENT, "
                        + "ts BIGINT NOT NULL, "
                        + "event_type VARCHAR(64) NOT NULL, "
                        + "actor_uuid VARCHAR(64), "
                        + "actor_name VARCHAR(255), "
                        + "world_key VARCHAR(255), "
                        + "x INT, "
                        + "y INT, "
                        + "z INT, "
                        + "target TEXT, "
                        + "payload LONGTEXT, "
                        + "rolled_back TINYINT(1) NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (id), "
                        + "KEY cp_events_ts_idx (ts), "
                        + "KEY cp_events_actor_idx (actor_uuid), "
                        + "KEY cp_events_world_xyz_idx (world_key, x, y, z), "
                        + "KEY cp_events_world_ts_idx (world_key, ts)"
                        + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4"
                );
            }
        }

        ensureColumn(connection, "cp_events", "rolled_back", databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE ? "INTEGER NOT NULL DEFAULT 0" : "TINYINT(1) NOT NULL DEFAULT 0");
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

    private void insert(EventRecord record) throws SQLException {
        awaitWritesResumed();
        try (PreparedStatement statement = writeConnection.prepareStatement(
            "INSERT INTO cp_events ("
                + "ts, "
                + "event_type, "
                + "actor_uuid, "
                + "actor_name, "
                + "world_key, "
                + "x, "
                + "y, "
                + "z, "
                + "target, "
                + "payload, "
                + "rolled_back"
                + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0)"
        )) {
            statement.setLong(1, record.timestamp());
            statement.setString(2, record.type().name());
            statement.setString(3, record.actorUuid());
            statement.setString(4, record.actorName());
            statement.setString(5, record.worldKey());
            bindNullableInt(statement, 6, record.x());
            bindNullableInt(statement, 7, record.y());
            bindNullableInt(statement, 8, record.z());
            statement.setString(9, record.target());
            statement.setString(10, record.payload());
            statement.executeUpdate();
        }
    }

    private void bindNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
            return;
        }
        statement.setInt(index, value);
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

    private void appendAnyValuePredicate(StringBuilder sql, String column, List<String> values, boolean negated) {
        if (values == null || values.isEmpty()) {
            return;
        }

        sql.append(negated ? " AND NOT (" : " AND (");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sql.append(negated ? " AND " : " OR ");
            }
            sql.append("LOWER(COALESCE(").append(column).append(", '')) = LOWER(?)");
        }
        sql.append(")");
    }

    private void appendLikePredicate(StringBuilder sql, List<String> values, boolean negated, String... columns) {
        if (values == null || values.isEmpty() || columns == null || columns.length == 0) {
            return;
        }

        sql.append(" AND (");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                sql.append(negated ? " AND " : " OR ");
            }
            sql.append("(");
            for (int columnIndex = 0; columnIndex < columns.length; columnIndex++) {
                if (columnIndex > 0) {
                    sql.append(negated ? " AND " : " OR ");
                }
                sql.append("LOWER(COALESCE(").append(columns[columnIndex]).append(", '')) ");
                sql.append(negated ? "NOT LIKE LOWER(?)" : "LIKE LOWER(?)");
            }
            sql.append(")");
        }
        sql.append(")");
    }

    private int bindValues(PreparedStatement statement, int index, List<String> values) throws SQLException {
        if (values == null || values.isEmpty()) {
            return index;
        }

        for (String value : values) {
            statement.setString(index++, value);
        }
        return index;
    }

    private int bindLikeValues(PreparedStatement statement, int index, List<String> values, int repetitions) throws SQLException {
        if (values == null || values.isEmpty()) {
            return index;
        }

        for (String value : values) {
            for (int repetition = 0; repetition < repetitions; repetition++) {
                statement.setString(index++, "%" + value + "%");
            }
        }
        return index;
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
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(readRow(resultSet));
            }
        }
        return rows;
    }

    private StoredEventRecord readRow(ResultSet resultSet) throws SQLException {
        Integer x = resultSet.getObject("x") == null ? null : resultSet.getInt("x");
        Integer y = resultSet.getObject("y") == null ? null : resultSet.getInt("y");
        Integer z = resultSet.getObject("z") == null ? null : resultSet.getInt("z");
        return new StoredEventRecord(
            resultSet.getLong("id"),
            resultSet.getLong("ts"),
            CoreProtectEventType.valueOf(resultSet.getString("event_type")),
            resultSet.getString("actor_uuid"),
            resultSet.getString("actor_name"),
            resultSet.getString("world_key"),
            x,
            y,
            z,
            resultSet.getString("target"),
            resultSet.getString("payload"),
            resultSet.getInt("rolled_back") != 0
        );
    }

    private Connection openConnection() throws SQLException {
        if (databaseType == CoreProtectFabricConfig.DatabaseType.SQLITE) {
            Connection connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout = 5000");
                statement.execute("PRAGMA journal_mode = WAL");
                statement.execute("PRAGMA synchronous = NORMAL");
            }
            return connection;
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
        while (pendingWrites.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10L);
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String[] parseSignPayload(String payload, boolean front) {
        if (payload == null || payload.isBlank()) {
            return null;
        }

        String[] rawParts = payload.split("\\n", -1);
        if (rawParts.length == 0) {
            return null;
        }

        boolean payloadFront = "front".equalsIgnoreCase(rawParts[0]);
        boolean payloadBack = "back".equalsIgnoreCase(rawParts[0]);
        if ((!payloadFront && !payloadBack) || payloadFront != front) {
            return null;
        }

        String[] lines = blankSignLines();
        for (int index = 0; index < lines.length && index + 1 < rawParts.length; index++) {
            lines[index] = rawParts[index + 1];
        }
        return lines;
    }

    private String[] blankSignLines() {
        return new String[] { "", "", "", "" };
    }

    @Override
    public void close() {
        setWritesPaused(false);
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

        if (writeConnection != null) {
            try {
                writeConnection.close();
            }
            catch (SQLException exception) {
                logger.warn("Failed to close CoreProtect Fabric database cleanly", exception);
            }
        }
    }
}
