package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

public final class BlockPlaceListener {
    private BlockPlaceListener() {
    }

    public static void logLecternBookPlace(
        ServerPlayerEntity player,
        ServerWorld world,
        BlockPos pos,
        ItemStack placedBook,
        Map<LoggedItemData, Integer> beforePlayerInventory,
        Map<LoggedItemData, Integer> afterPlayerInventory
    ) {
        if (placedBook == null || placedBook.isEmpty()) {
            return;
        }

        String containerType = BlockStateSerializer.describeBlock(world.getBlockState(pos));
        CoreProtectFabricMod.logContainerTransaction(
            player,
            world.getRegistryKey().getValue().toString(),
            pos,
            containerType,
            0,
            0,
            SlotActionType.PICKUP,
            ItemStack.EMPTY,
            placedBook,
            ItemStack.EMPTY,
            ItemStack.EMPTY
        );

        for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(beforePlayerInventory, afterPlayerInventory)) {
            if (delta.delta() > 0) {
                CoreProtectFabricMod.logItemPickup(player, world.getRegistryKey().getValue().toString(), pos, delta.item(), delta.delta(), containerType);
            }
            else {
                CoreProtectFabricMod.logItemDrop(player, world.getRegistryKey().getValue().toString(), pos, delta.item(), -delta.delta(), containerType);
            }
        }
    }
}
