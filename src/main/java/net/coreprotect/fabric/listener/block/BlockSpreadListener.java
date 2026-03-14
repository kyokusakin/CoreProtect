package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class BlockSpreadListener {
    private BlockSpreadListener() {
    }

    public static void logNaturalSpread(String actor, ServerWorld world, BlockPos pos, BlockState previousState, BlockState currentState) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || actor == null || actor.isBlank() || previousState == null || currentState == null || previousState.equals(currentState)) {
            return;
        }

        if ("#sculk_catalyst".equals(actor)) {
            if (!runtime.config(world).sculkSpread()) {
                return;
            }
        }
        else if ("#vine".equals(actor) || "#bamboo".equals(actor) || "#chorus".equals(actor) || "#amethyst".equals(actor)) {
            if (!runtime.config(world).vineGrowth()) {
                return;
            }
        }
        else {
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
