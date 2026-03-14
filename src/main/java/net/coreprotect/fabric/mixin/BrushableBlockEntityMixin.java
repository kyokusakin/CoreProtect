package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.FabricRuntime;
import net.coreprotect.fabric.util.BlockStateSerializer;
import net.coreprotect.fabric.util.LoggedItemData;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BrushableBlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BrushableBlockEntity.class)
public abstract class BrushableBlockEntityMixin {
    @Unique
    private BlockState coreprotect$beforeState;
    @Unique
    private ServerPlayerEntity coreprotect$brusher;

    @Inject(method = "finishBrushing", at = @At("HEAD"))
    private void coreprotect$captureBrushContext(ServerWorld world, LivingEntity brusher, ItemStack brush, CallbackInfo ci) {
        BrushableBlockEntity brushable = (BrushableBlockEntity) (Object) this;
        coreprotect$beforeState = brushable.getCachedState();
        coreprotect$brusher = brusher instanceof ServerPlayerEntity serverPlayer ? serverPlayer : null;
    }

    @Redirect(
        method = "spawnItem",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/world/ServerWorld;spawnEntity(Lnet/minecraft/entity/Entity;)Z")
    )
    private boolean coreprotect$logBrushLoot(ServerWorld world, Entity entity) {
        boolean spawned = world.spawnEntity(entity);
        if (!spawned || coreprotect$brusher == null || !(entity instanceof ItemEntity itemEntity)) {
            return spawned;
        }

        ItemStack stack = itemEntity.getStack().copy();
        if (stack.isEmpty()) {
            return spawned;
        }

        String worldKey = world.getRegistryKey().getValue().toString();
        String containerType = coreprotect$beforeState == null ? null : BlockStateSerializer.describeBlock(coreprotect$beforeState);
        CoreProtectFabricMod.logContainerTransaction(
            coreprotect$brusher,
            worldKey,
            ((BrushableBlockEntity) (Object) this).getPos(),
            containerType,
            0,
            0,
            SlotActionType.PICKUP,
            stack,
            ItemStack.EMPTY,
            ItemStack.EMPTY,
            ItemStack.EMPTY
        );
        CoreProtectFabricMod.logItemDrop(
            coreprotect$brusher,
            worldKey,
            ((BrushableBlockEntity) (Object) this).getPos(),
            LoggedItemData.fromStack(stack, world.getRegistryManager()),
            stack.getCount(),
            containerType
        );
        return spawned;
    }

    @Inject(method = "finishBrushing", at = @At("RETURN"))
    private void coreprotect$logBrushBlockChange(ServerWorld world, LivingEntity brusher, ItemStack brush, CallbackInfo ci) {
        try {
            if (coreprotect$brusher == null || coreprotect$beforeState == null) {
                return;
            }

            FabricRuntime runtime = CoreProtectFabricMod.getRuntime();
            if (runtime == null) {
                return;
            }

            BlockPos pos = ((BrushableBlockEntity) (Object) this).getPos();
            BlockState currentState = world.getBlockState(pos);
            if (currentState.equals(coreprotect$beforeState)) {
                return;
            }

            if (!coreprotect$beforeState.isAir()) {
                runtime.logger().logBlockBreak(coreprotect$brusher, world, pos, coreprotect$beforeState);
            }
            if (!currentState.isAir()) {
                runtime.logger().logBlockPlace(coreprotect$brusher, world, pos, currentState);
            }
        }
        finally {
            coreprotect$beforeState = null;
            coreprotect$brusher = null;
        }
    }
}
