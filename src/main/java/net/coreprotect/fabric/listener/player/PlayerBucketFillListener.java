package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class PlayerBucketFillListener {
    private PlayerBucketFillListener() {
    }

    public static void logBucketFill(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState originalState) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || originalState == null || !runtime.config(world).logBuckets()) {
            return;
        }

        runtime.logger().logBlockBreak(player, world, pos, originalState);
    }
}
