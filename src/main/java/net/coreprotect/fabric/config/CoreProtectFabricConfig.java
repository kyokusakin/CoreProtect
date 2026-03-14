package net.coreprotect.fabric.config;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class CoreProtectFabricConfig {
    public enum DatabaseType {
        SQLITE("sqlite"),
        MYSQL("mysql");

        private final String id;

        DatabaseType(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static DatabaseType from(String value) {
            if (value == null) {
                return SQLITE;
            }
            return "mysql".equalsIgnoreCase(value.trim()) ? MYSQL : SQLITE;
        }
    }

    private final DatabaseType databaseType;
    private final String databaseFile;
    private final String mysqlHost;
    private final int mysqlPort;
    private final String mysqlDatabase;
    private final String mysqlUsername;
    private final String mysqlPassword;
    private final boolean mysqlUseSsl;
    private final boolean hoverEvents;
    private final boolean logCancelledChat;
    private final boolean hopperFilterMeta;
    private final boolean excludeTnt;
    private final boolean checkUpdates;
    private final boolean logBlockBreaks;
    private final boolean logBlockPlaces;
    private final boolean logBlockUses;
    private final boolean playerInteractions;
    private final boolean pistons;
    private final boolean blockBurn;
    private final boolean blockIgnite;
    private final boolean logBuckets;
    private final boolean logEntityChanges;
    private final boolean explosions;
    private final boolean logCommands;
    private final boolean logChat;
    private final boolean logItemDrops;
    private final boolean logItemPickups;
    private final boolean rollbackItems;
    private final boolean rollbackEntities;
    private final boolean itemTransactions;
    private final boolean hopperTransactions;
    private final boolean logEntityKills;
    private final boolean logSessions;
    private final boolean usernameChanges;
    private final boolean leafDecay;
    private final boolean portals;
    private final boolean treeGrowth;
    private final boolean mushroomGrowth;
    private final boolean vineGrowth;
    private final boolean sculkSpread;
    private final boolean waterFlow;
    private final boolean lavaFlow;
    private final boolean liquidTracking;
    private final boolean networkDebug;
    private final int defaultRadius;
    private final int maxRadius;
    private final String donationKey;

    public CoreProtectFabricConfig(
        DatabaseType databaseType,
        String databaseFile,
        String mysqlHost,
        int mysqlPort,
        String mysqlDatabase,
        String mysqlUsername,
        String mysqlPassword,
        boolean mysqlUseSsl,
        boolean hoverEvents,
        boolean logCancelledChat,
        boolean hopperFilterMeta,
        boolean excludeTnt,
        boolean checkUpdates,
        boolean logBlockBreaks,
        boolean logBlockPlaces,
        boolean logBlockUses,
        boolean playerInteractions,
        boolean pistons,
        boolean blockBurn,
        boolean blockIgnite,
        boolean logBuckets,
        boolean logEntityChanges,
        boolean explosions,
        boolean logCommands,
        boolean logChat,
        boolean logItemDrops,
        boolean logItemPickups,
        boolean rollbackItems,
        boolean rollbackEntities,
        boolean itemTransactions,
        boolean hopperTransactions,
        boolean logEntityKills,
        boolean logSessions,
        boolean usernameChanges,
        boolean leafDecay,
        boolean portals,
        boolean treeGrowth,
        boolean mushroomGrowth,
        boolean vineGrowth,
        boolean sculkSpread,
        boolean waterFlow,
        boolean lavaFlow,
        boolean liquidTracking,
        boolean networkDebug,
        int defaultRadius,
        int maxRadius,
        String donationKey
    ) {
        this.databaseType = databaseType;
        this.databaseFile = databaseFile;
        this.mysqlHost = mysqlHost;
        this.mysqlPort = mysqlPort;
        this.mysqlDatabase = mysqlDatabase;
        this.mysqlUsername = mysqlUsername;
        this.mysqlPassword = mysqlPassword;
        this.mysqlUseSsl = mysqlUseSsl;
        this.hoverEvents = hoverEvents;
        this.logCancelledChat = logCancelledChat;
        this.hopperFilterMeta = hopperFilterMeta;
        this.excludeTnt = excludeTnt;
        this.checkUpdates = checkUpdates;
        this.logBlockBreaks = logBlockBreaks;
        this.logBlockPlaces = logBlockPlaces;
        this.logBlockUses = logBlockUses;
        this.playerInteractions = playerInteractions;
        this.pistons = pistons;
        this.blockBurn = blockBurn;
        this.blockIgnite = blockIgnite;
        this.logBuckets = logBuckets;
        this.logEntityChanges = logEntityChanges;
        this.explosions = explosions;
        this.logCommands = logCommands;
        this.logChat = logChat;
        this.logItemDrops = logItemDrops;
        this.logItemPickups = logItemPickups;
        this.rollbackItems = rollbackItems;
        this.rollbackEntities = rollbackEntities;
        this.itemTransactions = itemTransactions;
        this.hopperTransactions = hopperTransactions;
        this.logEntityKills = logEntityKills;
        this.logSessions = logSessions;
        this.usernameChanges = usernameChanges;
        this.leafDecay = leafDecay;
        this.portals = portals;
        this.treeGrowth = treeGrowth;
        this.mushroomGrowth = mushroomGrowth;
        this.vineGrowth = vineGrowth;
        this.sculkSpread = sculkSpread;
        this.waterFlow = waterFlow;
        this.lavaFlow = lavaFlow;
        this.liquidTracking = liquidTracking;
        this.networkDebug = networkDebug;
        this.defaultRadius = defaultRadius;
        this.maxRadius = maxRadius;
        this.donationKey = donationKey;
    }

    public String databaseFile() {
        return databaseFile;
    }

    public DatabaseType databaseType() {
        return databaseType;
    }

    public String mysqlHost() {
        return mysqlHost;
    }

    public int mysqlPort() {
        return mysqlPort;
    }

    public String mysqlDatabase() {
        return mysqlDatabase;
    }

    public String mysqlUsername() {
        return mysqlUsername;
    }

    public String mysqlPassword() {
        return mysqlPassword;
    }

    public boolean mysqlUseSsl() {
        return mysqlUseSsl;
    }

    public boolean hoverEvents() {
        return hoverEvents;
    }

    public boolean logCancelledChat() {
        return logCancelledChat;
    }

    public boolean hopperFilterMeta() {
        return hopperFilterMeta;
    }

    public boolean excludeTnt() {
        return excludeTnt;
    }

    public boolean checkUpdates() {
        return checkUpdates;
    }

    public boolean logBlockBreaks() {
        return logBlockBreaks;
    }

    public boolean logBlockPlaces() {
        return logBlockPlaces;
    }

    public boolean logBlockUses() {
        return logBlockUses;
    }

    public boolean playerInteractions() {
        return playerInteractions;
    }

    public boolean pistons() {
        return pistons;
    }

    public boolean blockBurn() {
        return blockBurn;
    }

    public boolean blockIgnite() {
        return blockIgnite;
    }

    public boolean logBuckets() {
        return logBuckets;
    }

    public boolean logEntityChanges() {
        return logEntityChanges;
    }

    public boolean explosions() {
        return explosions;
    }

    public boolean logCommands() {
        return logCommands;
    }

    public boolean logChat() {
        return logChat;
    }

    public boolean logItemDrops() {
        return logItemDrops;
    }

    public boolean logItemPickups() {
        return logItemPickups;
    }

    public boolean rollbackItems() {
        return rollbackItems;
    }

    public boolean rollbackEntities() {
        return rollbackEntities;
    }

    public boolean itemTransactions() {
        return itemTransactions;
    }

    public boolean hopperTransactions() {
        return hopperTransactions;
    }

    public boolean logEntityKills() {
        return logEntityKills;
    }

    public boolean logSessions() {
        return logSessions;
    }

    public boolean usernameChanges() {
        return usernameChanges;
    }

    public boolean leafDecay() {
        return leafDecay;
    }

    public boolean portals() {
        return portals;
    }

    public boolean treeGrowth() {
        return treeGrowth;
    }

    public boolean mushroomGrowth() {
        return mushroomGrowth;
    }

    public boolean vineGrowth() {
        return vineGrowth;
    }

    public boolean sculkSpread() {
        return sculkSpread;
    }

    public boolean waterFlow() {
        return waterFlow;
    }

    public boolean lavaFlow() {
        return lavaFlow;
    }

    public boolean liquidTracking() {
        return liquidTracking;
    }

    public boolean networkDebug() {
        return networkDebug;
    }

    public int defaultRadius() {
        return defaultRadius;
    }

    public int maxRadius() {
        return maxRadius;
    }

    public String donationKey() {
        return donationKey;
    }

    public static CoreProtectFabricConfig loadDefaults() {
        return fromProperties(defaultProperties());
    }

    public static CoreProtectFabricConfig load(Path path) throws IOException {
        Properties defaults = defaultProperties();
        Properties properties = loadProperties(path, defaults, true);
        return fromProperties(properties);
    }

    public static CoreProtectFabricConfig load(Path path, CoreProtectFabricConfig defaults) throws IOException {
        Properties defaultProperties = defaults == null ? defaultProperties() : defaults.toProperties();
        Properties properties = loadProperties(path, defaultProperties, false);
        return fromProperties(properties);
    }

    public CoreProtectFabricConfig withDatabaseType(DatabaseType type) {
        return new CoreProtectFabricConfig(
            type,
            databaseFile,
            mysqlHost,
            mysqlPort,
            mysqlDatabase,
            mysqlUsername,
            mysqlPassword,
            mysqlUseSsl,
            hoverEvents,
            logCancelledChat,
            hopperFilterMeta,
            excludeTnt,
            checkUpdates,
            logBlockBreaks,
            logBlockPlaces,
            logBlockUses,
            playerInteractions,
            pistons,
            blockBurn,
            blockIgnite,
            logBuckets,
            logEntityChanges,
            explosions,
            logCommands,
            logChat,
            logItemDrops,
            logItemPickups,
            rollbackItems,
            rollbackEntities,
            itemTransactions,
            hopperTransactions,
            logEntityKills,
            logSessions,
            usernameChanges,
            leafDecay,
            portals,
            treeGrowth,
            mushroomGrowth,
            vineGrowth,
            sculkSpread,
            waterFlow,
            lavaFlow,
            liquidTracking,
            networkDebug,
            defaultRadius,
            maxRadius,
            donationKey
        );
    }

    public void save(Path path) throws IOException {
        try (OutputStream outputStream = Files.newOutputStream(path)) {
            toProperties().store(outputStream, "CoreProtect Fabric configuration");
        }
    }

    public Properties toProperties() {
        Properties properties = new Properties();
        properties.setProperty("database.type", databaseType.id());
        properties.setProperty("database.file", databaseFile);
        properties.setProperty("database.mysql.host", mysqlHost);
        properties.setProperty("database.mysql.port", Integer.toString(mysqlPort));
        properties.setProperty("database.mysql.name", mysqlDatabase);
        properties.setProperty("database.mysql.username", mysqlUsername);
        properties.setProperty("database.mysql.password", mysqlPassword);
        properties.setProperty("database.mysql.ssl", Boolean.toString(mysqlUseSsl));
        properties.setProperty("hover-events", Boolean.toString(hoverEvents));
        properties.setProperty("log-cancelled-chat", Boolean.toString(logCancelledChat));
        properties.setProperty("hopper-filter-meta", Boolean.toString(hopperFilterMeta));
        properties.setProperty("exclude-tnt", Boolean.toString(excludeTnt));
        properties.setProperty("check-updates", Boolean.toString(checkUpdates));
        properties.setProperty("log.block-breaks", Boolean.toString(logBlockBreaks));
        properties.setProperty("log.block-places", Boolean.toString(logBlockPlaces));
        properties.setProperty("log.block-uses", Boolean.toString(logBlockUses));
        properties.setProperty("player-interactions", Boolean.toString(playerInteractions));
        properties.setProperty("pistons", Boolean.toString(pistons));
        properties.setProperty("block-burn", Boolean.toString(blockBurn));
        properties.setProperty("block-ignite", Boolean.toString(blockIgnite));
        properties.setProperty("log.buckets", Boolean.toString(logBuckets));
        properties.setProperty("log.entity-changes", Boolean.toString(logEntityChanges));
        properties.setProperty("explosions", Boolean.toString(explosions));
        properties.setProperty("log.commands", Boolean.toString(logCommands));
        properties.setProperty("log.chat", Boolean.toString(logChat));
        properties.setProperty("log.item-drops", Boolean.toString(logItemDrops));
        properties.setProperty("log.item-pickups", Boolean.toString(logItemPickups));
        properties.setProperty("rollback-items", Boolean.toString(rollbackItems));
        properties.setProperty("rollback-entities", Boolean.toString(rollbackEntities));
        properties.setProperty("item-transactions", Boolean.toString(itemTransactions));
        properties.setProperty("hopper-transactions", Boolean.toString(hopperTransactions));
        properties.setProperty("log.entity-kills", Boolean.toString(logEntityKills));
        properties.setProperty("log.sessions", Boolean.toString(logSessions));
        properties.setProperty("username-changes", Boolean.toString(usernameChanges));
        properties.setProperty("leaf-decay", Boolean.toString(leafDecay));
        properties.setProperty("portals", Boolean.toString(portals));
        properties.setProperty("tree-growth", Boolean.toString(treeGrowth));
        properties.setProperty("mushroom-growth", Boolean.toString(mushroomGrowth));
        properties.setProperty("vine-growth", Boolean.toString(vineGrowth));
        properties.setProperty("sculk-spread", Boolean.toString(sculkSpread));
        properties.setProperty("water-flow", Boolean.toString(waterFlow));
        properties.setProperty("lava-flow", Boolean.toString(lavaFlow));
        properties.setProperty("liquid-tracking", Boolean.toString(liquidTracking));
        properties.setProperty("network-debug", Boolean.toString(networkDebug));
        properties.setProperty("default-radius", Integer.toString(defaultRadius));
        properties.setProperty("max-radius", Integer.toString(maxRadius));
        properties.setProperty("donation-key", donationKey == null ? "" : donationKey);
        return properties;
    }

    private static Properties defaultProperties() {
        Properties defaults = new Properties();
        defaults.setProperty("database.type", "sqlite");
        defaults.setProperty("database.file", "coreprotect-fabric.db");
        defaults.setProperty("database.mysql.host", "127.0.0.1");
        defaults.setProperty("database.mysql.port", "3306");
        defaults.setProperty("database.mysql.name", "coreprotect");
        defaults.setProperty("database.mysql.username", "root");
        defaults.setProperty("database.mysql.password", "");
        defaults.setProperty("database.mysql.ssl", "false");
        defaults.setProperty("hover-events", "true");
        defaults.setProperty("log-cancelled-chat", "true");
        defaults.setProperty("hopper-filter-meta", "false");
        defaults.setProperty("exclude-tnt", "false");
        defaults.setProperty("check-updates", "true");
        defaults.setProperty("log.block-breaks", "true");
        defaults.setProperty("log.block-places", "true");
        defaults.setProperty("log.block-uses", "true");
        defaults.setProperty("player-interactions", "true");
        defaults.setProperty("pistons", "true");
        defaults.setProperty("block-burn", "true");
        defaults.setProperty("block-ignite", "true");
        defaults.setProperty("log.buckets", "true");
        defaults.setProperty("log.entity-changes", "true");
        defaults.setProperty("explosions", "true");
        defaults.setProperty("log.commands", "true");
        defaults.setProperty("log.chat", "true");
        defaults.setProperty("log.item-drops", "true");
        defaults.setProperty("log.item-pickups", "true");
        defaults.setProperty("rollback-items", "true");
        defaults.setProperty("rollback-entities", "true");
        defaults.setProperty("item-transactions", "true");
        defaults.setProperty("hopper-transactions", "true");
        defaults.setProperty("log.entity-kills", "true");
        defaults.setProperty("log.sessions", "true");
        defaults.setProperty("username-changes", "true");
        defaults.setProperty("leaf-decay", "true");
        defaults.setProperty("portals", "true");
        defaults.setProperty("tree-growth", "true");
        defaults.setProperty("mushroom-growth", "true");
        defaults.setProperty("vine-growth", "true");
        defaults.setProperty("sculk-spread", "true");
        defaults.setProperty("water-flow", "true");
        defaults.setProperty("lava-flow", "true");
        defaults.setProperty("liquid-tracking", "true");
        defaults.setProperty("network-debug", "false");
        defaults.setProperty("default-radius", "10");
        defaults.setProperty("max-radius", "100");
        defaults.setProperty("donation-key", "");
        return defaults;
    }

    private static Properties loadProperties(Path path, Properties defaults, boolean createIfMissing) throws IOException {
        Properties properties = new Properties(defaults);
        if (Files.exists(path)) {
            try (InputStream inputStream = Files.newInputStream(path)) {
                properties.load(inputStream);
            }
        }
        else if (createIfMissing) {
            try (OutputStream outputStream = Files.newOutputStream(path)) {
                defaults.store(outputStream, "CoreProtect Fabric configuration");
            }
        }
        return properties;
    }

    private static CoreProtectFabricConfig fromProperties(Properties properties) {
        return new CoreProtectFabricConfig(
            DatabaseType.from(properties.getProperty("database.type")),
            properties.getProperty("database.file"),
            properties.getProperty("database.mysql.host"),
            Integer.parseInt(properties.getProperty("database.mysql.port")),
            properties.getProperty("database.mysql.name"),
            properties.getProperty("database.mysql.username"),
            properties.getProperty("database.mysql.password"),
            Boolean.parseBoolean(properties.getProperty("database.mysql.ssl")),
            readBoolean(properties, "hover-events", null, true),
            readBoolean(properties, "log-cancelled-chat", null, true),
            readBoolean(properties, "hopper-filter-meta", null, false),
            readBoolean(properties, "exclude-tnt", null, false),
            readBoolean(properties, "check-updates", null, true),
            Boolean.parseBoolean(properties.getProperty("log.block-breaks")),
            Boolean.parseBoolean(properties.getProperty("log.block-places")),
            Boolean.parseBoolean(properties.getProperty("log.block-uses")),
            readBoolean(properties, "player-interactions", "log.block-uses", true),
            readBoolean(properties, "pistons", "log.pistons", true),
            readBoolean(properties, "block-burn", "log.block-burn", true),
            readBoolean(properties, "block-ignite", "log.block-ignite", true),
            readBoolean(properties, "log.buckets", "buckets", true),
            Boolean.parseBoolean(properties.getProperty("log.entity-changes")),
            readBoolean(properties, "explosions", "log.explosions", true),
            Boolean.parseBoolean(properties.getProperty("log.commands")),
            Boolean.parseBoolean(properties.getProperty("log.chat")),
            readBoolean(properties, "log.item-drops", "item-drops", true),
            readBoolean(properties, "log.item-pickups", "item-pickups", true),
            readBoolean(properties, "rollback-items", null, true),
            readBoolean(properties, "rollback-entities", null, true),
            readBoolean(properties, "item-transactions", null, true),
            readBoolean(properties, "hopper-transactions", "log.hopper-transactions", true),
            Boolean.parseBoolean(properties.getProperty("log.entity-kills")),
            Boolean.parseBoolean(properties.getProperty("log.sessions")),
            readBoolean(properties, "username-changes", null, true),
            readBoolean(properties, "leaf-decay", "log.leaf-decay", true),
            readBoolean(properties, "portals", "log.portals", true),
            readBoolean(properties, "tree-growth", "log.tree-growth", true),
            readBoolean(properties, "mushroom-growth", "log.mushroom-growth", true),
            readBoolean(properties, "vine-growth", "log.vine-growth", true),
            readBoolean(properties, "sculk-spread", "log.sculk-spread", true),
            readBoolean(properties, "water-flow", "log.water-flow", true),
            readBoolean(properties, "lava-flow", "log.lava-flow", true),
            readBoolean(properties, "liquid-tracking", "log.liquid-tracking", true),
            Boolean.parseBoolean(properties.getProperty("network-debug")),
            readInt(properties, "default-radius", null, 10),
            readInt(properties, "max-radius", null, 100),
            properties.getProperty("donation-key", "")
        );
    }

    private static boolean readBoolean(Properties properties, String primaryKey, String legacyKey, boolean defaultValue) {
        String value = properties.getProperty(primaryKey);
        if (value == null && legacyKey != null) {
            value = properties.getProperty(legacyKey);
        }
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static int readInt(Properties properties, String primaryKey, String legacyKey, int defaultValue) {
        String value = properties.getProperty(primaryKey);
        if (value == null && legacyKey != null) {
            value = properties.getProperty(legacyKey);
        }
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        }
        catch (NumberFormatException exception) {
            return defaultValue;
        }
    }
}
