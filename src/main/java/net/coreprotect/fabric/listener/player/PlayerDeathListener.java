package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;

public final class PlayerDeathListener {
    private PlayerDeathListener() {
    }

    public static void logDeathDrops(ServerPlayerEntity player, Map<LoggedItemData, Integer> beforeInventory, Map<LoggedItemData, Integer> afterInventory) {
        FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
        if (runtime == null || player == null || !runtime.config((net.minecraft.server.world.ServerWorld) player.getEntityWorld()).logItemDrops()) {
            return;
        }

        String worldKey = ((net.minecraft.server.world.ServerWorld) player.getEntityWorld()).getRegistryKey().getValue().toString();
        for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(beforeInventory, afterInventory)) {
            if (delta.delta() >= 0) {
                continue;
            }
            runtime.logger().logItemDrop(
                player.getUuidAsString(),
                player.getName().getString(),
                worldKey,
                player.getBlockPos(),
                delta.item(),
                -delta.delta(),
                "death"
            );
        }
    }
}
