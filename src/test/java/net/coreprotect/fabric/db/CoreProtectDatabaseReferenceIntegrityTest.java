package net.coreprotect.fabric.db;

import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class CoreProtectDatabaseReferenceIntegrityTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void skipsBulkRepairWhenAllReferencesAreEnforced() throws Exception {
        try (Connection connection = openDatabase("healthy.db")) {
            createSchema(connection, true);
            insertMappingsAndEvent(connection, 1L);
            execute(connection, "PRAGMA query_only = ON");

            assertDoesNotThrow(() -> database().repairReferenceIntegrity(connection));
        }
    }

    @Test
    void repairsOnlyReferencesWhoseConstraintIsMissing() throws Exception {
        try (Connection connection = openDatabase("legacy.db")) {
            createSchema(connection, false);
            insertMappingsAndEvent(connection, 99L);
            execute(connection, "PRAGMA foreign_keys = OFF");
            execute(connection, "DELETE FROM cp_actor WHERE id = 1");
            execute(connection, "PRAGMA foreign_keys = ON");

            database().repairReferenceIntegrity(connection);

            try (Statement statement = connection.createStatement();
                 ResultSet resultSet = statement.executeQuery("SELECT actor_id, target_id FROM cp_events WHERE id = 1")) {
                assertTrue(resultSet.next());
                assertEquals(1L, resultSet.getLong("actor_id"));
                assertNull(resultSet.getObject("target_id"));
            }
        }
    }

    @Test
    void createsIndexesUsedByReferenceIntegrityMigration() throws Exception {
        try (Connection connection = openDatabase("indexes.db")) {
            createSchema(connection, false);

            database().ensureReferenceIndexes(connection);

            assertTrue(hasIndex(connection, "cp_events_actor_id_idx"));
            assertTrue(hasIndex(connection, "cp_events_world_id_idx"));
            assertTrue(hasIndex(connection, "cp_events_target_id_idx"));
            assertTrue(hasIndex(connection, "cp_events_entity_key_idx"));
            assertTrue(hasIndex(connection, "cp_pending_inventory_rollbacks_actor_id_status_idx"));
            assertTrue(hasIndex(connection, "cp_pending_inventory_rollbacks_world_id_status_idx"));
            assertTrue(hasIndex(connection, "cp_pending_inventory_rollbacks_target_id_status_idx"));
        }
    }

    @Test
    void closesConnectionWhenSchemaInitializationFails() throws Exception {
        CoreProtectFabricConfig config = CoreProtectFabricConfig.loadDefaults()
            .withDatabaseType(CoreProtectFabricConfig.DatabaseType.SQLITE);
        Path databasePath = temporaryDirectory.resolve(config.databaseFile());
        Files.writeString(databasePath, "not a sqlite database");
        CoreProtectDatabase database = new CoreProtectDatabase(config, temporaryDirectory, LoggerFactory.getLogger(getClass()));

        assertThrows(IllegalStateException.class, database::start);

        assertFalse(database.hasOpenWriteConnection());
        assertDoesNotThrow(() -> Files.delete(databasePath));
    }

    private CoreProtectDatabase database() {
        CoreProtectFabricConfig config = CoreProtectFabricConfig.loadDefaults()
            .withDatabaseType(CoreProtectFabricConfig.DatabaseType.SQLITE);
        return new CoreProtectDatabase(config, temporaryDirectory, LoggerFactory.getLogger(getClass()));
    }

    private Connection openDatabase(String fileName) throws Exception {
        Class.forName("org.sqlite.JDBC");
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + temporaryDirectory.resolve(fileName));
        execute(connection, "PRAGMA foreign_keys = ON");
        return connection;
    }

    private void createSchema(Connection connection, boolean includeTargetForeignKey) throws SQLException {
        execute(connection, "CREATE TABLE cp_actor (id INTEGER PRIMARY KEY)");
        execute(connection, "CREATE TABLE cp_world (id INTEGER PRIMARY KEY)");
        execute(connection, "CREATE TABLE cp_target (id INTEGER PRIMARY KEY)");
        execute(connection, "CREATE TABLE cp_entity (id INTEGER PRIMARY KEY)");

        String targetForeignKey = includeTargetForeignKey
            ? ", FOREIGN KEY (target_id) REFERENCES cp_target (id)"
            : "";
        execute(connection,
            "CREATE TABLE cp_events ("
                + "id INTEGER PRIMARY KEY, actor_id INTEGER, world_id INTEGER, target_id INTEGER, entity_key INTEGER, "
                + "FOREIGN KEY (actor_id) REFERENCES cp_actor (id), "
                + "FOREIGN KEY (world_id) REFERENCES cp_world (id), "
                + "FOREIGN KEY (entity_key) REFERENCES cp_entity (id)"
                + targetForeignKey
                + ")");
        execute(connection,
            "CREATE TABLE cp_pending_inventory_rollbacks ("
                + "id INTEGER PRIMARY KEY, event_id INTEGER NOT NULL, actor_id INTEGER, world_id INTEGER, "
                + "target_id INTEGER, status TEXT NOT NULL, "
                + "FOREIGN KEY (event_id) REFERENCES cp_events (id) ON DELETE CASCADE, "
                + "FOREIGN KEY (actor_id) REFERENCES cp_actor (id), "
                + "FOREIGN KEY (world_id) REFERENCES cp_world (id), "
                + "FOREIGN KEY (target_id) REFERENCES cp_target (id)"
                + ")");
    }

    private void insertMappingsAndEvent(Connection connection, long targetId) throws SQLException {
        execute(connection, "INSERT INTO cp_actor (id) VALUES (1)");
        execute(connection, "INSERT INTO cp_world (id) VALUES (1)");
        execute(connection, "INSERT INTO cp_target (id) VALUES (1)");
        execute(connection, "INSERT INTO cp_entity (id) VALUES (1)");
        execute(connection,
            "INSERT INTO cp_events (id, actor_id, world_id, target_id, entity_key) VALUES (1, 1, 1, "
                + targetId
                + ", 1)");
    }

    private boolean hasIndex(Connection connection, String indexName) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type = 'index' AND name = ?"
        )) {
            statement.setString(1, indexName);
            try (ResultSet indexes = statement.executeQuery()) {
                return indexes.next();
            }
        }
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }
}
