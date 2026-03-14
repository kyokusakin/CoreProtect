package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.util.TransientLookupCache;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class PlayerInteractUtils {
    private PlayerInteractUtils() {
    }

    public static void clickedDragonEgg(ServerPlayerEntity player, ServerWorld world, BlockPos pos) {
        TransientLookupCache.rememberDragonEggInteraction(world.getRegistryKey().getValue().toString(), pos, player.getName().getString());
    }
}
