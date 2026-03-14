package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class BlockFadeListener {
    private BlockFadeListener() {
    }

    public static void logTurtleEggFade(ServerWorld world, BlockPos pos, BlockState previousState) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || previousState == null || previousState.isAir() || !runtime.config(world).logEntityChanges()) {
            return;
        }

        runtime.logger().logBlockBreak(null, "#turtle", world, pos, previousState);
    }
}
