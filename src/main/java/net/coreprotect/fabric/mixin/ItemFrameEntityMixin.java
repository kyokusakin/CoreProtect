package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.listener.entity.HangingBreakByEntityListener;
import net.coreprotect.fabric.listener.player.PlayerInteractEntityListener;
import net.coreprotect.fabric.util.ItemDeltaSnapshot;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.ItemFrameEntity;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

@Mixin(ItemFrameEntity.class)
public abstract class ItemFrameEntityMixin {
    @Unique
    private ItemStack coreprotect$beforeHeldItem = ItemStack.EMPTY;
    @Unique
    private int coreprotect$beforeRotation;

    @Inject(method = "interact", at = @At("HEAD"))
    private void coreprotect$captureItemFrameState(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        ItemFrameEntity itemFrameEntity = (ItemFrameEntity) (Object) this;
        coreprotect$beforeHeldItem = itemFrameEntity.getHeldItemStack().copy();
        coreprotect$beforeRotation = itemFrameEntity.getRotation();
    }

    @Inject(method = "interact", at = @At("RETURN"))
    private void coreprotect$logItemFrameInteraction(PlayerEntity player, Hand hand, CallbackInfoReturnable<ActionResult> cir) {
        if (!cir.getReturnValue().isAccepted()) {
            return;
        }
        if (!(player instanceof ServerPlayerEntity)) {
            return;
        }

        ItemFrameEntity itemFrameEntity = (ItemFrameEntity) (Object) this;
        if (!(itemFrameEntity.getEntityWorld() instanceof ServerWorld)) {
            return;
        }
        if (ItemStack.areEqual(coreprotect$beforeHeldItem, itemFrameEntity.getHeldItemStack()) && coreprotect$beforeRotation == itemFrameEntity.getRotation()) {
            return;
        }

        coreprotect$logItemFrameDelta((ServerPlayerEntity) player, (ServerWorld) itemFrameEntity.getEntityWorld(), itemFrameEntity);
        PlayerInteractEntityListener.logEntityUse((ServerPlayerEntity) player, (ServerWorld) itemFrameEntity.getEntityWorld(), itemFrameEntity.getAttachedBlockPos(), itemFrameEntity);
    }

    @Inject(method = "onBreak", at = @At("HEAD"))
    private void coreprotect$logItemFrameBreak(ServerWorld world, Entity breaker, CallbackInfo ci) {
        ItemFrameEntity itemFrameEntity = (ItemFrameEntity) (Object) this;
        if (breaker instanceof ServerPlayerEntity && !((ServerPlayerEntity) breaker).isCreative() && !itemFrameEntity.getHeldItemStack().isEmpty()) {
            CoreProtectFabricMod.logItemDrop((ServerPlayerEntity) breaker, world, itemFrameEntity.getAttachedBlockPos(), itemFrameEntity.getHeldItemStack().copy());
        }
        HangingBreakByEntityListener.logHangingBreak(world, itemFrameEntity.getAttachedBlockPos(), itemFrameEntity, breaker);
    }

    @Unique
    private void coreprotect$logItemFrameDelta(ServerPlayerEntity player, ServerWorld world, ItemFrameEntity itemFrameEntity) {
        for (ItemDeltaSnapshot.ItemDelta delta : ItemDeltaSnapshot.diff(
            ItemDeltaSnapshot.snapshotStacks(List.of(coreprotect$beforeHeldItem)),
            ItemDeltaSnapshot.snapshotStacks(List.of(itemFrameEntity.getHeldItemStack()))
        )) {
            if (delta.delta() > 0) {
                CoreProtectFabricMod.logItemDrop(player, world.getRegistryKey().getValue().toString(), itemFrameEntity.getAttachedBlockPos(), delta.item(), delta.delta(), "item_frame");
            }
            else {
                CoreProtectFabricMod.logItemPickup(player, world.getRegistryKey().getValue().toString(), itemFrameEntity.getAttachedBlockPos(), delta.item(), -delta.delta(), "item_frame");
            }
        }
    }
}
