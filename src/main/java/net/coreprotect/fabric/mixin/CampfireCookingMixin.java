package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.block.CampfireStartListener;
import net.minecraft.block.BlockState;
import net.minecraft.block.CampfireBlock;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(CampfireBlock.class)
public abstract class CampfireCookingMixin {
    @Unique
    private ItemStack coreprotect$beforeInsert = ItemStack.EMPTY;

    @Inject(method = "onUseWithItem", at = @At("HEAD"))
    private void coreprotect$captureCookingItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        coreprotect$beforeInsert = stack == null ? ItemStack.EMPTY : stack.copy();
    }

    @Inject(method = "onUseWithItem", at = @At("RETURN"))
    private void coreprotect$logCookingStart(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld)) {
                return;
            }
            if (!cir.getReturnValue().isAccepted() || coreprotect$beforeInsert.isEmpty()) {
                return;
            }

            CampfireStartListener.logCampfireStart(serverPlayer, serverWorld, pos, coreprotect$beforeInsert);
        }
        finally {
            coreprotect$beforeInsert = ItemStack.EMPTY;
        }
    }
}
