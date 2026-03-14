package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.BonemealGrowthContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.SaplingBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SaplingBlock.class)
public abstract class SaplingBlockMixin {
    @Inject(method = "grow", at = @At("HEAD"))
    private void coreprotect$beginBonemealGrow(ServerWorld world, Random random, BlockPos pos, BlockState state, CallbackInfo ci) {
        BonemealGrowthContext.begin();
    }

    @Inject(method = "grow", at = @At("RETURN"))
    private void coreprotect$endBonemealGrow(ServerWorld world, Random random, BlockPos pos, BlockState state, CallbackInfo ci) {
        BonemealGrowthContext.end();
    }
}
