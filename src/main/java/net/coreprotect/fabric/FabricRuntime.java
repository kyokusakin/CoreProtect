package net.coreprotect.fabric;

import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.coreprotect.fabric.db.CoreProtectDatabase;
import net.coreprotect.fabric.log.FabricEventLogger;
import net.coreprotect.fabric.service.ContainerSessionService;
import net.coreprotect.fabric.service.InspectorService;
import net.coreprotect.fabric.service.LookupService;
import net.coreprotect.fabric.service.LookupSessionService;
import net.coreprotect.fabric.service.PreviewService;
import net.coreprotect.fabric.service.AutoPurgeService;
import net.coreprotect.fabric.service.RollbackService;
import net.coreprotect.fabric.service.UndoSessionService;
import net.coreprotect.fabric.service.BlacklistService;
import net.coreprotect.fabric.service.UpdateCheckService;
import net.coreprotect.fabric.service.WorldConfigService;
import net.coreprotect.fabric.util.QueryBounds;
import net.coreprotect.fabric.util.TransientLookupCache;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class FabricRuntime {
    private static final String WORLDEDIT_INTEGRATION_CLASS = "net.coreprotect.fabric.integration.worldedit.WorldEditIntegration";
    private static final String WORLDEDIT_SELECTION_BRIDGE_CLASS = "net.coreprotect.fabric.integration.worldedit.WorldEditSelectionBridge";
    private static final String CONFIG_FILE_NAME = "coreprotect-fabric.properties";
    private static final String BLACKLIST_FILE_NAME = "blacklist.txt";

    private final Logger logger;
    private final Path rootDirectory;
    private final Path configPath;
    private final Path blacklistPath;
    private final RuntimeGeneration generation = new RuntimeGeneration();
    private Path databaseRootDirectory;
    private CoreProtectFabricConfig config;
    private WorldConfigService worldConfigs;
    private BlacklistService blacklist;
    private CoreProtectDatabase database;
    private FabricEventLogger eventLogger;
    private LookupService lookupService;
    private LookupSessionService lookupSessionService;
    private PreviewService previewService;
    private UndoSessionService undoSessionService;
    private ContainerSessionService containerSessionService;
    private InspectorService inspectorService;
    private RollbackService rollbackService;
    private UpdateCheckService updateCheckService;
    private AutoPurgeService autoPurgeService;
    private AutoCloseable worldEditIntegration;

    public FabricRuntime(Logger logger) {
        this.logger = logger;
        this.rootDirectory = FabricLoader.getInstance().getConfigDir().resolve("coreprotect-fabric");
        this.configPath = rootDirectory.resolve(CONFIG_FILE_NAME);
        this.blacklistPath = rootDirectory.resolve(BLACKLIST_FILE_NAME);
    }

    public synchronized void initialize(MinecraftServer server) {
        if (database != null) {
            return;
        }

        try {
            Files.createDirectories(rootDirectory);
            TransientLookupCache.clear();
            worldConfigs = WorldConfigService.load(rootDirectory, configPath);
            config = worldConfigs.globalConfig();
            blacklist = BlacklistService.load(blacklistPath);
            databaseRootDirectory = resolveDatabaseRootDirectory(server, config);
            Files.createDirectories(databaseRootDirectory);
            database = new CoreProtectDatabase(config, databaseRootDirectory, logger);
            database.start();
            containerSessionService = new ContainerSessionService();
            eventLogger = new FabricEventLogger(database, worldConfigs, blacklist, logger);
            lookupService = new LookupService(database);
            lookupSessionService = new LookupSessionService();
            previewService = new PreviewService();
            undoSessionService = new UndoSessionService();
            inspectorService = new InspectorService(lookupService);
            rollbackService = new RollbackService(database, worldConfigs, logger);
            updateCheckService = new UpdateCheckService(logger);
            updateCheckService.refreshAsync(config.checkUpdates());
            autoPurgeService = new AutoPurgeService(this, logger);
            worldEditIntegration = registerOptionalWorldEditIntegration();
            generation.advance();
            logger.info("CoreProtect initialized at {} (database root: {})", rootDirectory.toAbsolutePath(), databaseRootDirectory.toAbsolutePath());
        }
        catch (IOException | RuntimeException exception) {
            shutdown();
            throw new IllegalStateException("Unable to initialize CoreProtect runtime", exception);
        }
    }

    public synchronized void shutdown() {
        if (database == null) {
            return;
        }

        generation.advance();

        if (eventLogger != null) {
            eventLogger.flushInteractionAggregates();
        }
        closeOptionalIntegration(worldEditIntegration, "WorldEdit");
        worldEditIntegration = null;
        if (autoPurgeService != null) {
            autoPurgeService.shutdown();
            autoPurgeService = null;
        }
        if (updateCheckService != null) {
            updateCheckService.close();
            updateCheckService = null;
        }
        TransientLookupCache.clear();
        database.close();
        database = null;
        databaseRootDirectory = null;
        eventLogger = null;
        lookupService = null;
        lookupSessionService = null;
        previewService = null;
        undoSessionService = null;
        containerSessionService = null;
        inspectorService = null;
        rollbackService = null;
        worldConfigs = null;
        blacklist = null;
    }

    public synchronized void reload(MinecraftServer server) {
        if (database == null) {
            initialize(server);
            return;
        }

        CoreProtectDatabase previousDatabase = database;
        AutoCloseable previousWorldEditIntegration = worldEditIntegration;
        UpdateCheckService previousUpdateCheckService = updateCheckService;

        if (rollbackService != null && rollbackService.hasPendingWork()) {
            throw new IllegalStateException("Cannot reload CoreProtect while rollback work is pending");
        }
        if (previousDatabase.schemaMaintenancePending()) {
            throw new IllegalStateException("Cannot reload CoreProtect while database schema maintenance is active");
        }
        if (previousDatabase.writesPaused()) {
            throw new IllegalStateException("Cannot reload CoreProtect while database maintenance is active");
        }

        try {
            if (eventLogger != null) {
                eventLogger.flushInteractionAggregates();
            }
            TransientLookupCache.clear();
            WorldConfigService newWorldConfigs = WorldConfigService.load(rootDirectory, configPath);
            CoreProtectFabricConfig newConfig = newWorldConfigs.globalConfig();
            BlacklistService newBlacklist = BlacklistService.load(blacklistPath);
            Path newDatabaseRootDirectory = resolveDatabaseRootDirectory(server, newConfig);
            Files.createDirectories(newDatabaseRootDirectory);
            CoreProtectDatabase newDatabase = new CoreProtectDatabase(newConfig, newDatabaseRootDirectory, logger);
            newDatabase.start();

            closeOptionalIntegration(previousWorldEditIntegration, "WorldEdit");
            worldEditIntegration = null;

            config = newConfig;
            databaseRootDirectory = newDatabaseRootDirectory;
            worldConfigs = newWorldConfigs;
            blacklist = newBlacklist;
            database = newDatabase;
            containerSessionService = new ContainerSessionService();
            eventLogger = new FabricEventLogger(newDatabase, newWorldConfigs, newBlacklist, logger);
            lookupService = new LookupService(newDatabase);
            lookupSessionService = new LookupSessionService();
            previewService = new PreviewService();
            undoSessionService = new UndoSessionService();
            inspectorService = new InspectorService(lookupService);
            rollbackService = new RollbackService(newDatabase, newWorldConfigs, logger);
            updateCheckService = new UpdateCheckService(logger);
            updateCheckService.refreshAsync(newConfig.checkUpdates());
            worldEditIntegration = registerOptionalWorldEditIntegration();

            previousDatabase.close();
            if (previousUpdateCheckService != null) {
                previousUpdateCheckService.close();
            }
            generation.advance();
            logger.info("CoreProtect reloaded from {} (database root: {})", rootDirectory.toAbsolutePath(), databaseRootDirectory.toAbsolutePath());
        }
        catch (IOException | RuntimeException exception) {
            logger.error("CoreProtect failed to reload cleanly", exception);
            throw new IllegalStateException("Unable to reload CoreProtect runtime", exception);
        }
    }

    public Path rootDirectory() {
        return rootDirectory;
    }

    public Path databaseRootDirectory() {
        return databaseRootDirectory == null ? rootDirectory : databaseRootDirectory;
    }

    public Path configPath() {
        return configPath;
    }

    public CoreProtectFabricConfig config() {
        return config;
    }

    public long captureGeneration() {
        return generation.capture();
    }

    public boolean isCurrentGeneration(long capturedGeneration) {
        return generation.isCurrent(capturedGeneration);
    }

    public CoreProtectFabricConfig config(ServerWorld world) {
        if (world == null) {
            return config;
        }
        return config(world.getRegistryKey().getValue().toString());
    }

    public CoreProtectFabricConfig config(String worldKey) {
        if (worldConfigs == null) {
            return config;
        }
        return worldConfigs.resolve(worldKey);
    }

    public Path blacklistPath() {
        return blacklistPath;
    }

    public CoreProtectDatabase database() {
        return database;
    }

    public FabricEventLogger logger() {
        return eventLogger;
    }

    public LookupService lookup() {
        return lookupService;
    }

    public LookupSessionService lookupSessions() {
        return lookupSessionService;
    }

    public PreviewService previews() {
        return previewService;
    }

    public UndoSessionService undoSessions() {
        return undoSessionService;
    }

    public ContainerSessionService containers() {
        return containerSessionService;
    }

    public InspectorService inspector() {
        return inspectorService;
    }

    public RollbackService rollback() {
        return rollbackService;
    }

    public UpdateCheckService updates() {
        return updateCheckService;
    }

    public AutoPurgeService autoPurge() {
        return autoPurgeService;
    }

    public synchronized int swapDatabase(CoreProtectFabricConfig newConfig, CoreProtectDatabase newDatabase) {
        if (database == null || eventLogger == null) {
            throw new IllegalStateException("CoreProtect is not initialized.");
        }
        if (rollbackService != null && rollbackService.hasPendingWork()) {
            throw new IllegalStateException("Cannot migrate the CoreProtect database while rollback work is pending");
        }

        LookupService newLookupService = new LookupService(newDatabase);
        RollbackService newRollbackService = new RollbackService(newDatabase, worldConfigs, logger);
        InspectorService newInspectorService = new InspectorService(newLookupService);
        CoreProtectDatabase previousDatabase = database;
        int bufferedWrites = eventLogger.completeCutover(newDatabase, worldConfigs, blacklist);
        config = newConfig;
        database = newDatabase;
        lookupService = newLookupService;
        rollbackService = newRollbackService;
        inspectorService = newInspectorService;
        previousDatabase.close();
        generation.advance();
        logger.info("CoreProtect switched database backend to {}", newDatabase.databaseDescription());
        return bufferedWrites;
    }

    public boolean hasWorldEditIntegration() {
        return worldEditIntegration != null;
    }

    public QueryBounds resolveWorldEditSelection(ServerPlayerEntity player) {
        if (!FabricLoader.getInstance().isModLoaded("worldedit")) {
            throw new IllegalStateException("WorldEdit is not installed.");
        }

        try {
            Class<?> bridgeClass = Class.forName(WORLDEDIT_SELECTION_BRIDGE_CLASS);
            Object result = bridgeClass.getMethod("getSelection", ServerPlayerEntity.class).invoke(null, player);
            if (result instanceof QueryBounds bounds) {
                return bounds;
            }
            throw new IllegalStateException("WorldEdit selection lookup returned an unexpected result.");
        }
        catch (ReflectiveOperationException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("Unable to read the WorldEdit selection.", exception);
        }
    }

    private AutoCloseable registerOptionalWorldEditIntegration() {
        if (!FabricLoader.getInstance().isModLoaded("worldedit")) {
            return null;
        }

        try {
            Class<?> integrationClass = Class.forName(WORLDEDIT_INTEGRATION_CLASS);
            Object result = integrationClass.getMethod("register", FabricRuntime.class, Logger.class).invoke(null, this, logger);
            if (result instanceof AutoCloseable) {
                logger.info("CoreProtect enabled WorldEdit integration");
                return (AutoCloseable) result;
            }
            logger.warn("CoreProtect WorldEdit integration returned an unexpected type: {}", result);
        }
        catch (ReflectiveOperationException | LinkageError exception) {
            logger.warn("CoreProtect failed to enable WorldEdit integration", exception);
        }
        return null;
    }

    private void closeOptionalIntegration(AutoCloseable integration, String name) {
        if (integration == null) {
            return;
        }

        try {
            integration.close();
        }
        catch (Exception exception) {
            logger.warn("CoreProtect failed to close {} integration cleanly", name, exception);
        }
    }

    private Path resolveDatabaseRootDirectory(MinecraftServer server, CoreProtectFabricConfig resolvedConfig) {
        if (resolvedConfig == null || !resolvedConfig.databaseInWorld()) {
            return rootDirectory;
        }
        return server.getSavePath(WorldSavePath.ROOT).resolve("coreprotect-fabric");
    }
}
