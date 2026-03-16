package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.TransientLookupCache;
import net.minecraft.block.BlockState;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

public final class BlockFormListener {
    private static final String WATER_KEY = "minecraft:water";
    private static final String LAVA_KEY = "minecraft:lava";

    private BlockFormListener() {
    }

    public static void logLiquidForm(ServerWorld world, BlockPos pos, BlockState previousState, BlockState newState) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || previousState == null || newState == null || previousState.equals(newState) || !runtime.config(world).liquidTracking()) {
            return;
        }

        if (!isTrackedLiquidForm(previousState, newState)) {
            return;
        }

        String worldKey = world.getRegistryKey().getValue().toString();
        String actor = resolveActor(worldKey, pos);
        if (actor == null || actor.isBlank()) {
            return;
        }

        runtime.logger().logBlockBreak(null, actor, world, pos, previousState);
        runtime.logger().logBlockPlace(null, actor, world, pos, newState);
    }

    private static String resolveActor(String worldKey, BlockPos pos) {
        String actor = TransientLookupCache.findPlacedActor(worldKey, pos);
        if (actor != null && !actor.isBlank()) {
            return actor;
        }

        for (Direction direction : new Direction[] { Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST }) {
            actor = TransientLookupCache.findPlacedActor(worldKey, pos.offset(direction), WATER_KEY, LAVA_KEY);
            if (actor != null && !actor.isBlank()) {
                return actor;
            }
        }

        return null;
    }

    private static boolean isTrackedLiquidForm(BlockState previousState, BlockState newState) {
        String previousKey = Registries.BLOCK.getId(previousState.getBlock()).toString();
        String newKey = Registries.BLOCK.getId(newState.getBlock()).toString();
        if ("minecraft:obsidian".equals(newKey) || "minecraft:cobblestone".equals(newKey)) {
            return true;
        }
        return previousKey.endsWith("_concrete_powder") && !previousKey.equals(newKey);
    }
}
