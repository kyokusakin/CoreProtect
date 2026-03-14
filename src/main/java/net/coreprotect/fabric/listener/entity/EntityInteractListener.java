package net.coreprotect.fabric.listener.entity;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class EntityInteractListener {
    private EntityInteractListener() {
    }

    public static void logTurtleEggInteract(ServerWorld world, BlockPos pos, Entity entity, BlockState previousState, BlockState currentState) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || entity == null || previousState == null || currentState == null || previousState.equals(currentState) || !runtime.config(world).logEntityChanges()) {
            return;
        }

        String actor = "#" + Registries.ENTITY_TYPE.getId(entity.getType()).getPath().toLowerCase();
        runtime.logger().logBlockBreak(null, actor, world, pos, previousState);
        if (!currentState.isAir()) {
            runtime.logger().logBlockPlace(null, actor, world, pos, currentState);
        }
    }
}
