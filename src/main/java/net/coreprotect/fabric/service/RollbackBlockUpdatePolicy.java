package net.coreprotect.fabric.service;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.BlockView;

final class RollbackBlockUpdatePolicy {
    private RollbackBlockUpdatePolicy() {
    }

    static int updateFlags(boolean targetIsAir) {
        return updateFlags(targetIsAir, true);
    }

    static int updateFlags(boolean targetIsAir, boolean applyPhysics) {
        return targetIsAir || !applyPhysics
            ? Block.NOTIFY_LISTENERS | Block.FORCE_STATE
            : Block.NOTIFY_ALL;
    }

    static RollbackBlockApplyPlan.Phase phase(boolean targetIsAir, boolean requiresPhysics) {
        if (targetIsAir) {
            return RollbackBlockApplyPlan.Phase.AIR;
        }
        return requiresPhysics
            ? RollbackBlockApplyPlan.Phase.PHYSICS
            : RollbackBlockApplyPlan.Phase.STABLE;
    }

    static boolean requiresPhysics(BlockState targetState, BlockView world, BlockPos pos) {
        boolean stateful = targetState.isOf(Blocks.TNT)
            || targetState.isOf(Blocks.NETHER_PORTAL)
            || targetState.isOf(Blocks.MOVING_PISTON)
            || targetState.isOf(Blocks.PISTON)
            || targetState.isOf(Blocks.STICKY_PISTON)
            || targetState.emitsRedstonePower()
            || targetState.hasComparatorOutput()
            || targetState.contains(Properties.POWERED)
            || targetState.contains(Properties.LIT)
            || targetState.contains(Properties.WATERLOGGED);
        return requiresPhysics(
            targetState.isAir(),
            targetState.hasBlockEntity(),
            targetState.isSolidBlock(world, pos),
            stateful
        );
    }

    static boolean requiresPhysics(boolean targetIsAir, boolean hasBlockEntity, boolean solid, boolean stateful) {
        return !targetIsAir && (hasBlockEntity || !solid || stateful);
    }
}
