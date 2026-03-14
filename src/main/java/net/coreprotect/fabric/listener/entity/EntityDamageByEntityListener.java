package net.coreprotect.fabric.listener.entity;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class EntityDamageByEntityListener {
    private EntityDamageByEntityListener() {
    }

    public static void logEntityBreak(ServerWorld world, BlockPos pos, Entity entity, Entity breaker) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null) {
            return;
        }

        runtime.logger().logEntityBreak(world, pos, entity, breaker);
    }
}
