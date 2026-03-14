package net.coreprotect.fabric.log;

import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.coreprotect.fabric.db.CoreProtectDatabase;
import net.coreprotect.fabric.db.EventRecord;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.LoggedItemChange;
import net.coreprotect.fabric.util.LoggedItemData;
import net.coreprotect.fabric.util.TransientLookupCache;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.decoration.AbstractDecorationEntity;
import net.minecraft.entity.decoration.ItemFrameEntity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.entity.vehicle.AbstractBoatEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.storage.NbtWriteView;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.ErrorReporter;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class FabricEventLogger {
    private final Object cutoverMonitor = new Object();
    private final Logger logger;
    private CoreProtectDatabase database;
    private CoreProtectFabricConfig config;
    private boolean cutoverBuffering;
    private final List<EventRecord> cutoverBuffer = new ArrayList<>();

    public FabricEventLogger(CoreProtectDatabase database, CoreProtectFabricConfig config, Logger logger) {
        this.database = database;
        this.config = config;
        this.logger = logger;
    }

    public void beginCutoverBuffering() {
        synchronized (cutoverMonitor) {
            cutoverBuffering = true;
        }
    }

    public int completeCutover(CoreProtectDatabase newDatabase, CoreProtectFabricConfig newConfig) {
        synchronized (cutoverMonitor) {
            database = newDatabase;
            config = newConfig;
            int flushed = cutoverBuffer.size();
            for (EventRecord record : cutoverBuffer) {
                newDatabase.write(record);
            }
            cutoverBuffer.clear();
            cutoverBuffering = false;
            return flushed;
        }
    }

    public int abortCutoverBuffering() {
        synchronized (cutoverMonitor) {
            int flushed = cutoverBuffer.size();
            for (EventRecord record : cutoverBuffer) {
                database.write(record);
            }
            cutoverBuffer.clear();
            cutoverBuffering = false;
            return flushed;
        }
    }

    public void logServerStart(MinecraftServer server) {
        write(new EventRecord(
            System.currentTimeMillis(),
            CoreProtectEventType.SERVER_START,
            null,
            "server",
            null,
            null,
            null,
            null,
            server.getServerModName(),
            server.getVersion()
        ));
    }

    public void logServerStop(MinecraftServer server) {
        write(new EventRecord(
            System.currentTimeMillis(),
            CoreProtectEventType.SERVER_STOP,
            null,
            "server",
            null,
            null,
            null,
            null,
            server.getServerModName(),
            "pending-writes=" + database.pendingWrites()
        ));
    }

    public void logPlayerJoin(ServerPlayerEntity player) {
        if (!config.logSessions()) {
            return;
        }

        logUsernameChangeIfNeeded(player);
        write(playerRecord(CoreProtectEventType.PLAYER_JOIN, player, (ServerWorld) player.getEntityWorld(), player.getBlockPos(), player.getName().getString(), null));
    }

    public void logPlayerQuit(ServerPlayerEntity player) {
        if (!config.logSessions()) {
            return;
        }

        write(playerRecord(CoreProtectEventType.PLAYER_QUIT, player, (ServerWorld) player.getEntityWorld(), player.getBlockPos(), player.getName().getString(), null));
    }

    public void logCommand(ServerCommandSource source, String command) {
        if (!config.logCommands()) {
            return;
        }

        if (!(source.getEntity() instanceof ServerPlayerEntity)) {
            return;
        }

        ServerPlayerEntity player = (ServerPlayerEntity) source.getEntity();
        write(playerRecord(CoreProtectEventType.PLAYER_COMMAND, player, (ServerWorld) player.getEntityWorld(), player.getBlockPos(), command, null));
    }

    public void logCommand(String actorName, ServerWorld world, BlockPos pos, String command) {
        if (!config.logCommands()) {
            return;
        }
        if (actorName == null || actorName.isBlank() || world == null || pos == null || command == null || command.isBlank()) {
            return;
        }

        write(new EventRecord(
            System.currentTimeMillis(),
            CoreProtectEventType.PLAYER_COMMAND,
            null,
            actorName,
            world.getRegistryKey().getValue().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            command,
            null
        ));
    }

    public void logChat(ServerPlayerEntity player, ServerWorld world, BlockPos pos, String message) {
        if (!config.logChat()) {
            return;
        }

        write(playerRecord(CoreProtectEventType.PLAYER_CHAT, player, world, pos, message, null));
    }

    public void logChat(String actorName, ServerWorld world, BlockPos pos, String message) {
        if (!config.logChat()) {
            return;
        }
        if (actorName == null || actorName.isBlank() || world == null || pos == null || message == null || message.isBlank()) {
            return;
        }

        write(new EventRecord(
            System.currentTimeMillis(),
            CoreProtectEventType.PLAYER_CHAT,
            null,
            actorName,
            world.getRegistryKey().getValue().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            message,
            null
        ));
    }

    public void logItemPickup(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        logItemPickup(
            player,
            world.getRegistryKey().getValue().toString(),
            pos,
            LoggedItemData.fromStack(stack),
            stack.getCount(),
            null
        );
    }

    public void logItemPickup(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        logItemPickup(player, worldKey, pos, LoggedItemData.fromItemKey(itemKey), count, contextLabel);
    }

    public void logItemPickup(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_PICKUP, player.getUuidAsString(), player.getName().getString(), worldKey, pos, item, count, contextLabel);
    }

    public void logItemPickup(String actorName, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_PICKUP, null, actorName, worldKey, pos, item, count, contextLabel);
    }

    public void logItemDrop(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        logItemDrop(
            player,
            world.getRegistryKey().getValue().toString(),
            pos,
            LoggedItemData.fromStack(stack),
            stack.getCount(),
            null
        );
    }

    public void logItemDrop(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        logItemDrop(player, worldKey, pos, LoggedItemData.fromItemKey(itemKey), count, contextLabel);
    }

    public void logItemDrop(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_DROP, player.getUuidAsString(), player.getName().getString(), worldKey, pos, item, count, contextLabel);
    }

    public void logItemDrop(String actorName, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_DROP, null, actorName, worldKey, pos, item, count, contextLabel);
    }

    public void logItemDrop(String actorUuid, String actorName, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_DROP, actorUuid, actorName, worldKey, pos, item, count, contextLabel);
    }

    public void logItemThrow(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        logItemThrow(
            player,
            world.getRegistryKey().getValue().toString(),
            pos,
            LoggedItemData.fromStack(stack),
            stack.getCount(),
            null
        );
    }

    public void logItemThrow(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        logItemThrow(player, worldKey, pos, LoggedItemData.fromItemKey(itemKey), count, contextLabel);
    }

    public void logItemThrow(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_THROW, player.getUuidAsString(), player.getName().getString(), worldKey, pos, item, count, contextLabel);
    }

    public void logItemShoot(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        logItemShoot(
            player,
            world.getRegistryKey().getValue().toString(),
            pos,
            LoggedItemData.fromStack(stack),
            stack.getCount(),
            null
        );
    }

    public void logItemShoot(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        logItemShoot(player, worldKey, pos, LoggedItemData.fromItemKey(itemKey), count, contextLabel);
    }

    public void logItemShoot(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_SHOOT, player.getUuidAsString(), player.getName().getString(), worldKey, pos, item, count, contextLabel);
    }

    public void logItemBuy(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        logItemBuy(
            player,
            world.getRegistryKey().getValue().toString(),
            pos,
            LoggedItemData.fromStack(stack),
            stack.getCount(),
            null
        );
    }

    public void logItemBuy(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        logItemBuy(player, worldKey, pos, LoggedItemData.fromItemKey(itemKey), count, contextLabel);
    }

    public void logItemBuy(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_BUY, player.getUuidAsString(), player.getName().getString(), worldKey, pos, item, count, contextLabel);
    }

    public void logItemSell(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        logItemSell(
            player,
            world.getRegistryKey().getValue().toString(),
            pos,
            LoggedItemData.fromStack(stack),
            stack.getCount(),
            null
        );
    }

    public void logItemSell(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        logItemSell(player, worldKey, pos, LoggedItemData.fromItemKey(itemKey), count, contextLabel);
    }

    public void logItemSell(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_SELL, player.getUuidAsString(), player.getName().getString(), worldKey, pos, item, count, contextLabel);
    }

    public void logItemCreate(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        logItemCreate(
            player,
            world.getRegistryKey().getValue().toString(),
            pos,
            LoggedItemData.fromStack(stack),
            stack.getCount(),
            null
        );
    }

    public void logItemCreate(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        logItemCreate(player, worldKey, pos, LoggedItemData.fromItemKey(itemKey), count, contextLabel);
    }

    public void logItemCreate(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_CREATE, player.getUuidAsString(), player.getName().getString(), worldKey, pos, item, count, contextLabel);
    }

    public void logItemDestroy(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        logItemDestroy(
            player,
            world.getRegistryKey().getValue().toString(),
            pos,
            LoggedItemData.fromStack(stack),
            stack.getCount(),
            null
        );
    }

    public void logItemDestroy(ServerPlayerEntity player, String worldKey, BlockPos pos, String itemKey, int count, String contextLabel) {
        logItemDestroy(player, worldKey, pos, LoggedItemData.fromItemKey(itemKey), count, contextLabel);
    }

    public void logItemDestroy(ServerPlayerEntity player, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_DESTROY, player.getUuidAsString(), player.getName().getString(), worldKey, pos, item, count, contextLabel);
    }

    public void logItemDestroy(String actorUuid, String actorName, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_DESTROY, actorUuid, actorName, worldKey, pos, item, count, contextLabel);
    }

    public void logEntityPlace(ServerPlayerEntity player, ServerWorld world, BlockPos pos, Entity entity) {
        if (!config.logEntityChanges()) {
            return;
        }

        write(playerRecord(
            CoreProtectEventType.ENTITY_PLACE,
            player,
            world,
            pos,
            describeEntityState(entity),
            serializeEntityState(entity)
        ));
    }

    public void logEntityPlace(String actorName, ServerWorld world, BlockPos pos, Entity entity) {
        if (!config.logEntityChanges()) {
            return;
        }

        write(new EventRecord(
            System.currentTimeMillis(),
            CoreProtectEventType.ENTITY_PLACE,
            null,
            actorName,
            world.getRegistryKey().getValue().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            describeEntityState(entity),
            serializeEntityState(entity)
        ));
    }

    public void logEntityBreak(ServerWorld world, BlockPos pos, Entity entity, Entity breaker) {
        if (!config.logEntityChanges()) {
            return;
        }

        Entity actor = breaker == null ? null : resolveActor(breaker, null);
        String actorUuid = actor == null ? null : actor.getUuidAsString();
        String actorName = actor == null ? "#environment" : describeActor(actor);
        write(new EventRecord(
            System.currentTimeMillis(),
            CoreProtectEventType.ENTITY_BREAK,
            actorUuid,
            actorName,
            world.getRegistryKey().getValue().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            describeEntityState(entity),
            serializeEntityState(entity)
        ));
    }

    public void logEntityBreak(String actorName, ServerWorld world, BlockPos pos, Entity entity) {
        if (!config.logEntityChanges()) {
            return;
        }

        write(new EventRecord(
            System.currentTimeMillis(),
            CoreProtectEventType.ENTITY_BREAK,
            null,
            actorName,
            world.getRegistryKey().getValue().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            describeEntityState(entity),
            serializeEntityState(entity)
        ));
    }

    public void logEntityUse(ServerPlayerEntity player, ServerWorld world, BlockPos pos, Entity entity) {
        if (!config.logEntityChanges()) {
            return;
        }

        write(playerRecord(
            CoreProtectEventType.ENTITY_USE,
            player,
            world,
            pos,
            describeEntityState(entity),
            serializeEntityState(entity)
        ));
    }

    public void logEntityKill(ServerWorld world, Entity killer, LivingEntity killedEntity, DamageSource damageSource) {
        if (!config.logEntityKills()) {
            return;
        }

        Entity actor = resolveActor(killer, damageSource);
        Entity directSourceEntity = resolveDirectSource(killer, damageSource);
        String directSource = directSourceEntity == null || directSourceEntity == actor ? null : describeEntityType(directSourceEntity);
        String target = describeEntityType(killedEntity);
        if (directSource != null && !directSource.isBlank()) {
            target = target + " via " + directSource;
        }

        write(new EventRecord(
            System.currentTimeMillis(),
            CoreProtectEventType.ENTITY_KILL,
            actor.getUuidAsString(),
            describeActor(actor),
            world.getRegistryKey().getValue().toString(),
            killedEntity.getBlockX(),
            killedEntity.getBlockY(),
            killedEntity.getBlockZ(),
            target,
            serializeEntityKill(killer, actor, killedEntity, damageSource)
        ));
    }

    public void logSignChange(ServerPlayerEntity player, ServerWorld world, BlockPos pos, boolean front, String[] newLines) {
        logSignChange(player.getUuid(), player.getName().getString(), world, pos, front, newLines);
    }

    public void logSignChange(UUID actorUuid, String actorName, ServerWorld world, BlockPos pos, boolean front, String[] newLines) {
        String preview = summarizeSign(front, newLines);
        write(new EventRecord(
            System.currentTimeMillis(),
            CoreProtectEventType.SIGN_CHANGE,
            actorUuid == null ? null : actorUuid.toString(),
            actorName,
            world.getRegistryKey().getValue().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            preview,
            serializeSign(front, newLines)
        ));
    }

    public void logContainerTransaction(ServerPlayerEntity player, String worldKey, BlockPos pos, String containerType, int slotIndex, int button, SlotActionType actionType, ItemStack beforeSlot, ItemStack afterSlot, ItemStack beforeCursor, ItemStack afterCursor) {
        logContainerTransaction(player.getUuidAsString(), player.getName().getString(), worldKey, pos, containerType, slotIndex, button, actionType, beforeSlot, afterSlot, beforeCursor, afterCursor);
    }

    public void logContainerTransaction(String actorName, String worldKey, BlockPos pos, String containerType, int slotIndex, int button, SlotActionType actionType, ItemStack beforeSlot, ItemStack afterSlot, ItemStack beforeCursor, ItemStack afterCursor) {
        logContainerTransaction(null, actorName, worldKey, pos, containerType, slotIndex, button, actionType, beforeSlot, afterSlot, beforeCursor, afterCursor);
    }

    public void logContainerTransaction(String actorUuid, String actorName, String worldKey, BlockPos pos, String containerType, int slotIndex, int button, SlotActionType actionType, ItemStack beforeSlot, ItemStack afterSlot, ItemStack beforeCursor, ItemStack afterCursor) {
        String target = summarizeContainerTransaction(containerType, slotIndex, button, actionType, beforeSlot, afterSlot, beforeCursor, afterCursor);
        String payload = serializeContainerTransaction(containerType, slotIndex, button, actionType, beforeSlot, afterSlot, beforeCursor, afterCursor);
        write(new EventRecord(
            System.currentTimeMillis(),
            CoreProtectEventType.CONTAINER_TRANSACTION,
            actorUuid,
            actorName,
            worldKey,
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            target,
            payload
        ));
    }

    public void logBlockBreak(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState state) {
        if (!config.logBlockBreaks()) {
            return;
        }

        write(blockRecord(CoreProtectEventType.BLOCK_BREAK, player.getUuid(), player.getName().getString(), world, pos, state));
    }

    public void logBlockPlace(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState state) {
        if (!config.logBlockPlaces()) {
            return;
        }

        write(blockRecord(CoreProtectEventType.BLOCK_PLACE, player.getUuid(), player.getName().getString(), world, pos, state));
    }

    public void logBlockBreak(UUID actorUuid, String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        if (!config.logBlockBreaks()) {
            return;
        }

        if (actorName != null && !actorName.isBlank()) {
            TransientLookupCache.rememberRemovedActor(
                world.getRegistryKey().getValue().toString(),
                pos,
                actorName,
                BlockStateSerializer.describeBlock(state)
            );
        }
        write(blockRecord(CoreProtectEventType.BLOCK_BREAK, actorUuid, actorName, world, pos, state));
    }

    public void logBlockPlace(UUID actorUuid, String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        if (!config.logBlockPlaces()) {
            return;
        }

        if (actorName != null && !actorName.isBlank()) {
            TransientLookupCache.rememberPlacedActor(
                world.getRegistryKey().getValue().toString(),
                pos,
                actorName,
                BlockStateSerializer.describeBlock(state)
            );
        }
        write(blockRecord(CoreProtectEventType.BLOCK_PLACE, actorUuid, actorName, world, pos, state));
    }

    public void logBlockUse(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState state) {
        if (!config.logBlockUses()) {
            return;
        }

        write(playerRecord(CoreProtectEventType.BLOCK_USE, player, world, pos, BlockStateSerializer.describeBlock(state), BlockStateSerializer.serialize(state)));
    }

    public void logBlockUse(String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        if (!config.logBlockUses()) {
            return;
        }
        if (actorName == null || actorName.isBlank() || world == null || pos == null || state == null) {
            return;
        }

        write(blockRecord(CoreProtectEventType.BLOCK_USE, null, actorName, world, pos, state));
    }

    private EventRecord playerRecord(CoreProtectEventType type, ServerPlayerEntity player, ServerWorld world, BlockPos pos, String target, String payload) {
        return new EventRecord(
            System.currentTimeMillis(),
            type,
            player.getUuidAsString(),
            player.getName().getString(),
            world.getRegistryKey().getValue().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            target,
            payload
        );
    }

    private EventRecord blockRecord(CoreProtectEventType type, UUID actorUuid, String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        return new EventRecord(
            System.currentTimeMillis(),
            type,
            actorUuid == null ? null : actorUuid.toString(),
            actorName,
            world.getRegistryKey().getValue().toString(),
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            BlockStateSerializer.describeBlock(state),
            BlockStateSerializer.serialize(state)
        );
    }

    private void logItemChange(CoreProtectEventType type, String actorUuid, String actorName, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        if (actorName == null || actorName.isBlank() || pos == null || item == null || item.itemKey() == null || item.itemKey().isBlank() || count <= 0) {
            return;
        }
        if ((type == CoreProtectEventType.ITEM_DROP
            || type == CoreProtectEventType.ITEM_THROW
            || type == CoreProtectEventType.ITEM_SHOOT
            || type == CoreProtectEventType.ITEM_SELL
            || type == CoreProtectEventType.ITEM_DESTROY) && !config.logItemDrops()) {
            return;
        }
        if ((type == CoreProtectEventType.ITEM_PICKUP
            || type == CoreProtectEventType.ITEM_BUY
            || type == CoreProtectEventType.ITEM_CREATE) && !config.logItemPickups()) {
            return;
        }

        LoggedItemChange change = new LoggedItemChange(item, count, contextLabel);
        write(new EventRecord(
            System.currentTimeMillis(),
            type,
            actorUuid,
            actorName,
            worldKey,
            pos.getX(),
            pos.getY(),
            pos.getZ(),
            change.summaryTarget(),
            change.serializePayload()
        ));
    }

    private void write(EventRecord record) {
        synchronized (cutoverMonitor) {
            if (cutoverBuffering) {
                cutoverBuffer.add(record);
                return;
            }
            database.write(record);
        }
    }

    private void logUsernameChangeIfNeeded(ServerPlayerEntity player) {
        String currentName = player.getName().getString();
        String previousName = database.lookupLatestActorName(player.getUuidAsString());
        if (previousName == null || previousName.isBlank() || previousName.equals(currentName)) {
            return;
        }

        write(playerRecord(
            CoreProtectEventType.USERNAME_CHANGE,
            player,
            (ServerWorld) player.getEntityWorld(),
            player.getBlockPos(),
            previousName + " -> " + currentName,
            previousName + "\n" + currentName
        ));
    }

    private String summarizeSign(boolean front, String[] lines) {
        StringBuilder summary = new StringBuilder(front ? "front sign" : "back sign");
        String joined = joinNonBlank(lines, " | ");
        if (!joined.isBlank()) {
            summary.append(": ").append(joined);
        }
        return summary.toString();
    }

    private String serializeSign(boolean front, String[] lines) {
        StringBuilder payload = new StringBuilder(front ? "front" : "back");
        for (String line : lines) {
            payload.append('\n').append(line == null ? "" : line);
        }
        return payload.toString();
    }

    private String summarizeContainerTransaction(String containerType, int slotIndex, int button, SlotActionType actionType, ItemStack beforeSlot, ItemStack afterSlot, ItemStack beforeCursor, ItemStack afterCursor) {
        StringBuilder summary = new StringBuilder();
        summary.append(simplifyIdentifier(containerType))
            .append(" ")
            .append(actionType.name().toLowerCase())
            .append(" slot=");
        if (slotIndex < 0) {
            summary.append("outside");
        }
        else {
            summary.append(slotIndex);
        }
        summary.append(" button=").append(button)
            .append(" ")
            .append(describeStack(beforeSlot))
            .append(" -> ")
            .append(describeStack(afterSlot));
        if (!ItemStack.areEqual(beforeCursor, afterCursor)) {
            summary.append(" cursor=").append(describeStack(beforeCursor)).append(" -> ").append(describeStack(afterCursor));
        }
        return summary.toString();
    }

    private String serializeContainerTransaction(String containerType, int slotIndex, int button, SlotActionType actionType, ItemStack beforeSlot, ItemStack afterSlot, ItemStack beforeCursor, ItemStack afterCursor) {
        StringBuilder payload = new StringBuilder();
        payload.append(simplifyIdentifier(containerType))
            .append('\n').append(slotIndex)
            .append('\n').append(button)
            .append('\n').append(actionType.name())
            .append('\n').append(describeStack(beforeSlot))
            .append('\n').append(describeStack(afterSlot))
            .append('\n').append(describeStack(beforeCursor))
            .append('\n').append(describeStack(afterCursor));
        return payload.toString();
    }

    private String serializeEntityState(Entity entity) {
        StringBuilder payload = new StringBuilder();
        payload.append("type=").append(describeEntityType(entity));
        if (entity instanceof AbstractDecorationEntity) {
            AbstractDecorationEntity decorationEntity = (AbstractDecorationEntity) entity;
            payload.append('\n').append("facing=").append(decorationEntity.getHorizontalFacing().asString());
        }
        if (entity instanceof ItemFrameEntity) {
            ItemFrameEntity itemFrameEntity = (ItemFrameEntity) entity;
            payload.append('\n').append("item=").append(describeStack(itemFrameEntity.getHeldItemStack()))
                .append('\n').append("rotation=").append(itemFrameEntity.getRotation());
        }
        if (entity instanceof ArmorStandEntity) {
            appendArmorStandEquipment(payload, (ArmorStandEntity) entity, '\n');
        }
        if (entity instanceof AbstractBoatEntity || entity instanceof AbstractMinecartEntity) {
            payload.append('\n').append("yaw=").append(Math.round(entity.getYaw()));
        }
        if (entity instanceof EndCrystalEntity) {
            EndCrystalEntity endCrystalEntity = (EndCrystalEntity) entity;
            if (endCrystalEntity.getBeamTarget() != null) {
                payload.append('\n')
                    .append("beam_target=")
                    .append(endCrystalEntity.getBeamTarget().getX()).append(',')
                    .append(endCrystalEntity.getBeamTarget().getY()).append(',')
                    .append(endCrystalEntity.getBeamTarget().getZ());
            }
            payload.append('\n').append("show_bottom=").append(endCrystalEntity.shouldShowBottom());
        }
        if (entity instanceof PaintingEntity) {
            PaintingEntity paintingEntity = (PaintingEntity) entity;
            String variant = paintingEntity.getVariant().getKey()
                .map(key -> key.getValue().toString())
                .orElse("unknown");
            payload.append('\n').append("variant=").append(simplifyIdentifier(variant));
        }
        String entityNbt = serializeEntityNbt(entity);
        if (!entityNbt.isBlank()) {
            payload.append('\n').append("nbt=").append(entityNbt);
        }
        return payload.toString();
    }

    private String serializeEntityNbt(Entity entity) {
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
            logger.debug("Unable to serialize entity NBT for {}", describeEntityType(entity), exception);
            return "";
        }
    }

    private String serializeEntityKill(Entity killer, Entity actor, LivingEntity killedEntity, DamageSource damageSource) {
        StringBuilder payload = new StringBuilder();
        payload.append("killer=").append(describeEntityType(killer))
            .append('\n').append("actor=").append(describeActor(actor))
            .append('\n').append("target=").append(describeEntityType(killedEntity))
            .append('\n').append("target_uuid=").append(killedEntity.getUuidAsString())
            .append('\n').append("damage=").append(damageSource.getName());
        return payload.toString();
    }

    private String joinNonBlank(String[] lines, String separator) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(separator);
            }
            builder.append(line);
        }
        return builder.toString();
    }

    private String describeStack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "empty";
        }
        return LoggedItemChange.fromStack(stack, stack.getCount(), null).summaryTarget();
    }

    private String describeEntityState(Entity entity) {
        StringBuilder summary = new StringBuilder(describeEntityType(entity));
        if (entity instanceof AbstractDecorationEntity) {
            AbstractDecorationEntity decorationEntity = (AbstractDecorationEntity) entity;
            summary.append(" facing=").append(decorationEntity.getHorizontalFacing().asString());
        }
        if (entity instanceof ItemFrameEntity) {
            ItemFrameEntity itemFrameEntity = (ItemFrameEntity) entity;
            if (!itemFrameEntity.getHeldItemStack().isEmpty()) {
                summary.append(" item=").append(describeStack(itemFrameEntity.getHeldItemStack()));
            }
            if (itemFrameEntity.getRotation() != 0) {
                summary.append(" rotation=").append(itemFrameEntity.getRotation());
            }
        }
        if (entity instanceof ArmorStandEntity) {
            appendArmorStandEquipment(summary, (ArmorStandEntity) entity, ' ');
        }
        if ((entity instanceof AbstractBoatEntity || entity instanceof AbstractMinecartEntity) && Math.round(entity.getYaw()) != 0) {
            summary.append(" yaw=").append(Math.round(entity.getYaw()));
        }
        if (entity instanceof EndCrystalEntity) {
            EndCrystalEntity endCrystalEntity = (EndCrystalEntity) entity;
            if (endCrystalEntity.getBeamTarget() != null) {
                summary.append(" beam=")
                    .append(endCrystalEntity.getBeamTarget().getX()).append(',')
                    .append(endCrystalEntity.getBeamTarget().getY()).append(',')
                    .append(endCrystalEntity.getBeamTarget().getZ());
            }
            if (!endCrystalEntity.shouldShowBottom()) {
                summary.append(" show_bottom=false");
            }
        }
        if (entity instanceof PaintingEntity) {
            PaintingEntity paintingEntity = (PaintingEntity) entity;
            String variant = paintingEntity.getVariant().getKey()
                .map(key -> key.getValue().toString())
                .orElse("unknown");
            summary.append(" variant=").append(simplifyIdentifier(variant));
        }
        return summary.toString();
    }

    private Entity resolveActor(Entity killer, DamageSource damageSource) {
        if (damageSource != null && damageSource.getAttacker() != null) {
            return damageSource.getAttacker();
        }
        if (killer instanceof ProjectileEntity) {
            ProjectileEntity projectileEntity = (ProjectileEntity) killer;
            Entity owner = projectileEntity.getOwner();
            if (owner != null) {
                return owner;
            }
        }
        return killer;
    }

    private Entity resolveDirectSource(Entity killer, DamageSource damageSource) {
        if (damageSource != null && damageSource.getSource() != null) {
            return damageSource.getSource();
        }
        return killer;
    }

    private String describeActor(Entity entity) {
        if (entity instanceof ServerPlayerEntity) {
            return entity.getName().getString();
        }
        return describeEntityType(entity);
    }

    private String describeEntityType(Entity entity) {
        return simplifyIdentifier(Registries.ENTITY_TYPE.getId(entity.getType()).toString());
    }

    private void appendArmorStandEquipment(StringBuilder builder, ArmorStandEntity armorStand, char separator) {
        appendEquipment(builder, "feet", armorStand.getEquippedStack(EquipmentSlot.FEET), separator);
        appendEquipment(builder, "legs", armorStand.getEquippedStack(EquipmentSlot.LEGS), separator);
        appendEquipment(builder, "chest", armorStand.getEquippedStack(EquipmentSlot.CHEST), separator);
        appendEquipment(builder, "head", armorStand.getEquippedStack(EquipmentSlot.HEAD), separator);
        appendEquipment(builder, "mainhand", armorStand.getEquippedStack(EquipmentSlot.MAINHAND), separator);
        appendEquipment(builder, "offhand", armorStand.getEquippedStack(EquipmentSlot.OFFHAND), separator);
    }

    private void appendEquipment(StringBuilder builder, String slot, ItemStack stack, char separator) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        builder.append(separator).append(slot).append("=").append(describeStack(stack));
    }

    private String simplifyIdentifier(String value) {
        if (value == null) {
            return "";
        }
        return value.startsWith("minecraft:") ? value.substring("minecraft:".length()) : value;
    }

    public String buildStatusSummary() {
        return "db=" + database.databaseDescription()
            + ", pending-writes=" + database.pendingWrites()
            + ", consumer-paused=" + database.writesPaused();
    }
}
