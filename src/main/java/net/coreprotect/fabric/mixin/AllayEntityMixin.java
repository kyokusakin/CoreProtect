package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.minecraft.entity.passive.AllayEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AllayEntity.class)
public abstract class AllayEntityMixin {
    @Unique
    private ItemStack coreprotect$beforePlayerItem = ItemStack.EMPTY;
    @Unique
    private ItemStack coreprotect$beforeAllayItem = ItemStack.EMPTY;

    @Inject(method = "interactMob", at = @At("HEAD"))
    private void coreprotect$captureAllayItems(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        AllayEntity allayEntity = (AllayEntity) (Object) this;
        coreprotect$beforePlayerItem = player.getStackInHand(hand).copy();
        coreprotect$beforeAllayItem = allayEntity.getMainHandStack().copy();
    }

    @Inject(method = "interactMob", at = @At("RETURN"))
    private void coreprotect$logAllayExchange(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (!cir.getReturnValue().isAccepted()) {
                return;
            }
            if (!(player instanceof ServerPlayerEntity serverPlayerEntity)) {
                return;
            }

            AllayEntity allayEntity = (AllayEntity) (Object) this;
            if (!(allayEntity.getEntityWorld() instanceof ServerWorld serverWorld)) {
                return;
            }
            if (ItemStack.areItemsAndComponentsEqual(coreprotect$beforePlayerItem, coreprotect$beforeAllayItem)) {
                return;
            }

            if (coreprotect$beforeAllayItem.isEmpty() && !coreprotect$beforePlayerItem.isEmpty()) {
                CoreProtectFabricMod.logItemSell(serverPlayerEntity, serverWorld, player.getBlockPos(), coreprotect$beforePlayerItem.copyWithCount(1));
            }
            else if (coreprotect$beforePlayerItem.isEmpty() && !coreprotect$beforeAllayItem.isEmpty()) {
                CoreProtectFabricMod.logItemBuy(serverPlayerEntity, serverWorld, player.getBlockPos(), coreprotect$beforeAllayItem.copyWithCount(1));
            }
        }
        finally {
            coreprotect$beforePlayerItem = ItemStack.EMPTY;
            coreprotect$beforeAllayItem = ItemStack.EMPTY;
        }
    }
}
