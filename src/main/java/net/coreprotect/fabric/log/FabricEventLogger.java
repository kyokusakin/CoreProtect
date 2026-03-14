package net.coreprotect.fabric.log;

import net.coreprotect.fabric.config.CoreProtectFabricConfig;
import net.coreprotect.fabric.db.CoreProtectDatabase;
import net.coreprotect.fabric.db.EventRecord;
import net.coreprotect.fabric.service.BlacklistService;
import net.coreprotect.fabric.service.WorldConfigService;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.ContainerTransactionHelper;
import net.coreprotect.fabric.util.LoggedItemChange;
import net.coreprotect.fabric.util.LoggedItemData;
import net.coreprotect.fabric.util.LoggedSignState;
import net.coreprotect.fabric.util.TransientLookupCache;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.SignBlockEntity;
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
import java.util.Locale;
import java.util.UUID;

public final class FabricEventLogger {
    private final Object cutoverMonitor = new Object();
    private final Logger logger;
    private CoreProtectDatabase database;
    private WorldConfigService configs;
    private BlacklistService blacklist;
    private boolean cutoverBuffering;
    private final List<EventRecord> cutoverBuffer = new ArrayList<>();

    public FabricEventLogger(CoreProtectDatabase database, WorldConfigService configs, BlacklistService blacklist, Logger logger) {
        this.database = database;
        this.configs = configs;
        this.blacklist = blacklist;
        this.logger = logger;
    }

    public void beginCutoverBuffering() {
        synchronized (cutoverMonitor) {
            cutoverBuffering = true;
        }
    }

    public int completeCutover(CoreProtectDatabase newDatabase, WorldConfigService newConfigs, BlacklistService newBlacklist) {
        synchronized (cutoverMonitor) {
            database = newDatabase;
            configs = newConfigs;
            blacklist = newBlacklist;
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
        CoreProtectFabricConfig config = configFor((ServerWorld) player.getEntityWorld());
        logUsernameChangeIfNeeded(player);
        if (!config.logSessions()) {
            return;
        }

        write(playerRecord(CoreProtectEventType.PLAYER_JOIN, player, (ServerWorld) player.getEntityWorld(), player.getBlockPos(), player.getName().getString(), null));
    }

    public void logPlayerQuit(ServerPlayerEntity player) {
        if (!configFor((ServerWorld) player.getEntityWorld()).logSessions()) {
            return;
        }

        write(playerRecord(CoreProtectEventType.PLAYER_QUIT, player, (ServerWorld) player.getEntityWorld(), player.getBlockPos(), player.getName().getString(), null));
    }

    public void logCommand(ServerCommandSource source, String command) {
        if (!(source.getEntity() instanceof ServerPlayerEntity)) {
            return;
        }

        ServerPlayerEntity player = (ServerPlayerEntity) source.getEntity();
        if (!configFor((ServerWorld) player.getEntityWorld()).logCommands()) {
            return;
        }
        write(playerRecord(CoreProtectEventType.PLAYER_COMMAND, player, (ServerWorld) player.getEntityWorld(), player.getBlockPos(), command, null));
    }

    public void logCommand(String actorName, ServerWorld world, BlockPos pos, String command) {
        if (!configFor(world).logCommands()) {
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
        if (!configFor(world).logChat()) {
            return;
        }

        write(playerRecord(CoreProtectEventType.PLAYER_CHAT, player, world, pos, message, null));
    }

    public void logChat(String actorName, ServerWorld world, BlockPos pos, String message) {
        if (!configFor(world).logChat()) {
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
            LoggedItemData.fromStack(stack, world.getRegistryManager()),
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
            LoggedItemData.fromStack(stack, world.getRegistryManager()),
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
            LoggedItemData.fromStack(stack, world.getRegistryManager()),
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
            LoggedItemData.fromStack(stack, world.getRegistryManager()),
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

    public void logItemShoot(String actorName, String worldKey, BlockPos pos, LoggedItemData item, int count, String contextLabel) {
        logItemChange(CoreProtectEventType.ITEM_SHOOT, null, actorName, worldKey, pos, item, count, contextLabel);
    }

    public void logItemBuy(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return;
        }

        logItemBuy(
            player,
            world.getRegistryKey().getValue().toString(),
            pos,
            LoggedItemData.fromStack(stack, world.getRegistryManager()),
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
            LoggedItemData.fromStack(stack, world.getRegistryManager()),
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
            LoggedItemData.fromStack(stack, world.getRegistryManager()),
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
            LoggedItemData.fromStack(stack, world.getRegistryManager()),
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
        if (!configFor(world).logEntityChanges()) {
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
        if (!configFor(world).logEntityChanges()) {
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
        if (!configFor(world).logEntityChanges()) {
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
        if (!configFor(world).logEntityChanges()) {
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
        if (!configFor(world).playerInteractions()) {
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
        if (!configFor(world).logEntityKills()) {
            return;
        }

        Entity actor = resolveActor(killer, damageSource);
        Entity directSourceEntity = resolveDirectSource(killer, damageSource);
        String directSource = directSourceEntity == null || directSourceEntity == actor ? null : describeEntityType(directSourceEntity);
        String target = describeEntityType(killedEntity);
        String actorUuid = actor instanceof ServerPlayerEntity ? actor.getUuidAsString() : null;
        String actorName = describeKillActor(actor, damageSource);
        if (actorName == null || actorName.isBlank()) {
            return;
        }
        if (directSource != null && !directSource.isBlank()) {
            target = target + " via " + directSource;
        }

        write(new EventRecord(
            System.currentTimeMillis(),
            CoreProtectEventType.ENTITY_KILL,
            actorUuid,
            actorName,
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
        logSignChange(actorUuid, actorName, world, pos, resolveSignState(world, pos, front, newLines));
    }

    public void logSignChange(UUID actorUuid, String actorName, ServerWorld world, BlockPos pos, LoggedSignState signState) {
        if (signState == null) {
            return;
        }

        String preview = summarizeSign(signState.front(), signState.lines());
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
            signState.serialize()
        ));
    }

    public void logContainerTransaction(ServerPlayerEntity player, String worldKey, BlockPos pos, String containerType, int slotIndex, int button, SlotActionType actionType, ItemStack beforeSlot, ItemStack afterSlot, ItemStack beforeCursor, ItemStack afterCursor) {
        logContainerTransaction(player.getUuidAsString(), player.getName().getString(), worldKey, pos, containerType, slotIndex, button, actionType, beforeSlot, afterSlot, beforeCursor, afterCursor);
    }

    public void logContainerTransaction(String actorName, String worldKey, BlockPos pos, String containerType, int slotIndex, int button, SlotActionType actionType, ItemStack beforeSlot, ItemStack afterSlot, ItemStack beforeCursor, ItemStack afterCursor) {
        logContainerTransaction(null, actorName, worldKey, pos, containerType, slotIndex, button, actionType, beforeSlot, afterSlot, beforeCursor, afterCursor);
    }

    public void logContainerTransaction(String actorUuid, String actorName, String worldKey, BlockPos pos, String containerType, int slotIndex, int button, SlotActionType actionType, ItemStack beforeSlot, ItemStack afterSlot, ItemStack beforeCursor, ItemStack afterCursor) {
        if (!configFor(worldKey).itemTransactions()) {
            return;
        }

        for (ContainerTransactionHelper.ContainerDelta delta : ContainerTransactionHelper.diff(beforeSlot, afterSlot)) {
            logContainerChange(actorUuid, actorName, worldKey, pos, containerType, delta.item(), delta.count(), delta.added());
        }
    }

    public void logContainerChange(ServerPlayerEntity player, String worldKey, BlockPos pos, String containerType, LoggedItemData item, int count, boolean added) {
        logContainerChange(player.getUuidAsString(), player.getName().getString(), worldKey, pos, containerType, item, count, added);
    }

    public void logContainerChange(String actorName, String worldKey, BlockPos pos, String containerType, LoggedItemData item, int count, boolean added) {
        logContainerChange(null, actorName, worldKey, pos, containerType, item, count, added);
    }

    public void logContainerChange(String actorUuid, String actorName, String worldKey, BlockPos pos, String containerType, LoggedItemData item, int count, boolean added) {
        if (!configFor(worldKey).itemTransactions() || actorName == null || actorName.isBlank() || pos == null || item == null || item.itemKey() == null || item.itemKey().isBlank() || count <= 0) {
            return;
        }

        LoggedItemChange change = new LoggedItemChange(item, count, "");
        String target = change.summaryTarget();
        String payload = serializeContainerChange(containerType, change, added);
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
        if (!configFor(world).logBlockBreaks()) {
            return;
        }

        write(blockRecord(CoreProtectEventType.BLOCK_BREAK, player.getUuid(), player.getName().getString(), world, pos, state));
    }

    public void logBlockPlace(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState state) {
        if (!configFor(world).logBlockPlaces()) {
            return;
        }

        write(blockRecord(CoreProtectEventType.BLOCK_PLACE, player.getUuid(), player.getName().getString(), world, pos, state));
    }

    public void logBlockBreak(UUID actorUuid, String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        if (!configFor(world).logBlockBreaks()) {
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
        if (!configFor(world).logBlockPlaces()) {
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
        if (!configFor(world).playerInteractions()) {
            return;
        }

        write(playerRecord(CoreProtectEventType.BLOCK_USE, player, world, pos, BlockStateSerializer.describeBlock(state), BlockStateSerializer.serialize(state)));
    }

    public void logBlockUse(String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        if (!configFor(world).playerInteractions()) {
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
        CoreProtectFabricConfig config = configFor(worldKey);
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
        if (blacklist != null && blacklist.shouldSkip(record)) {
            return;
        }
        synchronized (cutoverMonitor) {
            if (cutoverBuffering) {
                cutoverBuffer.add(record);
                return;
            }
            database.write(record);
        }
    }

    private void logUsernameChangeIfNeeded(ServerPlayerEntity player) {
        if (!configFor((ServerWorld) player.getEntityWorld()).usernameChanges()) {
            return;
        }

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

    private LoggedSignState resolveSignState(ServerWorld world, BlockPos pos, boolean front, String[] lines) {
        if (world.getBlockEntity(pos) instanceof SignBlockEntity signBlockEntity) {
            return LoggedSignState.fromBlockEntity(signBlockEntity, front);
        }
        return LoggedSignState.of(front, null, false, false, lines);
    }

    private String serializeContainerChange(String containerType, LoggedItemChange change, boolean added) {
        StringBuilder payload = new StringBuilder();
        appendContainerEntry(payload, "container", simplifyIdentifier(containerType));
        String itemPayload = change.serializePayload();
        if (!itemPayload.isBlank()) {
            if (!payload.isEmpty()) {
                payload.append('\n');
            }
            payload.append(itemPayload);
        }
        appendContainerEntry(payload, "added", Boolean.toString(added));
        return payload.toString();
    }

    private void appendContainerEntry(StringBuilder payload, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (!payload.isEmpty()) {
            payload.append('\n');
        }
        payload.append(key).append('=').append(value);
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
            entity.saveSelfData(writeView);
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
        String serializedState = serializeEntityState(killedEntity);
        String killerType = killer == null ? "unknown" : describeEntityType(killer);
        String actorName = describeKillActor(actor, damageSource);
        payload.append("killer=").append(killerType)
            .append('\n').append("actor=").append(actorName)
            .append('\n').append("target=").append(describeEntityType(killedEntity))
            .append('\n').append("target_uuid=").append(killedEntity.getUuidAsString())
            .append('\n').append("damage=").append(damageSource == null ? "unknown" : damageSource.getName());
        if (!serializedState.isBlank()) {
            payload.append('\n').append(serializedState);
        }
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

    private String describeKillActor(Entity actor, DamageSource damageSource) {
        if (actor instanceof ServerPlayerEntity) {
            return actor.getName().getString();
        }
        if (actor != null) {
            return "#" + describeEntityType(actor);
        }
        return describeEnvironmentDamageSource(damageSource);
    }

    private String describeEnvironmentDamageSource(DamageSource damageSource) {
        if (damageSource == null || damageSource.getName() == null || damageSource.getName().isBlank()) {
            return null;
        }
        String name = damageSource.getName().toLowerCase(Locale.ROOT);
        if (name.contains("lava")) {
            return "#lava";
        }
        if (name.contains("fire")) {
            return "#fire";
        }
        if (name.contains("explosion")) {
            return "#explosion";
        }
        if (name.contains("magic")) {
            return "#magic";
        }
        if (name.contains("wither")) {
            return "#wither_effect";
        }
        return "#" + simplifyIdentifier(name);
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

    private CoreProtectFabricConfig configFor(ServerWorld world) {
        return configFor(world == null ? null : world.getRegistryKey().getValue().toString());
    }

    private CoreProtectFabricConfig configFor(String worldKey) {
        return configs == null ? CoreProtectFabricConfig.loadDefaults() : configs.resolve(worldKey);
    }
}

