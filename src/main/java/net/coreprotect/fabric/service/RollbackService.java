package net.coreprotect.fabric.service;

import net.coreprotect.fabric.db.CoreProtectDatabase;
import net.coreprotect.fabric.db.PendingInventoryRollbackRecord;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.LoggedItemChange;
import net.coreprotect.fabric.util.LoggedItemData;
import net.coreprotect.fabric.util.LoggedSignState;
import net.coreprotect.fabric.util.QueryBounds;
import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.inventory.Inventory;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.state.property.Property;
import net.minecraft.state.property.Properties;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

public final class RollbackService {
    private static final int CHUNK_APPLY_SLICE_SIZE = 128;
    private static final int MAX_ENTITY_NBT_LENGTH = 32_768;
    private static final double DECORATION_SEARCH_EXTENT = 6.0D;
    private static final List<CoreProtectEventType> SUPPORTED_EVENT_TYPES = List.of(
        CoreProtectEventType.BLOCK_BREAK,
        CoreProtectEventType.BLOCK_PLACE,
        CoreProtectEventType.SIGN_CHANGE,
        CoreProtectEventType.CONTAINER_TRANSACTION,
        CoreProtectEventType.ITEM_PICKUP,
        CoreProtectEventType.ITEM_DROP,
        CoreProtectEventType.ITEM_THROW,
        CoreProtectEventType.ITEM_SHOOT,
        CoreProtectEventType.ITEM_BUY,
        CoreProtectEventType.ITEM_SELL,
        CoreProtectEventType.ITEM_CREATE,
        CoreProtectEventType.ITEM_DESTROY
    );

    private final CoreProtectDatabase database;
    private final WorldConfigService configs;
    private final Logger logger;
    private final Queue<ServerTickTask> pendingServerTasks = new ConcurrentLinkedQueue<>();
    private ServerTickTask activeServerTask;

    public RollbackService(CoreProtectDatabase database, WorldConfigService configs, Logger logger) {
        this.database = database;
        this.configs = configs;
        this.logger = logger;
    }

    public RollbackExecutionResult apply(ServerPlayerEntity player, int seconds, int radius, String actorFilter, boolean restore) {
        return apply(player, seconds, radius, actorFilter, restore, null);
    }

    public RollbackExecutionResult apply(ServerPlayerEntity player, int seconds, int radius, String actorFilter, boolean restore, List<CoreProtectEventType> actionFilter) {
        return apply(
            player,
            0,
            seconds,
            radius,
            ((ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString(),
            actorFilter == null || actorFilter.isBlank() ? null : List.of(actorFilter),
            null,
            restore,
            actionFilter,
            null,
            null
        );
    }

    public RollbackExecutionResult apply(
        ServerPlayerEntity player,
        int minimumSeconds,
        int seconds,
        QueryBounds bounds,
        List<String> actorFilters,
        List<String> excludeActorFilters,
        boolean restore,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(bounds.worldKey(), actionFilter);
        List<StoredEventRecord> candidates = database.lookupRollbackCandidates(
            bounds.worldKey(),
            bounds.minimum(),
            bounds.maximum(),
            minimumSeconds,
            seconds,
            actorFilters,
            excludeActorFilters,
            restore,
            restore,
            rollbackTypes,
            includeTargets,
            excludeTargets
        );
        candidates = filterExactBounds(bounds, candidates);
        return applyCandidates(((ServerWorld) player.getEntityWorld()).getServer(), candidates, restore, -1, seconds, summarizeActors(actorFilters));
    }

    public RollbackExecutionResult applyBetween(
        ServerPlayerEntity player,
        long notBefore,
        long notAfter,
        QueryBounds bounds,
        List<String> actorFilters,
        List<String> excludeActorFilters,
        boolean restore,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(bounds.worldKey(), actionFilter);
        List<StoredEventRecord> candidates = database.lookupRollbackCandidatesBetween(
            bounds.worldKey(),
            bounds.minimum(),
            bounds.maximum(),
            notBefore,
            notAfter,
            actorFilters,
            excludeActorFilters,
            restore,
            restore,
            rollbackTypes,
            includeTargets,
            excludeTargets
        );
        candidates = filterExactBounds(bounds, candidates);
        return applyCandidates(((ServerWorld) player.getEntityWorld()).getServer(), candidates, restore, -1, describeSeconds(notBefore, notAfter), summarizeActors(actorFilters));
    }

    public RollbackExecutionResult apply(
        ServerPlayerEntity player,
        int minimumSeconds,
        int seconds,
        Integer radius,
        String worldKey,
        List<String> actorFilters,
        List<String> excludeActorFilters,
        boolean restore,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(worldKey, actionFilter);
        BlockPos center = radius == null ? null : player.getBlockPos();
        List<StoredEventRecord> candidates = database.lookupRollbackCandidates(
            worldKey,
            center,
            radius,
            minimumSeconds,
            seconds,
            actorFilters,
            excludeActorFilters,
            restore,
            restore,
            rollbackTypes,
            includeTargets,
            excludeTargets
        );
        return applyCandidates(((ServerWorld) player.getEntityWorld()).getServer(), candidates, restore, radius == null ? -1 : radius, seconds, summarizeActors(actorFilters));
    }

    public RollbackExecutionResult applyBetween(
        ServerPlayerEntity player,
        long notBefore,
        long notAfter,
        String worldKey,
        QueryBounds bounds,
        List<String> actorFilters,
        List<String> excludeActorFilters,
        boolean restore,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(bounds == null ? worldKey : bounds.worldKey(), actionFilter);
        List<StoredEventRecord> candidates;
        if (bounds != null) {
            candidates = database.lookupRollbackCandidatesBetween(
                bounds.worldKey(),
                bounds.minimum(),
                bounds.maximum(),
                notBefore,
                notAfter,
                actorFilters,
                excludeActorFilters,
                restore,
                restore,
                rollbackTypes,
                includeTargets,
                excludeTargets
            );
            candidates = filterExactBounds(bounds, candidates);
        }
        else {
            candidates = database.lookupRollbackCandidatesBetween(
                worldKey,
                null,
                null,
                notBefore,
                notAfter,
                actorFilters,
                excludeActorFilters,
                restore,
                restore,
                rollbackTypes,
                includeTargets,
                excludeTargets
            );
        }
        return applyCandidates(((ServerWorld) player.getEntityWorld()).getServer(), candidates, restore, -1, describeSeconds(notBefore, notAfter), summarizeActors(actorFilters));
    }

    public RollbackExecutionResult applyBetween(
        MinecraftServer server,
        long notBefore,
        long notAfter,
        String worldKey,
        QueryBounds bounds,
        List<String> actorFilters,
        List<String> excludeActorFilters,
        boolean restore,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(bounds == null ? worldKey : bounds.worldKey(), actionFilter);
        List<StoredEventRecord> candidates;
        if (bounds != null) {
            candidates = database.lookupRollbackCandidatesBetween(
                bounds.worldKey(),
                bounds.minimum(),
                bounds.maximum(),
                notBefore,
                notAfter,
                actorFilters,
                excludeActorFilters,
                restore,
                restore,
                rollbackTypes,
                includeTargets,
                excludeTargets
            );
            candidates = filterExactBounds(bounds, candidates);
        }
        else {
            candidates = database.lookupRollbackCandidatesBetween(
                worldKey,
                null,
                null,
                notBefore,
                notAfter,
                actorFilters,
                excludeActorFilters,
                restore,
                restore,
                rollbackTypes,
                includeTargets,
                excludeTargets
            );
        }
        return applyCandidates(server, candidates, restore, -1, describeSeconds(notBefore, notAfter), summarizeActors(actorFilters));
    }

    public List<StoredEventRecord> collectCandidatesBetween(
        long notBefore,
        long notAfter,
        String worldKey,
        QueryBounds bounds,
        List<String> actorFilters,
        List<String> excludeActorFilters,
        boolean restore,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(bounds == null ? worldKey : bounds.worldKey(), actionFilter);
        List<StoredEventRecord> candidates;
        if (bounds != null) {
            candidates = database.lookupRollbackCandidatesBetween(
                bounds.worldKey(),
                bounds.minimum(),
                bounds.maximum(),
                notBefore,
                notAfter,
                actorFilters,
                excludeActorFilters,
                restore,
                restore,
                rollbackTypes,
                includeTargets,
                excludeTargets
            );
            return filterExactBounds(bounds, candidates);
        }

        return database.lookupRollbackCandidatesBetween(
            worldKey,
            null,
            null,
            notBefore,
            notAfter,
            actorFilters,
            excludeActorFilters,
            restore,
            restore,
            rollbackTypes,
            includeTargets,
            excludeTargets
        );
    }

    public void enqueuePreparedApply(
        List<StoredEventRecord> candidates,
        boolean restore,
        int radius,
        int seconds,
        String actorSummary,
        Consumer<RollbackExecutionResult> completion
    ) {
        if (completion == null) {
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            completion.accept(new RollbackExecutionResult(restore, 0, 0, 0, radius, seconds, actorSummary));
            return;
        }
        pendingServerTasks.add(new ApplyTask(candidates, restore, radius, seconds, actorSummary, completion));
    }

    public void enqueuePreparedPreview(
        UUID playerUuid,
        List<StoredEventRecord> candidates,
        boolean restore,
        Consumer<RollbackPreviewResult> completion
    ) {
        if (completion == null) {
            return;
        }
        if (candidates == null || candidates.isEmpty()) {
            completion.accept(new RollbackPreviewResult(0, List.of()));
            return;
        }
        pendingServerTasks.add(new PreviewTask(playerUuid, candidates, restore, completion));
    }

    public void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }

        if (activeServerTask == null) {
            activeServerTask = pendingServerTasks.poll();
        }
        if (activeServerTask == null) {
            return;
        }

        if (activeServerTask.processSlice(server)) {
            activeServerTask = null;
        }
    }

    public int preview(
        ServerPlayerEntity player,
        int minimumSeconds,
        int seconds,
        Integer radius,
        String worldKey,
        List<String> actorFilters,
        List<String> excludeActorFilters,
        boolean restore,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(worldKey, actionFilter);
        BlockPos center = radius == null ? null : player.getBlockPos();
        return database.lookupRollbackCandidates(
            worldKey,
            center,
            radius,
            minimumSeconds,
            seconds,
            actorFilters,
            excludeActorFilters,
            restore,
            restore,
            rollbackTypes,
            includeTargets,
            excludeTargets
        ).size();
    }

    public int preview(
        ServerPlayerEntity player,
        int minimumSeconds,
        int seconds,
        QueryBounds bounds,
        List<String> actorFilters,
        List<String> excludeActorFilters,
        boolean restore,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(bounds.worldKey(), actionFilter);
        return filterExactBounds(bounds, database.lookupRollbackCandidates(
            bounds.worldKey(),
            bounds.minimum(),
            bounds.maximum(),
            minimumSeconds,
            seconds,
            actorFilters,
            excludeActorFilters,
            restore,
            restore,
            rollbackTypes,
            includeTargets,
            excludeTargets
        )).size();
    }

    public RollbackPreviewResult previewBetween(
        ServerPlayerEntity player,
        long notBefore,
        long notAfter,
        String worldKey,
        QueryBounds bounds,
        List<String> actorFilters,
        List<String> excludeActorFilters,
        boolean restore,
        List<CoreProtectEventType> actionFilter,
        List<String> includeTargets,
        List<String> excludeTargets
    ) {
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(bounds == null ? worldKey : bounds.worldKey(), actionFilter);
        List<StoredEventRecord> candidates;
        if (bounds != null) {
            candidates = database.lookupRollbackCandidatesBetween(
                bounds.worldKey(),
                bounds.minimum(),
                bounds.maximum(),
                notBefore,
                notAfter,
                actorFilters,
                excludeActorFilters,
                restore,
                restore,
                rollbackTypes,
                includeTargets,
                excludeTargets
            );
            candidates = filterExactBounds(bounds, candidates);
        }
        else {
            candidates = database.lookupRollbackCandidatesBetween(
                worldKey,
                null,
                null,
                notBefore,
                notAfter,
                actorFilters,
                excludeActorFilters,
                restore,
                restore,
                rollbackTypes,
                includeTargets,
                excludeTargets
            );
        }
        return new RollbackPreviewResult(candidates.size(), buildPreviewChanges(player, candidates, restore));
    }

    private List<StoredEventRecord> filterExactBounds(QueryBounds bounds, List<StoredEventRecord> candidates) {
        if (bounds == null || !bounds.hasExactPositions() || candidates.isEmpty()) {
            return candidates;
        }

        List<StoredEventRecord> filtered = new ArrayList<>();
        for (StoredEventRecord candidate : candidates) {
            if (candidate.hasPosition() && bounds.contains(candidate.blockPos())) {
                filtered.add(candidate);
            }
        }
        return filtered;
    }

    private RollbackExecutionResult applyCandidates(MinecraftServer server, List<StoredEventRecord> candidates, boolean restore, int radius, int seconds, String actorSummary) {
        if (candidates.isEmpty()) {
            return new RollbackExecutionResult(restore, 0, 0, 0, radius, seconds, actorSummary);
        }

        if (restore) {
            Collections.reverse(candidates);
        }

        int changed = 0;
        int deferred = 0;
        List<Long> changedIds = new ArrayList<>();
        Map<ChunkBucket, List<StoredEventRecord>> buckets = bucketCandidatesByChunk(candidates);
        for (Map.Entry<ChunkBucket, List<StoredEventRecord>> entry : buckets.entrySet()) {
            ChunkBucket bucket = entry.getKey();
            ServerWorld targetWorld = resolveWorld(server, bucket.worldKey());
            if (targetWorld == null) {
                logger.warn(
                    "Skipping rollback bucket {}:{}:{} because world {} is not loaded",
                    bucket.worldKey(),
                    bucket.chunkX(),
                    bucket.chunkZ(),
                    bucket.worldKey()
                );
                continue;
            }

            List<StoredEventRecord> bucketEvents = entry.getValue();
            for (int sliceStart = 0; sliceStart < bucketEvents.size(); sliceStart += CHUNK_APPLY_SLICE_SIZE) {
                int sliceEnd = Math.min(sliceStart + CHUNK_APPLY_SLICE_SIZE, bucketEvents.size());
                for (int index = sliceStart; index < sliceEnd; index++) {
                    StoredEventRecord event = bucketEvents.get(index);
                    ApplyOutcome outcome = applyEvent(targetWorld, event, restore);
                    if (outcome == ApplyOutcome.APPLIED) {
                        changed++;
                        changedIds.add(event.id());
                    }
                    else if (outcome == ApplyOutcome.DEFERRED) {
                        deferred++;
                    }
                }

                if (sliceEnd < bucketEvents.size()) {
                    Thread.yield();
                }
            }
        }

        int marked = database.updateRolledBack(changedIds, !restore);
        return new RollbackExecutionResult(restore, candidates.size(), changed, deferred, marked, radius, seconds, actorSummary);
    }

    private Map<ChunkBucket, List<StoredEventRecord>> bucketCandidatesByChunk(List<StoredEventRecord> candidates) {
        Map<ChunkBucket, List<StoredEventRecord>> buckets = new LinkedHashMap<>();
        for (StoredEventRecord candidate : candidates) {
            if (!candidate.hasPosition()) {
                continue;
            }

            BlockPos pos = candidate.blockPos();
            ChunkBucket bucket = new ChunkBucket(candidate.worldKey(), pos.getX() >> 4, pos.getZ() >> 4);
            buckets.computeIfAbsent(bucket, key -> new ArrayList<>()).add(candidate);
        }
        return buckets;
    }

    private List<PreviewService.PreviewBlockChange> buildPreviewChanges(ServerPlayerEntity player, List<StoredEventRecord> candidates, boolean restore) {
        Map<String, PreviewService.PreviewBlockChange> changes = new LinkedHashMap<>();
        for (StoredEventRecord event : candidates) {
            if (!event.hasPosition()) {
                continue;
            }
            if (event.type() != CoreProtectEventType.BLOCK_PLACE && event.type() != CoreProtectEventType.BLOCK_BREAK) {
                continue;
            }

            ServerWorld targetWorld = resolveWorld(((ServerWorld) player.getEntityWorld()).getServer(), event.worldKey());
            if (targetWorld == null) {
                continue;
            }

            BlockState targetState = previewBlockState(targetWorld, event, restore);
            if (targetState == null) {
                continue;
            }

            BlockPos pos = event.blockPos();
            String key = event.worldKey() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
            changes.put(key, new PreviewService.PreviewBlockChange(event.worldKey(), pos, targetState));
        }
        return new ArrayList<>(changes.values());
    }

    private BlockState previewBlockState(ServerWorld world, StoredEventRecord event, boolean restore) {
        BlockState currentState = event.hasPosition() ? world.getBlockState(event.blockPos()) : null;
        BlockState targetState = switch (event.type()) {
            case BLOCK_PLACE -> restore
                ? BlockStateSerializer.deserialize(world, event.target(), event.payload(), logger)
                : BlockStateSerializer.air();
            case BLOCK_BREAK -> restore
                ? BlockStateSerializer.air()
                : BlockStateSerializer.deserialize(world, event.target(), event.payload(), logger);
            default -> null;
        };
        if (targetState == null) {
            return null;
        }
        return normalizeRollbackTargetState(event, restore, currentState, targetState);
    }

    private ApplyOutcome applyEvent(ServerWorld world, StoredEventRecord event, boolean restore) {
        switch (event.type()) {
            case SIGN_CHANGE:
                return toApplyOutcome(applySignChange(world, event, restore));
            case BLOCK_PLACE:
            case BLOCK_BREAK:
                return toApplyOutcome(applyBlockChange(world, event, restore));
            case CONTAINER_TRANSACTION:
                return toApplyOutcome(applyContainerTransaction(world, event, restore));
            case ITEM_PICKUP:
            case ITEM_DROP:
            case ITEM_THROW:
            case ITEM_SHOOT:
            case ITEM_BUY:
            case ITEM_SELL:
            case ITEM_CREATE:
            case ITEM_DESTROY:
                return applyItemChange(world, event, restore);
            case ENTITY_PLACE:
            case ENTITY_BREAK:
            case ENTITY_KILL:
                return toApplyOutcome(applyEntityChange(world, event, restore));
            default:
                return ApplyOutcome.FAILED;
        }
    }

    private ApplyOutcome toApplyOutcome(boolean applied) {
        return applied ? ApplyOutcome.APPLIED : ApplyOutcome.FAILED;
    }

    private boolean applyContainerTransaction(ServerWorld world, StoredEventRecord event, boolean restore) {
        ContainerTransactionPayload payload = parseContainerTransactionPayload(event.payload());
        if (payload == null) {
            logger.warn("Skipping container transaction at {} due to unreadable payload", event.blockPos());
            return false;
        }
            if (payload.change() == null || payload.added() == null) {
            return false;
        }

            boolean addItems = payload.added() == restore;
        BlockEntity blockEntity = world.getBlockEntity(event.blockPos());
        if (blockEntity instanceof LecternBlockEntity lecternBlockEntity) {
                return addItems
                    ? addItemsToLectern(world, event.blockPos(), lecternBlockEntity, payload.change())
                    : removeItemsFromLectern(world, event.blockPos(), lecternBlockEntity, payload.change());
        }

        if (!(blockEntity instanceof Inventory inventory)) {
            return false;
        }

            boolean changed = addItems
                ? addItemsToInventory(world, inventory, payload.change())
                : removeItemsFromInventory(world, inventory, payload.change());
            if (!changed) {
                return false;
            }

        inventory.markDirty();
        BlockState state = world.getBlockState(event.blockPos());
        world.updateListeners(event.blockPos(), state, state, Block.NOTIFY_ALL);
        return true;
    }

    private ApplyOutcome applyItemChange(ServerWorld world, StoredEventRecord event, boolean restore) {
        LoggedItemChange change = LoggedItemChange.parse(event.target(), event.payload());
        if (change == null || change.item() == null || change.item().itemKey().isBlank()) {
            return ApplyOutcome.FAILED;
        }

        boolean addItems = switch (event.type()) {
            case ITEM_DROP, ITEM_THROW, ITEM_SHOOT, ITEM_SELL, ITEM_DESTROY -> !restore;
            case ITEM_PICKUP, ITEM_BUY, ITEM_CREATE -> restore;
            default -> false;
        };

        ServerPlayerEntity targetPlayer = resolveActorPlayer(world, event);
        if (targetPlayer != null) {
            boolean changed = addItems
                ? addItemsToPlayer(targetPlayer, change)
                : removeItemsFromPlayer(targetPlayer, change);
            if (changed) {
                syncPlayerInventory(targetPlayer);
            }
            return toApplyOutcome(changed);
        }

        PlayerConfigEntry actor = resolveActorConfigEntry(event);
        if (actor == null || world.getServer().isHost(actor)) {
            logger.debug("Skipping item rollback for {} because actor {} is not available for deferred apply", event.id(), event.actorName());
            return ApplyOutcome.FAILED;
        }

        boolean queued = database.queuePendingInventoryRollback(
            event.id(),
            actor.id().toString(),
            actor.name(),
            event.worldKey(),
            restore,
            addItems,
            event.target(),
            event.payload()
        );
        if (!queued) {
            return ApplyOutcome.FAILED;
        }
        logger.debug(
            "Deferred offline inventory {} for actor {} (event={})",
            restore ? "restore" : "rollback",
            actor.name(),
            event.id()
        );
        return ApplyOutcome.DEFERRED;
    }

    private boolean applyBlockChange(ServerWorld world, StoredEventRecord event, boolean restore) {
        BlockPos pos = event.blockPos();
        BlockState currentState = world.getBlockState(pos);
        BlockState targetState;
        switch (event.type()) {
            case BLOCK_PLACE:
                targetState = restore
                    ? BlockStateSerializer.deserialize(world, event.target(), event.payload(), logger)
                    : BlockStateSerializer.air();
                break;
            case BLOCK_BREAK:
                targetState = restore
                    ? BlockStateSerializer.air()
                    : BlockStateSerializer.deserialize(world, event.target(), event.payload(), logger);
                break;
            default:
                return false;
        }
        if (targetState == null) {
            logger.warn("Skipping {} at {} due to unreadable block state payload", event.type(), pos);
            return false;
        }

        targetState = normalizeRollbackTargetState(event, restore, currentState, targetState);

        if (currentState.equals(targetState)) {
            return false;
        }

        boolean changed = world.setBlockState(pos, targetState, Block.NOTIFY_ALL);
        if (!changed) {
            return false;
        }

        applyCompanionBlockCorrections(world, pos, currentState, targetState);
        reconcileAttachedDecorations(world, pos);
        return true;
    }

    private BlockState normalizeRollbackTargetState(StoredEventRecord event, boolean restore, BlockState currentState, BlockState targetState) {
        if (!restore && event.type() == CoreProtectEventType.BLOCK_BREAK && targetState.isOf(Blocks.NETHER_PORTAL)) {
            return Blocks.FIRE.getDefaultState();
        }
        if (targetState.isAir()
            && currentState != null
            && currentState.contains(Properties.WATERLOGGED)
            && Boolean.TRUE.equals(currentState.get(Properties.WATERLOGGED))) {
            return Blocks.WATER.getDefaultState();
        }
        return targetState;
    }

    private void applyCompanionBlockCorrections(ServerWorld world, BlockPos pos, BlockState previousState, BlockState targetState) {
        if (previousState != null && !previousState.isOf(targetState.getBlock())) {
            clearCompanionForRemovedState(world, pos, previousState);
        }

        if (targetState.isAir()) {
            return;
        }
        if (targetState.getBlock() instanceof DoorBlock) {
            syncDoorCompanion(world, pos, targetState);
            return;
        }
        if (targetState.getBlock() instanceof BedBlock) {
            syncBedCompanion(world, pos, targetState);
            return;
        }
        if (targetState.getBlock() instanceof ChestBlock) {
            syncChestCompanion(world, pos, targetState);
        }
    }

    private void reconcileAttachedDecorations(ServerWorld world, BlockPos supportPos) {
        List<AbstractDecorationEntity> decorations = world.getEntitiesByClass(
            AbstractDecorationEntity.class,
            decorationSearchBox(supportPos),
            entity -> entity.getAttachedBlockPos().equals(supportPos)
        );
        for (AbstractDecorationEntity decoration : decorations) {
            if (decoration.canStayAttached()) {
                continue;
            }
            decoration.discard();
        }
    }

    private void clearCompanionForRemovedState(ServerWorld world, BlockPos pos, BlockState removedState) {
        if (removedState.getBlock() instanceof DoorBlock) {
            clearDoorCompanion(world, pos, removedState);
            return;
        }
        if (removedState.getBlock() instanceof BedBlock) {
            clearBedCompanion(world, pos, removedState);
            return;
        }
        if (removedState.getBlock() instanceof ChestBlock) {
            downgradeChestCompanion(world, pos, removedState);
        }
    }

    private void syncDoorCompanion(ServerWorld world, BlockPos pos, BlockState targetState) {
        if (!targetState.contains(Properties.DOUBLE_BLOCK_HALF)) {
            return;
        }

        DoubleBlockHalf targetHalf = targetState.get(Properties.DOUBLE_BLOCK_HALF);
        DoubleBlockHalf companionHalf = targetHalf == DoubleBlockHalf.LOWER ? DoubleBlockHalf.UPPER : DoubleBlockHalf.LOWER;
        BlockPos companionPos = targetHalf == DoubleBlockHalf.LOWER ? pos.up() : pos.down();
        BlockState companionState = world.getBlockState(companionPos);
        if (!companionState.isAir() && !companionState.isOf(targetState.getBlock())) {
            return;
        }

        BlockState desired = copySharedProperties(targetState, companionState.isOf(targetState.getBlock())
            ? companionState
            : targetState.getBlock().getDefaultState());
        desired = withIfPresent(desired, Properties.DOUBLE_BLOCK_HALF, companionHalf);
        if (!desired.equals(companionState)) {
            world.setBlockState(companionPos, desired, Block.NOTIFY_ALL);
            reconcileAttachedDecorations(world, companionPos);
        }
    }

    private void clearDoorCompanion(ServerWorld world, BlockPos pos, BlockState removedState) {
        if (!removedState.contains(Properties.DOUBLE_BLOCK_HALF)) {
            return;
        }

        BlockPos companionPos = removedState.get(Properties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.LOWER ? pos.up() : pos.down();
        BlockState companionState = world.getBlockState(companionPos);
        if (!companionState.isOf(removedState.getBlock())) {
            return;
        }

        BlockState replacement = replacementForRemovedBlock(companionState);
        if (!replacement.equals(companionState)) {
            world.setBlockState(companionPos, replacement, Block.NOTIFY_ALL);
            reconcileAttachedDecorations(world, companionPos);
        }
    }

    private void syncBedCompanion(ServerWorld world, BlockPos pos, BlockState targetState) {
        if (!targetState.contains(Properties.BED_PART) || !targetState.contains(Properties.HORIZONTAL_FACING)) {
            return;
        }

        BedPart part = targetState.get(Properties.BED_PART);
        Direction facing = targetState.get(Properties.HORIZONTAL_FACING);
        BlockPos companionPos = part == BedPart.FOOT ? pos.offset(facing) : pos.offset(facing.getOpposite());
        BedPart companionPart = part == BedPart.FOOT ? BedPart.HEAD : BedPart.FOOT;
        BlockState companionState = world.getBlockState(companionPos);
        if (!companionState.isAir() && !companionState.isOf(targetState.getBlock())) {
            return;
        }

        BlockState desired = copySharedProperties(targetState, companionState.isOf(targetState.getBlock())
            ? companionState
            : targetState.getBlock().getDefaultState());
        desired = withIfPresent(desired, Properties.BED_PART, companionPart);
        desired = withIfPresent(desired, Properties.HORIZONTAL_FACING, facing);
        if (!desired.equals(companionState)) {
            world.setBlockState(companionPos, desired, Block.NOTIFY_ALL);
            reconcileAttachedDecorations(world, companionPos);
        }
    }

    private void clearBedCompanion(ServerWorld world, BlockPos pos, BlockState removedState) {
        if (!removedState.contains(Properties.BED_PART) || !removedState.contains(Properties.HORIZONTAL_FACING)) {
            return;
        }

        BedPart part = removedState.get(Properties.BED_PART);
        Direction facing = removedState.get(Properties.HORIZONTAL_FACING);
        BlockPos companionPos = part == BedPart.FOOT ? pos.offset(facing) : pos.offset(facing.getOpposite());
        BlockState companionState = world.getBlockState(companionPos);
        if (!companionState.isOf(removedState.getBlock())) {
            return;
        }

        BlockState replacement = replacementForRemovedBlock(companionState);
        if (!replacement.equals(companionState)) {
            world.setBlockState(companionPos, replacement, Block.NOTIFY_ALL);
            reconcileAttachedDecorations(world, companionPos);
        }
    }

    private void syncChestCompanion(ServerWorld world, BlockPos pos, BlockState targetState) {
        if (!targetState.contains(Properties.CHEST_TYPE) || !targetState.contains(Properties.HORIZONTAL_FACING)) {
            return;
        }

        ChestType chestType = targetState.get(Properties.CHEST_TYPE);
        if (chestType == ChestType.SINGLE) {
            return;
        }

        BlockPos companionPos = resolveChestPairPos(pos, targetState, chestType);
        BlockState companionState = world.getBlockState(companionPos);
        if (!companionState.isAir() && !companionState.isOf(targetState.getBlock())) {
            return;
        }

        ChestType companionType = chestType == ChestType.LEFT ? ChestType.RIGHT : ChestType.LEFT;
        BlockState desired = copySharedProperties(targetState, companionState.isOf(targetState.getBlock())
            ? companionState
            : targetState.getBlock().getDefaultState());
        desired = withIfPresent(desired, Properties.CHEST_TYPE, companionType);
        desired = withIfPresent(desired, Properties.HORIZONTAL_FACING, targetState.get(Properties.HORIZONTAL_FACING));
        if (!desired.equals(companionState)) {
            world.setBlockState(companionPos, desired, Block.NOTIFY_ALL);
            reconcileAttachedDecorations(world, companionPos);
        }
    }

    private void downgradeChestCompanion(ServerWorld world, BlockPos pos, BlockState removedState) {
        if (!removedState.contains(Properties.CHEST_TYPE) || !removedState.contains(Properties.HORIZONTAL_FACING)) {
            return;
        }

        ChestType removedType = removedState.get(Properties.CHEST_TYPE);
        if (removedType == ChestType.SINGLE) {
            return;
        }

        BlockPos companionPos = resolveChestPairPos(pos, removedState, removedType);
        BlockState companionState = world.getBlockState(companionPos);
        if (!companionState.isOf(removedState.getBlock())) {
            return;
        }

        BlockState desired = withIfPresent(companionState, Properties.CHEST_TYPE, ChestType.SINGLE);
        if (!desired.equals(companionState)) {
            world.setBlockState(companionPos, desired, Block.NOTIFY_ALL);
            reconcileAttachedDecorations(world, companionPos);
        }
    }

    private BlockPos resolveChestPairPos(BlockPos pos, BlockState state, ChestType chestType) {
        Direction facing = state.contains(Properties.HORIZONTAL_FACING)
            ? state.get(Properties.HORIZONTAL_FACING)
            : Direction.NORTH;
        Direction offset = chestType == ChestType.LEFT
            ? facing.rotateYClockwise()
            : facing.rotateYCounterclockwise();
        return pos.offset(offset);
    }

    private BlockState replacementForRemovedBlock(BlockState removedState) {
        if (removedState.contains(Properties.WATERLOGGED) && Boolean.TRUE.equals(removedState.get(Properties.WATERLOGGED))) {
            return Blocks.WATER.getDefaultState();
        }
        return BlockStateSerializer.air();
    }

    private BlockState copySharedProperties(BlockState source, BlockState targetBase) {
        BlockState result = targetBase;
        for (Property<?> property : source.getProperties()) {
            if (!result.contains(property)) {
                continue;
            }
            result = copyPropertyValue(source, result, property);
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private <T extends Comparable<T>> BlockState copyPropertyValue(BlockState source, BlockState target, Property<?> rawProperty) {
        Property<T> property = (Property<T>) rawProperty;
        return target.with(property, source.get(property));
    }

    private <T extends Comparable<T>> BlockState withIfPresent(BlockState state, Property<T> property, T value) {
        if (value == null || !state.contains(property)) {
            return state;
        }
        return state.with(property, value);
    }

    private boolean applySignChange(ServerWorld world, StoredEventRecord event, boolean restore) {
        BlockPos pos = event.blockPos();
        if (!(world.getBlockEntity(pos) instanceof SignBlockEntity)) {
            logger.debug("Skipping sign {} at {} because no sign block entity is present", restore ? "restore" : "rollback", pos);
            return false;
        }

        LoggedSignState payload = LoggedSignState.parse(event.payload());
        if (payload == null) {
            logger.warn("Skipping sign change at {} due to unreadable sign payload", pos);
            return false;
        }

        LoggedSignState targetState = restore
            ? payload
            : database.lookupPreviousSignState(event.worldKey(), pos, event.id(), payload.front());
        return updateSignText(world, pos, (SignBlockEntity) world.getBlockEntity(pos), targetState);
    }

    private boolean updateSignText(ServerWorld world, BlockPos pos, SignBlockEntity signBlockEntity, LoggedSignState targetState) {
        LoggedSignState currentState = LoggedSignState.fromBlockEntity(signBlockEntity, targetState.front());
        if (currentState.equals(targetState)) {
            return false;
        }

        boolean changed = false;
        SignText updatedText = targetState.applyTo(signBlockEntity.getText(targetState.front()));
        if (!signBlockEntity.getText(targetState.front()).equals(updatedText)) {
            changed = signBlockEntity.setText(updatedText, targetState.front());
        }
        if (signBlockEntity.isWaxed() != targetState.waxed()) {
            changed = signBlockEntity.setWaxed(targetState.waxed()) || changed;
        }
        if (!changed) {
            return false;
        }

        signBlockEntity.markDirty();
        BlockState state = world.getBlockState(pos);
        world.updateListeners(pos, state, state, Block.NOTIFY_ALL);
        return true;
    }

    private boolean applyEntityChange(ServerWorld world, StoredEventRecord event, boolean restore) {
        Map<String, String> payload = parseKeyValuePayload(event.payload());
        if (payload.isEmpty()) {
            logger.warn("Skipping entity {} at {} due to unreadable entity payload", event.type(), event.blockPos());
            return false;
        }

        return switch (event.type()) {
            case ENTITY_PLACE -> restore
                ? spawnLoggedEntity(world, event.blockPos(), payload)
                : removeLoggedEntity(world, event.blockPos(), payload);
            case ENTITY_BREAK -> restore
                ? removeLoggedEntity(world, event.blockPos(), payload)
                : spawnLoggedEntity(world, event.blockPos(), payload);
            case ENTITY_KILL -> restore
                ? removeLoggedEntity(world, event.blockPos(), payload)
                : spawnLoggedEntity(world, event.blockPos(), payload, true);
            default -> false;
        };
    }

    private List<CoreProtectEventType> resolveRollbackTypes(String worldKey, List<CoreProtectEventType> actionFilter) {
        CoreProtectFabricConfig config = configs == null ? CoreProtectFabricConfig.loadDefaults() : configs.resolve(worldKey);
        if (actionFilter == null || actionFilter.isEmpty()) {
            List<CoreProtectEventType> rollbackTypes = new ArrayList<>(SUPPORTED_EVENT_TYPES);
            if (config.rollbackEntities()) {
                rollbackTypes.add(CoreProtectEventType.ENTITY_PLACE);
                rollbackTypes.add(CoreProtectEventType.ENTITY_BREAK);
                rollbackTypes.add(CoreProtectEventType.ENTITY_KILL);
            }
            if (!config.rollbackItems()) {
                rollbackTypes.remove(CoreProtectEventType.CONTAINER_TRANSACTION);
                rollbackTypes.remove(CoreProtectEventType.ITEM_PICKUP);
                rollbackTypes.remove(CoreProtectEventType.ITEM_DROP);
                rollbackTypes.remove(CoreProtectEventType.ITEM_THROW);
                rollbackTypes.remove(CoreProtectEventType.ITEM_SHOOT);
                rollbackTypes.remove(CoreProtectEventType.ITEM_BUY);
                rollbackTypes.remove(CoreProtectEventType.ITEM_SELL);
                rollbackTypes.remove(CoreProtectEventType.ITEM_CREATE);
                rollbackTypes.remove(CoreProtectEventType.ITEM_DESTROY);
            }
            return rollbackTypes;
        }

        List<CoreProtectEventType> rollbackTypes = new ArrayList<>();
        for (CoreProtectEventType eventType : actionFilter) {
            if (!isSupported(eventType) || rollbackTypes.contains(eventType)) {
                continue;
            }
            if (!config.rollbackEntities()
                && (eventType == CoreProtectEventType.ENTITY_PLACE
                    || eventType == CoreProtectEventType.ENTITY_BREAK
                    || eventType == CoreProtectEventType.ENTITY_KILL)) {
                continue;
            }
            if (!config.rollbackItems() && eventType == CoreProtectEventType.CONTAINER_TRANSACTION) {
                continue;
            }
            if (!config.rollbackItems() && isItemRollbackEvent(eventType)) {
                continue;
            }
            rollbackTypes.add(eventType);
        }
        return rollbackTypes;
    }

    public static boolean isSupported(CoreProtectEventType eventType) {
        return SUPPORTED_EVENT_TYPES.contains(eventType)
            || eventType == CoreProtectEventType.ENTITY_PLACE
            || eventType == CoreProtectEventType.ENTITY_BREAK
            || eventType == CoreProtectEventType.ENTITY_KILL;
    }

    private boolean spawnLoggedEntity(ServerWorld world, BlockPos pos, Map<String, String> payload) {
        return spawnLoggedEntity(world, pos, payload, false);
    }

    private boolean spawnLoggedEntity(ServerWorld world, BlockPos pos, Map<String, String> payload, boolean forceSpawn) {
        if (forceSpawn) {
            Entity existingByUuid = findEntityByRecordedUuid(world, pos, payload);
            if (existingByUuid != null) {
                return false;
            }
        }
        else if (findMatchingEntity(world, pos, payload) != null) {
            return false;
        }

        Entity entity = createEntity(world, pos, payload);
        if (entity == null) {
            return false;
        }
        
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.setHealth(livingEntity.getMaxHealth());
        }

        if (!world.spawnEntity(entity)) {
            logger.warn("Rollback entity spawn failed for type {} at {}", payload.get("type"), pos);
            return false;
        }
        return true;
    }

    private boolean removeLoggedEntity(ServerWorld world, BlockPos pos, Map<String, String> payload) {
        Entity entity = findMatchingEntity(world, pos, payload);
        if (entity == null) {
            return false;
        }
        entity.discard();
        return true;
    }

    private Entity findMatchingEntity(ServerWorld world, BlockPos pos, Map<String, String> payload) {
        String type = payload.get("type");
        Entity exactMatch = findEntityByRecordedUuid(world, pos, payload);
        if (exactMatch != null && (type == null || type.isBlank() || simplifyEntityType(exactMatch).equals(type))) {
            return exactMatch;
        }

        Direction facing = parseFacing(payload.get("facing"));
        if ("item_frame".equals(type) || "glow_item_frame".equals(type)) {
            List<ItemFrameEntity> matches = world.getEntitiesByClass(
                ItemFrameEntity.class,
                decorationSearchBox(pos),
                entity -> simplifyEntityType(entity).equals(type)
                    && matchesItemFramePayload(world, entity, pos, facing, payload)
            );
            return matches.isEmpty() ? null : matches.get(0);
        }
        if ("painting".equals(type)) {
            List<PaintingEntity> matches = world.getEntitiesByClass(
                PaintingEntity.class,
                decorationSearchBox(pos),
                entity -> matchesPaintingPayload(entity, pos, facing, payload)
            );
            return matches.isEmpty() ? null : matches.get(0);
        }
        if ("armor_stand".equals(type)) {
            List<ArmorStandEntity> matches = world.getEntitiesByClass(
                ArmorStandEntity.class,
                Box.of(Vec3d.ofCenter(pos), 1.5, 3.0, 1.5),
                entity -> matchesArmorStandPayload(world, entity, pos, payload)
            );
            return matches.isEmpty() ? null : matches.get(0);
        }
        if ("end_crystal".equals(type)) {
            List<EndCrystalEntity> matches = world.getEntitiesByClass(
                EndCrystalEntity.class,
                Box.of(Vec3d.ofCenter(pos), 1.5, 3.0, 1.5),
                entity -> matchesEndCrystalPayload(entity, pos, payload)
            );
            return matches.isEmpty() ? null : matches.get(0);
        }
        if (isBoatType(type)) {
            List<AbstractBoatEntity> matches = world.getEntitiesByClass(
                AbstractBoatEntity.class,
                Box.of(Vec3d.ofCenter(pos), 2.0, 3.0, 2.0),
                entity -> entity.getBlockPos().equals(pos)
                    && simplifyEntityType(entity).equals(type)
                    && matchesLoggedYaw(entity, payload)
            );
            return matches.isEmpty() ? null : matches.get(0);
        }
        if (isMinecartType(type)) {
            List<AbstractMinecartEntity> matches = world.getEntitiesByClass(
                AbstractMinecartEntity.class,
                Box.of(Vec3d.ofCenter(pos), 2.0, 3.0, 2.0),
                entity -> entity.getBlockPos().equals(pos)
                    && simplifyEntityType(entity).equals(type)
                    && matchesLoggedYaw(entity, payload)
            );
            return matches.isEmpty() ? null : matches.get(0);
        }

        net.minecraft.entity.EntityType<?> entityType = resolveEntityType(type);
        if (entityType == null) {
            return null;
        }
        List<Entity> matches = world.getEntitiesByClass(
            Entity.class,
            Box.of(Vec3d.ofCenter(pos), 2.5, 3.0, 2.5),
            entity -> entity.getType() == entityType && genericEntityMatches(entity, pos, facing, payload)
        );
        return matches.isEmpty() ? null : matches.get(0);
    }

    private boolean matchesItemFramePayload(ServerWorld world, ItemFrameEntity entity, BlockPos pos, Direction facing, Map<String, String> payload) {
        if (!entity.getAttachedBlockPos().equals(pos)) {
            return false;
        }
        if (facing != null && entity.getHorizontalFacing() != facing) {
            return false;
        }

        Integer rotation = parseInteger(payload.get("rotation"));
        if (rotation != null && entity.getRotation() != rotation) {
            return false;
        }

        String serializedItem = payload.get("item");
        if (serializedItem == null || serializedItem.isBlank()) {
            return true;
        }

        ItemStack expected = parseItemStack(world, serializedItem);
        ItemStack actual = entity.getHeldItemStack();
        if (expected.isEmpty()) {
            return actual.isEmpty();
        }
        return actual.getCount() == expected.getCount()
            && ItemStack.areItemsAndComponentsEqual(actual, expected);
    }

    private boolean matchesPaintingPayload(PaintingEntity entity, BlockPos pos, Direction facing, Map<String, String> payload) {
        if (!entity.getAttachedBlockPos().equals(pos)) {
            return false;
        }
        if (facing != null && entity.getHorizontalFacing() != facing) {
            return false;
        }

        String expectedVariant = simplifyEntityType(payload.get("variant"));
        if (expectedVariant.isBlank()) {
            return true;
        }

        String actualVariant = entity.getVariant().getKey()
            .map(key -> simplifyEntityType(key.getValue().toString()))
            .orElse("");
        return actualVariant.equals(expectedVariant);
    }

    private boolean matchesArmorStandPayload(ServerWorld world, ArmorStandEntity entity, BlockPos pos, Map<String, String> payload) {
        if (!entity.getBlockPos().equals(pos)) {
            return false;
        }
        if (!matchesOptionalBoolean(payload.get("ShowArms"), entity.shouldShowArms())) {
            return false;
        }
        if (!matchesOptionalBoolean(payload.get("Small"), entity.isSmall())) {
            return false;
        }
        if (!matchesOptionalBoolean(payload.get("NoBasePlate"), !entity.shouldShowBasePlate())) {
            return false;
        }
        if (!matchesOptionalBoolean(payload.get("Marker"), entity.isMarker())) {
            return false;
        }
        if (!matchesOptionalBoolean(payload.get("Invisible"), entity.isInvisible())) {
            return false;
        }
        return matchesEquipmentPayload(world, entity.getEquippedStack(EquipmentSlot.FEET), payload.get("feet"))
            && matchesEquipmentPayload(world, entity.getEquippedStack(EquipmentSlot.LEGS), payload.get("legs"))
            && matchesEquipmentPayload(world, entity.getEquippedStack(EquipmentSlot.CHEST), payload.get("chest"))
            && matchesEquipmentPayload(world, entity.getEquippedStack(EquipmentSlot.HEAD), payload.get("head"))
            && matchesEquipmentPayload(world, entity.getEquippedStack(EquipmentSlot.MAINHAND), payload.get("mainhand"))
            && matchesEquipmentPayload(world, entity.getEquippedStack(EquipmentSlot.OFFHAND), payload.get("offhand"));
    }

    private boolean matchesEquipmentPayload(ServerWorld world, ItemStack actual, String serializedExpected) {
        if (serializedExpected == null || serializedExpected.isBlank()) {
            return true;
        }

        ItemStack expected = parseItemStack(world, serializedExpected);
        if (expected.isEmpty()) {
            return actual == null || actual.isEmpty();
        }
        return actual != null
            && actual.getCount() == expected.getCount()
            && ItemStack.areItemsAndComponentsEqual(actual, expected);
    }

    private boolean matchesEndCrystalPayload(EndCrystalEntity entity, BlockPos pos, Map<String, String> payload) {
        if (!entity.getBlockPos().equals(pos)) {
            return false;
        }

        BlockPos expectedBeamTarget = parseBlockPos(payload.get("beam_target"));
        if (expectedBeamTarget != null && !expectedBeamTarget.equals(entity.getBeamTarget())) {
            return false;
        }

        Boolean expectedShowBottom = parseBoolean(payload.get("show_bottom"));
        return expectedShowBottom == null || expectedShowBottom.equals(entity.shouldShowBottom());
    }

    private boolean matchesLoggedYaw(Entity entity, Map<String, String> payload) {
        Integer expectedYaw = parseInteger(payload.get("yaw"));
        if (expectedYaw == null) {
            return true;
        }
        return Math.abs(Math.round(entity.getYaw()) - expectedYaw) <= 10;
    }

    private boolean matchesOptionalBoolean(String serialized, boolean actual) {
        Boolean expected = parseBoolean(serialized);
        return expected == null || expected == actual;
    }

    private Entity findEntityByRecordedUuid(ServerWorld world, BlockPos pos, Map<String, String> payload) {
        UUID uuid = parsePayloadEntityUuid(payload);
        if (uuid == null) {
            return null;
        }

        Entity entity = world.getEntity(uuid);
        if (entity == null || entity.isRemoved()) {
            return null;
        }

        Box searchArea = decorationSearchBox(pos);
        return searchArea.intersects(entity.getBoundingBox()) ? entity : null;
    }

    private Box decorationSearchBox(BlockPos pos) {
        return Box.of(Vec3d.ofCenter(pos), DECORATION_SEARCH_EXTENT, DECORATION_SEARCH_EXTENT, DECORATION_SEARCH_EXTENT);
    }

    private UUID parsePayloadEntityUuid(Map<String, String> payload) {
        if (payload == null || payload.isEmpty()) {
            return null;
        }

        String raw = payload.get("target_uuid");
        if (raw == null || raw.isBlank()) {
            raw = payload.get("uuid");
        }
        if (raw == null || raw.isBlank()) {
            return null;
        }

        try {
            return UUID.fromString(raw.trim());
        }
        catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private Entity createEntity(ServerWorld world, BlockPos pos, Map<String, String> payload) {
        String nbtStr = payload.get("nbt");
        String type = payload.get("type");

        if (type == null || type.isBlank()) {
            return null;
        }

        if (usesStructuredEntityRestore(type)) {
            return createBaseEntity(world, pos, payload);
        }

        if (isSafeEntityNbtPayload(nbtStr)) {
            try {
                NbtCompound entityNbt = StringNbtReader.readCompound(nbtStr);
                sanitizeEntityRollbackNbt(entityNbt);

                if (!entityNbt.contains("id")) {
                    entityNbt.putString("id", type);
                }

                Entity entity = net.minecraft.entity.EntityType.loadEntityWithPassengers(
                    entityNbt,
                    world,
                    net.minecraft.entity.SpawnReason.COMMAND,
                    e -> e
                );

                if (entity != null && entityMatchesLoggedType(entity, type)) {
                    applyLoggedEntityPayload(world, pos, entity, payload);
                    return entity;
                }
            }
            catch (Exception exception) {
                logger.warn("Failed to parse NBT for entity rollback type {}", type, exception);
            }
        }
        return createBaseEntity(world, pos, payload);
    }

    private boolean usesStructuredEntityRestore(String type) {
        return "item_frame".equals(type)
            || "glow_item_frame".equals(type)
            || "painting".equals(type);
    }

    private boolean isSafeEntityNbtPayload(String nbtStr) {
        return nbtStr != null && !nbtStr.isBlank() && nbtStr.length() <= MAX_ENTITY_NBT_LENGTH;
    }

    private void sanitizeEntityRollbackNbt(NbtCompound entityNbt) {
        entityNbt.remove("UUID");
        entityNbt.remove("Pos");
        entityNbt.remove("Motion");
        entityNbt.remove("Rotation");
        entityNbt.remove("Passengers");
        entityNbt.remove("Brain");
        entityNbt.remove("Fire");
        entityNbt.remove("HasVisualFire");
    }

    private boolean entityMatchesLoggedType(Entity entity, String loggedType) {
        if (entity == null || loggedType == null || loggedType.isBlank()) {
            return false;
        }
        return simplifyEntityType(entity).equals(simplifyEntityType(loggedType));
    }

    private void applyLoggedEntityPayload(ServerWorld world, BlockPos pos, Entity entity, Map<String, String> payload) {
        float yaw = parseFloat(payload.get("yaw"), entity.getYaw());
        entity.refreshPositionAndAngles(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, yaw, entity.getPitch());

        if (entity instanceof ItemFrameEntity itemFrameEntity) {
            applyItemFramePayload(world, itemFrameEntity, payload);
            return;
        }
        if (entity instanceof ArmorStandEntity armorStandEntity) {
            applyArmorStandPayload(armorStandEntity, payload);
            return;
        }
        if (entity instanceof EndCrystalEntity endCrystalEntity) {
            applyEndCrystalPayload(endCrystalEntity, payload);
        }
    }

    private Entity createBaseEntity(ServerWorld world, BlockPos pos, Map<String, String> payload) {
        String type = payload.get("type");
        Direction facing = parseFacing(payload.get("facing"));
        if (type == null || type.isBlank()) {
            return null;
        }
        if (("item_frame".equals(type) || "glow_item_frame".equals(type)) && facing != null) {
            ItemFrameEntity itemFrameEntity = "glow_item_frame".equals(type)
                ? new ItemFrameEntity(net.minecraft.entity.EntityType.GLOW_ITEM_FRAME, world, pos, facing)
                : new ItemFrameEntity(world, pos, facing);
            applyItemFramePayload(world, itemFrameEntity, payload);
            return itemFrameEntity;
        }
        if ("painting".equals(type) && facing != null) {
            RegistryEntry<net.minecraft.entity.decoration.painting.PaintingVariant> variant = resolvePaintingVariant(world, payload.get("variant"));
            if (variant == null) {
                return null;
            }
            return new PaintingEntity(world, pos, facing, variant);
        }
        if ("armor_stand".equals(type)) {
            ArmorStandEntity armorStandEntity = new ArmorStandEntity(world, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            applyArmorStandPayload(armorStandEntity, payload);
            return armorStandEntity;
        }
        if ("end_crystal".equals(type)) {
            EndCrystalEntity endCrystalEntity = new EndCrystalEntity(world, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            applyEndCrystalPayload(endCrystalEntity, payload);
            return endCrystalEntity;
        }
        if (isBoatType(type) || isMinecartType(type)) {
            net.minecraft.entity.EntityType<?> entityType = resolveEntityType(type);
            if (entityType == null) {
                return null;
            }
            Entity entity = entityType.create(world, SpawnReason.COMMAND);
            if (entity == null) {
                return null;
            }

            float yaw = parseFloat(payload.get("yaw"), 0.0F);
            entity.refreshPositionAndAngles(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, yaw, entity.getPitch());
            return entity;
        }
        net.minecraft.entity.EntityType<?> entityType = resolveEntityType(type);
        if (entityType == null) {
            return null;
        }
        Entity entity = entityType.create(world, SpawnReason.COMMAND);
        if (entity == null) {
            return null;
        }
        entity.refreshPositionAndAngles(pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D, entity.getYaw(), entity.getPitch());
        return entity;
    }

    private void applyItemFramePayload(ServerWorld world, ItemFrameEntity itemFrameEntity, Map<String, String> payload) {
        ItemStack stack = parseItemStack(world, payload.get("item"));
        if (!stack.isEmpty()) {
            itemFrameEntity.setHeldItemStack(stack, false);
        }
        Integer rotation = parseInteger(payload.get("rotation"));
        if (rotation != null) {
            itemFrameEntity.setRotation(rotation);
        }
    }

    private void applyArmorStandPayload(ArmorStandEntity armorStandEntity, Map<String, String> payload) {
        equipIfPresent(armorStandEntity, EquipmentSlot.FEET, payload.get("feet"));
        equipIfPresent(armorStandEntity, EquipmentSlot.LEGS, payload.get("legs"));
        equipIfPresent(armorStandEntity, EquipmentSlot.CHEST, payload.get("chest"));
        equipIfPresent(armorStandEntity, EquipmentSlot.HEAD, payload.get("head"));
        equipIfPresent(armorStandEntity, EquipmentSlot.MAINHAND, payload.get("mainhand"));
        equipIfPresent(armorStandEntity, EquipmentSlot.OFFHAND, payload.get("offhand"));

        NbtCompound nbt = new NbtCompound();
        boolean modified = false;

        Boolean showArms = parseBoolean(payload.get("ShowArms"));
        if (showArms != null) {
            nbt.putBoolean("ShowArms", showArms);
            modified = true;
        }
        Boolean small = parseBoolean(payload.get("Small"));
        if (small != null) {
            nbt.putBoolean("Small", small);
            modified = true;
        }
        Boolean noBasePlate = parseBoolean(payload.get("NoBasePlate"));
        if (noBasePlate != null) {
            nbt.putBoolean("NoBasePlate", noBasePlate);
            modified = true;
        }
        Boolean marker = parseBoolean(payload.get("Marker"));
        if (marker != null) {
            nbt.putBoolean("Marker", marker);
            modified = true;
        }
        Boolean invisible = parseBoolean(payload.get("Invisible"));
        if (invisible != null) {
            nbt.putBoolean("Invisible", invisible);
            modified = true;
        }

        if (modified) {
            net.minecraft.storage.NbtReadView readView = (net.minecraft.storage.NbtReadView) net.minecraft.storage.NbtReadView.create(
                net.minecraft.util.ErrorReporter.EMPTY,
                ((ServerWorld) armorStandEntity.getEntityWorld()).getRegistryManager(),
                nbt
            );
            armorStandEntity.readData(readView);
        }
    }

    private void applyEndCrystalPayload(EndCrystalEntity endCrystalEntity, Map<String, String> payload) {
        BlockPos beamTarget = parseBlockPos(payload.get("beam_target"));
        if (beamTarget != null) {
            endCrystalEntity.setBeamTarget(beamTarget);
        }
        Boolean showBottom = parseBoolean(payload.get("show_bottom"));
        if (showBottom != null) {
            endCrystalEntity.setShowBottom(showBottom);
        }
    }

    private void equipIfPresent(ArmorStandEntity armorStandEntity, EquipmentSlot slot, String serializedStack) {
        ItemStack stack = parseItemStack((ServerWorld) armorStandEntity.getEntityWorld(), serializedStack);
        if (stack.isEmpty()) {
            return;
        }
        armorStandEntity.equipStack(slot, stack);
    }

    private RegistryEntry<net.minecraft.entity.decoration.painting.PaintingVariant> resolvePaintingVariant(ServerWorld world, String variantKey) {
        if (variantKey == null || variantKey.isBlank()) {
            return null;
        }
        Identifier identifier = Identifier.tryParse(variantKey.contains(":") ? variantKey : "minecraft:" + variantKey);
        if (identifier == null) {
            return null;
        }
        return world.getRegistryManager()
            .getOrThrow(RegistryKeys.PAINTING_VARIANT)
            .getEntry(identifier)
            .orElse(null);
    }

    private Map<String, String> parseKeyValuePayload(String payload) {
        Map<String, String> values = new HashMap<>();
        if (payload == null || payload.isBlank()) {
            return values;
        }
        for (String line : payload.split("\\n")) {
            int separator = line.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            values.put(line.substring(0, separator), line.substring(separator + 1));
        }
        return values;
    }

    private boolean genericEntityMatches(Entity entity, BlockPos pos, Direction facing, Map<String, String> payload) {
        if (entity instanceof net.minecraft.entity.decoration.AbstractDecorationEntity decorationEntity) {
            if (!decorationEntity.getAttachedBlockPos().equals(pos)) {
                return false;
            }
            return facing == null || decorationEntity.getHorizontalFacing() == facing;
        }
        return entity.getBlockPos().equals(pos) && matchesLoggedYaw(entity, payload);
    }

    private Direction parseFacing(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (Direction direction : Direction.values()) {
            if (direction.asString().equalsIgnoreCase(value) || direction.name().equalsIgnoreCase(value)) {
                return direction;
            }
        }
        return null;
    }

    private Integer parseInteger(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        }
        catch (NumberFormatException exception) {
            return null;
        }
    }

    private float parseFloat(String value, float fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Float.parseFloat(value);
        }
        catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private Boolean parseBoolean(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private BlockPos parseBlockPos(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String[] parts = value.split(",", -1);
        if (parts.length != 3) {
            return null;
        }

        Integer x = parseInteger(parts[0]);
        Integer y = parseInteger(parts[1]);
        Integer z = parseInteger(parts[2]);
        if (x == null || y == null || z == null) {
            return null;
        }
        return new BlockPos(x, y, z);
    }

    private ItemStack parseItemStack(ServerWorld world, String value) {
        if (value == null || value.isBlank() || "empty".equalsIgnoreCase(value)) {
            return ItemStack.EMPTY;
        }

        if (world != null && value.startsWith("{")) {
            try {
                NbtCompound nbt = StringNbtReader.readCompound(value);
                return ItemStack.UNCOUNTED_CODEC
                    .parse(RegistryOps.of(NbtOps.INSTANCE, world.getRegistryManager()), nbt)
                    .result()
                    .orElse(ItemStack.EMPTY);
            }
            catch (Exception exception) {
                logger.debug("Unable to decode serialized item stack {}", value, exception);
            }
        }

        int separator = value.lastIndexOf('x');
        String itemKey = separator > 0 ? value.substring(0, separator) : value;
        int count = 1;
        if (separator > 0) {
            Integer parsedCount = parseInteger(value.substring(separator + 1));
            if (parsedCount != null && parsedCount > 0) {
                count = parsedCount;
            }
        }

        Identifier identifier = Identifier.tryParse(itemKey.contains(":") ? itemKey : "minecraft:" + itemKey);
        if (identifier == null || !net.minecraft.registry.Registries.ITEM.containsId(identifier)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(net.minecraft.registry.Registries.ITEM.get(identifier), count);
    }

    private ServerPlayerEntity resolveActorPlayer(ServerWorld world, StoredEventRecord event) {
        if (event.actorUuid() != null && !event.actorUuid().isBlank()) {
            try {
                ServerPlayerEntity player = world.getServer().getPlayerManager().getPlayer(java.util.UUID.fromString(event.actorUuid()));
                if (player != null) {
                    return player;
                }
            }
            catch (IllegalArgumentException ignored) {
            }
        }
        if (event.actorName() != null && !event.actorName().isBlank()) {
            return world.getServer().getPlayerManager().getPlayer(event.actorName());
        }
        return null;
    }

    private boolean addItemsToPlayer(ServerPlayerEntity player, LoggedItemChange change) {
        ItemStack stack = buildRollbackItemStack((ServerWorld) player.getEntityWorld(), change, player.getInventory());
        if (stack.isEmpty()) {
            return false;
        }
        int initialCount = stack.getCount();
        ItemStack remaining = stack.copy();
        player.getInventory().insertStack(remaining);
        return remaining.getCount() < initialCount;
    }

    private boolean removeItemsFromPlayer(ServerPlayerEntity player, LoggedItemChange change) {
        ServerWorld world = (ServerWorld) player.getEntityWorld();
        ItemStack template = buildRollbackItemStack(world, change, player.getInventory()).copyWithCount(1);
        if (template.isEmpty()) {
            return false;
        }

        int initialRemaining = change.count();
        int remaining = initialRemaining;
        for (int slot = 0; slot < player.getInventory().size() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getStack(slot);
            if (!matchesRollbackItem(stack, template, change)) {
                continue;
            }

            int remove = Math.min(remaining, stack.getCount());
            stack.decrement(remove);
            if (stack.isEmpty()) {
                player.getInventory().setStack(slot, ItemStack.EMPTY);
            }
            remaining -= remove;
        }
        return remaining < initialRemaining;
    }

    private boolean addItemsToInventory(ServerWorld world, Inventory inventory, LoggedItemChange change) {
        ItemStack remaining = buildRollbackItemStack(world, change, inventory);
        if (remaining.isEmpty()) {
            return false;
        }
        int initialCount = remaining.getCount();

        for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            if (!matchesRollbackItem(stack, remaining.copyWithCount(1), change)) {
                continue;
            }

            int maxCount = Math.min(stack.getMaxCount(), inventory.getMaxCountPerStack());
            int space = Math.max(0, maxCount - stack.getCount());
            if (space <= 0) {
                continue;
            }

            int moved = Math.min(space, remaining.getCount());
            stack.increment(moved);
            remaining.decrement(moved);
        }

        for (int slot = 0; slot < inventory.size() && !remaining.isEmpty(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (stack != null && !stack.isEmpty()) {
                continue;
            }

            int moved = Math.min(remaining.getCount(), Math.min(remaining.getMaxCount(), inventory.getMaxCountPerStack()));
            inventory.setStack(slot, remaining.copyWithCount(moved));
            remaining.decrement(moved);
        }

        return remaining.getCount() < initialCount;
    }

    private boolean removeItemsFromInventory(ServerWorld world, Inventory inventory, LoggedItemChange change) {
        ItemStack template = buildRollbackItemStack(world, change, inventory).copyWithCount(1);
        if (template.isEmpty()) {
            return false;
        }

        int initialRemaining = change.count();
        int remaining = initialRemaining;
        for (int slot = inventory.size() - 1; slot >= 0 && remaining > 0; slot--) {
            ItemStack stack = inventory.getStack(slot);
            if (!matchesRollbackItem(stack, template, change)) {
                continue;
            }

            int remove = Math.min(remaining, stack.getCount());
            stack.decrement(remove);
            if (stack.isEmpty()) {
                inventory.setStack(slot, ItemStack.EMPTY);
            }
            remaining -= remove;
        }

        return remaining < initialRemaining;
    }

    private boolean addItemsToLectern(ServerWorld world, BlockPos pos, LecternBlockEntity lecternBlockEntity, LoggedItemChange change) {
        if (!lecternBlockEntity.getBook().isEmpty()) {
            return false;
        }
        ItemStack targetStack = buildRollbackItemStack(world, change).copyWithCount(change.count());
        if (targetStack.isEmpty()) {
            return false;
        }
        lecternBlockEntity.setBook(targetStack);
        lecternBlockEntity.markDirty();
        BlockState state = world.getBlockState(pos);
        world.updateListeners(pos, state, state, Block.NOTIFY_ALL);
        return true;
    }

    private boolean removeItemsFromLectern(ServerWorld world, BlockPos pos, LecternBlockEntity lecternBlockEntity, LoggedItemChange change) {
        ItemStack currentStack = lecternBlockEntity.getBook().copy();
        ItemStack template = buildRollbackItemStack(world, change).copyWithCount(1);
        if (currentStack.isEmpty() || template.isEmpty() || !matchesRollbackItem(currentStack, template, change)) {
            return false;
        }
        lecternBlockEntity.setBook(ItemStack.EMPTY);
        lecternBlockEntity.markDirty();
        BlockState state = world.getBlockState(pos);
        world.updateListeners(pos, state, state, Block.NOTIFY_ALL);
        return true;
    }

    private ItemStack buildRollbackItemStack(ServerWorld world, LoggedItemChange change) {
        return buildRollbackItemStack(world, change, null);
    }

    private ItemStack buildRollbackItemStack(ServerWorld world, LoggedItemChange change, Inventory inventory) {
        ItemStack stack = parseItemStack(world, change.item().serializedStack());
        if (stack.isEmpty() && hasLegacyMetadata(change)) {
            stack = findLegacyMetadataTemplate(inventory, change);
        }
        if (stack.isEmpty()) {
            String itemKey = change.item().itemKey();
            if (itemKey == null || itemKey.isBlank()) {
                return ItemStack.EMPTY;
            }

            Identifier identifier = Identifier.tryParse(itemKey.contains(":") ? itemKey : "minecraft:" + itemKey);
            if (identifier == null || !Registries.ITEM.containsId(identifier)) {
                return ItemStack.EMPTY;
            }
            stack = new ItemStack(Registries.ITEM.get(identifier), 1);
        }
        return stack.copyWithCount(change.count());
    }

    private boolean matchesRollbackItem(ItemStack stack, ItemStack template, LoggedItemChange change) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (hasSerializedRollbackItem(change)) {
            if (template.isEmpty()) {
                return false;
            }
            return ItemStack.areItemsAndComponentsEqual(stack, template);
        }
        if (hasLegacyMetadata(change)) {
            return matchesLegacyRollbackMetadata(stack, change);
        }
        return matchesRollbackItemKey(stack, change.item().itemKey());
    }

    private boolean hasSerializedRollbackItem(LoggedItemChange change) {
        return change != null
            && change.item() != null
            && change.item().serializedStack() != null
            && !change.item().serializedStack().isBlank();
    }

    private boolean hasLegacyMetadata(LoggedItemChange change) {
        return change != null
            && change.item() != null
            && !hasSerializedRollbackItem(change)
            && ((!change.item().componentChanges().isBlank()) || (!change.item().displayName().isBlank()));
    }

    private ItemStack findLegacyMetadataTemplate(Inventory inventory, LoggedItemChange change) {
        if (inventory == null) {
            return ItemStack.EMPTY;
        }

        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (matchesLegacyRollbackMetadata(stack, change)) {
                return stack.copyWithCount(change.count());
            }
        }
        return ItemStack.EMPTY;
    }

    private boolean matchesLegacyRollbackMetadata(ItemStack stack, LoggedItemChange change) {
        if (stack == null || stack.isEmpty() || change == null || change.item() == null) {
            return false;
        }
        if (!matchesRollbackItemKey(stack, change.item().itemKey())) {
            return false;
        }
        if (!change.item().displayName().isBlank() && !change.item().displayName().equals(stack.getName().getString())) {
            return false;
        }
        if (change.item().componentChanges().isBlank()) {
            return true;
        }

        String currentComponents = stack.getComponentChanges().toString();
        return change.item().componentChanges().equals(currentComponents)
            || change.item().componentChanges().equals(LoggedItemData.summarizeComponentChanges(currentComponents));
    }

    private boolean matchesRollbackItemKey(ItemStack stack, String itemKey) {
        if (stack == null || stack.isEmpty() || itemKey == null || itemKey.isBlank()) {
            return false;
        }
        Identifier identifier = Registries.ITEM.getId(stack.getItem());
        return identifier != null && itemKey.equalsIgnoreCase(identifier.toString());
    }

    private void syncPlayerInventory(ServerPlayerEntity player) {
        player.getInventory().markDirty();
        player.playerScreenHandler.syncState();
        player.currentScreenHandler.sendContentUpdates();
    }

    public int applyPendingInventoryRollbacks(ServerPlayerEntity player) {
        if (player == null) {
            return 0;
        }

        List<PendingInventoryRollbackRecord> pending = database.lookupPendingInventoryRollbacks(
            player.getUuidAsString(),
            player.getName().getString(),
            512
        );
        if (pending.isEmpty()) {
            return 0;
        }

        int applied = 0;
        int failed = 0;
        for (PendingInventoryRollbackRecord entry : pending) {
            try {
                LoggedItemChange change = LoggedItemChange.parse(entry.target(), entry.payload());
                if (change == null || change.item() == null || change.item().itemKey().isBlank()) {
                    database.markPendingInventoryRollbackFailed(entry.id(), "Unreadable logged item payload");
                    failed++;
                    continue;
                }

                boolean changed = entry.addItems()
                    ? addItemsToPlayer(player, change)
                    : removeItemsFromPlayer(player, change);
                if (!changed) {
                    String reason = entry.addItems()
                        ? "Player inventory had no capacity for rollback items"
                        : "Player inventory did not contain rollback items";
                    database.markPendingInventoryRollbackFailed(entry.id(), reason);
                    failed++;
                    continue;
                }

                database.markPendingInventoryRollbackApplied(entry.id(), entry.eventId(), entry.restore());
                applied++;
            }
            catch (RuntimeException exception) {
                database.markPendingInventoryRollbackFailed(entry.id(), exception.getMessage());
                failed++;
                logger.warn(
                    "Deferred inventory rollback failed for player {} (queueId={}, eventId={})",
                    player.getName().getString(),
                    entry.id(),
                    entry.eventId(),
                    exception
                );
            }
        }

        if (applied > 0) {
            syncPlayerInventory(player);
        }
        if (applied > 0 || failed > 0) {
            logger.info(
                "Processed deferred inventory rollbacks for {}: applied={}, failed={}, pending={}",
                player.getName().getString(),
                applied,
                failed,
                Math.max(0, pending.size() - applied - failed)
            );
        }

        return applied;
    }

    private PlayerConfigEntry resolveActorConfigEntry(StoredEventRecord event) {
        if (event.actorUuid() != null && !event.actorUuid().isBlank()) {
            try {
                UUID actorUuid = UUID.fromString(event.actorUuid());
                String actorName = event.actorName() == null || event.actorName().isBlank() ? actorUuid.toString() : event.actorName();
                return new PlayerConfigEntry(actorUuid, actorName);
            }
            catch (IllegalArgumentException ignored) {
            }
        }
        if (event.actorName() != null && !event.actorName().isBlank()) {
            return PlayerConfigEntry.fromNickname(event.actorName());
        }
        return null;
    }

    private boolean isItemRollbackEvent(CoreProtectEventType eventType) {
        return eventType == CoreProtectEventType.ITEM_PICKUP
            || eventType == CoreProtectEventType.ITEM_DROP
            || eventType == CoreProtectEventType.ITEM_THROW
            || eventType == CoreProtectEventType.ITEM_SHOOT
            || eventType == CoreProtectEventType.ITEM_BUY
            || eventType == CoreProtectEventType.ITEM_SELL
            || eventType == CoreProtectEventType.ITEM_CREATE
            || eventType == CoreProtectEventType.ITEM_DESTROY;
    }

    private enum ApplyOutcome {
        APPLIED,
        DEFERRED,
        FAILED
    }

    private interface ServerTickTask {
        boolean processSlice(MinecraftServer server);
    }

    private final class ApplyTask implements ServerTickTask {
        private final boolean restore;
        private final int radius;
        private final int seconds;
        private final String actorSummary;
        private final Consumer<RollbackExecutionResult> completion;
        private final List<Map.Entry<ChunkBucket, List<StoredEventRecord>>> buckets;
        private final List<Long> changedIds = new ArrayList<>();
        private int bucketIndex;
        private int sliceStart;
        private int changed;
        private int deferred;
        private int scanned;

        private ApplyTask(
            List<StoredEventRecord> candidates,
            boolean restore,
            int radius,
            int seconds,
            String actorSummary,
            Consumer<RollbackExecutionResult> completion
        ) {
            List<StoredEventRecord> ordered = new ArrayList<>(candidates);
            if (restore) {
                Collections.reverse(ordered);
            }
            this.restore = restore;
            this.radius = radius;
            this.seconds = seconds;
            this.actorSummary = actorSummary;
            this.completion = completion;
            this.scanned = ordered.size();
            this.buckets = new ArrayList<>(bucketCandidatesByChunk(ordered).entrySet());
        }

        @Override
        public boolean processSlice(MinecraftServer server) {
            while (bucketIndex < buckets.size()) {
                Map.Entry<ChunkBucket, List<StoredEventRecord>> entry = buckets.get(bucketIndex);
                ChunkBucket bucket = entry.getKey();
                ServerWorld targetWorld = resolveWorld(server, bucket.worldKey());
                if (targetWorld == null) {
                    logger.warn(
                        "Skipping rollback bucket {}:{}:{} because world {} is not loaded",
                        bucket.worldKey(),
                        bucket.chunkX(),
                        bucket.chunkZ(),
                        bucket.worldKey()
                    );
                    bucketIndex++;
                    sliceStart = 0;
                    continue;
                }

                List<StoredEventRecord> bucketEvents = entry.getValue();
                int sliceEnd = Math.min(sliceStart + CHUNK_APPLY_SLICE_SIZE, bucketEvents.size());
                for (int index = sliceStart; index < sliceEnd; index++) {
                    StoredEventRecord event = bucketEvents.get(index);
                    ApplyOutcome outcome = applyEvent(targetWorld, event, restore);
                    if (outcome == ApplyOutcome.APPLIED) {
                        changed++;
                        changedIds.add(event.id());
                    }
                    else if (outcome == ApplyOutcome.DEFERRED) {
                        deferred++;
                    }
                }

                if (sliceEnd < bucketEvents.size()) {
                    sliceStart = sliceEnd;
                    return false;
                }

                bucketIndex++;
                sliceStart = 0;
            }

            int marked = database.updateRolledBack(changedIds, !restore);
            completion.accept(new RollbackExecutionResult(restore, scanned, changed, deferred, marked, radius, seconds, actorSummary));
            return true;
        }
    }

    private final class PreviewTask implements ServerTickTask {
        private final UUID playerUuid;
        private final boolean restore;
        private final int matched;
        private final Consumer<RollbackPreviewResult> completion;
        private final List<StoredEventRecord> candidates;
        private final Map<String, PreviewService.PreviewBlockChange> changes = new LinkedHashMap<>();
        private int index;

        private PreviewTask(
            UUID playerUuid,
            List<StoredEventRecord> candidates,
            boolean restore,
            Consumer<RollbackPreviewResult> completion
        ) {
            this.playerUuid = playerUuid;
            this.restore = restore;
            this.matched = candidates.size();
            this.completion = completion;
            this.candidates = List.copyOf(candidates);
        }

        @Override
        public boolean processSlice(MinecraftServer server) {
            int end = Math.min(index + CHUNK_APPLY_SLICE_SIZE, candidates.size());
            for (int cursor = index; cursor < end; cursor++) {
                StoredEventRecord event = candidates.get(cursor);
                if (!event.hasPosition()) {
                    continue;
                }
                if (event.type() != CoreProtectEventType.BLOCK_PLACE && event.type() != CoreProtectEventType.BLOCK_BREAK) {
                    continue;
                }

                ServerWorld targetWorld = resolveWorld(server, event.worldKey());
                if (targetWorld == null) {
                    continue;
                }

                BlockState targetState = previewBlockState(targetWorld, event, restore);
                if (targetState == null) {
                    continue;
                }

                BlockPos pos = event.blockPos();
                String key = event.worldKey() + ":" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ();
                changes.put(key, new PreviewService.PreviewBlockChange(event.worldKey(), pos, targetState));
            }

            index = end;
            if (index < candidates.size()) {
                return false;
            }

            ServerPlayerEntity player = playerUuid == null ? null : server.getPlayerManager().getPlayer(playerUuid);
            List<PreviewService.PreviewBlockChange> previewChanges = player == null ? List.of() : new ArrayList<>(changes.values());
            completion.accept(new RollbackPreviewResult(matched, previewChanges));
            return true;
        }
    }

    private record ChunkBucket(String worldKey, int chunkX, int chunkZ) {
    }

    private ContainerTransactionPayload parseContainerTransactionPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }

        Map<String, String> structured = parseKeyValuePayload(payload);
        if (!structured.containsKey("added") || !structured.containsKey("item")) {
            return null;
        }

        LoggedItemChange change = LoggedItemChange.parse(null, payload);
        if (change.item() == null || change.item().itemKey() == null || change.item().itemKey().isBlank()) {
            return null;
        }

        return new ContainerTransactionPayload(
            structured.get("container"),
            change,
            Boolean.parseBoolean(structured.get("added"))
        );
    }

    private String simplifyEntityType(Entity entity) {
        Identifier identifier = net.minecraft.registry.Registries.ENTITY_TYPE.getId(entity.getType());
        String value = identifier == null ? "" : identifier.toString();
        return simplifyEntityType(value);
    }

    private String simplifyEntityType(String value) {
        if (value == null) {
            return "";
        }
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    private boolean isBoatType(String type) {
        net.minecraft.entity.EntityType<?> entityType = resolveEntityType(type);
        return entityType != null && AbstractBoatEntity.class.isAssignableFrom(entityType.getBaseClass());
    }

    private boolean isMinecartType(String type) {
        net.minecraft.entity.EntityType<?> entityType = resolveEntityType(type);
        return entityType != null && AbstractMinecartEntity.class.isAssignableFrom(entityType.getBaseClass());
    }

    private net.minecraft.entity.EntityType<?> resolveEntityType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }

        Identifier identifier = Identifier.tryParse(type.contains(":") ? type : "minecraft:" + type);
        if (identifier == null || !net.minecraft.registry.Registries.ENTITY_TYPE.containsId(identifier)) {
            return null;
        }
        return net.minecraft.registry.Registries.ENTITY_TYPE.get(identifier);
    }

    private ServerWorld resolveWorld(MinecraftServer server, String worldKey) {
        if (server == null || worldKey == null || worldKey.isBlank()) {
            return null;
        }

        Identifier identifier = Identifier.tryParse(worldKey);
        if (identifier == null) {
            return null;
        }

        return server.getWorld(RegistryKey.of(RegistryKeys.WORLD, identifier));
    }

    private String summarizeActors(List<String> actorFilters) {
        if (actorFilters == null || actorFilters.isEmpty()) {
            return null;
        }

        StringJoiner joiner = new StringJoiner(",");
        for (String actorFilter : actorFilters) {
            joiner.add(actorFilter);
        }
        return joiner.toString();
    }

    private int describeSeconds(long notBefore, long notAfter) {
        long deltaMillis = Math.max(0L, notAfter - notBefore);
        return (int) Math.max(0L, deltaMillis / 1000L);
    }

    private record ContainerTransactionPayload(
        String containerType,
        LoggedItemChange change,
        Boolean added
    ) {
    }

}
