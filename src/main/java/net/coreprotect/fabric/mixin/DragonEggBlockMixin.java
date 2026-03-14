package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.block.BlockFromToListener;
import net.minecraft.block.BlockState;
import net.minecraft.block.DragonEggBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(DragonEggBlock.class)
public abstract class DragonEggBlockMixin {
    @Redirect(
        method = "teleport",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/World;setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z"
        )
    )
    private boolean coreprotect$logDragonEggTeleport(World world, BlockPos targetPos, BlockState targetState, int flags, BlockState originalState, World originalWorld, BlockPos sourcePos) {
        boolean changed = world.setBlockState(targetPos, targetState, flags);
        if (changed && world instanceof ServerWorld serverWorld) {
            BlockFromToListener.logDragonEggMove(serverWorld, sourcePos, targetPos, originalState);
        }
        return changed;
    }
}
