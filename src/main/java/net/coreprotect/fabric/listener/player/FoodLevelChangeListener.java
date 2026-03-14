package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class FoodLevelChangeListener {
    private FoodLevelChangeListener() {
    }

    public static void logCakeEat(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState previousState, BlockState currentState) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || player == null || previousState == null || currentState == null || previousState.equals(currentState) || !runtime.config().logBlockBreaks()) {
            return;
        }

        runtime.logger().logBlockBreak(player, world, pos, previousState);
        if (!currentState.isAir()) {
            runtime.logger().logBlockPlace(player, world, pos, currentState);
        }
    }
}
