package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.TradeOutputSlot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.village.MerchantInventory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;
import java.util.Map;

@Mixin(TradeOutputSlot.class)
public abstract class TradeOutputSlotMixin {
    @Shadow
    @Final
    private MerchantInventory merchantInventory;

    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforeTradeInputs = Map.of();
    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforePlayerInventory = Map.of();

    @Inject(method = "onTakeItem", at = @At("HEAD"))
    private void coreprotect$captureTradeInputs(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        coreprotect$beforeTradeInputs = ItemDeltaSnapshot.snapshotStacks(List.of(
            merchantInventory.getStack(0),
            merchantInventory.getStack(1)
        ));
        if (player instanceof ServerPlayerEntity serverPlayerEntity) {
            coreprotect$beforePlayerInventory = ItemDeltaSnapshot.snapshotPlayerInventory(serverPlayerEntity);
        }
        else {
            coreprotect$beforePlayerInventory = Map.of();
        }
    }

    @Inject(method = "onTakeItem", at = @At("RETURN"))
    private void coreprotect$logTradeTransaction(PlayerEntity player, ItemStack stack, CallbackInfo ci) {
        try {
            if (!(player instanceof ServerPlayerEntity serverPlayerEntity)) {
                return;
            }
            if (!(player.getEntityWorld() instanceof ServerWorld serverWorld)) {
                return;
            }

            Map<LoggedItemData, Integer> afterTradeInputs = ItemDeltaSnapshot.snapshotStacks(List.of(
                merchantInventory.getStack(0),
                merchantInventory.getStack(1)
            ));
            Map<LoggedItemData, Integer> afterPlayerInventory = ItemDeltaSnapshot.snapshotPlayerInventory(serverPlayerEntity);
            for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(coreprotect$beforeTradeInputs, afterTradeInputs)) {
                if (delta.delta() < 0) {
                    CoreProtectFabricMod.logItemSell(
                        serverPlayerEntity,
                        serverWorld.getRegistryKey().getValue().toString(),
                        player.getBlockPos(),
                        delta.item(),
                        -delta.delta(),
                        "trade"
                    );
                }
            }

            if (!stack.isEmpty()) {
                LoggedItemData boughtItem = LoggedItemData.fromStack(stack);
                int boughtCount = ItemDeltaSnapshot.countDelta(coreprotect$beforePlayerInventory, afterPlayerInventory, boughtItem);
                if (boughtCount <= 0) {
                    boughtCount = stack.getCount();
                }
                CoreProtectFabricMod.logItemBuy(
                    serverPlayerEntity,
                    serverWorld.getRegistryKey().getValue().toString(),
                    player.getBlockPos(),
                    boughtItem,
                    boughtCount,
                    "trade"
                );
            }
        }
        finally {
            coreprotect$beforeTradeInputs = Map.of();
            coreprotect$beforePlayerInventory = Map.of();
        }
    }
}
