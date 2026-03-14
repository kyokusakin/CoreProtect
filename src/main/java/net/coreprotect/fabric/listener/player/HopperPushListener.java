package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

public final class HopperPushListener {
    private HopperPushListener() {
    }

    public static void logHopperPush(
        ServerWorld world,
        BlockPos hopperPos,
        Map<LoggedItemData, Integer> beforeHopper,
        Map<LoggedItemData, Integer> afterHopper,
        BlockPos destinationPos,
        String destinationType,
        Map<LoggedItemData, Integer> beforeDestination,
        Map<LoggedItemData, Integer> afterDestination
    ) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || !runtime.config().hopperTransactions()) {
            return;
        }

        String worldKey = world.getRegistryKey().getValue().toString();
        String hopperType = BlockStateSerializer.describeBlock(world.getBlockState(hopperPos));
        HopperTransactionLogger.logInventoryChange(runtime, "#hopper", worldKey, hopperPos, hopperType, beforeHopper, afterHopper);

        if (destinationPos != null && destinationType != null && beforeDestination != null && afterDestination != null) {
            HopperTransactionLogger.logInventoryChange(runtime, "#hopper", worldKey, destinationPos, destinationType, beforeDestination, afterDestination);
        }
    }
}
