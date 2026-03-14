package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.listener.block.ContainerInteractionLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.DecoratedPotBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.DecoratedPotBlockEntity;
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

@Mixin(DecoratedPotBlock.class)
public abstract class DecoratedPotBlockMixin {
    @Unique
    private ItemStack coreprotect$beforeStack = ItemStack.EMPTY;

    @Inject(method = "onUseWithItem", at = @At("HEAD"))
    private void coreprotect$capturePotInsert(
        ItemStack stack,
        BlockState state,
        World world,
        BlockPos pos,
        PlayerEntity player,
        Hand hand,
        BlockHitResult hit,
        CallbackInfoReturnable<ActionResult> cir
    ) {
        coreprotect$beforeStack = ItemStack.EMPTY;
        if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld)) {
            return;
        }
        if (CoreProtectFabricMod.getRuntime() == null || !CoreProtectFabricMod.getRuntime().config(serverWorld).itemTransactions()) {
            return;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof DecoratedPotBlockEntity decoratedPot) {
            coreprotect$beforeStack = decoratedPot.getStack().copy();
        }
    }

    @Inject(method = "onUseWithItem", at = @At("RETURN"))
    private void coreprotect$logPotInsert(
        ItemStack stack,
        BlockState state,
        World world,
        BlockPos pos,
        PlayerEntity player,
        Hand hand,
        BlockHitResult hit,
        CallbackInfoReturnable<ActionResult> cir
    ) {
        try {
            if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld) || !cir.getReturnValue().isAccepted()) {
                return;
            }

            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!(blockEntity instanceof DecoratedPotBlockEntity decoratedPot)) {
                return;
            }

            ItemStack afterStack = decoratedPot.getStack().copy();
            if (ItemStack.areEqual(coreprotect$beforeStack, afterStack)) {
                return;
            }

            ContainerInteractionLogger.logInventoryDelta(
                serverPlayer,
                serverWorld,
                pos,
                null,
                0,
                coreprotect$beforeStack,
                afterStack
            );
        }
        finally {
            coreprotect$beforeStack = ItemStack.EMPTY;
        }
    }
}
