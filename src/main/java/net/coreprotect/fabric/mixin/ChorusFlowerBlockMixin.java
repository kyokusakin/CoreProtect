package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalSpreadContext;
import net.minecraft.block.ChorusFlowerBlock;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChorusFlowerBlock.class)
public abstract class ChorusFlowerBlockMixin {
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void coreprotect$beginSpread(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        NaturalSpreadContext.push("#chorus");
    }

    @Inject(method = "randomTick", at = @At("RETURN"))
    private void coreprotect$endSpread(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        NaturalSpreadContext.pop();
    }
}
