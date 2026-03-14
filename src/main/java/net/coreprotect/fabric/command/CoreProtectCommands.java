package net.coreprotect.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.listener.channel.PluginChannelListener;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.permission.CoreProtectPermissions;
import net.coreprotect.fabric.service.DatabaseMigrationService;
import net.coreprotect.fabric.service.LookupNetworkingService;
import net.coreprotect.fabric.service.LookupSessionService;
import net.coreprotect.fabric.service.RollbackPreviewResult;
import net.coreprotect.fabric.service.RollbackExecutionResult;
import net.coreprotect.fabric.service.RollbackService;
import net.coreprotect.fabric.service.TeleportService;
import net.coreprotect.fabric.service.UndoSessionService;
import net.coreprotect.fabric.util.LoggedItemChange;
import net.coreprotect.fabric.util.QueryBounds;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CoreProtectCommands {
    private static final int DEFAULT_LOOKUP_LIMIT = 10;
    private static final int DEFAULT_LOOKUP_RADIUS = 5;
    private static final int DEFAULT_LOOKUP_SECONDS = 3600;
    private static final int DEFAULT_ROLLBACK_RADIUS = 5;
    private static final int PLAYER_PURGE_MIN_SECONDS = 30 * 24 * 60 * 60;
    private static final int CONSOLE_PURGE_MIN_SECONDS = 24 * 60 * 60;
    private static final long TELEPORT_THROTTLE_MS = 500L;
    private static final Map<UUID, Long> TELEPORT_THROTTLE = new ConcurrentHashMap<>();

    private CoreProtectCommands() {
    }

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, FabricRuntime runtime) {
        registerRoot(dispatcher, "coreprotect", runtime);
        registerRoot(dispatcher, "co", runtime);
        registerRoot(dispatcher, "core", runtime);
    }

    private static void registerRoot(CommandDispatcher<ServerCommandSource> dispatcher, String root, FabricRuntime runtime) {
        dispatcher.register(CommandManager.literal(root)
            .then(buildHelpCommand())
            .then(buildStatusCommand("status", runtime))
            .then(buildStatusCommand("stats", runtime))
            .then(buildStatusCommand("version", runtime))
            .then(buildInspectCommand("inspect", runtime))
            .then(buildInspectCommand("i", runtime))
            .then(buildInspectCommand("inspector", runtime))
            .then(buildLookupCommand("lookup", runtime))
            .then(buildLookupCommand("l", runtime))
            .then(buildPageCommand(runtime))
            .then(buildNearbyLookupCommand("near", runtime, null))
            .then(buildTeleportCommand("teleport"))
            .then(buildTeleportCommand("tp"))
            .then(buildRollbackCommand("rollback", runtime, false))
            .then(buildRollbackCommand("rb", runtime, false))
            .then(buildRollbackCommand("ro", runtime, false))
            .then(buildRollbackCommand("restore", runtime, true))
            .then(buildRollbackCommand("rs", runtime, true))
            .then(buildRollbackCommand("re", runtime, true))
            .then(buildApplyCommand(runtime))
            .then(buildCancelCommand(runtime))
            .then(buildUndoCommand(runtime))
            .then(buildPurgeCommand(runtime))
            .then(buildReloadCommand(runtime))
            .then(buildConsumerCommand(runtime))
            .then(buildNetworkDebugCommand(runtime))
            .then(buildMigrateCommand(runtime))
        );
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildHelpCommand() {
        return CommandManager.literal("help")
            .executes(context -> sendHelp(context.getSource(), null))
            .then(CommandManager.argument("topic", StringArgumentType.word())
                .executes(context -> sendHelp(context.getSource(), StringArgumentType.getString(context, "topic"))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildStatusCommand(String literal, FabricRuntime runtime) {
        return CommandManager.literal(literal)
            .executes(context -> sendStatus(context.getSource(), runtime));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildInspectCommand(String literal, FabricRuntime runtime) {
        return CommandManager.literal(literal)
            .executes(context -> toggleInspect(context.getSource(), runtime, null))
            .then(CommandManager.literal("on")
                .executes(context -> toggleInspect(context.getSource(), runtime, true)))
            .then(CommandManager.literal("off")
                .executes(context -> toggleInspect(context.getSource(), runtime, false)));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildLookupCommand(String literal, FabricRuntime runtime) {
        return CommandManager.literal(literal)
            .executes(context -> lookupTargetedBlock(context.getSource(), runtime, DEFAULT_LOOKUP_LIMIT))
            .then(CommandManager.literal("block")
                .executes(context -> lookupTargetedBlock(context.getSource(), runtime, DEFAULT_LOOKUP_LIMIT))
                .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                    .executes(context -> lookupTargetedBlock(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "limit")))))
            .then(CommandManager.literal("entity")
                .executes(context -> lookupTargetedEntity(context.getSource(), runtime, DEFAULT_LOOKUP_LIMIT))
                .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                    .executes(context -> lookupTargetedEntity(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "limit")))))
            .then(CommandManager.literal("click")
                .executes(context -> lookupTargetedClick(context.getSource(), runtime, DEFAULT_LOOKUP_LIMIT))
                .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                    .executes(context -> lookupTargetedClick(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "limit")))))
            .then(CommandManager.literal("sign")
                .executes(context -> lookupTargetedSign(context.getSource(), runtime, DEFAULT_LOOKUP_LIMIT))
                .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                    .executes(context -> lookupTargetedSign(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "limit")))))
            .then(CommandManager.literal("kill")
                .executes(context -> lookupTargetedKill(context.getSource(), runtime, DEFAULT_LOOKUP_LIMIT))
                .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                    .executes(context -> lookupTargetedKill(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "limit")))))
            .then(CommandManager.literal("container")
                .executes(context -> lookupTargetedContainer(context.getSource(), runtime, DEFAULT_LOOKUP_LIMIT))
                .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                    .executes(context -> lookupTargetedContainer(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "limit")))))
            .then(CommandManager.literal("item")
                .executes(context -> lookupTargetedItem(context.getSource(), runtime, DEFAULT_LOOKUP_LIMIT))
                .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                    .executes(context -> lookupTargetedItem(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "limit")))))
            .then(CommandManager.literal("inventory")
                .executes(context -> lookupTargetedInventory(context.getSource(), runtime, DEFAULT_LOOKUP_LIMIT))
                .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                    .executes(context -> lookupTargetedInventory(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "limit")))))
            .then(buildNearbyLookupCommand("chat", runtime, List.of(CoreProtectEventType.PLAYER_CHAT)))
            .then(buildNearbyLookupCommand("command", runtime, List.of(CoreProtectEventType.PLAYER_COMMAND)))
            .then(buildNearbyLookupCommand("session", runtime, List.of(CoreProtectEventType.PLAYER_JOIN, CoreProtectEventType.PLAYER_QUIT)))
            .then(CommandManager.literal("here")
                .executes(context -> lookupHere(context.getSource(), runtime, DEFAULT_LOOKUP_RADIUS, DEFAULT_LOOKUP_SECONDS, DEFAULT_LOOKUP_LIMIT, null))
                .then(CommandManager.argument("radius", IntegerArgumentType.integer(0, 64))
                    .executes(context -> lookupHere(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "radius"), DEFAULT_LOOKUP_SECONDS, DEFAULT_LOOKUP_LIMIT, null))
                    .then(CommandManager.argument("seconds", IntegerArgumentType.integer(1, 604800))
                        .executes(context -> lookupHere(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "radius"), IntegerArgumentType.getInteger(context, "seconds"), DEFAULT_LOOKUP_LIMIT, null))
                        .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                            .executes(context -> lookupHere(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "radius"), IntegerArgumentType.getInteger(context, "seconds"), IntegerArgumentType.getInteger(context, "limit"), null))
                            .then(CommandManager.argument("player", StringArgumentType.word())
                                .executes(context -> lookupHere(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "radius"), IntegerArgumentType.getInteger(context, "seconds"), IntegerArgumentType.getInteger(context, "limit"), StringArgumentType.getString(context, "player"))))))))
            .then(CommandManager.argument("legacy", StringArgumentType.greedyString())
                .executes(context -> runLegacyLookup(context.getSource(), runtime, StringArgumentType.getString(context, "legacy"))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildNearbyLookupCommand(String literal, FabricRuntime runtime, List<CoreProtectEventType> actionFilter) {
        return CommandManager.literal(literal)
            .executes(context -> lookupHere(context.getSource(), runtime, DEFAULT_LOOKUP_RADIUS, DEFAULT_LOOKUP_SECONDS, DEFAULT_LOOKUP_LIMIT, null, actionFilter))
            .then(CommandManager.argument("radius", IntegerArgumentType.integer(0, 64))
                .executes(context -> lookupHere(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "radius"), DEFAULT_LOOKUP_SECONDS, DEFAULT_LOOKUP_LIMIT, null, actionFilter))
                .then(CommandManager.argument("seconds", IntegerArgumentType.integer(1, 604800))
                    .executes(context -> lookupHere(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "radius"), IntegerArgumentType.getInteger(context, "seconds"), DEFAULT_LOOKUP_LIMIT, null, actionFilter))
                    .then(CommandManager.argument("limit", IntegerArgumentType.integer(1, 50))
                        .executes(context -> lookupHere(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "radius"), IntegerArgumentType.getInteger(context, "seconds"), IntegerArgumentType.getInteger(context, "limit"), null, actionFilter))
                        .then(CommandManager.argument("player", StringArgumentType.word())
                            .executes(context -> lookupHere(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "radius"), IntegerArgumentType.getInteger(context, "seconds"), IntegerArgumentType.getInteger(context, "limit"), StringArgumentType.getString(context, "player"), actionFilter))))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildPageCommand(FabricRuntime runtime) {
        return CommandManager.literal("page")
            .executes(context -> sendPageUsage(context.getSource()))
            .then(CommandManager.argument("page", StringArgumentType.word())
                .executes(context -> runPageAlias(context.getSource(), runtime, StringArgumentType.getString(context, "page"))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildTeleportCommand(String literal) {
        return CommandManager.literal(literal)
            .executes(context -> sendTeleportUsage(context.getSource()))
            .then(CommandManager.argument("args", StringArgumentType.greedyString())
                .executes(context -> runTeleport(context.getSource(), StringArgumentType.getString(context, "args"))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildRollbackCommand(String literal, FabricRuntime runtime, boolean restore) {
        return CommandManager.literal(literal)
            .then(CommandManager.argument("seconds", IntegerArgumentType.integer(1, 604800))
                .executes(context -> runRollback(context.getSource(), runtime, restore, IntegerArgumentType.getInteger(context, "seconds"), DEFAULT_ROLLBACK_RADIUS, null))
                .then(CommandManager.argument("radius", IntegerArgumentType.integer(0, 64))
                    .executes(context -> runRollback(context.getSource(), runtime, restore, IntegerArgumentType.getInteger(context, "seconds"), IntegerArgumentType.getInteger(context, "radius"), null))
                    .then(CommandManager.argument("player", StringArgumentType.word())
                        .executes(context -> runRollback(context.getSource(), runtime, restore, IntegerArgumentType.getInteger(context, "seconds"), IntegerArgumentType.getInteger(context, "radius"), StringArgumentType.getString(context, "player"))))))
            .then(CommandManager.argument("legacy", StringArgumentType.greedyString())
                .executes(context -> runLegacyRollback(context.getSource(), runtime, restore, StringArgumentType.getString(context, "legacy"))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildPurgeCommand(FabricRuntime runtime) {
        return CommandManager.literal("purge")
            .executes(context -> sendPurgeUsage(context.getSource()))
            .then(CommandManager.argument("seconds", IntegerArgumentType.integer(1))
                .executes(context -> runPurge(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "seconds"), null, null, false))
                .then(CommandManager.argument("world", StringArgumentType.word())
                    .executes(context -> runPurge(context.getSource(), runtime, IntegerArgumentType.getInteger(context, "seconds"), StringArgumentType.getString(context, "world"), null, false))))
            .then(CommandManager.argument("legacy", StringArgumentType.greedyString())
                .executes(context -> runLegacyPurge(context.getSource(), runtime, StringArgumentType.getString(context, "legacy"))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildReloadCommand(FabricRuntime runtime) {
        return CommandManager.literal("reload")
            .executes(context -> reloadRuntime(context.getSource(), runtime));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildConsumerCommand(FabricRuntime runtime) {
        return CommandManager.literal("consumer")
            .executes(context -> runConsumer(context.getSource(), runtime, null))
            .then(CommandManager.literal("status")
                .executes(context -> runConsumer(context.getSource(), runtime, null)))
            .then(CommandManager.literal("pause")
                .executes(context -> runConsumer(context.getSource(), runtime, true)))
            .then(CommandManager.literal("resume")
                .executes(context -> runConsumer(context.getSource(), runtime, false)));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildUndoCommand(FabricRuntime runtime) {
        return CommandManager.literal("undo")
            .executes(context -> runUndo(context.getSource(), runtime));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildApplyCommand(FabricRuntime runtime) {
        return CommandManager.literal("apply")
            .executes(context -> runApply(context.getSource(), runtime));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildCancelCommand(FabricRuntime runtime) {
        return CommandManager.literal("cancel")
            .executes(context -> runCancel(context.getSource(), runtime));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildMigrateCommand(FabricRuntime runtime) {
        return CommandManager.literal("migrate-db")
            .executes(context -> sendMigrateUsage(context.getSource()))
            .then(CommandManager.argument("database", StringArgumentType.word())
                .executes(context -> runMigrate(context.getSource(), runtime, StringArgumentType.getString(context, "database"))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildNetworkDebugCommand(FabricRuntime runtime) {
        return CommandManager.literal("network-debug")
            .executes(context -> runNetworkDebug(context.getSource(), runtime, null))
            .then(CommandManager.argument("type", StringArgumentType.word())
                .executes(context -> runNetworkDebug(context.getSource(), runtime, StringArgumentType.getString(context, "type"))));
    }

    private static int sendHelp(ServerCommandSource source, String topic) {
        if (!CoreProtectPermissions.canUseHelp(source, true)) {
            return 0;
        }

        String normalizedTopic = topic == null ? "" : topic.toLowerCase(Locale.ROOT);
        if (normalizedTopic.isBlank()) {
            source.sendFeedback(() -> Text.literal("CoreProtect Fabric commands"), false);
            sendUsageLine(source, "/co help [command]", "show command help");
            sendUsageLine(source, "/co status | stats | version", "show rewrite status");
            sendUsageLine(source, "/co inspect | i | inspector [on|off]", "toggle inspector mode");
            sendUsageLine(source, "/co lookup | l <params>", "lookup block, sign, entity, item, and session history");
            sendUsageLine(source, "/co page <page>[:<lines>]", "show another page from your previous lookup");
            sendUsageLine(source, "/co near [radius] [seconds] [limit] [player]", "run a nearby lookup with the old /co near alias");
            sendUsageLine(source, "/co teleport|tp [world] <x> [y] <z>", "teleport to lookup coordinates");
            sendUsageLine(source, "/co rollback | rb | ro <seconds> [radius] [player]", "rollback logged block and sign changes");
            sendUsageLine(source, "/co restore | rs | re <seconds> [radius] [player]", "restore logged block and sign changes");
            sendUsageLine(source, "/co apply", "apply your last rollback or restore preview");
            sendUsageLine(source, "/co cancel", "cancel your last rollback or restore preview");
            sendUsageLine(source, "/co undo", "revert your last rollback or restore");
            sendUsageLine(source, "/co purge t:<time> [r:#world] [i:target[,..]] [#optimize]", "delete old audit rows");
            sendUsageLine(source, "/co reload", "reload the Fabric rewrite configuration");
            sendUsageLine(source, "/co consumer <pause|resume|status>", "pause or resume the audit writer queue (console only)");
            sendUsageLine(source, "/co network-debug <type>", "send networking debug packets (requires network-debug=true)");
            sendUsageLine(source, "/co migrate-db <sqlite|mysql>", "migrate audit data between SQLite and MySQL (console only)");
            return 1;
        }

        switch (normalizedTopic) {
            case "status":
            case "stats":
            case "version":
                sendUsageLine(source, "/co status | stats | version", "show database path, pending writes, and WorldEdit status");
                return 1;
            case "inspect":
            case "i":
            case "inspector":
                sendUsageLine(source, "/co inspect [on|off]", "toggle inspector mode for blocks and entities");
                return 1;
            case "lookup":
            case "l":
                sendUsageLine(source, "/co lookup block|entity|click|sign|kill|container|inventory|item [limit]", "lookup targeted history");
                sendUsageLine(source, "/co lookup here|chat|command|session [radius] [seconds] [limit] [player]", "lookup nearby history");
                sendUsageLine(source, "/co l t:30m r:#global u:Notch,Intelli a:+block i:diamond_ore", "legacy-style lookup syntax");
                sendUsageLine(source, "/co l t:1h-2h a:chat #count", "count matching rows inside a time range");
                sendUsageLine(source, "/co l a:username u:Notch", "lookup username history for a player");
                sendUsageLine(source, "/co l t:30m u:Notch a:item", "lookup dropped, thrown, picked up, crafted, consumed, bought, sold, deposited, or withdrawn items");
                sendUsageLine(source, "/co l t:15m r:#worldedit", "query the current cuboid WorldEdit selection");
                sendUsageLine(source, "/co l 2 or /co l 1:20", "view another page of the previous lookup");
                return 1;
            case "page":
                sendUsageLine(source, "/co page <page>[:<lines>]", "alias for lookup pagination");
                return 1;
            case "near":
                sendUsageLine(source, "/co near [radius] [seconds] [limit] [player]", "shortcut for a nearby lookup with default radius 5");
                return 1;
            case "teleport":
            case "tp":
                sendUsageLine(source, "/co teleport [world] <x> [y] <z>", "teleport to a CoreProtect lookup location");
                sendUsageLine(source, "/co tp minecraft:the_nether 120 64 -30", "teleport across dimensions");
                sendUsageLine(source, "/co tp 120 -30", "teleport in your current world using your current Y");
                return 1;
            case "rollback":
            case "rb":
            case "ro":
                sendUsageLine(source, "/co rollback <seconds> [radius] [player]", "rollback block place/break and sign changes");
                sendUsageLine(source, "/co rb t:15m r:#world_nether u:Griefer i:stone", "legacy-style rollback syntax");
                sendUsageLine(source, "/co rb t:15m r:#global u:Griefer #preview", "preview a global rollback");
                sendUsageLine(source, "/co rb t:15m r:#worldedit", "target the current cuboid WorldEdit selection");
                sendUsageLine(source, "/co rb t:1h-2h u:Griefer #silent|#verbose", "control rollback output verbosity");
                return 1;
            case "restore":
            case "rs":
            case "re":
                sendUsageLine(source, "/co restore <seconds> [radius] [player]", "restore block place/break and sign changes");
                sendUsageLine(source, "/co rs t:15m r:#world_nether u:Griefer i:stone", "legacy-style restore syntax");
                return 1;
            case "undo":
                sendUsageLine(source, "/co undo", "revert your last Fabric rollback or restore");
                sendUsageLine(source, "/co undo", "uses the opposite action with the saved scope and fixed time window");
                return 1;
            case "apply":
                sendUsageLine(source, "/co apply", "apply your last rollback or restore preview");
                return 1;
            case "cancel":
                sendUsageLine(source, "/co cancel", "cancel your last rollback or restore preview");
                return 1;
            case "purge":
                sendUsageLine(source, "/co purge t:30d", "purge old audit data");
                sendUsageLine(source, "/co purge t:30d r:#minecraft:the_nether", "purge only one world");
                sendUsageLine(source, "/co purge t:30d i:stone,dirt", "purge only matching targets");
                sendUsageLine(source, "/co purge t:30d #optimize", "optimize MySQL tables after the purge");
                sendUsageLine(source, "/co purge 2592000", "direct seconds-based purge syntax");
                return 1;
            case "reload":
                sendUsageLine(source, "/co reload", "reload config/coreprotect-fabric/coreprotect-fabric.properties");
                return 1;
            case "consumer":
                sendUsageLine(source, "/co consumer status", "show consumer queue state");
                sendUsageLine(source, "/co consumer pause", "pause queued write processing");
                sendUsageLine(source, "/co consumer resume", "resume queued write processing");
                return 1;
            case "network-debug":
                sendUsageLine(source, "/co network-debug <type>", "send test packet on coreprotect:data (types: 1,2,3,4)");
                sendUsageLine(source, "/co network-debug 1", "send standard lookup-style test payload");
                return 1;
            case "migrate-db":
            case "migrate":
                sendUsageLine(source, "/co migrate-db <sqlite|mysql>", "migrate audit data to the selected database backend");
                sendUsageLine(source, "Console only", "configure the target database in coreprotect-fabric.properties before running it");
                return 1;
            default:
                source.sendFeedback(() -> Text.literal("Unknown help topic: " + topic), false);
                return sendHelp(source, null);
        }
    }

    private static void sendUsageLine(ServerCommandSource source, String usage, String description) {
        source.sendFeedback(() -> Text.literal(usage + " - " + description), false);
    }

    private static int sendStatus(ServerCommandSource source, FabricRuntime runtime) {
        if (!CoreProtectPermissions.canUseStatus(source, true)) {
            return 0;
        }

        if (runtime.database() == null) {
            source.sendFeedback(() -> Text.literal("CoreProtect Fabric rewrite is not initialized yet."), false);
            return 0;
        }

        source.sendFeedback(() -> Text.literal("CoreProtect Fabric status"), false);
        source.sendFeedback(() -> Text.literal(runtime.logger().buildStatusSummary()), false);
        source.sendFeedback(() -> Text.literal("worldedit=" + (runtime.hasWorldEditIntegration() ? "enabled" : "disabled")), false);
        source.sendFeedback(() -> Text.literal("root=" + runtime.rootDirectory().toAbsolutePath()), false);
        return 1;
    }

    private static int toggleInspect(ServerCommandSource source, FabricRuntime runtime, Boolean targetState) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (!CoreProtectPermissions.canUseInspect(source, true)) {
            runtime.inspector().disable(player);
            return 0;
        }

        boolean previous = runtime.inspector().isEnabled(player);
        boolean enabled = targetState == null ? runtime.inspector().toggle(player) : runtime.inspector().setEnabled(player, targetState);
        if (targetState != null && previous == enabled) {
            source.sendFeedback(() -> Text.literal(enabled ? "Inspector is already enabled." : "Inspector is already disabled."), false);
            return 1;
        }

        source.sendFeedback(() -> Text.literal(enabled ? "Inspector enabled." : "Inspector disabled."), false);
        return 1;
    }

    private static int lookupTargetedBlock(ServerCommandSource source, FabricRuntime runtime, int limit) {
        if (!CoreProtectPermissions.canLookupBlock(source, true)) {
            return 0;
        }

        return lookupTargetedBlockHistory(
            source,
            runtime,
            limit,
            null,
            "CoreProtect block history",
            "No block history recorded at this position."
        );
    }

    private static int lookupTargetedEntity(ServerCommandSource source, FabricRuntime runtime, int limit) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (!CoreProtectPermissions.canLookupEntity(source, true)) {
            return 0;
        }

        List<Text> lines = runtime.lookup().describeTargetedEntityHistory(player, limit);
        sendLines(source, lines);
        LookupNetworkingService.send(source, runtime.lookup().getTargetedEntityHistory(player, limit, null));
        return 1;
    }

    private static int lookupTargetedSign(ServerCommandSource source, FabricRuntime runtime, int limit) {
        if (!CoreProtectPermissions.canLookupSign(source, true)) {
            return 0;
        }

        return lookupTargetedBlockHistory(
            source,
            runtime,
            limit,
            List.of(CoreProtectEventType.SIGN_CHANGE),
            "CoreProtect sign history",
            "No sign changes recorded at this position."
        );
    }

    private static int lookupTargetedClick(ServerCommandSource source, FabricRuntime runtime, int limit) {
        if (!CoreProtectPermissions.canLookupClick(source, true)) {
            return 0;
        }

        return lookupTargetedBlockHistory(
            source,
            runtime,
            limit,
            List.of(CoreProtectEventType.BLOCK_USE, CoreProtectEventType.ENTITY_USE),
            "CoreProtect interaction history",
            "No interaction history recorded at this position."
        );
    }

    private static int lookupTargetedKill(ServerCommandSource source, FabricRuntime runtime, int limit) {
        if (!CoreProtectPermissions.canLookupKill(source, true)) {
            return 0;
        }

        return lookupTargetedBlockHistory(
            source,
            runtime,
            limit,
            List.of(CoreProtectEventType.ENTITY_KILL),
            "CoreProtect kill history",
            "No entity kills recorded at this position."
        );
    }

    private static int lookupTargetedContainer(ServerCommandSource source, FabricRuntime runtime, int limit) {
        if (!CoreProtectPermissions.canLookupContainer(source, true)) {
            return 0;
        }

        return lookupTargetedBlockHistory(
            source,
            runtime,
            limit,
            List.of(CoreProtectEventType.CONTAINER_TRANSACTION),
            "CoreProtect container history",
            "No container transactions recorded at this position."
        );
    }

    private static int lookupTargetedInventory(ServerCommandSource source, FabricRuntime runtime, int limit) {
        if (!CoreProtectPermissions.canLookupInventory(source, true)) {
            return 0;
        }

        return lookupTargetedBlockHistory(
            source,
            runtime,
            limit,
            List.of(
                CoreProtectEventType.CONTAINER_TRANSACTION,
                CoreProtectEventType.ITEM_PICKUP,
                CoreProtectEventType.ITEM_DROP,
                CoreProtectEventType.ITEM_THROW,
                CoreProtectEventType.ITEM_SHOOT,
                CoreProtectEventType.ITEM_BUY,
                CoreProtectEventType.ITEM_SELL,
                CoreProtectEventType.ITEM_CREATE,
                CoreProtectEventType.ITEM_DESTROY
            ),
            "CoreProtect inventory history",
            "No inventory transactions recorded at this position."
        );
    }

    private static int lookupTargetedItem(ServerCommandSource source, FabricRuntime runtime, int limit) {
        if (!CoreProtectPermissions.canLookupItem(source, true)) {
            return 0;
        }

        return lookupTargetedBlockHistory(
            source,
            runtime,
            limit,
            List.of(
                CoreProtectEventType.ITEM_PICKUP,
                CoreProtectEventType.ITEM_DROP,
                CoreProtectEventType.ITEM_THROW,
                CoreProtectEventType.ITEM_SHOOT,
                CoreProtectEventType.ITEM_BUY,
                CoreProtectEventType.ITEM_SELL,
                CoreProtectEventType.ITEM_CREATE,
                CoreProtectEventType.ITEM_DESTROY
            ),
            "CoreProtect item history",
            "No item transactions recorded at this position."
        );
    }

    private static int lookupHere(ServerCommandSource source, FabricRuntime runtime, int radius, int seconds, int limit, String actorName) {
        return lookupHere(source, runtime, radius, seconds, limit, actorName, null);
    }

    private static int lookupHere(ServerCommandSource source, FabricRuntime runtime, int radius, int seconds, int limit, String actorName, List<CoreProtectEventType> actionFilter) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (!CoreProtectPermissions.canLookupNearby(source, actionFilter, true)) {
            return 0;
        }

        ServerWorld world = (ServerWorld) player.getEntityWorld();
        List<Text> lines = runtime.lookup().describeNearbyHistory(world, player.getBlockPos(), radius, seconds, limit, actorName, actionFilter);
        List<StoredEventRecord> networkEvents = runtime.database().lookupNearby(
            world.getRegistryKey().getValue().toString(),
            player.getBlockPos(),
            radius,
            seconds,
            limit,
            actorName,
            actionFilter
        );
        sendLines(source, lines);
        sendLookupNetworkData(source, networkEvents);
        return 1;
    }

    private static int runLegacyLookup(ServerCommandSource source, FabricRuntime runtime, String input) {
        LookupPageRequest pageRequest = parseLookupPageRequest(input);
        if (pageRequest != null) {
            return runLookupPage(source, runtime, pageRequest.page(), pageRequest.linesPerPage());
        }

        LegacyCommandOptions options = LegacyCommandParser.parse(input);
        if (options.isEmpty()) {
            return lookupTargetedBlock(source, runtime, DEFAULT_LOOKUP_LIMIT);
        }

        if (!CoreProtectPermissions.canLookupNearby(source, options.actionFilter(), true)) {
            return 0;
        }

        int minimumSeconds = options.minimumSeconds() != null ? options.minimumSeconds() : 0;
        int seconds = options.seconds() != null ? options.seconds() : DEFAULT_LOOKUP_SECONDS;
        int limit = options.limit() != null ? options.limit() : DEFAULT_LOOKUP_LIMIT;
        Integer radius = options.radius();
        String worldKey = null;
        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity ? (ServerPlayerEntity) source.getEntity() : null;
        QueryBounds selectionBounds = null;
        if (options.globalScope()) {
            radius = null;
        }
        else if (isWorldEditWorldFilter(options.worldFilter())) {
            if (player == null) {
                source.sendFeedback(() -> Text.literal("This command can only be used in-game."), false);
                return 0;
            }
            try {
                selectionBounds = runtime.resolveWorldEditSelection(player);
            }
            catch (IllegalStateException exception) {
                source.sendFeedback(() -> Text.literal(exception.getMessage()), false);
                return 0;
            }
            worldKey = selectionBounds.worldKey();
            radius = null;
        }
        else if (options.worldFilter() != null) {
            worldKey = resolveWorldFilter(source, options.worldFilter());
            if (worldKey == null && !isGlobalWorldFilter(options.worldFilter())) {
                return 0;
            }
            radius = null;
        }
        else if (radius != null) {
            if (player == null) {
                source.sendFeedback(() -> Text.literal("This command can only be used in-game."), false);
                return 0;
            }
            worldKey = ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString();
        }

        String scopeSummary = selectionBounds == null ? describeScope(worldKey, radius, player) : describeScope(selectionBounds);
        String timeSummary = describeTimeWindow(minimumSeconds, seconds);
        String actorSummary = describeActors(options.actorNames(), options.excludeActorNames());
        String targetSummary = describeTargetFilters(options.includeTargets(), options.excludeTargets());
        BlockPos center = player != null && radius != null ? player.getBlockPos().toImmutable() : null;
        if (options.count()) {
            int total = runtime.lookup().countScopedHistory(
                worldKey,
                center,
                radius,
                selectionBounds,
                minimumSeconds,
                seconds,
                options.actorNames(),
                options.excludeActorNames(),
                options.actionFilter(),
                options.includeTargets(),
                options.excludeTargets()
            );
            source.sendFeedback(() -> Text.literal(
                "CoreProtect lookup count: matched=" + total
                    + ", scope=" + scopeSummary
                    + ", time=" + timeSummary
                    + actorSummary
                    + targetSummary
            ), false);
            return total > 0 ? 1 : 0;
        }

        int total = runtime.lookup().countScopedHistory(
            worldKey,
            center,
            radius,
            selectionBounds,
            minimumSeconds,
            seconds,
            options.actorNames(),
            options.excludeActorNames(),
            options.actionFilter(),
            options.includeTargets(),
            options.excludeTargets()
        );
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) limit));
        LookupSessionService.LookupQuery query = new LookupSessionService.LookupQuery(
            worldKey,
            center,
            radius,
            selectionBounds,
            minimumSeconds,
            seconds,
            limit,
            options.actorNames(),
            options.excludeActorNames(),
            options.actionFilter(),
            options.includeTargets(),
            options.excludeTargets()
        );
        runtime.lookupSessions().remember(lookupSessionKey(source), query);
        List<StoredEventRecord> networkEvents = runtime.lookup().getScopedHistory(
            worldKey,
            center,
            radius,
            selectionBounds,
            minimumSeconds,
            seconds,
            limit,
            0,
            options.actorNames(),
            options.excludeActorNames(),
            options.actionFilter(),
            options.includeTargets(),
            options.excludeTargets()
        );
        List<Text> lines = selectionBounds == null
            ? runtime.lookup().describeScopedHistory(
                worldKey,
                center,
                radius,
                null,
                minimumSeconds,
                seconds,
                limit,
                0,
                1,
                totalPages,
                options.actorNames(),
                options.excludeActorNames(),
                options.actionFilter(),
                options.includeTargets(),
                options.excludeTargets(),
                null
            )
            : runtime.lookup().describeScopedHistory(
                worldKey,
                null,
                null,
                selectionBounds,
                minimumSeconds,
                seconds,
                limit,
                0,
                1,
                totalPages,
                options.actorNames(),
                options.excludeActorNames(),
                options.actionFilter(),
                options.includeTargets(),
                options.excludeTargets(),
                scopeSummary
            );
        sendLines(source, lines);
        sendLookupNetworkData(source, networkEvents);
        if (totalPages > 1) {
            source.sendFeedback(() -> Text.literal("Use /co l <page> or /co l <page>:<lines> to view more results."), false);
        }
        return 1;
    }

    private static int sendPageUsage(ServerCommandSource source) {
        if (!CoreProtectPermissions.canUseLookupPagination(source, true)) {
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Usage: /co page <page>[:<lines>]"), false);
        return 1;
    }

    private static int runPageAlias(ServerCommandSource source, FabricRuntime runtime, String input) {
        LookupPageRequest request = parseLookupPageRequest(input);
        if (request == null) {
            return sendPageUsage(source);
        }
        return runLookupPage(source, runtime, request.page(), request.linesPerPage());
    }

    private static int sendTeleportUsage(ServerCommandSource source) {
        if (!CoreProtectPermissions.canUseTeleport(source, true)) {
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Usage: /co teleport [world] <x> [y] <z>"), false);
        source.sendFeedback(() -> Text.literal("Alias: /co tp [world] <x> [y] <z>"), false);
        return 1;
    }

    private static int runTeleport(ServerCommandSource source, String input) {
        if (!CoreProtectPermissions.canUseTeleport(source, true)) {
            return 0;
        }

        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        long now = System.currentTimeMillis();
        long lastTeleportAt = TELEPORT_THROTTLE.getOrDefault(player.getUuid(), 0L);
        if ((now - lastTeleportAt) < TELEPORT_THROTTLE_MS) {
            source.sendFeedback(() -> Text.literal("CoreProtect teleport throttled. Try again in a moment."), false);
            return 0;
        }

        TeleportRequest request = parseTeleportRequest(source, player, input);
        if (request == null) {
            return sendTeleportUsage(source);
        }

        TELEPORT_THROTTLE.put(player.getUuid(), now);
        TeleportService.TeleportResult result = TeleportService.teleport(
            player,
            request.world(),
            request.x(),
            request.y(),
            request.z(),
            request.explicitY()
        );
        source.sendFeedback(() -> Text.literal(
            "Teleported to x" + formatCoordinate(result.x())
                + "/y" + formatCoordinate(result.y())
                + "/z" + formatCoordinate(result.z())
                + "/" + result.world().getRegistryKey().getValue()
        ), false);
        return 1;
    }

    private static int runLookupPage(ServerCommandSource source, FabricRuntime runtime, int page, Integer requestedLines) {
        String sessionKey = lookupSessionKey(source);
        LookupSessionService.LookupQuery previousQuery = runtime.lookupSessions().get(sessionKey);
        if (previousQuery == null) {
            source.sendFeedback(() -> Text.literal("No previous lookup query is available for pagination."), false);
            return 0;
        }
        if (!CoreProtectPermissions.canLookupNearby(source, previousQuery.actionFilter(), true)) {
            return 0;
        }

        int linesPerPage = requestedLines == null ? previousQuery.linesPerPage() : Math.max(1, Math.min(50, requestedLines));
        LookupSessionService.LookupQuery query = previousQuery.withLinesPerPage(linesPerPage);
        runtime.lookupSessions().remember(sessionKey, query);

        int total = runtime.lookup().countScopedHistory(
            query.worldKey(),
            query.center(),
            query.radius(),
            query.bounds(),
            query.minimumSeconds(),
            query.maximumSeconds(),
            query.actorNames(),
            query.excludeActorNames(),
            query.actionFilter(),
            query.includeTargets(),
            query.excludeTargets()
        );
        if (total <= 0) {
            source.sendFeedback(() -> Text.literal("No matching events found for the previous lookup query."), false);
            return 0;
        }

        int totalPages = Math.max(1, (int) Math.ceil(total / (double) linesPerPage));
        if (page < 1 || page > totalPages) {
            source.sendFeedback(() -> Text.literal("Invalid lookup page. Available pages: 1-" + totalPages), false);
            return 0;
        }

        int offset = (page - 1) * linesPerPage;
        List<StoredEventRecord> networkEvents = runtime.lookup().getScopedHistory(
            query.worldKey(),
            query.center(),
            query.radius(),
            query.bounds(),
            query.minimumSeconds(),
            query.maximumSeconds(),
            linesPerPage,
            offset,
            query.actorNames(),
            query.excludeActorNames(),
            query.actionFilter(),
            query.includeTargets(),
            query.excludeTargets()
        );
        List<Text> lines = query.bounds() == null
            ? runtime.lookup().describeScopedHistory(
                query.worldKey(),
                query.center(),
                query.radius(),
                null,
                query.minimumSeconds(),
                query.maximumSeconds(),
                linesPerPage,
                offset,
                page,
                totalPages,
                query.actorNames(),
                query.excludeActorNames(),
                query.actionFilter(),
                query.includeTargets(),
                query.excludeTargets(),
                null
            )
            : runtime.lookup().describeScopedHistory(
                query.worldKey(),
                null,
                null,
                query.bounds(),
                query.minimumSeconds(),
                query.maximumSeconds(),
                linesPerPage,
                offset,
                page,
                totalPages,
                query.actorNames(),
                query.excludeActorNames(),
                query.actionFilter(),
                query.includeTargets(),
                query.excludeTargets(),
                describeScope(query.bounds())
            );
        sendLines(source, lines);
        sendLookupNetworkData(source, networkEvents);
        if (page < totalPages) {
            source.sendFeedback(() -> Text.literal("Use /co l " + (page + 1) + " to view the next page."), false);
        }
        return 1;
    }

    private static int runRollback(ServerCommandSource source, FabricRuntime runtime, boolean restore, int seconds, int radius, String actorName) {
        return runRollback(source, runtime, restore, seconds, radius, actorName, null);
    }

    private static int runRollback(ServerCommandSource source, FabricRuntime runtime, boolean restore, int seconds, int radius, String actorName, List<CoreProtectEventType> actionFilter) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }
        if (!CoreProtectPermissions.canRunRollback(source, restore, true)) {
            return 0;
        }

        runtime.previews().clear(player);
        TimeWindow timeWindow = fixedTimeWindow(0, seconds);
        QueryBounds radiusBounds = createRadiusBounds(player, radius);
        List<String> actorNames = actorName == null || actorName.isBlank() ? null : List.of(actorName);
        RollbackExecutionResult result = runtime.rollback().applyBetween(
            player,
            timeWindow.notBefore(),
            timeWindow.notAfter(),
            radiusBounds,
            actorNames,
            null,
            restore,
            actionFilter,
            null,
            null
        );
        String operation = restore ? "restore" : "rollback";
        String scopeSummary = describeScope(((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString(), radius, player);
        String actorSummary = actorName == null || actorName.isBlank() ? "" : ", actor=" + actorName;
        String timeSummary = describeDuration(seconds);
        rememberUndo(
            source,
            runtime,
            new UndoSessionService.UndoOperation(
                restore,
                false,
                null,
                radiusBounds,
                timeWindow.notBefore(),
                timeWindow.notAfter(),
                actorNames,
                null,
                actionFilter,
                null,
                null,
                describeUndoOperation(scopeSummary, timeSummary, actorSummary, "", describeActionFilters(actionFilter))
            )
        );
        source.sendFeedback(() -> Text.literal(
            "CoreProtect " + operation + ": scanned=" + result.scanned()
                + ", changed=" + result.changed()
                + ", marked=" + result.marked()
                + ", scope=" + scopeSummary
                + ", time=" + timeSummary
                + actorSummary
        ), false);
        return result.changed() > 0 ? 1 : 0;
    }

    private static int runLegacyRollback(ServerCommandSource source, FabricRuntime runtime, boolean restore, String input) {
        LegacyCommandOptions options = LegacyCommandParser.parse(input);
        if (!CoreProtectPermissions.canRunRollback(source, restore, true)) {
            return 0;
        }
        if (options.actionFilter() != null && !options.actionFilter().isEmpty()) {
            boolean supported = options.actionFilter().stream().allMatch(RollbackService::isSupported);
            if (!supported) {
                source.sendFeedback(() -> Text.literal("This Fabric rewrite currently supports rollback and restore for block and sign events only."), false);
                return 0;
            }
        }

        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        int minimumSeconds = options.minimumSeconds() != null ? options.minimumSeconds() : 0;
        int seconds = options.seconds() != null ? options.seconds() : DEFAULT_LOOKUP_SECONDS;
        Integer radius = options.radius();
        String worldKey;
        QueryBounds selectionBounds = null;
        if (options.globalScope()) {
            worldKey = null;
            radius = null;
        }
        else if (isWorldEditWorldFilter(options.worldFilter())) {
            try {
                selectionBounds = runtime.resolveWorldEditSelection(player);
            }
            catch (IllegalStateException exception) {
                source.sendFeedback(() -> Text.literal(exception.getMessage()), false);
                return 0;
            }
            worldKey = selectionBounds.worldKey();
            radius = null;
        }
        else if (options.worldFilter() != null) {
            worldKey = resolveWorldFilter(source, options.worldFilter());
            if (worldKey == null && !isGlobalWorldFilter(options.worldFilter())) {
                return 0;
            }
            radius = null;
        }
        else {
            worldKey = ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString();
            radius = radius != null ? radius : DEFAULT_ROLLBACK_RADIUS;
        }

        QueryBounds scopeBounds = selectionBounds != null ? selectionBounds : (radius == null ? null : createRadiusBounds(player, radius));
        TimeWindow timeWindow = fixedTimeWindow(minimumSeconds, seconds);

        if (options.preview()) {
            RollbackPreviewResult preview = runtime.rollback().previewBetween(
                player,
                timeWindow.notBefore(),
                timeWindow.notAfter(),
                scopeBounds == null ? worldKey : null,
                scopeBounds,
                options.actorNames(),
                options.excludeActorNames(),
                restore,
                options.actionFilter(),
                options.includeTargets(),
                options.excludeTargets()
            );
            runtime.previews().show(player, preview.blockChanges());
            String operation = restore ? "restore" : "rollback";
            String scopeSummary = selectionBounds == null ? describeScope(worldKey, radius, player) : describeScope(selectionBounds);
            String actorSummary = describeActors(options.actorNames(), options.excludeActorNames());
            String targetSummary = describeTargetFilters(options.includeTargets(), options.excludeTargets());
            String timeSummary = describeTimeWindow(minimumSeconds, seconds);
            source.sendFeedback(() -> Text.literal(
                "CoreProtect " + operation + " preview: matched=" + preview.matched()
                    + ", scope=" + scopeSummary
                    + ", time=" + timeSummary
                    + actorSummary
                    + targetSummary
            ), false);
            rememberUndo(
                source,
                runtime,
                new UndoSessionService.UndoOperation(
                    restore,
                    true,
                    scopeBounds == null ? worldKey : null,
                    scopeBounds,
                    timeWindow.notBefore(),
                    timeWindow.notAfter(),
                    options.actorNames(),
                    options.excludeActorNames(),
                    options.actionFilter(),
                    options.includeTargets(),
                    options.excludeTargets(),
                    describeUndoOperation(scopeSummary, timeSummary, actorSummary, targetSummary, describeActionFilters(options.actionFilter()))
                )
            );
            return preview.matched() > 0 ? 1 : 0;
        }

        runtime.previews().clear(player);
        RollbackExecutionResult result = runtime.rollback().applyBetween(
            player,
            timeWindow.notBefore(),
            timeWindow.notAfter(),
            scopeBounds == null ? worldKey : null,
            scopeBounds,
            options.actorNames(),
            options.excludeActorNames(),
            restore,
            options.actionFilter(),
            options.includeTargets(),
            options.excludeTargets()
        );
        String operation = restore ? "restore" : "rollback";
        String scopeSummary = selectionBounds == null ? describeScope(worldKey, radius, player) : describeScope(selectionBounds);
        String actorSummary = describeActors(options.actorNames(), options.excludeActorNames());
        String targetSummary = describeTargetFilters(options.includeTargets(), options.excludeTargets());
        String timeSummary = describeTimeWindow(minimumSeconds, seconds);
        rememberUndo(
            source,
            runtime,
            new UndoSessionService.UndoOperation(
                restore,
                false,
                scopeBounds == null ? worldKey : null,
                scopeBounds,
                timeWindow.notBefore(),
                timeWindow.notAfter(),
                options.actorNames(),
                options.excludeActorNames(),
                options.actionFilter(),
                options.includeTargets(),
                options.excludeTargets(),
                describeUndoOperation(scopeSummary, timeSummary, actorSummary, targetSummary, describeActionFilters(options.actionFilter()))
            )
        );
        if (options.silent()) {
            source.sendFeedback(() -> Text.literal("CoreProtect " + operation + ": changed=" + result.changed()), false);
            return result.changed() > 0 ? 1 : 0;
        }

        source.sendFeedback(() -> Text.literal(
            "CoreProtect " + operation + ": scanned=" + result.scanned()
                + ", changed=" + result.changed()
                + ", marked=" + result.marked()
                + ", scope=" + scopeSummary
                + ", time=" + timeSummary
                + actorSummary
                + targetSummary
        ), false);
        if (options.verbose()) {
            String actionSummary = describeActionFilters(options.actionFilter());
            if (!actionSummary.isBlank()) {
                source.sendFeedback(() -> Text.literal("actions=" + actionSummary), false);
            }
        }
        return result.changed() > 0 ? 1 : 0;
    }

    private static int sendPurgeUsage(ServerCommandSource source) {
        if (!CoreProtectPermissions.canUsePurge(source, true)) {
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Usage: /co purge t:<time> [r:#world] [i:target[,..]] [#optimize]"), false);
        source.sendFeedback(() -> Text.literal("Example: /co purge t:30d i:stone,dirt"), false);
        return 1;
    }

    private static int runLegacyPurge(ServerCommandSource source, FabricRuntime runtime, String input) {
        PurgeCommandOptions options = PurgeCommandParser.parse(input);
        if (options.isEmpty() || options.seconds() == null) {
            return sendPurgeUsage(source);
        }
        return runPurge(source, runtime, options.seconds(), options.worldFilter(), options.includeTargets(), options.optimize());
    }

    private static int runPurge(ServerCommandSource source, FabricRuntime runtime, int seconds, String worldFilter, List<String> includeTargets, boolean optimize) {
        if (!CoreProtectPermissions.canUsePurge(source, true)) {
            return 0;
        }

        if (runtime.database() == null) {
            source.sendFeedback(() -> Text.literal("CoreProtect Fabric rewrite is not initialized yet."), false);
            return 0;
        }

        int minimumSeconds = source.getEntity() instanceof ServerPlayerEntity ? PLAYER_PURGE_MIN_SECONDS : CONSOLE_PURGE_MIN_SECONDS;
        if (seconds < minimumSeconds) {
            String minimumLabel = describeDuration(minimumSeconds);
            source.sendFeedback(() -> Text.literal("Purge rejected. Minimum age is " + minimumLabel + " for this command source."), false);
            return 0;
        }

        String worldKey = resolveWorldFilter(source, worldFilter);
        if (worldFilter != null && worldKey == null && !isGlobalWorldFilter(worldFilter)) {
            return 0;
        }

        int deleted = runtime.database().purgeOlderThan(seconds, worldKey, includeTargets);
        String worldLabel = worldKey == null ? "all worlds" : worldKey;
        String includeSummary = includeTargets == null || includeTargets.isEmpty() ? "" : " matching " + String.join(",", includeTargets);
        source.sendFeedback(() -> Text.literal("Purged " + deleted + " events older than " + describeDuration(seconds) + " from " + worldLabel + includeSummary + "."), false);
        if (optimize) {
            if (runtime.database().optimizeStorage()) {
                source.sendFeedback(() -> Text.literal("CoreProtect optimize completed for the MySQL audit table."), false);
            }
            else {
                source.sendFeedback(() -> Text.literal("CoreProtect optimize is only available for MySQL. SQLite purges already reclaim space by default."), false);
            }
        }
        return 1;
    }

    private static int reloadRuntime(ServerCommandSource source, FabricRuntime runtime) {
        if (!CoreProtectPermissions.canUseReload(source, true)) {
            return 0;
        }

        MinecraftServer server = source.getServer();
        try {
            runtime.reload(server);
        }
        catch (RuntimeException exception) {
            CoreProtectFabricMod.LOGGER.error("CoreProtect Fabric reload failed", exception);
            source.sendFeedback(() -> Text.literal("CoreProtect Fabric reload failed. Check the server log for details."), false);
            return 0;
        }

        source.sendFeedback(() -> Text.literal("CoreProtect Fabric configuration reloaded."), false);
        return 1;
    }

    private static int runConsumer(ServerCommandSource source, FabricRuntime runtime, Boolean paused) {
        if (!CoreProtectPermissions.canUseConsumer(source, true)) {
            return 0;
        }

        if (source.getEntity() != null) {
            source.sendFeedback(() -> Text.literal("This command can only be used from the server console."), false);
            return 0;
        }

        if (runtime.database() == null) {
            source.sendFeedback(() -> Text.literal("CoreProtect Fabric rewrite is not initialized yet."), false);
            return 0;
        }

        if (paused != null) {
            runtime.database().setWritesPaused(paused);
            source.sendFeedback(() -> Text.literal(paused ? "CoreProtect consumer paused." : "CoreProtect consumer resumed."), false);
        }

        source.sendFeedback(() -> Text.literal("consumer-paused=" + runtime.database().writesPaused() + ", pending-writes=" + runtime.database().pendingWrites()), false);
        return 1;
    }

    private static int runNetworkDebug(ServerCommandSource source, FabricRuntime runtime, String type) {
        boolean permission = CoreProtectPermissions.canUseNetworking(source, false);
        String[] args = type == null ? new String[] { "network-debug" } : new String[] { "network-debug", type };
        return NetworkDebugCommand.runCommand(source, permission, args);
    }

    private static int sendMigrateUsage(ServerCommandSource source) {
        if (!CoreProtectPermissions.canUseMigrate(source, true)) {
            return 0;
        }

        source.sendFeedback(() -> Text.literal("Usage: /co migrate-db <sqlite|mysql>"), false);
        source.sendFeedback(() -> Text.literal("This command can only be used from the server console."), false);
        return 1;
    }

    private static int runMigrate(ServerCommandSource source, FabricRuntime runtime, String target) {
        if (!CoreProtectPermissions.canUseMigrate(source, true)) {
            return 0;
        }

        if (source.getEntity() != null) {
            source.sendFeedback(() -> Text.literal("This command can only be used from the server console."), false);
            return 0;
        }

        if (runtime.database() == null || runtime.config() == null) {
            source.sendFeedback(() -> Text.literal("CoreProtect Fabric rewrite is not initialized yet."), false);
            return 0;
        }

        if (runtime.database().writesPaused()) {
            source.sendFeedback(() -> Text.literal("CoreProtect consumer is paused. Resume it before running /co migrate-db."), false);
            return 0;
        }
        if (!hasValidDonationKey(runtime)) {
            source.sendFeedback(() -> Text.literal("A valid donation key is required for that command."), false);
            return 0;
        }

        CoreProtectFabricConfig.DatabaseType targetType;
        if ("sqlite".equalsIgnoreCase(target)) {
            targetType = CoreProtectFabricConfig.DatabaseType.SQLITE;
        }
        else if ("mysql".equalsIgnoreCase(target)) {
            targetType = CoreProtectFabricConfig.DatabaseType.MYSQL;
        }
        else {
            return sendMigrateUsage(source);
        }

        if (runtime.database().databaseType() == targetType) {
            source.sendFeedback(() -> Text.literal("CoreProtect is already using " + targetType.id() + "."), false);
            return 0;
        }

        try {
            DatabaseMigrationService.MigrationResult result = DatabaseMigrationService.migrate(source, runtime, targetType, CoreProtectFabricMod.LOGGER);
            source.sendFeedback(() -> Text.literal("Migration summary: copied=" + result.copiedRows() + ", buffered-cutover-writes=" + result.bufferedWrites()), false);
            return 1;
        }
        catch (RuntimeException exception) {
            CoreProtectFabricMod.LOGGER.error("CoreProtect Fabric migrate-db failed", exception);
            source.sendFeedback(() -> Text.literal("CoreProtect migrate-db failed. Check the server log for details."), false);
            source.sendFeedback(() -> Text.literal(exception.getMessage()), false);
            return 0;
        }
    }

    private static int runUndo(ServerCommandSource source, FabricRuntime runtime) {
        if (!CoreProtectPermissions.canUseUndo(source, true)) {
            return 0;
        }

        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        UndoSessionService.UndoOperation operation = runtime.undoSessions().consume(lookupSessionKey(source));
        if (operation == null) {
            source.sendFeedback(() -> Text.literal("No rollback or restore is available to undo."), false);
            return 0;
        }

        if (operation.preview()) {
            runtime.previews().clear(player);
            source.sendFeedback(() -> Text.literal("Cancelling preview..."), false);
            source.sendFeedback(() -> Text.literal("CoreProtect preview cancelled."), false);
            return 1;
        }

        runtime.previews().clear(player);
        boolean undoRestore = !operation.restore();
        RollbackExecutionResult result = runtime.rollback().applyBetween(
            player,
            operation.notBefore(),
            operation.notAfter(),
            operation.worldKey(),
            operation.bounds(),
            operation.actorNames(),
            operation.excludeActorNames(),
            undoRestore,
            operation.actionFilter(),
            operation.includeTargets(),
            operation.excludeTargets()
        );
        if (result.scanned() == 0) {
            String previousOperation = operation.restore() ? "restore" : "rollback";
            source.sendFeedback(() -> Text.literal("CoreProtect undo: no matching events remained from your last " + previousOperation + "."), false);
            return 0;
        }

        rememberUndo(
            source,
            runtime,
            new UndoSessionService.UndoOperation(
                undoRestore,
                false,
                operation.worldKey(),
                operation.bounds(),
                operation.notBefore(),
                operation.notAfter(),
                operation.actorNames(),
                operation.excludeActorNames(),
                operation.actionFilter(),
                operation.includeTargets(),
                operation.excludeTargets(),
                operation.description()
            )
        );
        String previousOperation = operation.restore() ? "restore" : "rollback";
        String currentOperation = undoRestore ? "restore" : "rollback";
        String description = operation.description().isBlank() ? "" : ", " + operation.description();
        source.sendFeedback(() -> Text.literal(
            "CoreProtect undo: last " + previousOperation + " -> " + currentOperation
                + ", scanned=" + result.scanned()
                + ", changed=" + result.changed()
                + ", marked=" + result.marked()
                + description
        ), false);
        return result.changed() > 0 ? 1 : 0;
    }

    private static int runApply(ServerCommandSource source, FabricRuntime runtime) {
        if (!CoreProtectPermissions.canUseApplyCancel(source, true)) {
            return 0;
        }

        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        String sessionKey = lookupSessionKey(source);
        UndoSessionService.UndoOperation operation = runtime.undoSessions().get(sessionKey);
        if (operation == null || !operation.preview()) {
            source.sendFeedback(() -> Text.literal("No rollback or restore preview is available to apply."), false);
            return 0;
        }

        runtime.previews().clear(player);
        runtime.undoSessions().clear(sessionKey);
        RollbackExecutionResult result = runtime.rollback().applyBetween(
            player,
            operation.notBefore(),
            operation.notAfter(),
            operation.worldKey(),
            operation.bounds(),
            operation.actorNames(),
            operation.excludeActorNames(),
            operation.restore(),
            operation.actionFilter(),
            operation.includeTargets(),
            operation.excludeTargets()
        );

        rememberUndo(
            source,
            runtime,
            new UndoSessionService.UndoOperation(
                operation.restore(),
                false,
                operation.worldKey(),
                operation.bounds(),
                operation.notBefore(),
                operation.notAfter(),
                operation.actorNames(),
                operation.excludeActorNames(),
                operation.actionFilter(),
                operation.includeTargets(),
                operation.excludeTargets(),
                operation.description()
            )
        );

        String action = operation.restore() ? "restore" : "rollback";
        source.sendFeedback(() -> Text.literal(
            "CoreProtect " + action + ": scanned=" + result.scanned()
                + ", changed=" + result.changed()
                + ", marked=" + result.marked()
                + (operation.description().isBlank() ? "" : ", " + operation.description())
        ), false);
        return result.changed() > 0 ? 1 : 0;
    }

    private static int runCancel(ServerCommandSource source, FabricRuntime runtime) {
        if (!CoreProtectPermissions.canUseApplyCancel(source, true)) {
            return 0;
        }

        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        String sessionKey = lookupSessionKey(source);
        UndoSessionService.UndoOperation operation = runtime.undoSessions().get(sessionKey);
        if (operation == null || !operation.preview()) {
            source.sendFeedback(() -> Text.literal("No rollback or restore preview is available to cancel."), false);
            return 0;
        }

        runtime.previews().clear(player);
        runtime.undoSessions().clear(sessionKey);
        source.sendFeedback(() -> Text.literal("Cancelling preview..."), false);
        source.sendFeedback(() -> Text.literal("CoreProtect preview cancelled."), false);
        return 1;
    }

    private static String resolveWorldFilter(ServerCommandSource source, String rawFilter) {
        if (rawFilter == null || rawFilter.isBlank()) {
            return null;
        }

        String cleaned = rawFilter.trim();
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isBlank() || isGlobalWorldFilter(cleaned)) {
            return null;
        }

        String vanillaAlias = resolveVanillaWorldAlias(cleaned);
        if (vanillaAlias != null) {
            return vanillaAlias;
        }

        MinecraftServer server = source.getServer();
        Identifier directIdentifier = Identifier.tryParse(cleaned);
        if (directIdentifier != null && server.getWorld(RegistryKey.of(RegistryKeys.WORLD, directIdentifier)) != null) {
            return directIdentifier.toString();
        }

        Identifier minecraftIdentifier = Identifier.tryParse("minecraft:" + cleaned);
        if (minecraftIdentifier != null && server.getWorld(RegistryKey.of(RegistryKeys.WORLD, minecraftIdentifier)) != null) {
            return minecraftIdentifier.toString();
        }

        source.sendFeedback(() -> Text.literal("Unknown world filter: " + rawFilter), false);
        return null;
    }

    private static String resolveVanillaWorldAlias(String worldName) {
        return switch (worldName.toLowerCase(Locale.ROOT)) {
            case "overworld", "world" -> World.OVERWORLD.getValue().toString();
            case "nether", "the_nether", "world_nether" -> World.NETHER.getValue().toString();
            case "end", "the_end", "world_the_end" -> World.END.getValue().toString();
            default -> null;
        };
    }

    private static boolean isGlobalWorldFilter(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1);
        }
        return "global".equalsIgnoreCase(cleaned);
    }

    private static boolean isWorldEditWorldFilter(String value) {
        String cleaned = value == null ? "" : value.trim();
        if (cleaned.startsWith("#")) {
            cleaned = cleaned.substring(1);
        }
        return "worldedit".equalsIgnoreCase(cleaned) || "we".equalsIgnoreCase(cleaned);
    }

    private static String describeScope(String worldKey, Integer radius, ServerPlayerEntity player) {
        if (radius != null && player != null) {
            ServerWorld world = (ServerWorld) player.getEntityWorld();
            return world.getRegistryKey().getValue() + "@r=" + radius;
        }
        if (worldKey != null && !worldKey.isBlank()) {
            return worldKey;
        }
        return "global";
    }

    private static String describeScope(QueryBounds bounds) {
        BlockPos minimum = bounds.minimum();
        BlockPos maximum = bounds.maximum();
        return "worldedit@" + bounds.worldKey()
            + " " + minimum.getX() + "," + minimum.getY() + "," + minimum.getZ()
            + "->"
            + maximum.getX() + "," + maximum.getY() + "," + maximum.getZ();
    }

    private static void rememberUndo(ServerCommandSource source, FabricRuntime runtime, UndoSessionService.UndoOperation operation) {
        String sessionKey = lookupSessionKey(source);
        runtime.undoSessions().clear(sessionKey);
        if (operation == null) {
            return;
        }
        runtime.undoSessions().remember(sessionKey, operation);
    }

    private static String describeUndoOperation(String scopeSummary, String timeSummary, String actorSummary, String targetSummary, String actionSummary) {
        StringBuilder builder = new StringBuilder();
        builder.append("scope=").append(scopeSummary).append(", time=").append(timeSummary);
        if (actorSummary != null && !actorSummary.isBlank()) {
            builder.append(actorSummary);
        }
        if (targetSummary != null && !targetSummary.isBlank()) {
            builder.append(targetSummary);
        }
        if (actionSummary != null && !actionSummary.isBlank()) {
            builder.append(", actions=").append(actionSummary);
        }
        return builder.toString();
    }

    private static String describeActors(List<String> actorNames, List<String> excludeActorNames) {
        StringBuilder builder = new StringBuilder();
        if (actorNames != null && !actorNames.isEmpty()) {
            builder.append(", actor=").append(String.join(",", actorNames));
        }
        if (excludeActorNames != null && !excludeActorNames.isEmpty()) {
            builder.append(", exclude-user=").append(String.join(",", excludeActorNames));
        }
        return builder.toString();
    }

    private static String describeActionFilters(List<CoreProtectEventType> actionFilter) {
        if (actionFilter == null || actionFilter.isEmpty()) {
            return "";
        }

        boolean hasContainer = actionFilter.contains(CoreProtectEventType.CONTAINER_TRANSACTION);
        boolean hasItem = actionFilter.contains(CoreProtectEventType.ITEM_PICKUP)
            || actionFilter.contains(CoreProtectEventType.ITEM_DROP)
            || actionFilter.contains(CoreProtectEventType.ITEM_THROW)
            || actionFilter.contains(CoreProtectEventType.ITEM_SHOOT)
            || actionFilter.contains(CoreProtectEventType.ITEM_BUY)
            || actionFilter.contains(CoreProtectEventType.ITEM_SELL)
            || actionFilter.contains(CoreProtectEventType.ITEM_CREATE)
            || actionFilter.contains(CoreProtectEventType.ITEM_DESTROY);
        StringBuilder builder = new StringBuilder();
        if (hasContainer && hasItem) {
            builder.append("inventory");
        }
        else if (hasContainer) {
            builder.append("container");
        }
        else if (hasItem) {
            builder.append("item");
        }

        for (CoreProtectEventType eventType : actionFilter) {
            if (eventType == CoreProtectEventType.CONTAINER_TRANSACTION
                || eventType == CoreProtectEventType.ITEM_PICKUP
                || eventType == CoreProtectEventType.ITEM_DROP
                || eventType == CoreProtectEventType.ITEM_THROW
                || eventType == CoreProtectEventType.ITEM_SHOOT
                || eventType == CoreProtectEventType.ITEM_BUY
                || eventType == CoreProtectEventType.ITEM_SELL
                || eventType == CoreProtectEventType.ITEM_CREATE
                || eventType == CoreProtectEventType.ITEM_DESTROY) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(",");
            }
            builder.append(eventType.name().toLowerCase(Locale.ROOT));
        }
        return builder.toString();
    }

    private static String describeTargetFilters(List<String> includeTargets, List<String> excludeTargets) {
        StringBuilder builder = new StringBuilder();
        if (includeTargets != null && !includeTargets.isEmpty()) {
            builder.append(", include=").append(String.join(",", includeTargets));
        }
        if (excludeTargets != null && !excludeTargets.isEmpty()) {
            builder.append(", exclude=").append(String.join(",", excludeTargets));
        }
        return builder.toString();
    }

    private static String describeTimeWindow(int minimumSeconds, int maximumSeconds) {
        if (minimumSeconds <= 0) {
            return describeDuration(maximumSeconds);
        }
        return describeDuration(minimumSeconds) + "-" + describeDuration(maximumSeconds);
    }

    private static QueryBounds createRadiusBounds(ServerPlayerEntity player, int radius) {
        BlockPos center = player.getBlockPos();
        String worldKey = ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString();
        return new QueryBounds(
            worldKey,
            new BlockPos(center.getX() - radius, center.getY() - radius, center.getZ() - radius),
            new BlockPos(center.getX() + radius, center.getY() + radius, center.getZ() + radius)
        );
    }

    private static TimeWindow fixedTimeWindow(int minimumSeconds, int maximumSeconds) {
        long now = System.currentTimeMillis();
        return new TimeWindow(
            now - (Math.max(minimumSeconds, maximumSeconds) * 1000L),
            now - (Math.min(minimumSeconds, maximumSeconds) * 1000L)
        );
    }

    private static LookupPageRequest parseLookupPageRequest(String input) {
        if (input == null) {
            return null;
        }

        String cleaned = input.trim();
        if (cleaned.regionMatches(true, 0, "page:", 0, "page:".length())) {
            cleaned = cleaned.substring("page:".length()).trim();
        }
        if (cleaned.isEmpty()) {
            return null;
        }

        String[] parts = cleaned.split(":", -1);
        if (parts.length == 0 || parts.length > 2) {
            return null;
        }
        if (!parts[0].matches("\\d+")) {
            return null;
        }

        int page = Integer.parseInt(parts[0]);
        Integer linesPerPage = null;
        if (parts.length == 2) {
            if (!parts[1].matches("\\d+")) {
                return null;
            }
            linesPerPage = Integer.parseInt(parts[1]);
        }
        return new LookupPageRequest(page, linesPerPage);
    }

    private static TeleportRequest parseTeleportRequest(ServerCommandSource source, ServerPlayerEntity player, String input) {
        if (input == null) {
            return null;
        }

        String cleaned = input.trim();
        if (cleaned.isEmpty()) {
            return null;
        }

        String[] tokens = cleaned.split("\\s+");
        if (tokens.length < 2 || tokens.length > 4) {
            return null;
        }

        ServerWorld world = (ServerWorld) player.getEntityWorld();
        int startIndex = 0;
        if (tokens.length >= 3) {
            ServerWorld parsedWorld = resolveTeleportWorld(source.getServer(), tokens[0]);
            if (parsedWorld != null) {
                world = parsedWorld;
                startIndex = 1;
            }
            else if (tokens.length == 4) {
                source.sendFeedback(() -> Text.literal("Unknown world: " + tokens[0]), false);
                return null;
            }
        }

        int coordinateCount = tokens.length - startIndex;
        if (coordinateCount < 2 || coordinateCount > 3) {
            return null;
        }

        Double x = parseCoordinate(tokens[startIndex]);
        if (x == null) {
            return null;
        }

        boolean explicitY = coordinateCount == 3;
        Double y = explicitY ? parseCoordinate(tokens[startIndex + 1]) : player.getY();
        Double z = parseCoordinate(tokens[startIndex + (explicitY ? 2 : 1)]);
        if (y == null || z == null) {
            return null;
        }

        return new TeleportRequest(world, x, y, z, explicitY);
    }

    private static ServerWorld resolveTeleportWorld(MinecraftServer server, String rawWorld) {
        if (rawWorld == null || rawWorld.isBlank()) {
            return null;
        }

        String cleaned = rawWorld.trim();
        String vanillaAlias = resolveVanillaWorldAlias(cleaned);
        if (vanillaAlias != null) {
            return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, Identifier.of(vanillaAlias)));
        }

        Identifier directIdentifier = Identifier.tryParse(cleaned);
        if (directIdentifier != null) {
            ServerWorld directWorld = server.getWorld(RegistryKey.of(RegistryKeys.WORLD, directIdentifier));
            if (directWorld != null) {
                return directWorld;
            }
        }

        Identifier minecraftIdentifier = Identifier.tryParse("minecraft:" + cleaned);
        if (minecraftIdentifier != null) {
            return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, minecraftIdentifier));
        }

        return null;
    }

    private static Double parseCoordinate(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }

        try {
            return Double.parseDouble(input);
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private static String formatCoordinate(double value) {
        long whole = (long) value;
        return Double.compare(value, whole) == 0 ? Long.toString(whole) : Double.toString(value);
    }

    private static boolean hasValidDonationKey(FabricRuntime runtime) {
        if (runtime == null || runtime.config() == null) {
            return false;
        }

        String donationKey = runtime.config().donationKey();
        return donationKey != null && !donationKey.trim().isEmpty();
    }

    private static void sendLookupNetworkData(ServerCommandSource source, List<StoredEventRecord> events) {
        LookupNetworkingService.send(source, events);
    }
    private static String lookupSessionKey(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity player) {
            return player.getUuidAsString();
        }
        return "console";
    }

    private static final class LookupPageRequest {
        private final int page;
        private final Integer linesPerPage;

        private LookupPageRequest(int page, Integer linesPerPage) {
            this.page = page;
            this.linesPerPage = linesPerPage;
        }

        private int page() {
            return page;
        }

        private Integer linesPerPage() {
            return linesPerPage;
        }
    }

    private static final class TeleportRequest {
        private final ServerWorld world;
        private final double x;
        private final double y;
        private final double z;
        private final boolean explicitY;

        private TeleportRequest(ServerWorld world, double x, double y, double z, boolean explicitY) {
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.explicitY = explicitY;
        }

        private ServerWorld world() {
            return world;
        }

        private double x() {
            return x;
        }

        private double y() {
            return y;
        }

        private double z() {
            return z;
        }

        private boolean explicitY() {
            return explicitY;
        }
    }

    private static final class TimeWindow {
        private final long notBefore;
        private final long notAfter;

        private TimeWindow(long notBefore, long notAfter) {
            this.notBefore = notBefore;
            this.notAfter = notAfter;
        }

        private long notBefore() {
            return notBefore;
        }

        private long notAfter() {
            return notAfter;
        }
    }

    private static String describeDuration(int seconds) {
        if (seconds % 31536000 == 0) {
            return (seconds / 31536000) + "y";
        }
        if (seconds % 2592000 == 0) {
            return (seconds / 2592000) + "mo";
        }
        if (seconds % 604800 == 0) {
            return (seconds / 604800) + "w";
        }
        if (seconds % 86400 == 0) {
            return (seconds / 86400) + "d";
        }
        if (seconds % 3600 == 0) {
            return (seconds / 3600) + "h";
        }
        if (seconds % 60 == 0) {
            return (seconds / 60) + "m";
        }
        return seconds + "s";
    }

    private static ServerPlayerEntity requirePlayer(ServerCommandSource source) {
        if (source.getEntity() instanceof ServerPlayerEntity) {
            return (ServerPlayerEntity) source.getEntity();
        }

        source.sendFeedback(() -> Text.literal("This command can only be used in-game."), false);
        return null;
    }

    private static int lookupTargetedBlockHistory(ServerCommandSource source, FabricRuntime runtime, int limit, List<CoreProtectEventType> eventTypes, String title, String emptyMessage) {
        ServerPlayerEntity player = requirePlayer(source);
        if (player == null) {
            return 0;
        }

        sendLines(source, runtime.lookup().describeTargetedBlockHistory(player, limit, eventTypes, title, emptyMessage));
        LookupNetworkingService.send(source, runtime.lookup().getTargetedBlockHistory(player, limit, eventTypes));
        return 1;
    }

    private static void sendLines(ServerCommandSource source, List<Text> lines) {
        for (Text line : lines) {
            source.sendFeedback(() -> line, false);
        }
    }
}
