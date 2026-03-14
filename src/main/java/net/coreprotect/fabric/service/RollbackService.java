package net.coreprotect.fabric.service;

import net.coreprotect.fabric.db.CoreProtectDatabase;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.QueryBounds;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.SpawnReason;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtReadView;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.text.Text;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

public final class RollbackService {
    private static final List<CoreProtectEventType> SUPPORTED_EVENT_TYPES = List.of(
        CoreProtectEventType.BLOCK_BREAK,
        CoreProtectEventType.BLOCK_PLACE,
        CoreProtectEventType.SIGN_CHANGE
    );

    private final CoreProtectDatabase database;
    private final Logger logger;

    public RollbackService(CoreProtectDatabase database, Logger logger) {
        this.database = database;
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
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(actionFilter);
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
        return applyCandidates(player, candidates, restore, -1, seconds, summarizeActors(actorFilters));
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
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(actionFilter);
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
        return applyCandidates(player, candidates, restore, -1, describeSeconds(notBefore, notAfter), summarizeActors(actorFilters));
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
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(actionFilter);
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
        return applyCandidates(player, candidates, restore, radius == null ? -1 : radius, seconds, summarizeActors(actorFilters));
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
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(actionFilter);
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
        return applyCandidates(player, candidates, restore, -1, describeSeconds(notBefore, notAfter), summarizeActors(actorFilters));
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
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(actionFilter);
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
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(actionFilter);
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
        List<CoreProtectEventType> rollbackTypes = resolveRollbackTypes(actionFilter);
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

    private RollbackExecutionResult applyCandidates(ServerPlayerEntity player, List<StoredEventRecord> candidates, boolean restore, int radius, int seconds, String actorSummary) {
        if (candidates.isEmpty()) {
            return new RollbackExecutionResult(restore, 0, 0, 0, radius, seconds, actorSummary);
        }

        int changed = 0;
        List<Long> changedIds = new ArrayList<>();
        for (StoredEventRecord event : candidates) {
            if (!event.hasPosition()) {
                continue;
            }

            ServerWorld targetWorld = resolveWorld(player, event.worldKey());
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

            ServerWorld targetWorld = resolveWorld(player, event.worldKey());
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
        return switch (event.type()) {
            case BLOCK_PLACE -> restore
                ? BlockStateSerializer.deserialize(world, event.target(), event.payload(), logger)
                : BlockStateSerializer.air();
            case BLOCK_BREAK -> restore
                ? BlockStateSerializer.air()
                : BlockStateSerializer.deserialize(world, event.target(), event.payload(), logger);
            default -> null;
        };
    }

    private boolean applyEvent(ServerWorld world, StoredEventRecord event, boolean restore) {
        switch (event.type()) {
            case SIGN_CHANGE:
                return applySignChange(world, event, restore);
            case BLOCK_PLACE:
            case BLOCK_BREAK:
                return applyBlockChange(world, event, restore);
            case ENTITY_PLACE:
            case ENTITY_BREAK:
                return applyEntityChange(world, event, restore);
            default:
                return false;
        }
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

        if (hasRollbackConflict(world, event, restore, currentState, targetState)) {
            logger.debug("Skipping {} at {} because the current state would conflict with rollback safety checks", event.type(), pos);
            return false;
        }
        if (currentState.equals(targetState)) {
            return false;
        }

        return world.setBlockState(pos, targetState, Block.NOTIFY_ALL);
    }

    private boolean hasRollbackConflict(ServerWorld world, StoredEventRecord event, boolean restore, BlockState currentState, BlockState targetState) {
        BlockState loggedState = BlockStateSerializer.deserialize(world, event.target(), event.payload(), logger);
        if (loggedState == null) {
            return false;
        }

        if (event.type() == CoreProtectEventType.BLOCK_PLACE) {
            if (restore) {
                return !currentState.isAir() && !currentState.equals(loggedState);
            }
            return !currentState.equals(loggedState) && !currentState.isAir();
        }
        if (event.type() == CoreProtectEventType.BLOCK_BREAK) {
            if (restore) {
                return !currentState.equals(loggedState) && !currentState.isAir();
            }
            return !currentState.isAir() && !currentState.equals(targetState);
        }
        return false;
    }

    private boolean applySignChange(ServerWorld world, StoredEventRecord event, boolean restore) {
        BlockPos pos = event.blockPos();
        if (!(world.getBlockEntity(pos) instanceof SignBlockEntity)) {
            logger.debug("Skipping sign {} at {} because no sign block entity is present", restore ? "restore" : "rollback", pos);
            return false;
        }

        SignPayload payload = parseSignPayload(event.payload());
        if (payload == null) {
            logger.warn("Skipping sign change at {} due to unreadable sign payload", pos);
            return false;
        }

        String[] targetLines = restore
            ? payload.lines()
            : database.lookupPreviousSignState(event.worldKey(), pos, event.id(), payload.front());
        return updateSignText(world, pos, (SignBlockEntity) world.getBlockEntity(pos), payload.front(), targetLines);
    }

    private boolean updateSignText(ServerWorld world, BlockPos pos, SignBlockEntity signBlockEntity, boolean front, String[] targetLines) {
        String[] normalizedLines = normalizeLines(targetLines);
        String[] currentLines = readSignLines(signBlockEntity, front);
        if (Arrays.equals(currentLines, normalizedLines)) {
            return false;
        }

        SignText updatedText = signBlockEntity.getText(front);
        for (int index = 0; index < normalizedLines.length; index++) {
            updatedText = updatedText.withMessage(index, Text.literal(normalizedLines[index]));
        }

        if (!signBlockEntity.setText(updatedText, front)) {
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
            default -> false;
        };
    }

    private String[] readSignLines(SignBlockEntity signBlockEntity, boolean front) {
        String[] lines = new String[4];
        for (int index = 0; index < lines.length; index++) {
            lines[index] = signBlockEntity.getText(front).getMessage(index, false).getString();
        }
        return lines;
    }

    private String[] normalizeLines(String[] lines) {
        String[] normalized = new String[] { "", "", "", "" };
        if (lines == null) {
            return normalized;
        }

        for (int index = 0; index < normalized.length && index < lines.length; index++) {
            normalized[index] = lines[index] == null ? "" : lines[index];
        }
        return normalized;
    }

    private SignPayload parseSignPayload(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }

        String[] rawParts = payload.split("\\n", -1);
        if (rawParts.length == 0) {
            return null;
        }

        boolean front;
        if ("front".equalsIgnoreCase(rawParts[0])) {
            front = true;
        }
        else if ("back".equalsIgnoreCase(rawParts[0])) {
            front = false;
        }
        else {
            return null;
        }

        String[] lines = new String[] { "", "", "", "" };
        for (int index = 0; index < lines.length && index + 1 < rawParts.length; index++) {
            lines[index] = rawParts[index + 1];
        }
        return new SignPayload(front, lines);
    }

    private List<CoreProtectEventType> resolveRollbackTypes(List<CoreProtectEventType> actionFilter) {
        if (actionFilter == null || actionFilter.isEmpty()) {
            return SUPPORTED_EVENT_TYPES;
        }

        List<CoreProtectEventType> rollbackTypes = new ArrayList<>();
        for (CoreProtectEventType eventType : actionFilter) {
            if (!isSupported(eventType) || rollbackTypes.contains(eventType)) {
                continue;
            }
            rollbackTypes.add(eventType);
        }
        return rollbackTypes.isEmpty() ? SUPPORTED_EVENT_TYPES : rollbackTypes;
    }

    public static boolean isSupported(CoreProtectEventType eventType) {
        return SUPPORTED_EVENT_TYPES.contains(eventType)
            || eventType == CoreProtectEventType.ENTITY_PLACE
            || eventType == CoreProtectEventType.ENTITY_BREAK;
    }

    private boolean spawnLoggedEntity(ServerWorld world, BlockPos pos, Map<String, String> payload) {
        if (findMatchingEntity(world, pos, payload) != null) {
            return false;
        }

        Entity entity = createEntity(world, pos, payload);
        if (entity == null) {
            return false;
        }
        return world.spawnEntity(entity);
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
        String type = payload.get("type");
        Direction facing = parseFacing(payload.get("facing"));
        if (type == null || type.isBlank()) {
            return null;
        }
        if (("item_frame".equals(type) || "glow_item_frame".equals(type)) && facing != null) {
            ItemFrameEntity itemFrameEntity = "glow_item_frame".equals(type)
                ? new ItemFrameEntity(net.minecraft.entity.EntityType.GLOW_ITEM_FRAME, world, pos, facing)
                : new ItemFrameEntity(world, pos, facing);
            ItemStack stack = parseItemStack(payload.get("item"));
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
        applyEntityNbt(entity, payload.get("nbt"));
        return entity;
    }

    private void equipIfPresent(ArmorStandEntity armorStandEntity, EquipmentSlot slot, String serializedStack) {
        ItemStack stack = parseItemStack(serializedStack);
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
        if (!entity.getBlockPos().equals(pos)) {
            return false;
        }
        String nbtString = payload.get("nbt");
        if (nbtString == null || nbtString.isBlank()) {
            return true;
        }
        String currentNbt = serializeComparableEntityNbt(entity);
        return currentNbt.isBlank() || currentNbt.equals(nbtString);
    }

    private void applyEntityNbt(Entity entity, String nbtString) {
        if (nbtString == null || nbtString.isBlank()) {
            return;
        }
        try {
            NbtCompound nbt = StringNbtReader.readCompound(nbtString);
            nbt.remove("UUID");
            if (entity.getEntityWorld() instanceof ServerWorld serverWorld) {
                entity.readData(NbtReadView.create(new ErrorReporter.Impl(entity.getErrorReporterContext()), serverWorld.getRegistryManager(), nbt));
            }
        }
        catch (Exception exception) {
            logger.debug("Unable to apply entity NBT for {}", simplifyEntityType(entity), exception);
        }
    }

    private String serializeComparableEntityNbt(Entity entity) {
        try {
            if (!(entity.getEntityWorld() instanceof ServerWorld serverWorld)) {
                return "";
            }
            NbtCompound nbt = new NbtCompound();
            NbtWriteView writeView = NbtWriteView.create(new ErrorReporter.Impl(entity.getErrorReporterContext()), serverWorld.getRegistryManager());
            entity.writeData(writeView);
            nbt.copyFrom(writeView.getNbt());
            nbt.remove("UUID");
            return nbt.toString();
        }
        catch (RuntimeException exception) {
            return "";
        }
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

    private ItemStack parseItemStack(String value) {
        if (value == null || value.isBlank() || "empty".equalsIgnoreCase(value)) {
            return ItemStack.EMPTY;
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

    private ServerWorld resolveWorld(ServerPlayerEntity player, String worldKey) {
        if (worldKey == null || worldKey.isBlank()) {
            return (ServerWorld) player.getEntityWorld();
        }

        Identifier identifier = Identifier.tryParse(worldKey);
        if (identifier == null) {
            return null;
        }

        return ((ServerWorld) player.getEntityWorld()).getServer().getWorld(RegistryKey.of(RegistryKeys.WORLD, identifier));
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

    private static final class SignPayload {
        private final boolean front;
        private final String[] lines;

        private SignPayload(boolean front, String[] lines) {
            this.front = front;
            this.lines = lines;
        }

        private boolean front() {
            return front;
        }

        private String[] lines() {
            return lines;
        }
    }
}
