package net.coreprotect.fabric.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.language.PhraseService;
import net.coreprotect.fabric.listener.channel.PluginChannelListener;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.permission.CoreProtectPermissions;
import net.coreprotect.language.Phrase;
import net.coreprotect.language.Selector;
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
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.BrushableBlockEntity;
import net.minecraft.block.entity.ChiseledBookshelfBlockEntity;
import net.minecraft.block.entity.DecoratedPotBlockEntity;
import net.minecraft.block.entity.JukeboxBlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CoreProtectCommands {
    private static final String COMMAND_PREFIX = "CoreProtect - ";
    private static final int DEFAULT_LOOKUP_LIMIT = 10;
    private static final int DEFAULT_LOOKUP_RADIUS = 5;
    private static final int DEFAULT_LOOKUP_SECONDS = 3600;
    private static final int DEFAULT_ROLLBACK_RADIUS = 10;
    private static final int DEFAULT_MAX_RADIUS = 100;
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
            .requires(CoreProtectPermissions::canAccessCoreProtectCommand)
            .executes(context -> sendHelp(context.getSource(), null))
            .then(buildHelpCommand())
            .then(buildStatusCommand("status", runtime))
            .then(buildStatusCommand("stats", runtime))
            .then(buildStatusCommand("version", runtime))
            .then(buildInspectCommand("inspect", runtime))
            .then(buildInspectCommand("i", runtime))
            .then(buildLookupCommand("lookup", runtime))
            .then(buildLookupCommand("l", runtime))
            .then(buildPageCommand(runtime))
            .then(CommandManager.literal("near")
                .requires(source -> CoreProtectPermissions.canLookupNearby(source, null, false))
                .executes(context -> runLegacyLookup(context.getSource(), runtime, "r:5x5")))
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
            .requires(source -> CoreProtectPermissions.canUseHelp(source, false))
            .executes(context -> sendHelp(context.getSource(), null))
            .then(CommandManager.argument("topic", StringArgumentType.word())
                .executes(context -> sendHelp(context.getSource(), StringArgumentType.getString(context, "topic"))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildStatusCommand(String literal, FabricRuntime runtime) {
        return CommandManager.literal(literal)
            .requires(source -> CoreProtectPermissions.canUseStatus(source, false))
            .executes(context -> sendStatus(context.getSource(), runtime));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildInspectCommand(String literal, FabricRuntime runtime) {
        return CommandManager.literal(literal)
            .requires(source -> CoreProtectPermissions.canUseInspect(source, false))
            .executes(context -> toggleInspect(context.getSource(), runtime, null))
            .then(CommandManager.literal("on")
                .executes(context -> toggleInspect(context.getSource(), runtime, true)))
            .then(CommandManager.literal("off")
                .executes(context -> toggleInspect(context.getSource(), runtime, false)));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildLookupCommand(String literal, FabricRuntime runtime) {
        LiteralArgumentBuilder<ServerCommandSource> builder = CommandManager.literal(literal)
            .requires(source -> CoreProtectPermissions.canUseLookupPagination(source, false))
            .executes(context -> {
                sendCoreProtectPhrase(context.getSource(), Phrase.MISSING_PARAMETERS, "/co l <params>");
                return 0;
            })
            .then(CommandManager.argument("legacy", StringArgumentType.greedyString())
                .executes(context -> runLegacyLookup(context.getSource(), runtime, StringArgumentType.getString(context, "legacy"))));
        attachStructuredParameters(builder, StructuredParameterSupport.CommandKind.LOOKUP, (source, input) -> runLegacyLookup(source, runtime, input));
        return builder;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildNearbyLookupCommand(String literal, FabricRuntime runtime, List<CoreProtectEventType> actionFilter) {
        return CommandManager.literal(literal)
            .requires(source -> CoreProtectPermissions.canLookupNearby(source, actionFilter, false))
            .executes(context -> lookupHere(context.getSource(), runtime, DEFAULT_LOOKUP_RADIUS, DEFAULT_LOOKUP_SECONDS, DEFAULT_LOOKUP_LIMIT, null, actionFilter))
            .then(CommandManager.argument("radius", IntegerArgumentType.integer(0, configuredMaxRadius(runtime)))
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
            .requires(source -> CoreProtectPermissions.canUseLookupPagination(source, false))
            .executes(context -> sendPageUsage(context.getSource()))
            .then(CommandManager.argument("page", StringArgumentType.word())
                .executes(context -> runPageAlias(context.getSource(), runtime, StringArgumentType.getString(context, "page"))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildTeleportCommand(String literal) {
        return CommandManager.literal(literal)
            .requires(source -> CoreProtectPermissions.canUseTeleport(source, false))
            .executes(context -> sendTeleportUsage(context.getSource()))
            .then(CommandManager.argument("args", StringArgumentType.greedyString())
                .executes(context -> runTeleport(context.getSource(), StringArgumentType.getString(context, "args"))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildRollbackCommand(String literal, FabricRuntime runtime, boolean restore) {
        LiteralArgumentBuilder<ServerCommandSource> builder = CommandManager.literal(literal)
            .requires(source -> CoreProtectPermissions.canRunRollback(source, restore, false))
            .executes(context -> {
                sendCoreProtectPhrase(
                    context.getSource(),
                    Phrase.MISSING_PARAMETERS,
                    restore ? "/co restore <params>" : "/co rollback <params>"
                );
                return 0;
            })
            .then(CommandManager.argument("legacy", StringArgumentType.greedyString())
                .executes(context -> runLegacyRollback(context.getSource(), runtime, restore, StringArgumentType.getString(context, "legacy"))));
        attachStructuredParameters(builder, StructuredParameterSupport.CommandKind.ROLLBACK, (source, input) -> runLegacyRollback(source, runtime, restore, input));
        return builder;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildPurgeCommand(FabricRuntime runtime) {
        LiteralArgumentBuilder<ServerCommandSource> builder = CommandManager.literal("purge")
            .requires(source -> CoreProtectPermissions.canUsePurge(source, false))
            .executes(context -> sendPurgeUsage(context.getSource()))
            .then(CommandManager.argument("legacy", StringArgumentType.greedyString())
                .executes(context -> runLegacyPurge(context.getSource(), runtime, StringArgumentType.getString(context, "legacy"))));
        attachStructuredParameters(builder, StructuredParameterSupport.CommandKind.PURGE, (source, input) -> runLegacyPurge(source, runtime, input));
        return builder;
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildReloadCommand(FabricRuntime runtime) {
        return CommandManager.literal("reload")
            .requires(source -> CoreProtectPermissions.canUseReload(source, false))
            .executes(context -> reloadRuntime(context.getSource(), runtime));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildConsumerCommand(FabricRuntime runtime) {
        return CommandManager.literal("consumer")
            .requires(source -> CoreProtectPermissions.canUseConsumer(source, false))
            .executes(context -> sendConsumerUsage(context.getSource()))
            .then(CommandManager.literal("pause")
                .executes(context -> runConsumer(context.getSource(), runtime, true)))
            .then(CommandManager.literal("disable")
                .executes(context -> runConsumer(context.getSource(), runtime, true)))
            .then(CommandManager.literal("stop")
                .executes(context -> runConsumer(context.getSource(), runtime, true)))
            .then(CommandManager.literal("resume")
                .executes(context -> runConsumer(context.getSource(), runtime, false)))
            .then(CommandManager.literal("enable")
                .executes(context -> runConsumer(context.getSource(), runtime, false)))
            .then(CommandManager.literal("start")
                .executes(context -> runConsumer(context.getSource(), runtime, false)))
            .then(CommandManager.argument("legacy", StringArgumentType.greedyString())
                .executes(context -> sendConsumerUsage(context.getSource())));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildUndoCommand(FabricRuntime runtime) {
        return CommandManager.literal("undo")
            .requires(source -> CoreProtectPermissions.canUseUndo(source, false))
            .executes(context -> runUndo(context.getSource(), runtime));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildApplyCommand(FabricRuntime runtime) {
        return CommandManager.literal("apply")
            .requires(source -> CoreProtectPermissions.canUseApplyCancel(source, false))
            .executes(context -> runApply(context.getSource(), runtime));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildCancelCommand(FabricRuntime runtime) {
        return CommandManager.literal("cancel")
            .requires(source -> CoreProtectPermissions.canUseApplyCancel(source, false))
            .executes(context -> runCancel(context.getSource(), runtime));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildMigrateCommand(FabricRuntime runtime) {
        return CommandManager.literal("migrate-db")
            .requires(source -> CoreProtectPermissions.canUseMigrate(source, false))
            .executes(context -> sendMigrateUsage(context.getSource()))
            .then(CommandManager.argument("database", StringArgumentType.word())
                .executes(context -> runMigrate(context.getSource(), runtime, StringArgumentType.getString(context, "database"))));
    }

    private static LiteralArgumentBuilder<ServerCommandSource> buildNetworkDebugCommand(FabricRuntime runtime) {
        return CommandManager.literal("network-debug")
            .requires(source -> CoreProtectPermissions.canUseNetworking(source, false))
            .executes(context -> runNetworkDebug(context.getSource(), runtime, null))
            .then(CommandManager.argument("type", StringArgumentType.word())
                .executes(context -> runNetworkDebug(context.getSource(), runtime, StringArgumentType.getString(context, "type"))));
    }

    @FunctionalInterface
    private interface StructuredParamExecutor {
        int execute(ServerCommandSource source, String input);
    }

    private static void attachStructuredParameters(
        LiteralArgumentBuilder<ServerCommandSource> builder,
        StructuredParameterSupport.CommandKind kind,
        StructuredParamExecutor executor
    ) {
        builder.then(buildStructuredParameterNode(kind, 1, executor));
    }

    private static RequiredArgumentBuilder<ServerCommandSource, String> buildStructuredParameterNode(
        StructuredParameterSupport.CommandKind kind,
        int index,
        StructuredParamExecutor executor
    ) {
        RequiredArgumentBuilder<ServerCommandSource, String> node = CommandManager.argument(StructuredParameterSupport.paramName(index), StringArgumentType.word())
            .suggests((context, suggestionsBuilder) -> StructuredParameterSupport.suggest(kind, context, suggestionsBuilder))
            .executes(context -> executor.execute(context.getSource(), StructuredParameterSupport.joinArguments(context, index)));

        if (index < StructuredParameterSupport.MAX_PARAMS) {
            node.then(buildStructuredParameterNode(kind, index + 1, executor));
        }
        return node;
    }

    private static int sendHelp(ServerCommandSource source, String topic) {
        if (!CoreProtectPermissions.canUseHelp(source, true)) {
            return 0;
        }

        String originalTopic = topic == null ? "" : topic;
        String normalizedTopic = originalTopic.toLowerCase(Locale.ROOT).replaceAll("[^a-zA-Z]", "");
        sendHeader(source, Phrase.build(Phrase.HELP_HEADER, "CoreProtect"));

        if (normalizedTopic.isBlank()) {
            sendUsageLine(source, "/co help <command>", Phrase.build(Phrase.HELP_COMMAND));
            sendUsageLine(source, "/co inspect", Phrase.build(Phrase.HELP_INSPECT_COMMAND));
            sendUsageLine(source, "/co rollback <params>", Phrase.build(Phrase.HELP_ROLLBACK_COMMAND));
            sendUsageLine(source, "/co restore <params>", Phrase.build(Phrase.HELP_RESTORE_COMMAND));
            sendUsageLine(source, "/co lookup <params>", Phrase.build(Phrase.HELP_LOOKUP_COMMAND));
            sendUsageLine(source, "/co purge <params>", Phrase.build(Phrase.HELP_PURGE_COMMAND));
            sendUsageLine(source, "/co reload", Phrase.build(Phrase.HELP_RELOAD_COMMAND));
            sendUsageLine(source, "/co status", Phrase.build(Phrase.HELP_STATUS_COMMAND));
            return 1;
        }

        switch (normalizedTopic) {
            case "help":
                sendUsageLine(source, "/co help", Phrase.build(Phrase.HELP_LIST));
                return 1;
            case "inspect":
            case "inspector":
            case "in":
                sendLine(source, Phrase.build(Phrase.HELP_INSPECT_1));
                sendLine(source, "* " + Phrase.build(Phrase.HELP_INSPECT_2));
                sendLine(source, "* " + Phrase.build(Phrase.HELP_INSPECT_3));
                sendLine(source, "* " + Phrase.build(Phrase.HELP_INSPECT_4));
                sendLine(source, "* " + Phrase.build(Phrase.HELP_INSPECT_5));
                sendLine(source, "* " + Phrase.build(Phrase.HELP_INSPECT_6));
                sendLine(source, Phrase.build(Phrase.HELP_INSPECT_7));
                return 1;
            case "params":
            case "param":
            case "parameters":
            case "parameter":
                sendUsageLine(source, "/co lookup <params>", Phrase.build(Phrase.HELP_PARAMS_1, Selector.FIRST));
                sendUsageLine(source, "u:<users>", Phrase.build(Phrase.HELP_PARAMS_2, Selector.FIRST));
                sendUsageLine(source, "t:<time>", Phrase.build(Phrase.HELP_PARAMS_3, Selector.FIRST));
                sendUsageLine(source, "r:<radius>", Phrase.build(Phrase.HELP_PARAMS_4, Selector.FIRST));
                sendUsageLine(source, "a:<action>", Phrase.build(Phrase.HELP_PARAMS_5, Selector.FIRST));
                sendUsageLine(source, "i:<include>", Phrase.build(Phrase.HELP_PARAMS_6, Selector.FIRST));
                sendUsageLine(source, "e:<exclude>", Phrase.build(Phrase.HELP_PARAMS_7, Selector.FIRST));
                sendLine(source, Phrase.build(Phrase.HELP_PARAMETER, "/co help <param>"));
                return 1;
            case "rollback":
            case "rollbacks":
            case "rb":
            case "ro":
                sendUsageLine(source, "/co rollback <params>", Phrase.build(Phrase.HELP_PARAMS_1, Selector.SECOND));
                sendUsageLine(source, "u:<users>", Phrase.build(Phrase.HELP_PARAMS_2, Selector.SECOND));
                sendUsageLine(source, "t:<time>", Phrase.build(Phrase.HELP_PARAMS_3, Selector.SECOND));
                sendUsageLine(source, "r:<radius>", Phrase.build(Phrase.HELP_PARAMS_4, Selector.SECOND));
                sendUsageLine(source, "a:<action>", Phrase.build(Phrase.HELP_PARAMS_5, Selector.SECOND));
                sendUsageLine(source, "i:<include>", Phrase.build(Phrase.HELP_PARAMS_6, Selector.SECOND));
                sendUsageLine(source, "e:<exclude>", Phrase.build(Phrase.HELP_PARAMS_7, Selector.SECOND));
                sendLine(source, Phrase.build(Phrase.HELP_PARAMETER, "/co help <param>"));
                return 1;
            case "restore":
            case "restores":
            case "re":
            case "rs":
                sendUsageLine(source, "/co restore <params>", Phrase.build(Phrase.HELP_PARAMS_1, Selector.THIRD));
                sendUsageLine(source, "u:<users>", Phrase.build(Phrase.HELP_PARAMS_2, Selector.THIRD));
                sendUsageLine(source, "t:<time>", Phrase.build(Phrase.HELP_PARAMS_3, Selector.THIRD));
                sendUsageLine(source, "r:<radius>", Phrase.build(Phrase.HELP_PARAMS_4, Selector.THIRD));
                sendUsageLine(source, "a:<action>", Phrase.build(Phrase.HELP_PARAMS_5, Selector.THIRD));
                sendUsageLine(source, "i:<include>", Phrase.build(Phrase.HELP_PARAMS_6, Selector.THIRD));
                sendUsageLine(source, "e:<exclude>", Phrase.build(Phrase.HELP_PARAMS_7, Selector.THIRD));
                sendLine(source, Phrase.build(Phrase.HELP_PARAMETER, "/co help <param>"));
                return 1;
            case "lookup":
            case "lookups":
            case "l":
                sendLine(source, "/co lookup <params>");
                sendUsageLine(source, "/co l <params>", Phrase.build(Phrase.HELP_LOOKUP_1));
                sendUsageLine(source, "/co lookup <page>", Phrase.build(Phrase.HELP_LOOKUP_2));
                sendLine(source, Phrase.build(Phrase.HELP_PARAMETER, "/co help params"));
                return 1;
            case "purge":
            case "purges":
                sendUsageLine(source, "/co purge t:<time>", Phrase.build(Phrase.HELP_PURGE_1));
                sendLine(source, Phrase.build(Phrase.HELP_PURGE_2, "/co purge t:30d"));
                return 1;
            case "reload":
                sendUsageLine(source, "/co reload", Phrase.build(Phrase.HELP_RELOAD_COMMAND));
                return 1;
            case "status":
                sendUsageLine(source, "/co status", Phrase.build(Phrase.HELP_STATUS));
                return 1;
            case "teleport":
                sendUsageLine(source, "/co teleport <world> <x> <y> <z>", Phrase.build(Phrase.HELP_TELEPORT));
                return 1;
            case "u":
            case "user":
            case "users":
            case "uuser":
            case "uusers":
                sendUsageLine(source, "/co lookup u:<users>", Phrase.build(Phrase.HELP_USER_1));
                sendLine(source, Phrase.build(Phrase.HELP_USER_2));
                return 1;
            case "t":
            case "time":
            case "ttime":
                sendUsageLine(source, "/co lookup t:<time>", Phrase.build(Phrase.HELP_TIME_1));
                sendLine(source, Phrase.build(Phrase.HELP_TIME_2));
                return 1;
            case "r":
            case "radius":
            case "rradius":
                sendUsageLine(source, "/co lookup r:<radius>", Phrase.build(Phrase.HELP_RADIUS_1));
                sendLine(source, Phrase.build(Phrase.HELP_RADIUS_2));
                return 1;
            case "a":
            case "action":
            case "actions":
            case "aaction":
                sendUsageLine(source, "/co lookup a:<action>", Phrase.build(Phrase.HELP_ACTION_1));
                sendLine(source, Phrase.build(Phrase.HELP_ACTION_2));
                return 1;
            case "i":
            case "include":
            case "iinclude":
            case "b":
            case "block":
            case "blocks":
            case "bblock":
            case "bblocks":
                sendUsageLine(source, "/co lookup i:<include>", Phrase.build(Phrase.HELP_INCLUDE_1));
                sendLine(source, Phrase.build(Phrase.HELP_INCLUDE_2));
                sendLine(source, Phrase.build(Phrase.LINK_WIKI_BLOCK, "https://coreprotect.net/wiki-blocks"));
                sendLine(source, Phrase.build(Phrase.LINK_WIKI_ENTITY, "https://coreprotect.net/wiki-entities"));
                return 1;
            case "e":
            case "exclude":
            case "eexclude":
                sendUsageLine(source, "/co lookup e:<exclude>", Phrase.build(Phrase.HELP_EXCLUDE_1));
                sendLine(source, Phrase.build(Phrase.HELP_EXCLUDE_2));
                sendLine(source, Phrase.build(Phrase.LINK_WIKI_BLOCK, "https://coreprotect.net/wiki-blocks"));
                return 1;
            default:
                sendLine(source, Phrase.build(Phrase.HELP_NO_INFO, "/co help " + originalTopic));
                return 1;
        }
    }

    private static void sendHeader(ServerCommandSource source, String title) {
        source.sendFeedback(() -> CoreProtectText.header(title), false);
    }

    private static void sendCoreProtectMessage(ServerCommandSource source, String message) {
        source.sendFeedback(() -> CoreProtectText.prefixed(message), false);
    }

    private static void sendCoreProtectPhrase(ServerCommandSource source, Phrase phrase, String... params) {
        sendCoreProtectMessage(source, Phrase.build(phrase, params));
    }

    private static void sendLocalizedMessage(ServerCommandSource source, String key, String fallback, Object... args) {
        sendCoreProtectMessage(source, PhraseService.getInstance().phrase(key, fallback, args));
    }

    private static void sendLine(ServerCommandSource source, String line) {
        source.sendFeedback(() -> CoreProtectText.line(line), false);
    }

    private static void sendUsageLine(ServerCommandSource source, String usage, String description) {
        source.sendFeedback(() -> CoreProtectText.usage(usage, description), false);
    }

    private static Text notInitializedText() {
        return CoreProtectText.prefixed(PhraseService.getInstance().phrase("fabric.runtime.not_initialized", "CoreProtect is not initialized."));
    }

    private static String databaseBackendLabel(FabricRuntime runtime) {
        return runtime.database().databaseType() == CoreProtectFabricConfig.DatabaseType.MYSQL ? "MySQL" : "SQLite";
    }

    private static String licenseStatusLabel(FabricRuntime runtime) {
        if (runtime.config() == null) {
            return "unknown";
        }
        return hasValidDonationKey(runtime) ? "valid donation key" : "invalid donation key";
    }

    private static int sendStatus(ServerCommandSource source, FabricRuntime runtime) {
        if (!CoreProtectPermissions.canUseStatus(source, true)) {
            return 0;
        }

        if (runtime.database() == null) {
            source.sendFeedback(CoreProtectCommands::notInitializedText, false);
            return 0;
        }

        sendHeader(source, "CoreProtect");

        String currentVersion = runtime.updates() == null ? "unknown" : runtime.updates().currentVersion();
        String versionLine = Phrase.build(Phrase.STATUS_VERSION, "CoreProtect v" + currentVersion + ".");
        if (runtime.updates() != null && runtime.config().checkUpdates() && runtime.updates().checked() && runtime.updates().latestVersion() != null) {
            versionLine += " (" + Phrase.build(Phrase.LATEST_VERSION, "v" + runtime.updates().latestVersion()) + ")";
        }
        sendLine(source, versionLine);

        String donationKey = runtime.config() == null ? null : runtime.config().donationKey();
        if (donationKey != null && !donationKey.isBlank()) {
            sendLine(source, Phrase.build(Phrase.STATUS_LICENSE, Phrase.build(Phrase.VALID_DONATION_KEY) + " (" + donationKey + ")"));
        }
        else {
            sendLine(source, Phrase.build(Phrase.STATUS_LICENSE, Phrase.build(Phrase.INVALID_DONATION_KEY) + " (" + Phrase.build(Phrase.CHECK_CONFIG) + ")"));
        }

        sendLine(source, Phrase.build(Phrase.STATUS_DATABASE, databaseBackendLabel(runtime)));
        if (runtime.hasWorldEditIntegration()) {
            sendLine(source, Phrase.build(Phrase.STATUS_INTEGRATION, "WorldEdit", Selector.FIRST));
        }
        else {
            sendLine(source, Phrase.build(Phrase.STATUS_INTEGRATION, "WorldEdit", Selector.SECOND));
        }

        int pending = runtime.database().pendingWrites();
        sendLine(source, Phrase.build(Phrase.STATUS_CONSUMER, NumberFormat.getInstance().format(pending), pending == 1 ? Selector.FIRST : Selector.SECOND));
        sendLine(source, Phrase.build(Phrase.STATUS_SYSTEM, runtime.logger().buildStatusSummary()));
        sendLine(source, Phrase.build(Phrase.LINK_DISCORD, "www.coreprotect.net/discord/"));
        sendLine(source, Phrase.build(Phrase.LINK_PATREON, "www.patreon.com/coreprotect/"));
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
            sendCoreProtectPhrase(source, Phrase.INSPECTOR_ERROR, enabled ? Selector.FIRST : Selector.SECOND);
            return 1;
        }

        sendCoreProtectPhrase(source, Phrase.INSPECTOR_TOGGLED, enabled ? Selector.FIRST : Selector.SECOND);
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
            Phrase.build(Phrase.LOOKUP_HEADER, "CoreProtect"),
            "CoreProtect - " + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.FIRST)
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
            Phrase.build(Phrase.SIGN_HEADER),
            "CoreProtect - " + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.FOURTH)
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
            Phrase.build(Phrase.INTERACTIONS_HEADER),
            "CoreProtect - " + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.THIRD)
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
            Phrase.build(Phrase.INTERACTIONS_HEADER),
            "CoreProtect - " + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.THIRD)
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
            Phrase.build(Phrase.CONTAINER_HEADER),
            "CoreProtect - " + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.SECOND)
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
            Phrase.build(Phrase.CONTAINER_HEADER),
            "CoreProtect - " + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.SECOND)
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
            Phrase.build(Phrase.LOOKUP_HEADER, "CoreProtect"),
            "CoreProtect - " + Phrase.build(Phrase.NO_DATA_LOCATION, Selector.SECOND)
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

        List<String> actorNames = withoutActorToken(options.actorNames(), "#container");
        List<CoreProtectEventType> actionFilter = options.actionFilter();
        ContainerContinuationContext containerContinuation = null;
        if (containsActorToken(options.actorNames(), "#container")) {
            if (!supportsContainerContinuationActions(actionFilter)) {
                sendCoreProtectPhrase(source, Phrase.INVALID_USERNAME, "#container");
                return 0;
            }
            containerContinuation = resolveContainerContinuation(source, runtime, source.getEntity() instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null, options);
            if (containerContinuation == null) {
                sendCoreProtectPhrase(source, Phrase.INVALID_CONTAINER);
                return 0;
            }
            actionFilter = ensureActionFilter(actionFilter, CoreProtectEventType.CONTAINER_TRANSACTION);
        }

        if (!CoreProtectPermissions.canLookupNearby(source, actionFilter, true)) {
            return 0;
        }

        int minimumSeconds = options.minimumSeconds() != null ? options.minimumSeconds() : 0;
        int seconds = options.seconds() != null ? options.seconds() : DEFAULT_LOOKUP_SECONDS;
        int limit = options.limit() != null ? options.limit() : DEFAULT_LOOKUP_LIMIT;
        Integer radius = options.radius();
        if (!validateRadiusLimit(source, runtime, radius, Selector.FIRST)) {
            return 0;
        }
        String worldKey = null;
        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity ? (ServerPlayerEntity) source.getEntity() : null;
        QueryBounds selectionBounds = null;
        if (containerContinuation != null) {
            worldKey = containerContinuation.worldKey();
            selectionBounds = containerContinuation.bounds();
            radius = null;
        }
        else if (options.globalScope()) {
            radius = null;
        }
        else if (isWorldEditWorldFilter(options.worldFilter())) {
            if (player == null) {
                sendLocalizedMessage(source, "fabric.command.in_game_only", "CoreProtect - This command can only be used in-game.");
                return 0;
            }
            try {
                selectionBounds = runtime.resolveWorldEditSelection(player);
            }
            catch (IllegalStateException exception) {
                sendCoreProtectPhrase(source, Phrase.INVALID_SELECTION, "WorldEdit");
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
        else if (radius != null || options.coordinates() != null) {
            if (player == null && isConsoleSource(source) && options.coordinates() == null) {
                sendLocalizedMessage(source, "fabric.command.in_game_only", "CoreProtect - This command can only be used in-game.");
                return 0;
            }
            worldKey = player != null
                ? ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString()
                : source.getWorld().getRegistryKey().getValue().toString();
        }

        QueryBounds legacyBounds = selectionBounds == null ? createLegacyBounds(source, player, worldKey, options) : null;
        String scopeSummary = selectionBounds != null
            ? describeScope(selectionBounds)
            : legacyBounds != null
                ? describeScope(legacyBounds)
                : describeScope(worldKey, radius, player);
        String timeSummary = describeTimeWindow(minimumSeconds, seconds);
        String actorSummary = describeActors(actorNames, options.excludeActorNames());
        String targetSummary = describeTargetFilters(options.includeTargets(), options.excludeTargets());
        BlockPos center = legacyBounds == null && player != null && radius != null ? player.getBlockPos().toImmutable() : null;
        if (options.count()) {
            int total = runtime.lookup().countScopedHistory(
                worldKey,
                center,
                legacyBounds == null ? radius : null,
                selectionBounds != null ? selectionBounds : legacyBounds,
                minimumSeconds,
                seconds,
                actorNames,
                options.excludeActorNames(),
                actionFilter,
                options.includeTargets(),
                options.excludeTargets()
            );
            sendCoreProtectPhrase(source, Phrase.LOOKUP_ROWS_FOUND, NumberFormat.getInstance().format(total), total == 1 ? Selector.FIRST : Selector.SECOND);
            return total > 0 ? 1 : 0;
        }

        int total = runtime.lookup().countScopedHistory(
            worldKey,
            center,
            legacyBounds == null ? radius : null,
            selectionBounds != null ? selectionBounds : legacyBounds,
            minimumSeconds,
            seconds,
            actorNames,
            options.excludeActorNames(),
            actionFilter,
            options.includeTargets(),
            options.excludeTargets()
        );
        int totalPages = Math.max(1, (int) Math.ceil(total / (double) limit));
        LookupSessionService.LookupQuery query = new LookupSessionService.LookupQuery(
            worldKey,
            center,
            legacyBounds == null ? radius : null,
            selectionBounds != null ? selectionBounds : legacyBounds,
            minimumSeconds,
            seconds,
            limit,
            actorNames,
            options.excludeActorNames(),
            actionFilter,
            options.includeTargets(),
            options.excludeTargets()
        );
        runtime.lookupSessions().remember(lookupSessionKey(source), query);
        List<StoredEventRecord> networkEvents = runtime.lookup().getScopedHistory(
            worldKey,
            center,
            legacyBounds == null ? radius : null,
            selectionBounds != null ? selectionBounds : legacyBounds,
            minimumSeconds,
            seconds,
            limit,
            0,
            actorNames,
            options.excludeActorNames(),
            actionFilter,
            options.includeTargets(),
            options.excludeTargets()
        );
        List<Text> lines = selectionBounds == null && legacyBounds == null
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
                actorNames,
                options.excludeActorNames(),
                actionFilter,
                options.includeTargets(),
                options.excludeTargets(),
                null
            )
            : runtime.lookup().describeScopedHistory(
                worldKey,
                null,
                null,
                selectionBounds != null ? selectionBounds : legacyBounds,
                minimumSeconds,
                seconds,
                limit,
                0,
                1,
                totalPages,
                actorNames,
                options.excludeActorNames(),
                actionFilter,
                options.includeTargets(),
                options.excludeTargets(),
                scopeSummary
            );
        sendLines(source, lines);
        sendLookupNetworkData(source, networkEvents);
        if (totalPages > 1) {
            source.sendFeedback(() -> buildLookupNavigation(1, totalPages), false);
        }
        return 1;
    }

    private static int sendPageUsage(ServerCommandSource source) {
        if (!CoreProtectPermissions.canUseLookupPagination(source, true)) {
            return 0;
        }

        sendCoreProtectPhrase(source, Phrase.MISSING_PARAMETERS, "/co page <page>");
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

        sendCoreProtectPhrase(source, Phrase.MISSING_PARAMETERS, "/co teleport <world> <x> <y> <z>");
        return 1;
    }

    private static int runTeleport(ServerCommandSource source, String input) {
        if (!CoreProtectPermissions.canUseTeleport(source, true)) {
            return 0;
        }

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            sendCoreProtectPhrase(source, Phrase.TELEPORT_PLAYERS);
            return 0;
        }

        long now = System.currentTimeMillis();
        long lastTeleportAt = TELEPORT_THROTTLE.getOrDefault(player.getUuid(), 0L);
        if ((now - lastTeleportAt) < TELEPORT_THROTTLE_MS) {
            sendCoreProtectPhrase(source, Phrase.COMMAND_THROTTLED);
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
        source.sendFeedback(() -> Text.literal(Phrase.build(
            Phrase.TELEPORTED,
            "x" + formatCoordinate(result.x())
                + "/y" + formatCoordinate(result.y())
                + "/z" + formatCoordinate(result.z())
                + "/" + result.world().getRegistryKey().getValue()
        )), false);
        return 1;
    }

    private static int runLookupPage(ServerCommandSource source, FabricRuntime runtime, int page, Integer requestedLines) {
        String sessionKey = lookupSessionKey(source);
        LookupSessionService.LookupQuery previousQuery = runtime.lookupSessions().get(sessionKey);
        if (previousQuery == null) {
            sendCoreProtectPhrase(source, Phrase.NO_RESULTS_PAGE, Selector.FIRST);
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
            sendCoreProtectPhrase(source, Phrase.NO_RESULTS_PAGE, Selector.FIRST);
            return 0;
        }

        int totalPages = Math.max(1, (int) Math.ceil(total / (double) linesPerPage));
        if (page < 1 || page > totalPages) {
            sendCoreProtectPhrase(source, Phrase.NO_RESULTS_PAGE, Selector.FIRST);
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
        if (totalPages > 1) {
            source.sendFeedback(() -> buildLookupNavigation(page, totalPages), false);
        }
        return 1;
    }

    private static Text buildLookupNavigation(int page, int totalPages) {
        MutableText line = Text.literal("");

        if (page > 1) {
            line.append(navigationButton("◀ ", page - 1));
        }

        line.append(Text.literal(Phrase.build(Phrase.LOOKUP_PAGE, page + "/" + totalPages)).formatted(Formatting.DARK_AQUA));

        if (page < totalPages) {
            line.append(navigationButton(" ▶", page + 1));
        }

        if (totalPages > 1) {
            line.append(Text.literal(" ").formatted(Formatting.GRAY));
            line.append(buildPaginationText(page, totalPages));
        }

        return line;
    }

    private static Text navigationButton(String label, int targetPage) {
        String command = "/co l " + targetPage;
        return Text.literal(label).styled(style -> style
            .withFormatting(Formatting.WHITE)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Text.literal(command)))
        );
    }

    private static Text buildPaginationText(int page, int totalPages) {
        MutableText pagination = Text.literal("(").formatted(Formatting.GRAY);
        if (page > 3) {
            pagination.append(pageNumber(1, page));
            pagination.append(Text.literal(page > 4 && totalPages > 7 ? " ... " : " | ").formatted(Formatting.GRAY));
        }

        int displayStart = Math.max(1, page - 2);
        int displayEnd = Math.min(totalPages, page + 2);
        if (page > 999 || (page > 101 && totalPages > 99999)) {
            displayStart = Math.min(displayEnd, displayStart + 1);
            displayEnd = Math.max(displayStart, displayEnd - 1);
            if (displayStart > totalPages - 3) {
                displayStart = Math.max(1, totalPages - 3);
            }
        }
        else {
            if (displayStart > totalPages - 5) {
                displayStart = Math.max(1, totalPages - 5);
            }
            if (displayEnd < 6) {
                displayEnd = Math.min(totalPages, 6);
            }
        }

        if (page > 99999) {
            displayStart = Math.min(displayEnd, displayStart + 1);
            displayEnd = Math.max(displayStart, displayEnd - 1);
            if (page == totalPages - 1) {
                displayEnd = totalPages - 1;
            }
            if (displayStart < displayEnd) {
                displayStart = displayEnd;
            }
        }

        if (page > 3 && displayStart == 1) {
            displayStart = 2;
        }

        for (int displayPage = displayStart; displayPage <= displayEnd; displayPage++) {
            if (displayPage != displayStart) {
                pagination.append(Text.literal(" | ").formatted(Formatting.GRAY));
            }
            pagination.append(pageNumber(displayPage, page));
        }

        if (displayEnd < totalPages) {
            pagination.append(Text.literal(displayEnd < totalPages - 1 ? " ... " : " | ").formatted(Formatting.GRAY));
            pagination.append(pageNumber(totalPages, page));
        }

        pagination.append(Text.literal(")").formatted(Formatting.GRAY));
        return pagination;
    }

    private static Text pageNumber(int displayPage, int currentPage) {
        if (displayPage == currentPage) {
            return Text.literal(Integer.toString(displayPage)).formatted(Formatting.WHITE, Formatting.UNDERLINE);
        }

        String command = "/co l " + displayPage;
        return Text.literal(Integer.toString(displayPage)).styled(style -> style
            .withFormatting(Formatting.WHITE)
            .withClickEvent(new ClickEvent.RunCommand(command))
            .withHoverEvent(new HoverEvent.ShowText(Text.literal(command)))
        );
    }

    private static int runRollback(ServerCommandSource source, FabricRuntime runtime, boolean restore, int seconds, int radius, String actorName) {
        return runRollback(source, runtime, restore, seconds, radius, actorName, null);
    }

    private static int runRollback(ServerCommandSource source, FabricRuntime runtime, boolean restore, int seconds, int radius, String actorName, List<CoreProtectEventType> actionFilter) {
        if (!CoreProtectPermissions.canRunRollback(source, restore, true)) {
            return 0;
        }
        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null;
        if (player == null && isConsoleSource(source)) {
            sendCoreProtectPhrase(source, Phrase.GLOBAL_ROLLBACK, "r:#global", restore ? Selector.SECOND : Selector.FIRST);
            return 0;
        }

        if (player != null) {
            runtime.previews().clear(player);
        }
        TimeWindow timeWindow = fixedTimeWindow(0, seconds);
        QueryBounds radiusBounds = player != null ? createRadiusBounds(player, radius) : createRadiusBounds(source, radius);
        List<String> actorNames = actorName == null || actorName.isBlank() ? null : List.of(actorName);
        String worldKey = source.getWorld().getRegistryKey().getValue().toString();
        String subject = describeRollbackSubject(worldKey, actorNames);
        sendCoreProtectPhrase(source, Phrase.ROLLBACK_STARTED, subject, restore ? Selector.SECOND : Selector.FIRST);
        long startedAt = System.nanoTime();
        RollbackExecutionResult result = player != null
            ? runtime.rollback().applyBetween(
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
            )
            : runtime.rollback().applyBetween(
                source.getServer(),
                timeWindow.notBefore(),
                timeWindow.notAfter(),
                null,
                radiusBounds,
                actorNames,
                null,
                restore,
                actionFilter,
                null,
                null
            );
        String scopeSummary = describeScope(source.getWorld().getRegistryKey().getValue().toString(), radius, player);
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
        sendRollbackOutcome(source, restore, false, subject, timeSummary, radius, null, worldKey, result.changed(), System.nanoTime() - startedAt, false);
        return result.changed() > 0 ? 1 : 0;
    }

    private static int runLegacyRollback(ServerCommandSource source, FabricRuntime runtime, boolean restore, String input) {
        LegacyCommandOptions options = LegacyCommandParser.parse(input);
        if (!CoreProtectPermissions.canRunRollback(source, restore, true)) {
            return 0;
        }
        if (options.previewCancel()) {
            return runCancel(source, runtime);
        }
        List<String> actorNames = withoutActorToken(options.actorNames(), "#container");
        List<CoreProtectEventType> actionFilter = options.actionFilter();
        ContainerContinuationContext containerContinuation = null;
        if (containsActorToken(options.actorNames(), "#container")) {
            if (!supportsContainerContinuationActions(actionFilter)) {
                sendCoreProtectPhrase(source, Phrase.INVALID_USERNAME, "#container");
                return 0;
            }
            if (options.preview()) {
                sendCoreProtectPhrase(source, Phrase.PREVIEW_TRANSACTION, Selector.FIRST);
                return 0;
            }
            containerContinuation = resolveContainerContinuation(source, runtime, source.getEntity() instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null, options);
            if (containerContinuation == null) {
                sendCoreProtectPhrase(source, Phrase.INVALID_CONTAINER);
                return 0;
            }
            actionFilter = ensureActionFilter(actionFilter, CoreProtectEventType.CONTAINER_TRANSACTION);
        }
        if (actionFilter != null && !actionFilter.isEmpty()) {
            boolean supported = actionFilter.stream().allMatch(RollbackService::isSupported);
            if (!supported) {
                sendCoreProtectPhrase(source, Phrase.ACTION_NOT_SUPPORTED);
                return 0;
            }
        }
        if (runtime.config() != null
            && runtime.config().excludeTnt()
            && !hasContainerAction(actionFilter)
            && !containsTargetIdentifier(options.includeTargets(), "minecraft:tnt")
            && !containsTargetIdentifier(options.excludeTargets(), "minecraft:tnt")) {
            List<String> excluded = options.excludeTargets() == null ? new ArrayList<>() : new ArrayList<>(options.excludeTargets());
            excluded.add("tnt");
            options = new LegacyCommandOptions(
                options.minimumSeconds(),
                options.seconds(),
                options.radius(),
                options.radiusX(),
                options.radiusY(),
                options.radiusZ(),
                options.coordinates(),
                options.worldFilter(),
                options.globalScope(),
                options.limit(),
                actorNames,
                options.excludeActorNames(),
                actionFilter,
                options.includeTargets(),
                excluded,
                options.preview(),
                options.previewCancel(),
                options.count(),
                options.silent(),
                options.verbose()
            );
        }

        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null;
        if (options.preview() && player == null) {
            sendCoreProtectPhrase(source, Phrase.PREVIEW_IN_GAME);
            return 0;
        }

        int minimumSeconds = options.minimumSeconds() != null ? options.minimumSeconds() : 0;
        int seconds = options.seconds() != null ? options.seconds() : DEFAULT_LOOKUP_SECONDS;
        Integer radius = options.radius();
        if (!validateRadiusLimit(source, runtime, radius, restore ? Selector.THIRD : Selector.SECOND)) {
            return 0;
        }
        String worldKey;
        QueryBounds selectionBounds = null;
        if (containerContinuation != null) {
            worldKey = containerContinuation.worldKey();
            selectionBounds = containerContinuation.bounds();
            radius = null;
        }
        else if (options.globalScope()) {
            worldKey = null;
            radius = null;
        }
        else if (isWorldEditWorldFilter(options.worldFilter())) {
            try {
                selectionBounds = runtime.resolveWorldEditSelection(player);
            }
            catch (IllegalStateException exception) {
                sendCoreProtectPhrase(source, Phrase.INVALID_SELECTION, "WorldEdit");
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
            if (player != null) {
                worldKey = ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString();
                radius = radius != null ? radius : configuredDefaultRollbackRadius(runtime);
            }
            else if (!isConsoleSource(source) || options.coordinates() != null) {
                worldKey = source.getWorld().getRegistryKey().getValue().toString();
                radius = radius != null ? radius : configuredDefaultRollbackRadius(runtime);
            }
            else {
                sendCoreProtectPhrase(source, Phrase.GLOBAL_ROLLBACK, "r:#global", restore ? Selector.SECOND : Selector.FIRST);
                return 0;
            }
        }

        QueryBounds scopeBounds = selectionBounds != null
            ? selectionBounds
            : createLegacyBounds(source, player, worldKey, options);
        TimeWindow timeWindow = fixedTimeWindow(minimumSeconds, seconds);
        String subject = describeRollbackSubject(scopeBounds == null ? worldKey : scopeBounds.worldKey(), actorNames);
        String timeSummary = describeTimeWindow(minimumSeconds, seconds);
        boolean worldEditSelection = isWorldEditWorldFilter(options.worldFilter());

        if (options.preview()) {
            sendCoreProtectPhrase(source, Phrase.ROLLBACK_STARTED, subject, Selector.THIRD);
            long startedAt = System.nanoTime();
            RollbackPreviewResult preview = runtime.rollback().previewBetween(
                player,
                timeWindow.notBefore(),
                timeWindow.notAfter(),
                scopeBounds == null ? worldKey : null,
                scopeBounds,
                actorNames,
                options.excludeActorNames(),
                restore,
                actionFilter,
                options.includeTargets(),
                options.excludeTargets()
            );
            runtime.previews().show(player, preview.blockChanges());
            String scopeSummary = selectionBounds == null ? describeScope(worldKey, radius, player) : describeScope(selectionBounds);
            String actorSummary = describeActors(actorNames, options.excludeActorNames());
            String targetSummary = describeTargetFilters(options.includeTargets(), options.excludeTargets());
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
                    actorNames,
                    options.excludeActorNames(),
                    actionFilter,
                    options.includeTargets(),
                    options.excludeTargets(),
                    describeUndoOperation(scopeSummary, timeSummary, actorSummary, targetSummary, describeActionFilters(actionFilter))
                )
            );
            sendRollbackOutcome(
                source,
                restore,
                true,
                subject,
                timeSummary,
                radius,
                worldEditSelection ? "#worldedit" : null,
                scopeBounds == null ? worldKey : null,
                preview.matched(),
                System.nanoTime() - startedAt,
                true
            );
            return preview.matched() > 0 ? 1 : 0;
        }

        if (player != null) {
            runtime.previews().clear(player);
        }
        sendCoreProtectPhrase(source, Phrase.ROLLBACK_STARTED, subject, restore ? Selector.SECOND : Selector.FIRST);
        long startedAt = System.nanoTime();
        RollbackExecutionResult result = player != null
            ? runtime.rollback().applyBetween(
                player,
                timeWindow.notBefore(),
                timeWindow.notAfter(),
                scopeBounds == null ? worldKey : null,
                scopeBounds,
                actorNames,
                options.excludeActorNames(),
                restore,
                actionFilter,
                options.includeTargets(),
                options.excludeTargets()
            )
            : runtime.rollback().applyBetween(
                source.getServer(),
                timeWindow.notBefore(),
                timeWindow.notAfter(),
                scopeBounds == null ? worldKey : null,
                scopeBounds,
                actorNames,
                options.excludeActorNames(),
                restore,
                actionFilter,
                options.includeTargets(),
                options.excludeTargets()
            );
        String scopeSummary = selectionBounds == null ? describeScope(worldKey, radius, player) : describeScope(selectionBounds);
        String actorSummary = describeActors(actorNames, options.excludeActorNames());
        String targetSummary = describeTargetFilters(options.includeTargets(), options.excludeTargets());
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
                actorNames,
                options.excludeActorNames(),
                actionFilter,
                options.includeTargets(),
                options.excludeTargets(),
                describeUndoOperation(scopeSummary, timeSummary, actorSummary, targetSummary, describeActionFilters(actionFilter))
                )
        );
        sendRollbackOutcome(
            source,
            restore,
            false,
            subject,
            timeSummary,
            radius,
            worldEditSelection ? "#worldedit" : null,
            scopeBounds == null ? worldKey : null,
            result.changed(),
            System.nanoTime() - startedAt,
            false
        );
        return result.changed() > 0 ? 1 : 0;
    }

    private static int sendPurgeUsage(ServerCommandSource source) {
        if (!CoreProtectPermissions.canUsePurge(source, true)) {
            return 0;
        }

        sendCoreProtectPhrase(source, Phrase.MISSING_PARAMETERS, "/co purge t:<time>");
        sendLine(source, Phrase.build(Phrase.HELP_PURGE_2, "/co purge t:30d"));
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
            source.sendFeedback(CoreProtectCommands::notInitializedText, false);
            return 0;
        }

        int minimumSeconds = source.getEntity() instanceof ServerPlayerEntity ? PLAYER_PURGE_MIN_SECONDS : CONSOLE_PURGE_MIN_SECONDS;
        if (seconds < minimumSeconds) {
            final long minimumDays = minimumSeconds / 86400L;
            sendCoreProtectPhrase(source, Phrase.PURGE_MINIMUM_TIME, String.valueOf(minimumDays), minimumDays == 1 ? Selector.FIRST : Selector.SECOND);
            return 0;
        }

        String worldKey = resolveWorldFilter(source, worldFilter);
        if (worldFilter != null && worldKey == null && !isGlobalWorldFilter(worldFilter)) {
            return 0;
        }

        int deleted = runtime.database().purgeOlderThan(seconds, worldKey, includeTargets);
        sendCoreProtectPhrase(source, Phrase.PURGE_SUCCESS);
        sendCoreProtectPhrase(source, Phrase.PURGE_ROWS, NumberFormat.getInstance().format(deleted), deleted == 1 ? Selector.FIRST : Selector.SECOND);
        if (optimize) {
            if (runtime.database().optimizeStorage()) {
                sendCoreProtectPhrase(source, Phrase.PURGE_OPTIMIZING);
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
            sendCoreProtectPhrase(source, Phrase.RELOAD_STARTED);
            runtime.reload(server);
        }
        catch (RuntimeException exception) {
            CoreProtectFabricMod.LOGGER.error("CoreProtect reload failed", exception);
            sendLocalizedMessage(source, "fabric.reload.failed", "CoreProtect - Reload failed. Check the server log for details.");
            return 0;
        }

        sendCoreProtectPhrase(source, Phrase.RELOAD_SUCCESS);
        return 1;
    }

    private static int sendConsumerUsage(ServerCommandSource source) {
        if (!CoreProtectPermissions.canUseConsumer(source, true)) {
            return 0;
        }

        sendCoreProtectPhrase(source, Phrase.MISSING_PARAMETERS, "/co consumer <pause|resume>");
        return 1;
    }

    private static int runConsumer(ServerCommandSource source, FabricRuntime runtime, Boolean paused) {
        if (!CoreProtectPermissions.canUseConsumer(source, true)) {
            return 0;
        }

        if (source.getEntity() != null) {
            sendCoreProtectPhrase(source, Phrase.COMMAND_CONSOLE);
            return 0;
        }

        if (runtime.database() == null) {
            source.sendFeedback(CoreProtectCommands::notInitializedText, false);
            return 0;
        }

        if (paused != null) {
            boolean alreadyPaused = runtime.database().writesPaused();
            if (alreadyPaused == paused) {
                sendCoreProtectPhrase(source, Phrase.CONSUMER_ERROR, paused ? Selector.FIRST : Selector.SECOND);
                return 0;
            }
            runtime.database().setWritesPaused(paused);
            sendCoreProtectPhrase(source, Phrase.CONSUMER_TOGGLED, paused ? Selector.FIRST : Selector.SECOND);
            return 1;
        }

        return sendConsumerUsage(source);
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

        sendCoreProtectPhrase(source, Phrase.MISSING_PARAMETERS, "/co migrate-db <sqlite|mysql>");
        sendCoreProtectPhrase(source, Phrase.COMMAND_CONSOLE);
        return 1;
    }

    private static int runMigrate(ServerCommandSource source, FabricRuntime runtime, String target) {
        if (!CoreProtectPermissions.canUseMigrate(source, true)) {
            return 0;
        }

        if (source.getEntity() != null) {
            sendCoreProtectPhrase(source, Phrase.COMMAND_CONSOLE);
            return 0;
        }

        if (runtime.database() == null || runtime.config() == null) {
            source.sendFeedback(CoreProtectCommands::notInitializedText, false);
            return 0;
        }

        if (runtime.database().writesPaused()) {
            sendCoreProtectPhrase(source, Phrase.CONSUMER_ERROR, Selector.FIRST);
            return 0;
        }
        if (!hasValidDonationKey(runtime)) {
            sendCoreProtectPhrase(source, Phrase.DONATION_KEY_REQUIRED);
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
            sendCoreProtectPhrase(source, targetType == CoreProtectFabricConfig.DatabaseType.MYSQL ? Phrase.USING_MYSQL : Phrase.USING_SQLITE);
            return 0;
        }

        try {
            DatabaseMigrationService.migrate(source, runtime, targetType, CoreProtectFabricMod.LOGGER);
            return 1;
        }
        catch (DatabaseMigrationService.UserFacingMigrationException exception) {
            sendLine(source, exception.getMessage());
            return 0;
        }
        catch (RuntimeException exception) {
            CoreProtectFabricMod.LOGGER.error("CoreProtect migrate-db failed", exception);
            sendLocalizedMessage(source, "fabric.migrate.failed", "CoreProtect - Migration failed. Check the server log for details.");
            return 0;
        }
    }

    private static int runUndo(ServerCommandSource source, FabricRuntime runtime) {
        if (!CoreProtectPermissions.canUseUndo(source, true)) {
            return 0;
        }

        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null;

        UndoSessionService.UndoOperation operation = runtime.undoSessions().consume(lookupSessionKey(source));
        if (operation == null) {
            sendCoreProtectPhrase(source, Phrase.NO_ROLLBACK, Selector.SECOND);
            return 0;
        }

        if (operation.preview()) {
            if (player != null) {
                runtime.previews().clear(player);
            }
            sendCoreProtectPhrase(source, Phrase.PREVIEW_CANCELLING);
            sendCoreProtectPhrase(source, Phrase.PREVIEW_CANCELLED);
            return 1;
        }

        if (player != null) {
            runtime.previews().clear(player);
        }
        boolean undoRestore = !operation.restore();
        long startedAt = System.nanoTime();
        RollbackExecutionResult result = player != null
            ? runtime.rollback().applyBetween(
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
            )
            : runtime.rollback().applyBetween(
                source.getServer(),
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
        sendRollbackOutcome(
            source,
            undoRestore,
            false,
            describeRollbackSubject(resolveUndoWorldKey(operation), operation.actorNames()),
            extractUndoTimeSummary(operation.description()),
            extractUndoRadius(operation.description()),
            extractUndoSelection(operation.description()),
            resolveUndoWorldKey(operation),
            result.changed(),
            System.nanoTime() - startedAt,
            false
        );
        return result.changed() > 0 ? 1 : 0;
    }

    private static int runApply(ServerCommandSource source, FabricRuntime runtime) {
        if (!CoreProtectPermissions.canUseApplyCancel(source, true)) {
            return 0;
        }

        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null;

        String sessionKey = lookupSessionKey(source);
        UndoSessionService.UndoOperation operation = runtime.undoSessions().get(sessionKey);
        if (operation == null || !operation.preview()) {
            sendCoreProtectPhrase(source, Phrase.NO_ROLLBACK, Selector.FIRST);
            return 0;
        }

        if (player != null) {
            runtime.previews().clear(player);
        }
        runtime.undoSessions().clear(sessionKey);
        long startedAt = System.nanoTime();
        RollbackExecutionResult result = player != null
            ? runtime.rollback().applyBetween(
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
            )
            : runtime.rollback().applyBetween(
                source.getServer(),
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

        sendRollbackOutcome(
            source,
            operation.restore(),
            false,
            describeRollbackSubject(resolveUndoWorldKey(operation), operation.actorNames()),
            extractUndoTimeSummary(operation.description()),
            extractUndoRadius(operation.description()),
            extractUndoSelection(operation.description()),
            resolveUndoWorldKey(operation),
            result.changed(),
            System.nanoTime() - startedAt,
            false
        );
        return result.changed() > 0 ? 1 : 0;
    }

    private static int runCancel(ServerCommandSource source, FabricRuntime runtime) {
        if (!CoreProtectPermissions.canUseApplyCancel(source, true)) {
            return 0;
        }

        ServerPlayerEntity player = source.getEntity() instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null;

        String sessionKey = lookupSessionKey(source);
        UndoSessionService.UndoOperation operation = runtime.undoSessions().get(sessionKey);
        if (operation == null || !operation.preview()) {
            sendCoreProtectPhrase(source, Phrase.NO_ROLLBACK, Selector.FIRST);
            return 0;
        }

        if (player != null) {
            runtime.previews().clear(player);
        }
        runtime.undoSessions().clear(sessionKey);
        sendCoreProtectPhrase(source, Phrase.PREVIEW_CANCELLING);
        sendCoreProtectPhrase(source, Phrase.PREVIEW_CANCELLED);
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

        sendCoreProtectPhrase(source, Phrase.WORLD_NOT_FOUND, rawFilter);
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

    private static int configuredDefaultRollbackRadius(FabricRuntime runtime) {
        if (runtime == null || runtime.config() == null) {
            return DEFAULT_ROLLBACK_RADIUS;
        }
        return Math.max(0, runtime.config().defaultRadius());
    }

    private static int configuredMaxRadius(FabricRuntime runtime) {
        if (runtime == null || runtime.config() == null) {
            return DEFAULT_MAX_RADIUS;
        }
        int configured = runtime.config().maxRadius();
        return configured <= 0 ? Integer.MAX_VALUE : configured;
    }

    private static boolean validateRadiusLimit(ServerCommandSource source, FabricRuntime runtime, Integer radius, String selector) {
        if (radius == null) {
            return true;
        }

        int maxRadius = configuredMaxRadius(runtime);
        if (maxRadius == Integer.MAX_VALUE || radius <= maxRadius) {
            return true;
        }

        sendCoreProtectPhrase(source, Phrase.MAXIMUM_RADIUS, Integer.toString(maxRadius), selector);
        return false;
    }

    private static boolean hasContainerAction(List<CoreProtectEventType> actionFilter) {
        return actionFilter != null && actionFilter.contains(CoreProtectEventType.CONTAINER_TRANSACTION);
    }

    private static boolean containsTargetIdentifier(List<String> targets, String expected) {
        if (targets == null || expected == null || expected.isBlank()) {
            return false;
        }

        for (String target : targets) {
            if (target == null || target.isBlank()) {
                continue;
            }
            String normalized = target.contains(":") ? target : "minecraft:" + target;
            if (expected.equalsIgnoreCase(normalized)) {
                return true;
            }
        }
        return false;
    }

    private static String describeScope(String worldKey, Integer radius, ServerPlayerEntity player) {
        if (radius != null) {
            if (player != null) {
                ServerWorld world = (ServerWorld) player.getEntityWorld();
                return world.getRegistryKey().getValue() + "@r=" + radius;
            }
            if (worldKey != null && !worldKey.isBlank()) {
                return worldKey + "@r=" + radius;
            }
        }
        if (worldKey != null && !worldKey.isBlank()) {
            return worldKey;
        }
        return "global";
    }

    private static String describeRollbackSubject(String worldKey, List<String> actorNames) {
        if (actorNames != null && !actorNames.isEmpty()) {
            return String.join(", ", actorNames);
        }
        if (worldKey != null && !worldKey.isBlank()) {
            return "#" + displayWorldName(worldKey);
        }
        return "#global";
    }

    private static String displayWorldName(String worldKey) {
        if (worldKey == null || worldKey.isBlank()) {
            return "global";
        }
        return switch (worldKey.toLowerCase(Locale.ROOT)) {
            case "minecraft:overworld" -> "world";
            case "minecraft:the_nether" -> "world_nether";
            case "minecraft:the_end" -> "world_the_end";
            default -> worldKey;
        };
    }

    private static void sendRollbackOutcome(
        ServerCommandSource source,
        boolean restore,
        boolean preview,
        String subject,
        String timeSummary,
        Integer radius,
        String selectionLabel,
        String worldKey,
        int changed,
        long elapsedNanos,
        boolean promptSelection
    ) {
        sendLine(source, "-----");
        sendCoreProtectPhrase(source, Phrase.ROLLBACK_COMPLETED, subject, preview ? Selector.THIRD : restore ? Selector.SECOND : Selector.FIRST);
        if (timeSummary != null && !timeSummary.isBlank()) {
            sendCoreProtectPhrase(source, Phrase.ROLLBACK_TIME, timeSummary);
        }
        if (radius != null && radius >= 0) {
            sendCoreProtectPhrase(source, Phrase.ROLLBACK_RADIUS, Integer.toString(radius), radius == 1 ? Selector.FIRST : Selector.SECOND);
        }
        else if (selectionLabel != null && !selectionLabel.isBlank()) {
            sendCoreProtectPhrase(source, Phrase.ROLLBACK_SELECTION, selectionLabel);
        }
        else if (worldKey != null && !worldKey.isBlank()) {
            sendCoreProtectPhrase(source, Phrase.ROLLBACK_WORLD_ACTION, displayWorldName(worldKey), Selector.FIRST);
        }

        String modified = Phrase.build(Phrase.AMOUNT_BLOCK, NumberFormat.getInstance().format(changed), changed == 1 ? Selector.FIRST : Selector.SECOND);
        sendCoreProtectPhrase(source, Phrase.ROLLBACK_MODIFIED, modified, preview ? Selector.SECOND : Selector.FIRST);

        if (!preview && elapsedNanos > 0L) {
            BigDecimal elapsedSeconds = BigDecimal.valueOf(elapsedNanos)
                .divide(BigDecimal.valueOf(1_000_000_000L), 1, RoundingMode.HALF_EVEN)
                .stripTrailingZeros();
            sendCoreProtectPhrase(
                source,
                Phrase.ROLLBACK_LENGTH,
                elapsedSeconds.toPlainString(),
                elapsedSeconds.compareTo(BigDecimal.ONE) == 0 ? Selector.FIRST : Selector.SECOND
            );
        }

        sendLine(source, "-----");
        if (promptSelection) {
            sendCoreProtectPhrase(source, Phrase.PLEASE_SELECT, "/co apply", "/co cancel");
        }
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

    private static String resolveUndoWorldKey(UndoSessionService.UndoOperation operation) {
        if (operation == null) {
            return null;
        }
        if (operation.worldKey() != null && !operation.worldKey().isBlank()) {
            return operation.worldKey();
        }
        return operation.bounds() == null ? null : operation.bounds().worldKey();
    }

    private static String extractUndoTimeSummary(String description) {
        return extractUndoDescriptionValue(description, "time=");
    }

    private static Integer extractUndoRadius(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        int marker = description.indexOf("@r=");
        if (marker < 0) {
            return null;
        }
        int start = marker + 3;
        int end = start;
        while (end < description.length() && Character.isDigit(description.charAt(end))) {
            end++;
        }
        if (end <= start) {
            return null;
        }
        try {
            return Integer.parseInt(description.substring(start, end));
        }
        catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String extractUndoSelection(String description) {
        if (description == null || !description.startsWith("scope=worldedit@")) {
            return null;
        }
        return "#worldedit";
    }

    private static String extractUndoDescriptionValue(String description, String token) {
        if (description == null || description.isBlank() || token == null || token.isBlank()) {
            return "";
        }
        int start = description.indexOf(token);
        if (start < 0) {
            return "";
        }
        start += token.length();
        int end = description.length();
        String[] suffixes = new String[] { ", actor=", ", exclude-user=", ", include=", ", exclude=", ", actions=" };
        for (String suffix : suffixes) {
            int candidate = description.indexOf(suffix, start);
            if (candidate >= 0 && candidate < end) {
                end = candidate;
            }
        }
        return description.substring(start, end).trim();
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

    private static QueryBounds createRadiusBounds(ServerCommandSource source, int radius) {
        BlockPos center = BlockPos.ofFloored(source.getPosition());
        String worldKey = source.getWorld().getRegistryKey().getValue().toString();
        return new QueryBounds(
            worldKey,
            new BlockPos(center.getX() - radius, center.getY() - radius, center.getZ() - radius),
            new BlockPos(center.getX() + radius, center.getY() + radius, center.getZ() + radius)
        );
    }

    private static QueryBounds createLegacyBounds(ServerCommandSource source, ServerPlayerEntity player, String worldKey, LegacyCommandOptions options) {
        if (options == null || options.radius() == null) {
            return null;
        }

        ServerWorld world = resolveLegacyWorld(source, worldKey, player);
        if (world == null) {
            return null;
        }

        BlockPos center = resolveLegacyCenter(source, player, options);
        if (center == null) {
            return null;
        }

        int radiusX = options.radiusX() != null ? options.radiusX() : options.radius();
        int radiusZ = options.radiusZ() != null ? options.radiusZ() : options.radius();
        Integer radiusY = options.radiusY();
        int minimumY = radiusY == null ? world.getBottomY() : center.getY() - radiusY;
        int maximumY = radiusY == null ? world.getTopYInclusive() : center.getY() + radiusY;
        return new QueryBounds(
            world.getRegistryKey().getValue().toString(),
            new BlockPos(center.getX() - radiusX, minimumY, center.getZ() - radiusZ),
            new BlockPos(center.getX() + radiusX, maximumY, center.getZ() + radiusZ)
        );
    }

    private static ServerWorld resolveLegacyWorld(ServerCommandSource source, String worldKey, ServerPlayerEntity player) {
        if (worldKey != null && !worldKey.isBlank()) {
            Identifier identifier = Identifier.tryParse(worldKey);
            return identifier == null ? null : source.getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, identifier));
        }
        if (player != null) {
            return (ServerWorld) player.getEntityWorld();
        }
        return source.getWorld();
    }

    private static BlockPos resolveLegacyCenter(ServerCommandSource source, ServerPlayerEntity player, LegacyCommandOptions options) {
        if (options != null && options.coordinates() != null && !options.coordinates().isBlank()) {
            String[] parts = options.coordinates().split(",", -1);
            try {
                int x = (int) Math.floor(Double.parseDouble(parts[0]));
                if (parts.length == 2) {
                    int z = (int) Math.floor(Double.parseDouble(parts[1]));
                    int y = player != null ? player.getBlockY() : BlockPos.ofFloored(source.getPosition()).getY();
                    return new BlockPos(x, y, z);
                }
                int y = (int) Math.floor(Double.parseDouble(parts[1]));
                int z = (int) Math.floor(Double.parseDouble(parts[2]));
                return new BlockPos(x, y, z);
            }
            catch (NumberFormatException exception) {
                return null;
            }
        }
        if (player != null) {
            return player.getBlockPos();
        }
        return BlockPos.ofFloored(source.getPosition());
    }

    private static boolean containsActorToken(List<String> actorNames, String token) {
        if (actorNames == null || actorNames.isEmpty() || token == null || token.isBlank()) {
            return false;
        }
        for (String actorName : actorNames) {
            if (token.equalsIgnoreCase(actorName)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> withoutActorToken(List<String> actorNames, String token) {
        if (actorNames == null || actorNames.isEmpty()) {
            return actorNames;
        }

        List<String> filtered = new ArrayList<>();
        for (String actorName : actorNames) {
            if (actorName == null || token.equalsIgnoreCase(actorName)) {
                continue;
            }
            filtered.add(actorName);
        }
        return filtered.isEmpty() ? null : filtered;
    }

    private static List<CoreProtectEventType> ensureActionFilter(List<CoreProtectEventType> actionFilter, CoreProtectEventType requiredType) {
        List<CoreProtectEventType> resolved = actionFilter == null ? new ArrayList<>() : new ArrayList<>(actionFilter);
        if (requiredType != null && !resolved.contains(requiredType)) {
            resolved.add(requiredType);
        }
        return resolved.isEmpty() ? null : resolved;
    }

    private static boolean supportsContainerContinuationActions(List<CoreProtectEventType> actionFilter) {
        if (actionFilter == null || actionFilter.isEmpty()) {
            return true;
        }
        for (CoreProtectEventType eventType : actionFilter) {
            if (eventType != CoreProtectEventType.CONTAINER_TRANSACTION
                && eventType != CoreProtectEventType.BLOCK_BREAK
                && eventType != CoreProtectEventType.BLOCK_PLACE) {
                return false;
            }
        }
        return true;
    }

    private static ContainerContinuationContext resolveContainerContinuation(
        ServerCommandSource source,
        FabricRuntime runtime,
        ServerPlayerEntity player,
        LegacyCommandOptions options
    ) {
        ServerWorld world;
        BlockPos pos;
        if (options != null && options.coordinates() != null && !options.coordinates().isBlank()) {
            String worldKey = options.worldFilter() == null || isGlobalWorldFilter(options.worldFilter())
                ? source.getWorld().getRegistryKey().getValue().toString()
                : resolveWorldFilter(source, options.worldFilter());
            world = resolveLegacyWorld(source, worldKey, player);
            pos = resolveLegacyCenter(source, player, options);
        }
        else {
            if (player == null) {
                return null;
            }
            world = (ServerWorld) player.getEntityWorld();
            pos = runtime.lookup().findTargetedBlockPos(player);
        }

        if (world == null || pos == null || !isSupportedContainerContinuationTarget(world, pos)) {
            return null;
        }

        String worldKey = world.getRegistryKey().getValue().toString();
        return new ContainerContinuationContext(
            worldKey,
            new QueryBounds(worldKey, pos, pos, new long[] { pos.asLong() })
        );
    }

    private static boolean isSupportedContainerContinuationTarget(ServerWorld world, BlockPos pos) {
        BlockEntity blockEntity = world.getBlockEntity(pos);
        return blockEntity instanceof Inventory
            || blockEntity instanceof LecternBlockEntity
            || blockEntity instanceof JukeboxBlockEntity
            || blockEntity instanceof ChiseledBookshelfBlockEntity
            || blockEntity instanceof DecoratedPotBlockEntity
            || blockEntity instanceof BrushableBlockEntity;
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
                sendCoreProtectPhrase(source, Phrase.WORLD_NOT_FOUND, tokens[0]);
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
        if (!isConsoleSource(source)) {
            BlockPos pos = BlockPos.ofFloored(source.getPosition());
            return "source:" + source.getWorld().getRegistryKey().getValue() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
        }
        return "console";
    }

    private static boolean isConsoleSource(ServerCommandSource source) {
        return source != null && !source.isExecutedByPlayer() && "Server".equalsIgnoreCase(source.getName());
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

    private static final class ContainerContinuationContext {
        private final String worldKey;
        private final QueryBounds bounds;

        private ContainerContinuationContext(String worldKey, QueryBounds bounds) {
            this.worldKey = worldKey;
            this.bounds = bounds;
        }

        private String worldKey() {
            return worldKey;
        }

        private QueryBounds bounds() {
            return bounds;
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

        sendLocalizedMessage(source, "fabric.command.in_game_only", "CoreProtect - This command can only be used in-game.");
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
