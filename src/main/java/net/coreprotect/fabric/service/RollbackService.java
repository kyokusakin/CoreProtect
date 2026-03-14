package net.coreprotect.fabric.service;

import com.mojang.authlib.GameProfile;
import net.coreprotect.fabric.db.CoreProtectDatabase;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.LoggedItemChange;
import net.coreprotect.fabric.util.LoggedSignState;
import net.coreprotect.fabric.util.QueryBounds;
import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.LecternBlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.StackWithSlot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.RegistryOps;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.PlayerConfigEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.network.packet.c2s.common.SyncedClientOptions;
import net.minecraft.state.property.Properties;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.Optional;
import java.util.UUID;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RollbackService {
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
        List<Long> changedIds = new ArrayList<>();
        for (StoredEventRecord event : candidates) {
            if (!event.hasPosition()) {
                continue;
            }

            ServerWorld targetWorld = resolveWorld(server, event.worldKey());
            if (targetWorld == null) {
                logger.warn("Skipping rollback event {} because world {} is not loaded", event.id(), event.worldKey());
                continue;
            }

            if (applyEvent(targetWorld, event, restore)) {
                changed++;
                changedIds.add(event.id());
            }
        }

        int marked = database.updateRolledBack(changedIds, !restore);
        return new RollbackExecutionResult(restore, candidates.size(), changed, marked, radius, seconds, actorSummary);
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

    private boolean applyEvent(ServerWorld world, StoredEventRecord event, boolean restore) {
        switch (event.type()) {
            case SIGN_CHANGE:
                return applySignChange(world, event, restore);
            case BLOCK_PLACE:
            case BLOCK_BREAK:
                return applyBlockChange(world, event, restore);
            case CONTAINER_TRANSACTION:
                return applyContainerTransaction(world, event, restore);
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
                return applyEntityChange(world, event, restore);
            default:
                return false;
        }
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

    private boolean applyItemChange(ServerWorld world, StoredEventRecord event, boolean restore) {
        LoggedItemChange change = LoggedItemChange.parse(event.target(), event.payload());
        if (change == null || change.item() == null || change.item().itemKey().isBlank()) {
            return false;
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
            return changed;
        }

        OfflinePlayerInventory offlineInventory = loadOfflineActorInventory(world.getServer(), world, event);
        if (offlineInventory == null) {
            logger.debug("Skipping item rollback for {} because actor {} is not available online or offline", event.id(), event.actorName());
            return false;
        }

        boolean changed = addItems
            ? addItemsToPlayer(offlineInventory.player(), change)
            : removeItemsFromPlayer(offlineInventory.player(), change);
        if (!changed) {
            return false;
        }

        return saveOfflineActorInventory(world.getServer(), offlineInventory);
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

        return world.setBlockState(pos, targetState, Block.NOTIFY_ALL);
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
        if (!forceSpawn && findMatchingEntity(world, pos, payload) != null) {
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
        Direction facing = parseFacing(payload.get("facing"));
        if ("item_frame".equals(type) || "glow_item_frame".equals(type)) {
            List<ItemFrameEntity> matches = world.getEntitiesByClass(
                ItemFrameEntity.class,
                Box.of(Vec3d.ofCenter(pos), 2.0, 2.0, 2.0),
                entity -> entity.getAttachedBlockPos().equals(pos)
                    && (facing == null || entity.getHorizontalFacing() == facing)
                    && simplifyEntityType(entity).equals(type)
            );
            return matches.isEmpty() ? null : matches.get(0);
        }
        if ("painting".equals(type)) {
            List<PaintingEntity> matches = world.getEntitiesByClass(
                PaintingEntity.class,
                Box.of(Vec3d.ofCenter(pos), 2.0, 2.0, 2.0),
                entity -> entity.getAttachedBlockPos().equals(pos)
                    && (facing == null || entity.getHorizontalFacing() == facing)
            );
            return matches.isEmpty() ? null : matches.get(0);
        }
        if ("armor_stand".equals(type)) {
            List<ArmorStandEntity> matches = world.getEntitiesByClass(
                ArmorStandEntity.class,
                Box.of(Vec3d.ofCenter(pos), 1.5, 3.0, 1.5),
                entity -> entity.getBlockPos().equals(pos)
            );
            return matches.isEmpty() ? null : matches.get(0);
        }
        if ("end_crystal".equals(type)) {
            List<EndCrystalEntity> matches = world.getEntitiesByClass(
                EndCrystalEntity.class,
                Box.of(Vec3d.ofCenter(pos), 1.5, 3.0, 1.5),
                entity -> entity.getBlockPos().equals(pos)
            );
            return matches.isEmpty() ? null : matches.get(0);
        }
        if (isBoatType(type)) {
            List<AbstractBoatEntity> matches = world.getEntitiesByClass(
                AbstractBoatEntity.class,
                Box.of(Vec3d.ofCenter(pos), 2.0, 3.0, 2.0),
                entity -> entity.getBlockPos().equals(pos) && simplifyEntityType(entity).equals(type)
            );
            return matches.isEmpty() ? null : matches.get(0);
        }
        if (isMinecartType(type)) {
            List<AbstractMinecartEntity> matches = world.getEntitiesByClass(
                AbstractMinecartEntity.class,
                Box.of(Vec3d.ofCenter(pos), 2.0, 3.0, 2.0),
                entity -> entity.getBlockPos().equals(pos) && simplifyEntityType(entity).equals(type)
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

    private Entity createEntity(ServerWorld world, BlockPos pos, Map<String, String> payload) {
        String nbtStr = payload.get("nbt");
        String type = payload.get("type");

        if (nbtStr != null && !nbtStr.isBlank()) {
            try {
                NbtCompound entityNbt = StringNbtReader.readCompound(nbtStr);
                
                if (!entityNbt.contains("id") && type != null) {
                    entityNbt.putString("id", type);
                }
                
                Entity entity = net.minecraft.entity.EntityType.loadEntityWithPassengers(
                    entityNbt,
                    world,
                    net.minecraft.entity.SpawnReason.COMMAND,
                    e -> e
                );

                if (entity != null) {
                    entity.setPosition(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                    return entity;
                }
            } catch (Exception e) {
                logger.warn("Failed to parse NBT for entity rollback", e);
            }
        }
        return createBaseEntity(world, pos, payload);
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
            ItemStack stack = parseItemStack(world, payload.get("item"));
            if (!stack.isEmpty()) {
                itemFrameEntity.setHeldItemStack(stack, false);
            }
            Integer rotation = parseInteger(payload.get("rotation"));
            if (rotation != null) {
                itemFrameEntity.setRotation(rotation);
            }
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
            equipIfPresent(armorStandEntity, EquipmentSlot.FEET, payload.get("feet"));
            equipIfPresent(armorStandEntity, EquipmentSlot.LEGS, payload.get("legs"));
            equipIfPresent(armorStandEntity, EquipmentSlot.CHEST, payload.get("chest"));
            equipIfPresent(armorStandEntity, EquipmentSlot.HEAD, payload.get("head"));
            equipIfPresent(armorStandEntity, EquipmentSlot.MAINHAND, payload.get("mainhand"));
            equipIfPresent(armorStandEntity, EquipmentSlot.OFFHAND, payload.get("offhand"));

            NbtCompound nbt = new NbtCompound();
            boolean modified = false;

            if (Boolean.TRUE.equals(parseBoolean(payload.get("ShowArms")))) {
                nbt.putBoolean("ShowArms", true);
                modified = true;
            }
            if (Boolean.TRUE.equals(parseBoolean(payload.get("Small")))) {
                nbt.putBoolean("Small", true);
                modified = true;
            }
            if (Boolean.TRUE.equals(parseBoolean(payload.get("NoBasePlate")))) {
                nbt.putBoolean("NoBasePlate", true);
                modified = true;
            }
            if (Boolean.TRUE.equals(parseBoolean(payload.get("Marker")))) {
                nbt.putBoolean("Marker", true);
                modified = true;
            }
            if (Boolean.TRUE.equals(parseBoolean(payload.get("Invisible")))) {
                nbt.putBoolean("Invisible", true);
                modified = true;
            }

            if (modified) {
                net.minecraft.storage.NbtReadView readView = (net.minecraft.storage.NbtReadView) net.minecraft.storage.NbtReadView.create(net.minecraft.util.ErrorReporter.EMPTY, world.getRegistryManager(), nbt);
                armorStandEntity.readData(readView);
            }

            return armorStandEntity;
        }
        if ("end_crystal".equals(type)) {
            EndCrystalEntity endCrystalEntity = new EndCrystalEntity(world, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D);
            BlockPos beamTarget = parseBlockPos(payload.get("beam_target"));
            if (beamTarget != null) {
                endCrystalEntity.setBeamTarget(beamTarget);
            }
            Boolean showBottom = parseBoolean(payload.get("show_bottom"));
            if (showBottom != null) {
                endCrystalEntity.setShowBottom(showBottom);
            }
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
        return entity.getBlockPos().equals(pos);
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
        return change.item().componentChanges().isBlank()
            || change.item().componentChanges().equals(stack.getComponentChanges().toString());
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

    private OfflinePlayerInventory loadOfflineActorInventory(MinecraftServer server, ServerWorld fallbackWorld, StoredEventRecord event) {
        PlayerConfigEntry actor = resolveActorConfigEntry(event);
        if (actor == null || server.isHost(actor)) {
            return null;
        }

        Optional<NbtCompound> loaded = server.getPlayerManager().loadPlayerData(actor);
        if (loaded.isEmpty()) {
            return null;
        }

        ServerWorld playerWorld = fallbackWorld != null ? fallbackWorld : server.getOverworld();
        if (playerWorld == null) {
            return null;
        }

        ServerPlayerEntity player = new ServerPlayerEntity(
            server,
            playerWorld,
            new GameProfile(actor.id(), actor.name()),
            SyncedClientOptions.createDefault()
        );
        NbtCompound nbt = loaded.get().copy();
        NbtReadView readView = (NbtReadView) NbtReadView.create(ErrorReporter.EMPTY, server.getRegistryManager(), nbt);
        player.getInventory().readData(readView.getTypedListView("Inventory", StackWithSlot.CODEC));
        player.getInventory().setSelectedSlot(readView.getInt("SelectedItemSlot", 0));
        return new OfflinePlayerInventory(actor, nbt, player);
    }

    private boolean saveOfflineActorInventory(MinecraftServer server, OfflinePlayerInventory inventory) {
        try {
            NbtWriteView inventoryView = NbtWriteView.create(ErrorReporter.EMPTY, server.getRegistryManager());
            inventory.player().getInventory().writeData(inventoryView.getListAppender("Inventory", StackWithSlot.CODEC));
            inventoryView.putInt("SelectedItemSlot", inventory.player().getInventory().getSelectedSlot());

            NbtCompound updatedInventory = inventoryView.getNbt();
            inventory.nbt().put("Inventory", updatedInventory.get("Inventory"));
            inventory.nbt().putInt("SelectedItemSlot", updatedInventory.getInt("SelectedItemSlot", 0));

            Path playerDataDir = server.getSavePath(WorldSavePath.PLAYERDATA);
            Files.createDirectories(playerDataDir);
            Path tempPath = Files.createTempFile(playerDataDir, inventory.actor().id() + "-", ".dat");
            NbtIo.writeCompressed(inventory.nbt(), tempPath);
            Path currentPath = playerDataDir.resolve(inventory.actor().id() + ".dat");
            Path backupPath = playerDataDir.resolve(inventory.actor().id() + ".dat_old");
            Util.backupAndReplace(currentPath, tempPath, backupPath);
            return true;
        }
        catch (IOException exception) {
            logger.warn("Failed to save offline player inventory for {}", inventory.actor().name(), exception);
            return false;
        }
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

    private record OfflinePlayerInventory(PlayerConfigEntry actor, NbtCompound nbt, ServerPlayerEntity player) {
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

