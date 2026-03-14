package net.coreprotect.fabric.listener.player;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.service.ContainerSessionService;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;

public final class PlayerTakeLecternBookListener {
    private PlayerTakeLecternBookListener() {
    }

    public static void logTakeLecternBook(
        ServerPlayerEntity player,
        ContainerSessionService.ContainerContext context,
        int buttonId,
        ItemStack beforeBook,
        ItemStack afterBook
    ) {
        if (context == null || beforeBook == null || beforeBook.isEmpty()) {
            return;
        }
        if (CoreProtectFabricMod.getRuntime() == null || !CoreProtectFabricMod.getRuntime().config(context.worldKey()).itemTransactions()) {
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
    }
}
