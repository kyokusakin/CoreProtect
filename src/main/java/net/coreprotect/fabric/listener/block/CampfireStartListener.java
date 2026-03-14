package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class CampfireStartListener {
    private CampfireStartListener() {
    }

    public static void logCampfireStart(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack source) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || player == null || source == null || source.isEmpty()) {
            return;
        }

        ItemStack single = source.copy();
        single.setCount(1);
        runtime.logger().logItemDrop(player, world, pos, single);
    }
}
