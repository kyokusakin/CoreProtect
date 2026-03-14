package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.TransientLookupCache;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidFillable;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.Registries;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class BlockFromToListener {
    private BlockFromToListener() {
    }

    public static void logFluidFlow(ServerWorld world, BlockPos sourcePos, BlockPos targetPos, BlockState previousTargetState, BlockState currentTargetState, Fluid fluid) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || previousTargetState == null || currentTargetState == null || fluid == null) {
            return;
        }

        boolean isWater = fluid.matchesType(Fluids.WATER);
        boolean isLava = fluid.matchesType(Fluids.LAVA);
        if ((!isWater && !isLava)
            || (isWater && !runtime.config(world).waterFlow())
            || (isLava && !runtime.config(world).lavaFlow())) {
            return;
        }

        if (previousTargetState.equals(currentTargetState) || previousTargetState.getFluidState().getFluid().matchesType(fluid)) {
            return;
        }

        String worldKey = world.getRegistryKey().getValue().toString();
        String fluidKey = Registries.FLUID.getId(fluid).toString();
        boolean emptyLikeTarget = previousTargetState.isAir() || previousTargetState.getBlock() instanceof FluidFillable;
        if (emptyLikeTarget && TransientLookupCache.markSpreadAndCheckDuplicate(worldKey, targetPos, fluidKey)) {
            return;
        }

        String actor = isWater ? "#water" : "#lava";
        if (runtime.config(world).liquidTracking()) {
            String trackedActor = TransientLookupCache.findPlacedActor(worldKey, sourcePos);
            if (trackedActor != null && !trackedActor.isBlank()) {
                actor = trackedActor;
            }
        }

        if (!previousTargetState.isAir() && previousTargetState.getFluidState().isEmpty() && !(previousTargetState.getBlock() instanceof FluidFillable)) {
            runtime.logger().logBlockBreak(null, actor, world, targetPos, previousTargetState);
        }

        runtime.logger().logBlockPlace(null, actor, world, targetPos, currentTargetState);
        TransientLookupCache.rememberPlacedActor(worldKey, targetPos, actor, BlockStateSerializer.describeBlock(currentTargetState));
    }

    public static void logDragonEggMove(ServerWorld world, BlockPos sourcePos, BlockPos targetPos, BlockState originalState) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || originalState == null) {
            return;
        }

        String worldKey = world.getRegistryKey().getValue().toString();
        String actor = TransientLookupCache.consumeDragonEggInteraction(worldKey, sourcePos);
        if (actor == null || actor.isBlank()) {
            actor = "#entity";
        }

        if (runtime.config(world).logBlockBreaks()) {
            runtime.logger().logBlockBreak(null, actor, world, sourcePos, originalState);
        }
        if (runtime.config(world).logBlockPlaces()) {
            BlockState placedState = world.getBlockState(targetPos);
            runtime.logger().logBlockPlace(null, actor, world, targetPos, placedState);
            TransientLookupCache.rememberPlacedActor(worldKey, targetPos, actor, BlockStateSerializer.describeBlock(placedState));
        }
    }
}
