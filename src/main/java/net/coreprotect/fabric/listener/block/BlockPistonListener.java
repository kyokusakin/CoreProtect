package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Map;

public final class BlockPistonListener {
    private BlockPistonListener() {
    }

    public static void logPistonMove(ServerWorld world, Direction motionDirection, List<BlockPos> movedBlocks, Map<BlockPos, BlockState> sourceStates, List<BlockPos> brokenBlocks, Map<BlockPos, BlockState> brokenStates) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || !runtime.config().pistons()) {
            return;
        }

        for (BlockPos brokenPos : brokenBlocks) {
            BlockState brokenState = brokenStates.get(brokenPos);
            if (brokenState != null && !brokenState.isAir()) {
                runtime.logger().logBlockBreak(null, "#piston", world, brokenPos, brokenState);
            }
        }

        for (BlockPos sourcePos : movedBlocks) {
            BlockState sourceState = sourceStates.get(sourcePos);
            if (sourceState == null || sourceState.isAir()) {
                continue;
            }

            runtime.logger().logBlockBreak(null, "#piston", world, sourcePos, sourceState);
            runtime.logger().logBlockPlace(null, "#piston", world, sourcePos.offset(motionDirection), sourceState);
        }
    }
}
