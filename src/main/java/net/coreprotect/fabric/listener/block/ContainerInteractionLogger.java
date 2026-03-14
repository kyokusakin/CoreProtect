package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

public final class ContainerInteractionLogger {
    private ContainerInteractionLogger() {
    }

    public static void logInventoryDelta(
        ServerPlayerEntity player,
        ServerWorld world,
        BlockPos pos,
        String containerType,
        int slotIndex,
        ItemStack beforeSlot,
        ItemStack afterSlot
    ) {
        if (player == null || world == null || pos == null || CoreProtectFabricMod.getRuntime() == null || !CoreProtectFabricMod.getRuntime().config(world).itemTransactions()) {
            return;
        }

        String resolvedContainerType = containerType == null || containerType.isBlank()
            ? BlockStateSerializer.describeBlock(world.getBlockState(pos))
            : containerType;
        CoreProtectFabricMod.logContainerTransaction(
            player,
            world.getRegistryKey().getValue().toString(),
            pos,
            resolvedContainerType,
            slotIndex,
            0,
            SlotActionType.PICKUP,
            beforeSlot == null ? ItemStack.EMPTY : beforeSlot,
            afterSlot == null ? ItemStack.EMPTY : afterSlot,
            ItemStack.EMPTY,
            ItemStack.EMPTY
        );
    }
}
