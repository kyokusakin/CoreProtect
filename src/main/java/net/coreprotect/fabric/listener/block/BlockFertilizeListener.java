package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.BonemealFertilizeContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.MushroomPlantBlock;
import net.minecraft.block.SaplingBlock;

public final class BlockFertilizeListener {
    private BlockFertilizeListener() {
    }

    public static void logCompletedFertilize(BonemealFertilizeContext.CompletedFertilize fertilize) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || fertilize == null || !runtime.config().logBlockPlaces()) {
            return;
        }

        BlockState originState = fertilize.originState();
        int changeCount = fertilize.changes().size();
        if (originState.getBlock() instanceof SaplingBlock) {
            if (!runtime.config().treeGrowth() || (changeCount == 1 && fertilize.changes().iterator().next().pos().equals(fertilize.originPos()))) {
                return;
            }
        }
        if (originState.getBlock() instanceof MushroomPlantBlock) {
            if (!runtime.config().mushroomGrowth() || (changeCount == 1 && fertilize.changes().iterator().next().pos().equals(fertilize.originPos()))) {
                return;
            }
        }

        for (BonemealFertilizeContext.FertilizeChange change : fertilize.changes()) {
            if (!change.previousState().isAir()) {
                runtime.logger().logBlockBreak(null, fertilize.actor(), fertilize.world(), change.pos(), change.previousState());
            }
            if (!change.currentState().isAir()) {
                runtime.logger().logBlockPlace(null, fertilize.actor(), fertilize.world(), change.pos(), change.currentState());
            }
        }
    }
}
