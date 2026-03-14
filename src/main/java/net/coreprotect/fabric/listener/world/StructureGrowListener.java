package net.coreprotect.fabric.listener.world;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.StructureGrowContext;
import net.coreprotect.fabric.util.TransientLookupCache;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class StructureGrowListener {
    private StructureGrowListener() {
    }

    public static String resolveTreeActor(ServerWorld world, BlockPos pos) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || !runtime.config(world).treeGrowth()) {
            return null;
        }

        String worldKey = world.getRegistryKey().getValue().toString();
        for (BlockPos candidate : new BlockPos[] {
            pos,
            pos.west(),
            pos.north(),
            pos.north().west(),
            pos.east(),
            pos.south()
        }) {
            String actor = TransientLookupCache.findPlacedActor(worldKey, candidate);
            if (actor != null && !actor.isBlank()) {
                return actor;
            }
            actor = runtime.database().lookupLatestBlockPlaceActor(worldKey, candidate);
            if (actor != null && !actor.isBlank()) {
                return actor;
            }
        }
        return "#tree";
    }

    public static String resolveMushroomActor(ServerWorld world, BlockPos pos) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || !runtime.config(world).mushroomGrowth()) {
            return null;
        }
        return "#mushroom";
    }

    public static void logCompletedGrowth(StructureGrowContext.CompletedGrowth growth) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || growth == null) {
            return;
        }

        for (StructureGrowContext.GrowthChange change : growth.changes()) {
            BlockState previousState = change.previousState();
            BlockState currentState = change.currentState();
            BlockPos pos = change.pos();
            if (!previousState.isAir()) {
                runtime.logger().logBlockBreak(null, growth.actor(), growth.world(), pos, previousState);
            }
            if (!currentState.isAir()) {
                runtime.logger().logBlockPlace(null, growth.actor(), growth.world(), pos, currentState);
            }
        }
    }
}
