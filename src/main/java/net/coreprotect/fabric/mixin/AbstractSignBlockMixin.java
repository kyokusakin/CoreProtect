package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.util.LoggedSignState;
import net.minecraft.block.AbstractSignBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.SignChangingItem;
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

@Mixin(AbstractSignBlock.class)
public abstract class AbstractSignBlockMixin {
    @Unique
    private BlockPos coreprotect$signPos;
    @Unique
    private boolean coreprotect$signFront;
    @Unique
    private LoggedSignState coreprotect$beforeSignState;

    @Inject(method = "onUseWithItem", at = @At("HEAD"))
    private void coreprotect$captureSignItemUse(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        coreprotect$clearSignState();

        if (!(player instanceof ServerPlayerEntity) || !(world instanceof ServerWorld)) {
            return;
        }
        if (!(stack.getItem() instanceof SignChangingItem)) {
            return;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (!(blockEntity instanceof SignBlockEntity signBlockEntity)) {
            return;
        }

        coreprotect$signPos = pos.toImmutable();
        coreprotect$signFront = signBlockEntity.isPlayerFacingFront(player);
        coreprotect$beforeSignState = LoggedSignState.fromBlockEntity(signBlockEntity, coreprotect$signFront);
    }

    @Inject(method = "onUseWithItem", at = @At("RETURN"))
    private void coreprotect$logSignItemUse(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld)) {
                return;
            }
            if (!cir.getReturnValue().isAccepted() || coreprotect$beforeSignState == null || coreprotect$signPos == null) {
                return;
            }

            BlockEntity blockEntity = world.getBlockEntity(coreprotect$signPos);
            if (!(blockEntity instanceof SignBlockEntity signBlockEntity)) {
                return;
            }

            LoggedSignState currentState = LoggedSignState.fromBlockEntity(signBlockEntity, coreprotect$signFront);
            if (coreprotect$beforeSignState.equals(currentState)) {
                return;
            }

            CoreProtectFabricMod.getRuntime().logger().logSignChange(serverPlayer.getUuid(), serverPlayer.getName().getString(), serverWorld, coreprotect$signPos, currentState);
        }
        finally {
            coreprotect$clearSignState();
        }
    }

    @Unique
    private void coreprotect$clearSignState() {
        coreprotect$signPos = null;
        coreprotect$beforeSignState = null;
    }
}
