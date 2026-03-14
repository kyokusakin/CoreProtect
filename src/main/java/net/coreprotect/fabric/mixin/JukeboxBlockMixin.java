package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.block.JukeboxInteractionListener;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.JukeboxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.JukeboxBlockEntity;
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

import java.util.Map;

@Mixin(JukeboxBlock.class)
public abstract class JukeboxBlockMixin implements BlockEntityProvider {
    @Unique
    private ItemStack coreprotect$beforeRecord = ItemStack.EMPTY;
    @Unique
    private Map<LoggedItemData, Integer> coreprotect$beforeInventory = Map.of();

    @Inject(method = "onUseWithItem", at = @At("HEAD"))
    private void coreprotect$captureJukeboxInsert(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        coreprotect$beforeRecord = ItemStack.EMPTY;
        coreprotect$beforeInventory = Map.of();
        if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld)) {
            return;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof JukeboxBlockEntity jukeboxBlockEntity) {
            coreprotect$beforeRecord = jukeboxBlockEntity.getStack().copy();
            coreprotect$beforeInventory = ItemDeltaSnapshot.snapshotPlayerInventory(serverPlayer);
        }
    }

    @Inject(method = "onUseWithItem", at = @At("RETURN"))
    private void coreprotect$logJukeboxInsert(ItemStack stack, BlockState state, World world, BlockPos pos, PlayerEntity player, Hand hand, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld) || !cir.getReturnValue().isAccepted()) {
                return;
            }

            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!(blockEntity instanceof JukeboxBlockEntity jukeboxBlockEntity)) {
                return;
            }

            ItemStack afterRecord = jukeboxBlockEntity.getStack().copy();
            if (ItemStack.areEqual(coreprotect$beforeRecord, afterRecord)) {
                return;
            }

            JukeboxInteractionListener.logJukeboxInsert(
                serverPlayer,
                serverWorld,
                pos,
                coreprotect$beforeRecord,
                afterRecord,
                coreprotect$beforeInventory,
                ItemDeltaSnapshot.snapshotPlayerInventory(serverPlayer)
            );
        }
        finally {
            coreprotect$beforeRecord = ItemStack.EMPTY;
            coreprotect$beforeInventory = Map.of();
        }
    }

    @Inject(method = "onUse", at = @At("HEAD"))
    private void coreprotect$captureJukeboxEject(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        coreprotect$beforeRecord = ItemStack.EMPTY;
        if (!(world instanceof ServerWorld)) {
            return;
        }

        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity instanceof JukeboxBlockEntity jukeboxBlockEntity) {
            coreprotect$beforeRecord = jukeboxBlockEntity.getStack().copy();
        }
    }

    @Inject(method = "onUse", at = @At("RETURN"))
    private void coreprotect$logJukeboxEject(BlockState state, World world, BlockPos pos, PlayerEntity player, BlockHitResult hit, CallbackInfoReturnable<ActionResult> cir) {
        try {
            if (!(player instanceof ServerPlayerEntity serverPlayer) || !(world instanceof ServerWorld serverWorld) || !cir.getReturnValue().isAccepted()) {
                return;
            }
            if (coreprotect$beforeRecord.isEmpty()) {
                return;
            }

            BlockEntity blockEntity = world.getBlockEntity(pos);
            if (!(blockEntity instanceof JukeboxBlockEntity jukeboxBlockEntity)) {
                return;
            }
            if (!jukeboxBlockEntity.getStack().isEmpty()) {
                return;
            }

            JukeboxInteractionListener.logJukeboxEject(serverPlayer, serverWorld, pos, coreprotect$beforeRecord);
        }
        finally {
            coreprotect$beforeRecord = ItemStack.EMPTY;
        }
    }
}
