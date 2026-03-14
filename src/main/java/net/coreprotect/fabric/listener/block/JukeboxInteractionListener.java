package net.coreprotect.fabric.listener.block;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Map;

public final class JukeboxInteractionListener {
    private JukeboxInteractionListener() {
    }

    public static void logJukeboxInsert(
        ServerPlayerEntity player,
        ServerWorld world,
        BlockPos pos,
        ItemStack beforeRecord,
        ItemStack afterRecord,
        Map<LoggedItemData, Integer> beforePlayerInventory,
        Map<LoggedItemData, Integer> afterPlayerInventory
    ) {
        if (CoreProtectFabricMod.getRuntime() == null || !CoreProtectFabricMod.getRuntime().config(world).itemTransactions()) {
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
            beforeRecord == null ? ItemStack.EMPTY : beforeRecord,
            afterRecord == null ? ItemStack.EMPTY : afterRecord,
            ItemStack.EMPTY,
            ItemStack.EMPTY
        );
    }

    public static void logJukeboxEject(ServerPlayerEntity player, ServerWorld world, BlockPos pos, ItemStack beforeRecord) {
        if (beforeRecord == null || beforeRecord.isEmpty()) {
            return;
        }
        if (CoreProtectFabricMod.getRuntime() == null || !CoreProtectFabricMod.getRuntime().config(world).itemTransactions()) {
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
            beforeRecord,
            ItemStack.EMPTY,
            ItemStack.EMPTY,
            ItemStack.EMPTY
        );
    }
}
