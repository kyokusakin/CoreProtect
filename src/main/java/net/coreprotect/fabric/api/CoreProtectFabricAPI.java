package net.coreprotect.fabric.api;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.language.PhraseService;
import net.coreprotect.language.Phrase;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.service.RollbackExecutionResult;
import net.coreprotect.fabric.service.RollbackService;
import net.coreprotect.fabric.util.QueryBounds;
import net.coreprotect.fabric.util.TransientLookupCache;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public final class CoreProtectFabricAPI {
    private static final int API_VERSION = 11;
    private static final int DEFAULT_LOOKUP_LIMIT = 100;
    private static final String SESSION_TYPE_ID = "0";

    public static final class ParseResult {
        private final StoredEventRecord record;
        private final String[] legacy;

        public ParseResult(StoredEventRecord record) {
            if (record == null) {
                throw new IllegalArgumentException("record cannot be null");
            }
            this.record = record;
            this.legacy = null;
        }

        public ParseResult(String[] legacy) {
            if (legacy == null) {
                throw new IllegalArgumentException("legacy cannot be null");
            }
            this.record = null;
            this.legacy = legacy;
        }

        public StoredEventRecord record() {
            return record;
        }

        public String[] raw() {
            return legacy;
        }

        public CoreProtectEventType getEventType() {
            if (record != null) {
                return record.type();
            }

            if (legacy.length < 8) {
                return null;
            }
            if (legacy.length < 13 && SESSION_TYPE_ID.equals(legacy[6])) {
                return "1".equals(legacy[7]) ? CoreProtectEventType.PLAYER_JOIN : CoreProtectEventType.PLAYER_QUIT;
            }

            return switch (getActionId()) {
                case 0 -> CoreProtectEventType.BLOCK_BREAK;
                case 1 -> CoreProtectEventType.BLOCK_PLACE;
                case 2 -> CoreProtectEventType.BLOCK_USE;
                case 3 -> CoreProtectEventType.ENTITY_KILL;
                default -> null;
            };
        }

        public int getActionId() {
            if (record != null) {
                return actionId(record.type());
            }
            return parseInteger(legacy.length < 8 ? null : legacy[7], -1);
        }

        public String getActionString() {
            if (legacy != null && legacy.length < 13 && SESSION_TYPE_ID.equals(legacy[6])) {
                return switch (getActionId()) {
                    case 0 -> "logout";
                    case 1 -> "login";
                    default -> "unknown";
                };
            }

            return switch (getActionId()) {
                case 0 -> "break";
                case 1 -> "place";
                case 2 -> "click";
                case 3 -> "kill";
                default -> "unknown";
            };
        }

        public String getPlayer() {
            return record != null ? record.actorName() : (legacy.length > 1 ? legacy[1] : null);
        }

        @Deprecated
        public int getTime() {
            if (record != null) {
                return (int) (record.timestamp() / 1000L);
            }
            return parseInteger(legacy.length > 0 ? legacy[0] : null, 0);
        }

        public long getTimestamp() {
            if (record != null) {
                return record.timestamp();
            }
            return parseLong(legacy.length > 0 ? legacy[0] : null, 0L) * 1000L;
        }

        public String getType() {
            if (record != null) {
                return legacyType(record);
            }
            return legacy.length < 13 ? null : legacy[5];
        }

        public String getTypeKey() {
            return getType();
        }

        public int getData() {
            if (record != null) {
                return 0;
            }
            return parseInteger(legacy.length < 13 ? null : legacy[6], 0);
        }

        public String getBlockData() {
            if (record != null) {
                return legacyBlockData(record);
            }
            return legacy.length < 13 ? null : legacy[12];
        }

        public String getBlockDataString() {
            return getBlockData();
        }

        public boolean hasPosition() {
            if (record != null) {
                return record.hasPosition();
            }
            return legacy.length > 4;
        }

        public int getX() {
            if (record != null) {
                return record.x() == null ? 0 : record.x();
            }
            return parseInteger(legacy.length > 2 ? legacy[2] : null, 0);
        }

        public int getY() {
            if (record != null) {
                return record.y() == null ? 0 : record.y();
            }
            return parseInteger(legacy.length > 3 ? legacy[3] : null, 0);
        }

        public int getZ() {
            if (record != null) {
                return record.z() == null ? 0 : record.z();
            }
            return parseInteger(legacy.length > 4 ? legacy[4] : null, 0);
        }

        public String getTarget() {
            return record != null ? record.target() : getType();
        }

        public String getPayload() {
            return record != null ? record.payload() : getBlockData();
        }

        public boolean isRolledBack() {
            if (record != null) {
                return record.rolledBack();
            }
            return parseInteger(legacy.length < 13 ? null : legacy[8], 0) != 0;
        }

        public String worldName() {
            return toLegacyWorldName(getWorldKey());
        }

        public String getWorldKey() {
            if (record != null) {
                return record.worldKey();
            }
            return legacy.length < 13 ? (legacy.length > 5 ? legacy[5] : "") : legacy[9];
        }

        private String toLegacyWorldName(String worldKey) {
            if (worldKey == null || worldKey.isBlank()) {
                return "";
            }

            return switch (worldKey) {
                case "minecraft:overworld" -> "world";
                case "minecraft:the_nether" -> "world_nether";
                case "minecraft:the_end" -> "world_the_end";
                default -> worldKey;
            };
        }

        private int parseInteger(String value, int fallback) {
            try {
                return value == null ? fallback : Integer.parseInt(value);
            }
            catch (NumberFormatException exception) {
                return fallback;
            }
        }

        private long parseLong(String value, long fallback) {
            try {
                return value == null ? fallback : Long.parseLong(value);
            }
            catch (NumberFormatException exception) {
                return fallback;
            }
        }

    }

    public int apiVersion() {
        return API_VERSION;
    }

    public int APIVersion() {
        return API_VERSION;
    }

    public void testAPI() {
        CoreProtectFabricMod.LOGGER.info(Phrase.build(Phrase.API_TEST));
    }

    public boolean isEnabled() {
        return runtime() != null && runtime().database() != null && runtime().logger() != null;
    }

    public ParseResult parseResult(StoredEventRecord result) {
        return new ParseResult(result);
    }

    public ParseResult parseResult(String[] result) {
        return new ParseResult(result);
    }

    public List<String[]> blockLookup(ServerWorld world, BlockPos pos, int seconds) {
        return toLegacyLookupResults(blockLookupRecords(world, pos, seconds));
    }

    public List<StoredEventRecord> blockLookupRecords(ServerWorld world, BlockPos pos, int seconds) {
        FabricRuntime runtime = runtime();
        if (runtime == null || world == null || pos == null || seconds < 0) {
            return List.of();
        }
        return runtime.database().lookupHistory(
            world.getRegistryKey().getValue().toString(),
            pos,
            0,
            0,
            seconds,
            DEFAULT_LOOKUP_LIMIT,
            null,
            null,
            null,
            null,
            null
        );
    }

    public List<String[]> queueLookup(ServerWorld world, BlockPos pos) {
        return toLegacyLookupResults(queueLookupRecords(world, pos));
    }

    public List<StoredEventRecord> queueLookupRecords(ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return List.of();
        }

        String worldKey = world.getRegistryKey().getValue().toString();
        List<StoredEventRecord> pending = new ArrayList<>();
        TransientLookupCache.CachedBlockAction removed = TransientLookupCache.findRemovedAction(worldKey, pos);
        if (removed != null) {
            pending.add(new StoredEventRecord(-2L, removed.timestampMs(), CoreProtectEventType.BLOCK_BREAK, null, removed.actorName(), worldKey, pos.getX(), pos.getY(), pos.getZ(), removed.stateKey(), null, false));
        }
        TransientLookupCache.CachedBlockAction placed = TransientLookupCache.findPlacedAction(worldKey, pos);
        if (placed != null) {
            pending.add(new StoredEventRecord(-1L, placed.timestampMs(), CoreProtectEventType.BLOCK_PLACE, null, placed.actorName(), worldKey, pos.getX(), pos.getY(), pos.getZ(), placed.stateKey(), null, false));
        }
        pending.sort((left, right) -> Long.compare(right.timestamp(), left.timestamp()));
        return pending;
    }

    public List<String[]> sessionLookup(String actorName, int seconds) {
        return sessionLookup(actorName, seconds, DEFAULT_LOOKUP_LIMIT);
    }

    public List<String[]> sessionLookup(String actorName, int seconds, int limit) {
        return toLegacySessionResults(sessionLookupRecords(actorName, seconds, limit));
    }

    public List<StoredEventRecord> sessionLookupRecords(String actorName, int seconds) {
        return sessionLookupRecords(actorName, seconds, DEFAULT_LOOKUP_LIMIT);
    }

    public List<StoredEventRecord> sessionLookupRecords(String actorName, int seconds, int limit) {
        FabricRuntime runtime = runtime();
        String normalizedActorName = normalizeActorName(actorName);
        if (runtime == null || normalizedActorName == null || seconds < 0 || limit <= 0) {
            return List.of();
        }
        return runtime.database().lookupHistory(
            null,
            (BlockPos) null,
            (Integer) null,
            0,
            seconds,
            limit,
            List.of(normalizedActorName),
            null,
            List.of(CoreProtectEventType.PLAYER_JOIN, CoreProtectEventType.PLAYER_QUIT),
            null,
            null
        );
    }

    public boolean hasPlaced(String actorName, ServerWorld world, BlockPos pos, int seconds, int offsetSeconds) {
        return hasAction(actorName, world, pos, seconds, offsetSeconds, CoreProtectEventType.BLOCK_PLACE);
    }

    public boolean hasRemoved(String actorName, ServerWorld world, BlockPos pos, int seconds, int offsetSeconds) {
        return hasAction(actorName, world, pos, seconds, offsetSeconds, CoreProtectEventType.BLOCK_BREAK);
    }

    public List<StoredEventRecord> performLookup(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        int offset,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        FabricRuntime runtime = runtime();
        if (runtime == null || limit <= 0) {
            return List.of();
        }
        List<String> normalizedActorNames = normalizeUserFilters(actorNames);
        List<String> normalizedExcludeActorNames = normalizeUserFilters(excludeActorNames);
        List<String> normalizedIncludeTargets = normalizeTargetStrings(includeTargets);
        List<String> normalizedExcludeTargets = normalizeTargetStrings(excludeTargets);
        return runtime.database().lookupHistory(
            worldKey,
            center,
            radius,
            minimumSeconds,
            maximumSeconds,
            limit,
            offset,
            normalizedActorNames,
            normalizedExcludeActorNames,
            actionFilter,
            normalizedIncludeTargets,
            normalizedExcludeTargets
        );
    }

    public List<String[]> performLookup(
        int time,
        List<String> restrictUsers,
        List<String> excludeUsers,
        List<Object> restrictBlocks,
        List<Object> excludeBlocks,
        List<Integer> actionList,
        int radius,
        ServerWorld radiusWorld,
        BlockPos radiusLocation
    ) {
        if (!isEnabled()) {
            return null;
        }

        LegacyQuery query = buildLegacyQuery(restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusWorld, radiusLocation);
        if (query == null) {
            return null;
        }

        return toLegacyLookupResults(performLookup(
            query.worldKey(),
            query.center(),
            query.radius(),
            0,
            time,
            DEFAULT_LOOKUP_LIMIT,
            0,
            query.actorNames(),
            query.excludeActorNames(),
            query.actionFilter(),
            query.includeTargets(),
            query.excludeTargets()
        ));
    }

    @Deprecated
    public List<String[]> performLookup(String user, int time, int radius, ServerWorld radiusWorld, BlockPos radiusLocation, List<Object> restrict, List<Object> exclude) {
        List<String> restrictUsers = user == null ? null : List.of(user);
        return performLookup(time, restrictUsers, null, restrict, exclude, null, radius, radiusWorld, radiusLocation);
    }

    public List<StoredEventRecord> performPartialLookup(
        String worldKey,
        BlockPos center,
        Integer radius,
        int minimumSeconds,
        int maximumSeconds,
        int limit,
        int offset,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        return performLookup(
            worldKey,
            center,
            radius,
            minimumSeconds,
            maximumSeconds,
            limit,
            offset,
            actorNames,
            excludeActorNames,
            actionFilter,
            includeTargets,
            excludeTargets
        );
    }

    public List<String[]> performPartialLookup(
        int time,
        List<String> restrictUsers,
        List<String> excludeUsers,
        List<Object> restrictBlocks,
        List<Object> excludeBlocks,
        List<Integer> actionList,
        int radius,
        ServerWorld radiusWorld,
        BlockPos radiusLocation,
        int limitOffset,
        int limitCount
    ) {
        if (!isEnabled()) {
            return null;
        }

        LegacyQuery query = buildLegacyQuery(restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusWorld, radiusLocation);
        if (query == null) {
            return null;
        }

        return toLegacyLookupResults(performPartialLookup(
            query.worldKey(),
            query.center(),
            query.radius(),
            0,
            time,
            limitCount,
            limitOffset,
            query.actorNames(),
            query.excludeActorNames(),
            query.actionFilter(),
            query.includeTargets(),
            query.excludeTargets()
        ));
    }

    @Deprecated
    public List<String[]> performPartialLookup(
        String user,
        int time,
        int radius,
        ServerWorld radiusWorld,
        BlockPos radiusLocation,
        List<Object> restrict,
        List<Object> exclude,
        int limitOffset,
        int limitCount
    ) {
        List<String> restrictUsers = user == null ? null : List.of(user);
        return performPartialLookup(time, restrictUsers, null, restrict, exclude, null, radius, radiusWorld, radiusLocation, limitOffset, limitCount);
    }

    public RollbackExecutionResult performRollback(
        ServerPlayerEntity player,
        long notBefore,
        long notAfter,
        String worldKey,
        QueryBounds bounds,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        FabricRuntime runtime = runtime();
        if (runtime == null || player == null) {
            return new RollbackExecutionResult(false, 0, 0, 0, -1, 0, null);
        }
        List<String> normalizedActorNames = normalizeUserFilters(actorNames);
        List<String> normalizedExcludeActorNames = normalizeUserFilters(excludeActorNames);
        List<String> normalizedIncludeTargets = normalizeTargetStrings(includeTargets);
        List<String> normalizedExcludeTargets = normalizeTargetStrings(excludeTargets);
        return runtime.rollback().applyBetween(
            player,
            notBefore,
            notAfter,
            worldKey,
            bounds,
            normalizedActorNames,
            normalizedExcludeActorNames,
            false,
            actionFilter,
            normalizedIncludeTargets,
            normalizedExcludeTargets
        );
    }

    public List<String[]> performRollback(
        ServerPlayerEntity player,
        int time,
        List<String> restrictUsers,
        List<String> excludeUsers,
        List<Object> restrictBlocks,
        List<Object> excludeBlocks,
        List<Integer> actionList,
        int radius,
        ServerWorld radiusWorld,
        BlockPos radiusLocation
    ) {
        return toLegacyLookupResults(performLegacyRollbackRestore(player, time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusWorld, radiusLocation, false));
    }

    @Deprecated
    public List<String[]> performRollback(
        ServerPlayerEntity player,
        String user,
        int time,
        int radius,
        ServerWorld radiusWorld,
        BlockPos radiusLocation,
        List<Object> restrict,
        List<Object> exclude
    ) {
        List<String> restrictUsers = user == null ? null : List.of(user);
        return performRollback(player, time, restrictUsers, null, restrict, exclude, null, radius, radiusWorld, radiusLocation);
    }

    public RollbackExecutionResult performRestore(
        ServerPlayerEntity player,
        long notBefore,
        long notAfter,
        String worldKey,
        QueryBounds bounds,
        List<String> actorNames,
        List<String> excludeActorNames,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        FabricRuntime runtime = runtime();
        if (runtime == null || player == null) {
            return new RollbackExecutionResult(true, 0, 0, 0, -1, 0, null);
        }
        List<String> normalizedActorNames = normalizeUserFilters(actorNames);
        List<String> normalizedExcludeActorNames = normalizeUserFilters(excludeActorNames);
        List<String> normalizedIncludeTargets = normalizeTargetStrings(includeTargets);
        List<String> normalizedExcludeTargets = normalizeTargetStrings(excludeTargets);
        return runtime.rollback().applyBetween(
            player,
            notBefore,
            notAfter,
            worldKey,
            bounds,
            normalizedActorNames,
            normalizedExcludeActorNames,
            true,
            actionFilter,
            normalizedIncludeTargets,
            normalizedExcludeTargets
        );
    }

    public List<String[]> performRestore(
        ServerPlayerEntity player,
        int time,
        List<String> restrictUsers,
        List<String> excludeUsers,
        List<Object> restrictBlocks,
        List<Object> excludeBlocks,
        List<Integer> actionList,
        int radius,
        ServerWorld radiusWorld,
        BlockPos radiusLocation
    ) {
        return toLegacyLookupResults(performLegacyRollbackRestore(player, time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusWorld, radiusLocation, true));
    }

    @Deprecated
    public List<String[]> performRestore(
        ServerPlayerEntity player,
        String user,
        int time,
        int radius,
        ServerWorld radiusWorld,
        BlockPos radiusLocation,
        List<Object> restrict,
        List<Object> exclude
    ) {
        List<String> restrictUsers = user == null ? null : List.of(user);
        return performRestore(player, time, restrictUsers, null, restrict, exclude, null, radius, radiusWorld, radiusLocation);
    }

    public void performPurge(int seconds) {
        FabricRuntime runtime = runtime();
        if (runtime == null || seconds < 0) {
            return;
        }
        runtime.database().purgeOlderThan(seconds, null, null);
    }

    public boolean logChat(String actorName, ServerWorld world, BlockPos pos, String message) {
        FabricRuntime runtime = runtime();
        String normalizedActorName = normalizeActorName(actorName);
        String normalizedMessage = trimToNull(message);
        if (runtime == null || normalizedActorName == null || world == null || pos == null) {
            return false;
        }
        if (normalizedMessage == null || normalizedMessage.startsWith("/")) {
            return false;
        }
        runtime.logger().logChat(normalizedActorName, world, pos, normalizedMessage);
        return true;
    }

    public boolean logCommand(String actorName, ServerWorld world, BlockPos pos, String command) {
        FabricRuntime runtime = runtime();
        String normalizedActorName = normalizeActorName(actorName);
        String normalizedCommand = trimToNull(command);
        if (runtime == null || normalizedActorName == null || world == null || pos == null) {
            return false;
        }
        if (normalizedCommand == null || !normalizedCommand.startsWith("/")) {
            return false;
        }
        runtime.logger().logCommand(normalizedActorName, world, pos, normalizedCommand);
        return true;
    }

    public boolean logInteraction(String actorName, ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        return logInteraction(actorName, world, pos, world.getBlockState(pos));
    }

    public boolean logInteraction(String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        FabricRuntime runtime = runtime();
        String normalizedActorName = normalizeActorName(actorName);
        if (runtime == null || normalizedActorName == null || world == null || pos == null || state == null) {
            return false;
        }
        runtime.logger().logBlockUse(normalizedActorName, world, pos, state);
        return true;
    }

    public boolean logPlacement(String actorName, ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        return logPlacement(actorName, world, pos, world.getBlockState(pos));
    }

    public boolean logPlacement(String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        FabricRuntime runtime = runtime();
        String normalizedActorName = normalizeActorName(actorName);
        if (runtime == null || normalizedActorName == null || world == null || pos == null || state == null) {
            return false;
        }
        runtime.logger().logBlockPlace(null, normalizedActorName, world, pos, state);
        return true;
    }

    public boolean logRemoval(String actorName, ServerWorld world, BlockPos pos) {
        if (world == null || pos == null) {
            return false;
        }
        return logRemoval(actorName, world, pos, world.getBlockState(pos));
    }

    public boolean logRemoval(String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        FabricRuntime runtime = runtime();
        String normalizedActorName = normalizeActorName(actorName);
        if (runtime == null || normalizedActorName == null || world == null || pos == null || state == null) {
            return false;
        }
        runtime.logger().logBlockBreak(null, normalizedActorName, world, pos, state);
        return true;
    }

    public boolean logSignChange(String actorName, ServerWorld world, BlockPos pos, boolean front, String[] lines) {
        FabricRuntime runtime = runtime();
        String normalizedActorName = normalizeActorName(actorName);
        if (runtime == null || normalizedActorName == null || world == null || pos == null) {
            return false;
        }
        runtime.logger().logSignChange(null, normalizedActorName, world, pos, front, normalizeSignLines(lines));
        return true;
    }

    public boolean logContainerTransaction(
        String actorName,
        String worldKey,
        BlockPos pos,
        String containerType,
        int slotIndex,
        int button,
        SlotActionType actionType,
        ItemStack beforeSlot,
        ItemStack afterSlot,
        ItemStack beforeCursor,
        ItemStack afterCursor
    ) {
        FabricRuntime runtime = runtime();
        String normalizedActorName = normalizeActorName(actorName);
        String normalizedWorldKey = trimToNull(worldKey);
        String normalizedContainerType = trimToNull(containerType);
        if (runtime == null || normalizedActorName == null || normalizedWorldKey == null || pos == null || actionType == null) {
            return false;
        }
        runtime.logger().logContainerTransaction(normalizedActorName, normalizedWorldKey, pos, normalizedContainerType, slotIndex, button, actionType, beforeSlot, afterSlot, beforeCursor, afterCursor);
        return true;
    }

    public boolean logEntityPlacement(String actorName, ServerWorld world, BlockPos pos, net.minecraft.entity.Entity entity) {
        FabricRuntime runtime = runtime();
        String normalizedActorName = normalizeActorName(actorName);
        if (runtime == null || normalizedActorName == null || world == null || pos == null || entity == null) {
            return false;
        }
        runtime.logger().logEntityPlace(normalizedActorName, world, pos, entity);
        return true;
    }

    public boolean logEntityRemoval(String actorName, ServerWorld world, BlockPos pos, net.minecraft.entity.Entity entity) {
        FabricRuntime runtime = runtime();
        String normalizedActorName = normalizeActorName(actorName);
        if (runtime == null || normalizedActorName == null || world == null || pos == null || entity == null) {
            return false;
        }
        runtime.logger().logEntityBreak(normalizedActorName, world, pos, entity);
        return true;
    }

    private boolean hasAction(String actorName, ServerWorld world, BlockPos pos, int seconds, int offsetSeconds, CoreProtectEventType eventType) {
        FabricRuntime runtime = runtime();
        String normalizedActorName = normalizeActorName(actorName);
        if (runtime == null || normalizedActorName == null || world == null || pos == null || seconds < 0 || offsetSeconds < 0) {
            return false;
        }

        long cutoff = System.currentTimeMillis() - (offsetSeconds * 1000L);
        List<StoredEventRecord> events = runtime.database().lookupHistory(
            world.getRegistryKey().getValue().toString(),
            pos,
            0,
            0,
            seconds,
            DEFAULT_LOOKUP_LIMIT,
            List.of(normalizedActorName),
            null,
            List.of(eventType),
            null,
            null
        );
        for (StoredEventRecord event : events) {
            if (event.type() == eventType && event.timestamp() <= cutoff) {
                return true;
            }
        }
        return false;
    }

    private List<StoredEventRecord> performLegacyRollbackRestore(
        ServerPlayerEntity player,
        int time,
        List<String> restrictUsers,
        List<String> excludeUsers,
        List<Object> restrictBlocks,
        List<Object> excludeBlocks,
        List<Integer> actionList,
        int radius,
        ServerWorld radiusWorld,
        BlockPos radiusLocation,
        boolean restore
    ) {
        if (!isEnabled() || player == null) {
            return null;
        }

        LegacyQuery query = buildLegacyQuery(restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusWorld, radiusLocation);
        if (query == null) {
            return null;
        }

        List<CoreProtectEventType> rollbackActions = filterRollbackActions(query.actionFilter());
        if (query.actionFilter() != null && rollbackActions.isEmpty()) {
            return List.of();
        }

        List<StoredEventRecord> candidates = lookupLegacyRollbackCandidates(query, time, rollbackActions, restore);
        if (candidates.isEmpty()) {
            return candidates;
        }

        FabricRuntime runtime = runtime();
        if (query.bounds() != null) {
            runtime.rollback().apply(
                player,
                0,
                time,
                query.bounds(),
                query.actorNames(),
                query.excludeActorNames(),
                restore,
                rollbackActions,
                query.includeTargets(),
                query.excludeTargets()
            );
        }
        else {
            runtime.rollback().apply(
                player,
                0,
                time,
                (Integer) null,
                query.worldKey(),
                query.actorNames(),
                query.excludeActorNames(),
                restore,
                rollbackActions,
                query.includeTargets(),
                query.excludeTargets()
            );
        }
        return candidates;
    }

    private List<StoredEventRecord> lookupLegacyRollbackCandidates(LegacyQuery query, int time, List<CoreProtectEventType> rollbackActions, boolean restore) {
        FabricRuntime runtime = runtime();
        if (runtime == null) {
            return List.of();
        }

        if (query.bounds() != null) {
            return runtime.database().lookupRollbackCandidates(
                query.bounds().worldKey(),
                query.bounds().minimum(),
                query.bounds().maximum(),
                0,
                time,
                query.actorNames(),
                query.excludeActorNames(),
                restore,
                restore,
                rollbackActions,
                query.includeTargets(),
                query.excludeTargets()
            );
        }

        return runtime.database().lookupRollbackCandidates(
            query.worldKey(),
            (BlockPos) null,
            (Integer) null,
            0,
            time,
            query.actorNames(),
            query.excludeActorNames(),
            restore,
            restore,
            rollbackActions,
            query.includeTargets(),
            query.excludeTargets()
        );
    }

    private LegacyQuery buildLegacyQuery(
        List<String> restrictUsers,
        List<String> excludeUsers,
        List<Object> restrictBlocks,
        List<Object> excludeBlocks,
        List<Integer> actionList,
        int radius,
        ServerWorld radiusWorld,
        BlockPos radiusLocation
    ) {
        Integer normalizedRadius = radius > 0 ? radius : null;
        if (normalizedRadius != null && (radiusWorld == null || radiusLocation == null)) {
            return null;
        }

        List<String> actorNames = normalizeUserFilters(restrictUsers);
        if ((actorNames == null || actorNames.isEmpty()) && normalizedRadius == null) {
            return null;
        }

        List<String> excludeActorNames = normalizeUserFilters(excludeUsers);
        List<Integer> actionIds = resolveLegacyActionIds(actionList, restrictBlocks);
        List<CoreProtectEventType> eventTypes = mapLegacyActionIds(actionIds);
        List<String> includeTargets = normalizeTargetFilters(restrictBlocks);
        List<String> excludeTargets = normalizeTargetFilters(excludeBlocks);

        QueryBounds bounds = null;
        String worldKey = null;
        BlockPos center = null;
        Integer queryRadius = null;
        if (normalizedRadius != null) {
            worldKey = radiusWorld.getRegistryKey().getValue().toString();
            center = radiusLocation.toImmutable();
            queryRadius = normalizedRadius;
            bounds = new QueryBounds(
                worldKey,
                new BlockPos(center.getX() - normalizedRadius, radiusWorld.getBottomY(), center.getZ() - normalizedRadius),
                new BlockPos(center.getX() + normalizedRadius, radiusWorld.getTopYInclusive(), center.getZ() + normalizedRadius)
            );
        }

        return new LegacyQuery(actorNames, excludeActorNames, includeTargets, excludeTargets, eventTypes, worldKey, center, queryRadius, bounds);
    }

    private List<String> normalizeUserFilters(List<String> users) {
        if (users == null || users.isEmpty()) {
            return null;
        }

        List<String> normalized = new ArrayList<>();
        for (String user : users) {
            String normalizedUser = normalizeActorName(user);
            if (normalizedUser == null || containsIgnoreCase(normalized, normalizedUser)) {
                continue;
            }
            normalized.add(normalizedUser);
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private List<Integer> resolveLegacyActionIds(List<Integer> actionList, List<Object> restrictBlocks) {
        List<Integer> resolved = new ArrayList<>();
        if (actionList != null) {
            for (Integer action : actionList) {
                if (action != null) {
                    resolved.add(action);
                }
            }
        }

        if (resolved.isEmpty() && restrictBlocks != null && !restrictBlocks.isEmpty()) {
            boolean hasBlockFilter = false;
            boolean hasEntityFilter = false;
            for (Object value : restrictBlocks) {
                if (isEntityFilter(value)) {
                    hasEntityFilter = true;
                }
                else if (isBlockFilter(value)) {
                    hasBlockFilter = true;
                }
            }
            if (hasBlockFilter) {
                resolved.add(0);
                resolved.add(1);
            }
            if (hasEntityFilter) {
                resolved.add(3);
            }
        }

        if (resolved.isEmpty()) {
            resolved.add(0);
            resolved.add(1);
        }

        resolved.removeIf(action -> action == null || action < 0 || action > 3);
        return resolved;
    }

    private List<CoreProtectEventType> mapLegacyActionIds(List<Integer> actionIds) {
        if (actionIds == null || actionIds.isEmpty()) {
            return null;
        }

        List<CoreProtectEventType> eventTypes = new ArrayList<>();
        for (Integer actionId : actionIds) {
            switch (actionId) {
                case 0 -> addIfMissing(eventTypes, CoreProtectEventType.BLOCK_BREAK);
                case 1 -> addIfMissing(eventTypes, CoreProtectEventType.BLOCK_PLACE);
                case 2 -> {
                    addIfMissing(eventTypes, CoreProtectEventType.BLOCK_USE);
                    addIfMissing(eventTypes, CoreProtectEventType.CONTAINER_TRANSACTION);
                    addIfMissing(eventTypes, CoreProtectEventType.ENTITY_USE);
                    addIfMissing(eventTypes, CoreProtectEventType.SIGN_CHANGE);
                }
                case 3 -> addIfMissing(eventTypes, CoreProtectEventType.ENTITY_KILL);
                default -> {
                }
            }
        }
        return eventTypes.isEmpty() ? null : eventTypes;
    }

    private List<CoreProtectEventType> filterRollbackActions(List<CoreProtectEventType> actionFilter) {
        if (actionFilter == null || actionFilter.isEmpty()) {
            return null;
        }

        List<CoreProtectEventType> filtered = new ArrayList<>();
        for (CoreProtectEventType eventType : actionFilter) {
            if (RollbackService.isSupported(eventType)) {
                addIfMissing(filtered, eventType);
            }
        }
        return filtered;
    }

    private List<String> normalizeTargetFilters(List<Object> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }

        List<String> normalized = new ArrayList<>();
        for (Object filter : filters) {
            for (String mapped : expandTargetFilters(filter)) {
                if (mapped == null || containsIgnoreCase(normalized, mapped)) {
                    continue;
                }
                normalized.add(mapped);
            }
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private List<String> normalizeTargetStrings(List<String> filters) {
        if (filters == null || filters.isEmpty()) {
            return null;
        }

        List<String> normalized = new ArrayList<>();
        for (String filter : filters) {
            for (String mapped : expandTargetFilters(filter)) {
                if (mapped == null || containsIgnoreCase(normalized, mapped)) {
                    continue;
                }
                normalized.add(mapped);
            }
        }
        return normalized.isEmpty() ? null : normalized;
    }

    private List<String> expandTargetFilters(Object filter) {
        if (filter instanceof String value) {
            String normalizedValue = trimToNull(value);
            if ("#natural".equalsIgnoreCase(normalizedValue)) {
                return naturalTargetFilters();
            }
        }

        String mapped = mapTargetFilter(filter);
        if (mapped == null) {
            return List.of();
        }
        return List.of(mapped);
    }

    private List<String> naturalTargetFilters() {
        List<String> targets = new ArrayList<>();
        addNaturalTagTargets(targets, BlockTags.LOGS);
        addNaturalTagTargets(targets, BlockTags.LEAVES);
        addNaturalTagTargets(targets, BlockTags.SAND);
        addNaturalTagTargets(targets, BlockTags.ICE);
        addTargetId(targets, Blocks.STONE);
        addTargetId(targets, Blocks.GRASS_BLOCK);
        addTargetId(targets, Blocks.DIRT);
        addTargetId(targets, Blocks.COARSE_DIRT);
        addTargetId(targets, Blocks.ROOTED_DIRT);
        addTargetId(targets, Blocks.PODZOL);
        addTargetId(targets, Blocks.MYCELIUM);
        addTargetId(targets, Blocks.CLAY);
        addTargetId(targets, Blocks.SNOW);
        addTargetId(targets, Blocks.CACTUS);
        addTargetId(targets, Blocks.SUGAR_CANE);
        addTargetId(targets, Blocks.BAMBOO);
        addTargetId(targets, Blocks.BAMBOO_SAPLING);
        addTargetId(targets, Blocks.KELP);
        addTargetId(targets, Blocks.KELP_PLANT);
        addTargetId(targets, Blocks.CHORUS_FLOWER);
        addTargetId(targets, Blocks.CHORUS_PLANT);
        addTargetId(targets, Blocks.BROWN_MUSHROOM);
        addTargetId(targets, Blocks.RED_MUSHROOM);
        addTargetId(targets, Blocks.BROWN_MUSHROOM_BLOCK);
        addTargetId(targets, Blocks.RED_MUSHROOM_BLOCK);
        addTargetId(targets, Blocks.MUSHROOM_STEM);
        addTargetId(targets, Blocks.SWEET_BERRY_BUSH);
        addTargetId(targets, Blocks.NETHER_WART);
        addTargetId(targets, Blocks.PUMPKIN);
        addTargetId(targets, Blocks.MELON);
        addTargetId(targets, Blocks.PUMPKIN_STEM);
        addTargetId(targets, Blocks.MELON_STEM);
        addTargetId(targets, Blocks.CORNFLOWER);
        addTargetId(targets, Blocks.LILY_OF_THE_VALLEY);
        addTargetId(targets, Blocks.WITHER_ROSE);
        addTargetId(targets, Blocks.FERN);
        addTargetId(targets, Blocks.DEAD_BUSH);
        addTargetId(targets, Blocks.DANDELION);
        addTargetId(targets, Blocks.POPPY);
        addTargetId(targets, Blocks.BLUE_ORCHID);
        addTargetId(targets, Blocks.ALLIUM);
        addTargetId(targets, Blocks.AZURE_BLUET);
        addTargetId(targets, Blocks.RED_TULIP);
        addTargetId(targets, Blocks.ORANGE_TULIP);
        addTargetId(targets, Blocks.WHITE_TULIP);
        addTargetId(targets, Blocks.PINK_TULIP);
        addTargetId(targets, Blocks.OXEYE_DAISY);
        return targets;
    }

    private void addNaturalTagTargets(List<String> targets, net.minecraft.registry.tag.TagKey<net.minecraft.block.Block> tag) {
        for (net.minecraft.registry.entry.RegistryEntry<Block> entry : Registries.BLOCK.iterateEntries(tag)) {
            addTargetId(targets, entry.value());
        }
    }

    private void addTargetId(List<String> targets, Block block) {
        String normalized = normalizeIdentifier(Registries.BLOCK.getId(block));
        if (normalized != null && !containsIgnoreCase(targets, normalized)) {
            targets.add(normalized);
        }
    }

    private String mapTargetFilter(Object filter) {
        if (filter == null) {
            return null;
        }
        if (filter instanceof Block block) {
            return normalizeIdentifier(Registries.BLOCK.getId(block));
        }
        if (filter instanceof BlockState state) {
            return normalizeIdentifier(Registries.BLOCK.getId(state.getBlock()));
        }
        if (filter instanceof EntityType<?> entityType) {
            return normalizeIdentifier(Registries.ENTITY_TYPE.getId(entityType));
        }
        if (filter instanceof Identifier identifier) {
            return normalizeIdentifier(identifier);
        }
        if (filter instanceof String value) {
            String normalizedValue = trimToNull(value);
            if (normalizedValue == null) {
                return null;
            }
            Identifier identifier = extractIdentifier(normalizedValue);
            if (identifier != null && isRegisteredTargetIdentifier(identifier)) {
                return normalizeIdentifier(identifier);
            }
            return normalizedValue;
        }
        return null;
    }

    private boolean isBlockFilter(Object filter) {
        return filter instanceof Block || filter instanceof BlockState || isBlockIdentifier(filter);
    }

    private boolean isEntityFilter(Object filter) {
        return filter instanceof EntityType<?> || isEntityIdentifier(filter);
    }

    private boolean isBlockIdentifier(Object filter) {
        Identifier identifier = extractIdentifier(filter);
        return identifier != null && Registries.BLOCK.containsId(identifier);
    }

    private boolean isEntityIdentifier(Object filter) {
        Identifier identifier = extractIdentifier(filter);
        return identifier != null && Registries.ENTITY_TYPE.containsId(identifier);
    }

    private boolean isRegisteredTargetIdentifier(Identifier identifier) {
        return Registries.BLOCK.containsId(identifier)
            || Registries.ITEM.containsId(identifier)
            || Registries.ENTITY_TYPE.containsId(identifier);
    }

    private Identifier extractIdentifier(Object filter) {
        if (filter instanceof Identifier identifier) {
            return identifier;
        }
        if (filter instanceof String value) {
            String normalizedValue = trimToNull(value);
            if (normalizedValue == null) {
                return null;
            }
            return Identifier.tryParse(normalizedValue.contains(":") ? normalizedValue : "minecraft:" + normalizedValue);
        }
        return null;
    }

    private String normalizeIdentifier(Identifier identifier) {
        if (identifier == null) {
            return null;
        }
        return "minecraft".equals(identifier.getNamespace()) ? identifier.getPath() : identifier.toString();
    }

    private List<String[]> toLegacyLookupResults(List<StoredEventRecord> records) {
        if (records == null) {
            return null;
        }

        List<String[]> results = new ArrayList<>();
        for (StoredEventRecord record : records) {
            results.add(toLegacyLookupResult(record));
        }
        return results;
    }

    private List<String[]> toLegacySessionResults(List<StoredEventRecord> records) {
        if (records == null) {
            return null;
        }

        List<String[]> results = new ArrayList<>();
        for (StoredEventRecord record : records) {
            results.add(new String[] {
                Long.toString(record.timestamp() / 1000L),
                blankIfNull(record.actorName()),
                Integer.toString(record.x() == null ? 0 : record.x()),
                Integer.toString(record.y() == null ? 0 : record.y()),
                Integer.toString(record.z() == null ? 0 : record.z()),
                blankIfNull(record.worldKey()),
                SESSION_TYPE_ID,
                Integer.toString(actionId(record.type()))
            });
        }
        return results;
    }

    private String[] toLegacyLookupResult(StoredEventRecord record) {
        return new String[] {
            Long.toString(record.timestamp() / 1000L),
            blankIfNull(record.actorName()),
            Integer.toString(record.x() == null ? 0 : record.x()),
            Integer.toString(record.y() == null ? 0 : record.y()),
            Integer.toString(record.z() == null ? 0 : record.z()),
            blankIfNull(legacyType(record)),
            "0",
            Integer.toString(actionId(record.type())),
            record.rolledBack() ? "1" : "0",
            blankIfNull(record.worldKey()),
            "",
            "",
            blankIfNull(legacyBlockData(record))
        };
    }

    private static String legacyType(StoredEventRecord record) {
        if (record == null) {
            return "";
        }

        return switch (record.type()) {
            case BLOCK_BREAK, BLOCK_PLACE, BLOCK_USE -> simplifyTarget(record.target());
            case ENTITY_PLACE, ENTITY_BREAK -> parsePayloadValue(record.payload(), "type");
            case ENTITY_KILL -> simplifyTarget(parsePayloadValue(record.payload(), "target"));
            case SIGN_CHANGE -> "sign";
            case CONTAINER_TRANSACTION -> "container";
            case PLAYER_CHAT -> "chat";
            case PLAYER_COMMAND -> "command";
            case USERNAME_CHANGE -> "username";
            default -> simplifyTarget(record.target());
        };
    }

    private static String legacyBlockData(StoredEventRecord record) {
        if (record == null || record.payload() == null) {
            return "";
        }

        return switch (record.type()) {
            case BLOCK_BREAK, BLOCK_PLACE, BLOCK_USE, SIGN_CHANGE -> record.payload();
            default -> "";
        };
    }

    private static String parsePayloadValue(String payload, String key) {
        if (payload == null || payload.isBlank() || key == null || key.isBlank()) {
            return "";
        }

        for (String line : payload.split("\\n")) {
            if (line.startsWith(key + "=")) {
                return line.substring((key + "=").length());
            }
        }
        return "";
    }

    private static String simplifyTarget(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }

        String simplified = value.toLowerCase(Locale.ROOT);
        int viaIndex = simplified.indexOf(" via ");
        if (viaIndex > 0) {
            simplified = simplified.substring(0, viaIndex);
        }
        int spaceIndex = simplified.indexOf(' ');
        if (spaceIndex > 0) {
            simplified = simplified.substring(0, spaceIndex);
        }
        return simplified.startsWith("minecraft:") ? simplified.substring("minecraft:".length()) : simplified;
    }

    private String blankIfNull(String value) {
        return value == null ? "" : value;
    }

    private static int actionId(CoreProtectEventType eventType) {
        return switch (eventType) {
            case BLOCK_BREAK, ENTITY_BREAK, PLAYER_QUIT -> 0;
            case BLOCK_PLACE, ENTITY_PLACE, PLAYER_JOIN -> 1;
            case BLOCK_USE, ENTITY_USE, CONTAINER_TRANSACTION, SIGN_CHANGE, PLAYER_CHAT, PLAYER_COMMAND, USERNAME_CHANGE -> 2;
            case ENTITY_KILL -> 3;
            default -> -1;
        };
    }

    private String[] normalizeSignLines(String[] lines) {
        String[] normalized = new String[] { "", "", "", "" };
        if (lines == null) {
            return normalized;
        }
        for (int index = 0; index < normalized.length && index < lines.length; index++) {
            normalized[index] = lines[index] == null ? "" : lines[index];
        }
        return normalized;
    }

    private String normalizeActorName(String actorName) {
        return trimToNull(actorName);
    }

    private boolean containsIgnoreCase(List<String> values, String candidate) {
        if (values == null || candidate == null) {
            return false;
        }

        for (String value : values) {
            if (value != null && value.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }

        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void addIfMissing(List<CoreProtectEventType> eventTypes, CoreProtectEventType eventType) {
        if (!eventTypes.contains(eventType)) {
            eventTypes.add(eventType);
        }
    }

    private FabricRuntime runtime() {
        return CoreProtectFabricMod.getRuntime();
    }

    private static final class LegacyQuery {
        private final List<String> actorNames;
        private final List<String> excludeActorNames;
        private final List<String> includeTargets;
        private final List<String> excludeTargets;
        private final List<CoreProtectEventType> actionFilter;
        private final String worldKey;
        private final BlockPos center;
        private final Integer radius;
        private final QueryBounds bounds;

        private LegacyQuery(
            List<String> actorNames,
            List<String> excludeActorNames,
            List<String> includeTargets,
            List<String> excludeTargets,
            List<CoreProtectEventType> actionFilter,
            String worldKey,
            BlockPos center,
            Integer radius,
            QueryBounds bounds
        ) {
            this.actorNames = actorNames;
            this.excludeActorNames = excludeActorNames;
            this.includeTargets = includeTargets;
            this.excludeTargets = excludeTargets;
            this.actionFilter = actionFilter;
            this.worldKey = worldKey;
            this.center = center;
            this.radius = radius;
            this.bounds = bounds;
        }

        private List<String> actorNames() {
            return actorNames;
        }

        private List<String> excludeActorNames() {
            return excludeActorNames;
        }

        private List<String> includeTargets() {
            return includeTargets;
        }

        private List<String> excludeTargets() {
            return excludeTargets;
        }

        private List<CoreProtectEventType> actionFilter() {
            return actionFilter;
        }

        private String worldKey() {
            return worldKey;
        }

        private BlockPos center() {
            return center;
        }

        private Integer radius() {
            return radius;
        }

        private QueryBounds bounds() {
            return bounds;
        }
    }
}
