package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.block.CampfireBlock;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;

public final class BlockIgniteListener {
    private BlockIgniteListener() {
    }

    public static void logFireIgnite(String actor, ServerWorld world, BlockPos pos, BlockState previousState, BlockState currentState) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || actor == null || actor.isBlank() || previousState == null || currentState == null || previousState.equals(currentState) || !runtime.config(world).blockIgnite()) {
            return;
        }

        if (isFireBlock(currentState) && !isFireBlock(previousState)) {
            runtime.logger().logBlockPlace(null, actor, world, pos, currentState);
            return;
        }

        if (isCampfireIgnition(previousState, currentState)) {
            runtime.logger().logBlockPlace(null, actor, world, pos, currentState);
        }
    }

    private static boolean isFireBlock(BlockState state) {
        return state.isOf(Blocks.FIRE) || state.isOf(Blocks.SOUL_FIRE);
    }

    private static boolean isCampfireIgnition(BlockState previousState, BlockState currentState) {
        if (!(previousState.getBlock() instanceof CampfireBlock) || !(currentState.getBlock() instanceof CampfireBlock)) {
            return false;
        }
        if (!previousState.getProperties().contains(Properties.LIT) || !currentState.getProperties().contains(Properties.LIT)) {
            return false;
        }
        return !previousState.get(Properties.LIT) && currentState.get(Properties.LIT);
    }
}
