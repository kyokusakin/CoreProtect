package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.CoreProtectFabricMod;
import net.coreprotect.fabric.listener.player.HopperTransactionLogger;
import net.minecraft.block.BlockState;
import net.minecraft.block.DropperBlock;
import net.minecraft.block.entity.DropperBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DropperBlock.class)
public abstract class DropperBlockMixin {
    @Unique
    private static final ThreadLocal<java.util.Map<net.coreprotect.fabric.util.LoggedItemData, Integer>> coreprotect$beforeInventory = new ThreadLocal<>();

    @Inject(method = "dispense", at = @At("HEAD"))
    private void coreprotect$captureDropper(ServerWorld world, BlockState state, BlockPos pos, CallbackInfo ci) {
        DropperBlockEntity dropper = world.getBlockEntity(pos, net.minecraft.block.entity.BlockEntityType.DROPPER).orElse(null);
        coreprotect$beforeInventory.set(dropper == null ? java.util.Map.of() : HopperTransactionLogger.snapshotInventory(dropper));
    }

    @Inject(method = "dispense", at = @At("RETURN"))
    private void coreprotect$logDropper(ServerWorld world, BlockState state, BlockPos pos, CallbackInfo ci) {
        try {
            DropperBlockEntity dropper = world.getBlockEntity(pos, net.minecraft.block.entity.BlockEntityType.DROPPER).orElse(null);
            if (dropper == null) {
                return;
            }
            HopperTransactionLogger.logInventoryChange(
                CoreProtectFabricMod.getRuntime(),
                "#dropper",
                world.getRegistryKey().getValue().toString(),
                pos,
                net.coreprotect.fabric.util.BlockStateSerializer.describeBlock(state),
                coreprotect$beforeInventory.get() == null ? java.util.Map.of() : coreprotect$beforeInventory.get(),
                HopperTransactionLogger.snapshotInventory(dropper)
            );
        }
        finally {
            coreprotect$beforeInventory.remove();
        }
    }
}
