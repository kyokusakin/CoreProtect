package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

public final class HopperPullListener {
    private HopperPullListener() {
    }

    public static void logHopperPull(
        ServerWorld world,
        BlockPos hopperPos,
        Map<LoggedItemData, Integer> beforeHopper,
        Map<LoggedItemData, Integer> afterHopper,
        BlockPos sourcePos,
        String sourceType,
        Map<LoggedItemData, Integer> beforeSource,
        Map<LoggedItemData, Integer> afterSource
    ) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || !runtime.config(world).hopperTransactions()) {
            return;
        }

        String worldKey = world.getRegistryKey().getValue().toString();
        String hopperType = BlockStateSerializer.describeBlock(world.getBlockState(hopperPos));
        HopperTransactionLogger.logInventoryChange(runtime, "#hopper", worldKey, hopperPos, hopperType, beforeHopper, afterHopper);

        if (sourcePos != null && sourceType != null && beforeSource != null && afterSource != null) {
            HopperTransactionLogger.logInventoryChange(runtime, "#hopper", worldKey, sourcePos, sourceType, beforeSource, afterSource);
        }
    }
}
