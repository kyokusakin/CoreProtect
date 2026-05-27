package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.TransientLookupCache;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.Fluid;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class PlayerBucketEmptyListener {
    private PlayerBucketEmptyListener() {
    }

    public static void logBucketEmpty(ServerPlayerEntity player, ServerWorld world, BlockPos pos, Fluid fluid) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || !runtime.config(world).logBuckets()) {
            return;
        }

        BlockState placedState = world.getBlockState(pos);
        if (fluid != null && placedState.getFluidState().isEmpty() && placedState.isAir()) {
            // Fluid never settled (e.g. water evaporates instantly in ultrawarm dimensions like the Nether).
            // Logging a phantom water/lava placement here would make rollbacks restore a block that never existed.
            return;
        }

        CoreProtectFabricMod.logBlockPlace(player, world, pos, placedState);
        if (fluid != null) {
            TransientLookupCache.rememberPlacedActor(
                world.getRegistryKey().getValue().toString(),
                pos,
                player.getName().getString(),
                BlockStateSerializer.describeBlock(placedState)
            );
        }
    }
}
