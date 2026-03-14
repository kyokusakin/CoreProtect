package net.coreprotect;

import net.coreprotect.fabric.api.CoreProtectFabric;
import net.coreprotect.fabric.api.CoreProtectFabricAPI;
import net.coreprotect.fabric.db.StoredEventRecord;
import net.coreprotect.fabric.log.CoreProtectEventType;
import net.coreprotect.fabric.service.RollbackExecutionResult;
import net.coreprotect.fabric.util.QueryBounds;
import net.minecraft.block.BlockState;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.List;

public class CoreProtectAPI {
    public static class ParseResult extends net.coreprotect.api.result.ParseResult {
        public ParseResult(String[] data) {
            super(data);
        }

        public ParseResult(StoredEventRecord record) {
            super(record);
        }
    }

    protected CoreProtectFabricAPI delegate() {
        return CoreProtectFabric.getAPI();
    }

    public int APIVersion() {
        return delegate().APIVersion();
    }

    public int apiVersion() {
        return delegate().apiVersion();
    }

    public void testAPI() {
        delegate().testAPI();
    }

    public boolean isEnabled() {
        return delegate().isEnabled();
    }

    public ParseResult parseResult(String[] results) {
        return new ParseResult(results);
    }

    public ParseResult parseResult(StoredEventRecord result) {
        return new ParseResult(result);
    }

    public List<String[]> blockLookup(ServerWorld world, BlockPos pos, int time) {
        return delegate().blockLookup(world, pos, time);
    }

    public List<String[]> queueLookup(ServerWorld world, BlockPos pos) {
        return delegate().queueLookup(world, pos);
    }

    public List<String[]> sessionLookup(String user, int time) {
        return delegate().sessionLookup(user, time);
    }

    public List<String[]> sessionLookup(String user, int time, int limit) {
        return delegate().sessionLookup(user, time, limit);
    }

    public boolean hasPlaced(String user, ServerWorld world, BlockPos pos, int time, int offset) {
        return delegate().hasPlaced(user, world, pos, time, offset);
    }

    public boolean hasRemoved(String user, ServerWorld world, BlockPos pos, int time, int offset) {
        return delegate().hasRemoved(user, world, pos, time, offset);
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
        return delegate().performLookup(
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
        return delegate().performLookup(time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusWorld, radiusLocation);
    }

    @Deprecated
    public List<String[]> performLookup(String user, int time, int radius, ServerWorld radiusWorld, BlockPos radiusLocation, List<Object> restrict, List<Object> exclude) {
        return delegate().performLookup(user, time, radius, radiusWorld, radiusLocation, restrict, exclude);
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
        return delegate().performPartialLookup(
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
        return delegate().performPartialLookup(time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusWorld, radiusLocation, limitOffset, limitCount);
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
        return delegate().performPartialLookup(user, time, radius, radiusWorld, radiusLocation, restrict, exclude, limitOffset, limitCount);
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
        return delegate().performRollback(player, notBefore, notAfter, worldKey, bounds, actorNames, excludeActorNames, actionFilter, includeTargets, excludeTargets);
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
        return delegate().performRollback(player, time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusWorld, radiusLocation);
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
        return delegate().performRollback(player, user, time, radius, radiusWorld, radiusLocation, restrict, exclude);
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
        return delegate().performRestore(player, notBefore, notAfter, worldKey, bounds, actorNames, excludeActorNames, actionFilter, includeTargets, excludeTargets);
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
        return delegate().performRestore(player, time, restrictUsers, excludeUsers, restrictBlocks, excludeBlocks, actionList, radius, radiusWorld, radiusLocation);
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
        return delegate().performRestore(player, user, time, radius, radiusWorld, radiusLocation, restrict, exclude);
    }

    public void performPurge(int time) {
        delegate().performPurge(time);
    }

    public boolean logChat(String actorName, ServerWorld world, BlockPos pos, String message) {
        return delegate().logChat(actorName, world, pos, message);
    }

    public boolean logCommand(String actorName, ServerWorld world, BlockPos pos, String command) {
        return delegate().logCommand(actorName, world, pos, command);
    }

    public boolean logInteraction(String actorName, ServerWorld world, BlockPos pos) {
        return delegate().logInteraction(actorName, world, pos);
    }

    public boolean logInteraction(String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        return delegate().logInteraction(actorName, world, pos, state);
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
        return delegate().logContainerTransaction(actorName, worldKey, pos, containerType, slotIndex, button, actionType, beforeSlot, afterSlot, beforeCursor, afterCursor);
    }

    public boolean logPlacement(String actorName, ServerWorld world, BlockPos pos) {
        return delegate().logPlacement(actorName, world, pos);
    }

    public boolean logPlacement(String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        return delegate().logPlacement(actorName, world, pos, state);
    }

    public boolean logRemoval(String actorName, ServerWorld world, BlockPos pos) {
        return delegate().logRemoval(actorName, world, pos);
    }

    public boolean logRemoval(String actorName, ServerWorld world, BlockPos pos, BlockState state) {
        return delegate().logRemoval(actorName, world, pos, state);
    }

    public boolean logSignChange(String actorName, ServerWorld world, BlockPos pos, boolean front, String[] lines) {
        return delegate().logSignChange(actorName, world, pos, front, lines);
    }

    public boolean logEntityPlacement(String actorName, ServerWorld world, BlockPos pos, net.minecraft.entity.Entity entity) {
        return delegate().logEntityPlacement(actorName, world, pos, entity);
    }

    public boolean logEntityRemoval(String actorName, ServerWorld world, BlockPos pos, net.minecraft.entity.Entity entity) {
        return delegate().logEntityRemoval(actorName, world, pos, entity);
    }
}
