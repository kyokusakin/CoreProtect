package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.EntityBlockChangeContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.TurtleEggBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TurtleEggBlock.class)
public abstract class TurtleEggFadeMixin {
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void coreprotect$beginFade(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        EntityBlockChangeContext.push("#turtle");
    }

    @Inject(method = "randomTick", at = @At("RETURN"))
    private void coreprotect$endFade(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        EntityBlockChangeContext.pop();
    }
}
