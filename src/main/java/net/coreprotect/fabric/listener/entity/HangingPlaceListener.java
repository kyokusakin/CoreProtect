package net.coreprotect.fabric.listener.entity;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.entity.Entity;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class HangingPlaceListener {
    private HangingPlaceListener() {
    }

    public static void logHangingPlace(ServerPlayerEntity player, ServerWorld world, BlockPos pos, Entity entity) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null) {
            return;
        }

        runtime.logger().logEntityPlace(player, world, pos, entity);
    }
}
