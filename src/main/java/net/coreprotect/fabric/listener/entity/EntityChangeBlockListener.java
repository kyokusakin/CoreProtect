package net.coreprotect.fabric.listener.entity;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class EntityChangeBlockListener {
    private EntityChangeBlockListener() {
    }

    public static void logEntityBlockChange(String actor, ServerWorld world, BlockPos pos, BlockState previousState, BlockState currentState) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || actor == null || actor.isBlank() || previousState == null || currentState == null || previousState.equals(currentState) || !runtime.config(world).logEntityChanges()) {
            return;
        }

        if (!previousState.isAir()) {
            runtime.logger().logBlockBreak(null, actor, world, pos, previousState);
        }
        if (!currentState.isAir()) {
            runtime.logger().logBlockPlace(null, actor, world, pos, currentState);
        }
    }
}
