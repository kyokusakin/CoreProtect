package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.listener.block.ContainerInteractionLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChiseledBookshelfBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChiseledBookshelfBlockEntity;
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

@Mixin(ChiseledBookshelfBlock.class)
public abstract class ChiseledBookshelfBlockMixin {
    @Unique
    private ItemStack[] coreprotect$beforeStacks = new ItemStack[0];

    @Inject(method = "onUseWithItem", at = @At("HEAD"))
    private void coreprotect$captureBookshelfInsert(
        ItemStack stack,
        BlockState state,
        World world,
        BlockPos pos,
        PlayerEntity player,
        Hand hand,
        BlockHitResult hit,
        CallbackInfoReturnable<ActionResult> cir
    ) {
        coreprotect$captureBookshelfState(world, pos, player);
    }

    @Inject(method = "onUseWithItem", at = @At("RETURN"))
    private void coreprotect$logBookshelfInsert(
        ItemStack stack,
        BlockState state,
        World world,
        BlockPos pos,
        PlayerEntity player,
        Hand hand,
        BlockHitResult hit,
        CallbackInfoReturnable<ActionResult> cir
    ) {
        coreprotect$logBookshelfChange(world, pos, player, cir);
    }

    @Inject(method = "onUse", at = @At("HEAD"))
    private void coreprotect$captureBookshelfRemove(
        BlockState state,
        World world,
        BlockPos pos,
        PlayerEntity player,
        BlockHitResult hit,
        CallbackInfoReturnable<ActionResult> cir
    ) {
        coreprotect$captureBookshelfState(world, pos, player);
    }

    @Inject(method = "onUse", at = @At("RETURN"))
    private void coreprotect$logBookshelfRemove(
        BlockState state,
        World world,
        BlockPos pos,
        PlayerEntity player,
        BlockHitResult hit,
        CallbackInfoReturnable<ActionResult> cir
    ) {
        coreprotect$logBookshelfChange(world, pos, player, cir);
    }

    @Unique
    private void coreprotect$captureBookshelfState(World world, BlockPos pos, PlayerEntity player) {
        coreprotect$beforeStacks = new ItemStack[0];
        if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld)) {
            return;
        }
        if (CoreProtectFabricMod.getRuntime() == null || !CoreProtectFabricMod.getRuntime().config(serverWorld).itemTransactions()) {
            return;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof ChiseledBookshelfBlockEntity bookshelf)) {
            return;
        }

        coreprotect$beforeStacks = coreprotect$snapshotStacks(bookshelf);
    }

    @Unique
    private void coreprotect$logBookshelfChange(World world, BlockPos pos, PlayerEntity player, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld) || !cir.getReturnValue().isAccepted()) {
                return;
            }

            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!(blockEntity instanceof ChiseledBookshelfBlockEntity bookshelf)) {
                return;
            }

            int slotIndex = bookshelf.getLastInteractedSlot();
            if (slotIndex < 0 || slotIndex >= bookshelf.getHeldStacks().size()) {
                return;
            }

            ItemStack beforeSlot = slotIndex < coreprotect$beforeStacks.length ? coreprotect$beforeStacks[slotIndex] : ItemStack.EMPTY;
            ItemStack afterSlot = bookshelf.getHeldStacks().get(slotIndex).copy();
            if (ItemStack.areEqual(beforeSlot, afterSlot)) {
                return;
            }

            ContainerInteractionLogger.logInventoryDelta(
                serverPlayer,
                serverWorld,
                pos,
                null,
                slotIndex,
                beforeSlot,
                afterSlot
            );
        }
        finally {
            coreprotect$beforeStacks = new ItemStack[0];
        }
    }

    @Unique
    private static ItemStack[] coreprotect$snapshotStacks(ChiseledBookshelfBlockEntity bookshelf) {
        ItemStack[] stacks = new ItemStack[bookshelf.getHeldStacks().size()];
        for (int slot = 0; slot < stacks.length; slot++) {
            stacks[slot] = bookshelf.getHeldStacks().get(slot).copy();
        }
        return stacks;
    }
}
