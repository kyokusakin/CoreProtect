package net.coreprotect.fabric.listener.world;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class LeavesDecayListener {
    private LeavesDecayListener() {
    }

    public static void logLeavesDecay(ServerWorld world, BlockPos pos, BlockState previousState) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || previousState == null || previousState.isAir() || !runtime.config(world).leafDecay()) {
            return;
        }

        runtime.logger().logBlockBreak(null, "#decay", world, pos, previousState);
    }
}
