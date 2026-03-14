package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalSpreadContext;
import net.minecraft.block.BambooBlock;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BambooBlock.class)
public abstract class BambooBlockMixin {
    @Inject(method = "randomTick", at = @At("HEAD"))
    private void coreprotect$beginSpread(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        NaturalSpreadContext.push("#bamboo");
    }

    @Inject(method = "randomTick", at = @At("RETURN"))
    private void coreprotect$endSpread(BlockState state, ServerWorld world, BlockPos pos, Random random, CallbackInfo ci) {
        NaturalSpreadContext.pop();
    }
}
