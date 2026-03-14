package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.world.StructureGrowListener;
import net.coreprotect.fabric.util.BonemealGrowthContext;
import net.coreprotect.fabric.util.StructureGrowContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.SaplingGenerator;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(SaplingGenerator.class)
public abstract class SaplingGeneratorMixin {
    @Inject(method = "generate", at = @At("HEAD"))
    private void coreprotect$beginTreeGrow(ServerWorld world, ChunkGenerator chunkGenerator, BlockPos pos, BlockState state, Random random, CallbackInfoReturnable<Boolean> cir) {
        if (BonemealGrowthContext.isActive()) {
            return;
        }

        String actor = StructureGrowListener.resolveTreeActor(world, pos);
        if (actor != null && !actor.isBlank()) {
            StructureGrowContext.begin(world, actor);
        }
    }

    @Inject(method = "generate", at = @At("RETURN"))
    private void coreprotect$endTreeGrow(ServerWorld world, ChunkGenerator chunkGenerator, BlockPos pos, BlockState state, Random random, CallbackInfoReturnable<Boolean> cir) {
        if (BonemealGrowthContext.isActive()) {
            return;
        }

        StructureGrowListener.logCompletedGrowth(StructureGrowContext.end(cir.getReturnValueZ()));
    }
}
