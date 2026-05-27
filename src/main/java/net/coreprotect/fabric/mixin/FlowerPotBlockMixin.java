package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.block.FlowerPotInteractListener;
import net.minecraft.block.BlockState;
import net.minecraft.block.FlowerPotBlock;
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

@Mixin(FlowerPotBlock.class)
public abstract class FlowerPotBlockMixin {
    @Unique
    private static final ThreadLocal<FlowerPotContext> coreprotect$context = new ThreadLocal<>();

    @Inject(method = "onUseWithItem", at = @At("HEAD"))
    private void coreprotect$captureUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        coreprotect$capture(state, world, pos, player);
    }

    @Inject(method = "onUseWithItem", at = @At("RETURN"))
    private void coreprotect$logUseWithItem(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        coreprotect$log(cir.getReturnValue());
    }

    @Inject(method = "onUse", at = @At("HEAD"))
    private void coreprotect$captureUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        coreprotect$capture(state, world, pos, player);
    }

    @Inject(method = "onUse", at = @At("RETURN"))
    private void coreprotect$logUse(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        coreprotect$log(cir.getReturnValue());
    }

    @Unique
    private void coreprotect$capture(BlockState state, World world, BlockPos pos, PlayerEntity player) {
        if (world instanceof ServerWorld serverWorld && player instanceof ServerPlayerEntity serverPlayer) {
            coreprotect$context.set(new FlowerPotContext(serverPlayer, serverWorld, pos.toImmutable(), state));
        }
        else {
            coreprotect$context.remove();
        }
    }

    @Unique
    private void coreprotect$log(ActionResult result) {
        FlowerPotContext context = coreprotect$context.get();
        coreprotect$context.remove();
        if (context == null || !result.isAccepted()) {
            return;
        }

        FlowerPotInteractListener.logFlowerPotChange(
            context.player(),
            context.world(),
            context.pos(),
            context.previousState(),
            context.world().getBlockState(context.pos())
        );
    }

    @Unique
    private record FlowerPotContext(ServerPlayerEntity player, ServerWorld world, BlockPos pos, BlockState previousState) {
    }
}
