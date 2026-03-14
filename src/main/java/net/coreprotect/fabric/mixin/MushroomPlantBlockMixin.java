package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.world.StructureGrowListener;
import net.coreprotect.fabric.util.BonemealGrowthContext;
import net.coreprotect.fabric.util.StructureGrowContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.MushroomPlantBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MushroomPlantBlock.class)
public abstract class MushroomPlantBlockMixin {
    @Inject(method = "grow", at = @At("HEAD"))
    private void coreprotect$beginBonemealGrow(ServerWorld world, Random random, BlockPos pos, BlockState state, CallbackInfo ci) {
        BonemealGrowthContext.begin();
    }

    @Inject(method = "grow", at = @At("RETURN"))
    private void coreprotect$endBonemealGrow(ServerWorld world, Random random, BlockPos pos, BlockState state, CallbackInfo ci) {
        BonemealGrowthContext.end();
    }

    @Inject(method = "trySpawningBigMushroom", at = @At("HEAD"))
    private void coreprotect$beginMushroomGrow(ServerWorld world, BlockPos pos, BlockState state, Random random, CallbackInfoReturnable<Boolean> cir) {
        if (BonemealGrowthContext.isActive()) {
            return;
        }

        String actor = StructureGrowListener.resolveMushroomActor(world, pos);
        if (actor != null && !actor.isBlank()) {
            StructureGrowContext.begin(world, actor);
        }
    }

    @Inject(method = "trySpawningBigMushroom", at = @At("RETURN"))
    private void coreprotect$endMushroomGrow(ServerWorld world, BlockPos pos, BlockState state, Random random, CallbackInfoReturnable<Boolean> cir) {
        if (BonemealGrowthContext.isActive()) {
            return;
        }

        StructureGrowListener.logCompletedGrowth(StructureGrowContext.end(cir.getReturnValueZ()));
    }
}
