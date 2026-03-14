package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class BlockBurnListener {
    private BlockBurnListener() {
    }

    public static void logBlockBurn(String actor, ServerWorld world, BlockPos pos, BlockState previousState) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || actor == null || actor.isBlank() || previousState == null || previousState.isAir() || !runtime.config().blockBurn()) {
            return;
        }

        if (previousState.isOf(Blocks.FIRE) || previousState.isOf(Blocks.SOUL_FIRE)) {
            return;
        }

        runtime.logger().logBlockBreak(null, actor, world, pos, previousState);
    }
}
