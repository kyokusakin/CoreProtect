package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.NaturalSpreadContext;
import net.minecraft.fluid.LavaFluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LavaFluid.class)
public abstract class LavaFluidMixin {
    @Inject(method = "onRandomTick", at = @At("HEAD"))
    private void coreprotect$beginLavaIgnite(ServerWorld world, BlockPos pos, FluidState state, Random random, CallbackInfo ci) {
        NaturalSpreadContext.push("#fire");
    }

    @Inject(method = "onRandomTick", at = @At("RETURN"))
    private void coreprotect$endLavaIgnite(ServerWorld world, BlockPos pos, FluidState state, Random random, CallbackInfo ci) {
        NaturalSpreadContext.pop();
    }
}
