package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalSpreadContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.FireBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FireBlock.class)
public abstract class FireBlockMixin {
    @Inject(method = "scheduledTick", at = @At("HEAD"))
    private void coreprotect$beginFireTick(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        NaturalSpreadContext.push("#fire");
    }

    @Inject(method = "scheduledTick", at = @At("RETURN"))
    private void coreprotect$endFireTick(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        NaturalSpreadContext.pop();
    }
}
