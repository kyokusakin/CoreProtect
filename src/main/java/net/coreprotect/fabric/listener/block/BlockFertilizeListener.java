package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.BonemealFertilizeContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.MushroomPlantBlock;
import net.minecraft.block.SaplingBlock;
import net.minecraft.block.Blocks;
import net.minecraft.registry.tag.BlockTags;

public final class BlockFertilizeListener {
    private BlockFertilizeListener() {
    }

    public static void logCompletedFertilize(BonemealFertilizeContext.CompletedFertilize fertilize) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || fertilize == null || !runtime.config(fertilize.world()).logBlockPlaces()) {
            return;
        }

        BlockState originState = fertilize.originState();
        int changeCount = fertilize.changes().size();
        boolean treeGrowth = isTreeGrowth(originState, fertilize);
        boolean mushroomGrowth = isMushroomGrowth(originState, fertilize);
        if (treeGrowth && (!runtime.config(fertilize.world()).treeGrowth() || isOriginOnlyChange(fertilize, changeCount))) {
            return;
        }
        if (mushroomGrowth && (!runtime.config(fertilize.world()).mushroomGrowth() || isOriginOnlyChange(fertilize, changeCount))) {
            return;
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

    private static boolean isOriginOnlyChange(BonemealFertilizeContext.CompletedFertilize fertilize, int changeCount) {
        return changeCount == 1 && fertilize.changes().iterator().next().pos().equals(fertilize.originPos());
    }

    private static boolean isTreeGrowth(BlockState originState, BonemealFertilizeContext.CompletedFertilize fertilize) {
        if (originState.getBlock() instanceof SaplingBlock) {
            return true;
        }

        for (BonemealFertilizeContext.FertilizeChange change : fertilize.changes()) {
            if (change.currentState().isIn(BlockTags.LOGS) || change.currentState().isIn(BlockTags.LEAVES)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMushroomGrowth(BlockState originState, BonemealFertilizeContext.CompletedFertilize fertilize) {
        if (originState.getBlock() instanceof MushroomPlantBlock) {
            return true;
        }

        for (BonemealFertilizeContext.FertilizeChange change : fertilize.changes()) {
            if (change.currentState().isOf(Blocks.BROWN_MUSHROOM_BLOCK)
                || change.currentState().isOf(Blocks.RED_MUSHROOM_BLOCK)
                || change.currentState().isOf(Blocks.MUSHROOM_STEM)) {
                return true;
            }
        }
        return false;
    }
}
