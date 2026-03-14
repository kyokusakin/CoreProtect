package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.service.ContainerSessionService;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

import java.util.Map;

public final class PlayerTakeLecternBookListener {
    private PlayerTakeLecternBookListener() {
    }

    public static void logTakeLecternBook(
        ServerPlayerEntity player,
        ContainerSessionService.ContainerContext context,
        int buttonId,
        ItemStack beforeBook,
        ItemStack afterBook,
        Map<LoggedItemData, Integer> beforePlayerInventory,
        Map<LoggedItemData, Integer> afterPlayerInventory
    ) {
        if (context == null || beforeBook == null || beforeBook.isEmpty()) {
            return;
        }

        CoreProtectFabricMod.logContainerTransaction(
            player,
            context.worldKey(),
            context.pos(),
            context.containerType(),
            0,
            buttonId,
            SlotActionType.PICKUP,
            beforeBook,
            afterBook == null ? ItemStack.EMPTY : afterBook,
            ItemStack.EMPTY,
            ItemStack.EMPTY
        );

        for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(beforePlayerInventory, afterPlayerInventory)) {
            if (delta.delta() > 0) {
                CoreProtectFabricMod.logItemPickup(player, context.worldKey(), context.pos(), delta.item(), delta.delta(), context.containerType());
            }
            else {
                CoreProtectFabricMod.logItemDrop(player, context.worldKey(), context.pos(), delta.item(), -delta.delta(), context.containerType());
            }
        }
    }
}
