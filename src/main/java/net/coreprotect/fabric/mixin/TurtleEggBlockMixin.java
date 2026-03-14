package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.entity.EntityInteractListener;
import net.minecraft.block.BlockState;
import net.minecraft.block.TurtleEggBlock;
import net.minecraft.entity.Entity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TurtleEggBlock.class)
public abstract class TurtleEggBlockMixin {
    @Unique
    private static final ThreadLocal<EggInteractionContext> coreprotect$eggInteraction = new ThreadLocal<>();

    @Inject(method = "onSteppedOn", at = @At("HEAD"))
    private void coreprotect$captureStep(World world, BlockPos pos, BlockState state, Entity entity, CallbackInfo ci) {
        capture(world, pos, state, entity);
    }

    @Inject(method = "onSteppedOn", at = @At("RETURN"))
    private void coreprotect$logStep(World world, BlockPos pos, BlockState state, Entity entity, CallbackInfo ci) {
        log(world);
    }

    @Inject(method = "onLandedUpon", at = @At("HEAD"))
    private void coreprotect$captureLand(World world, BlockState state, BlockPos pos, Entity entity, double fallDistance, CallbackInfo ci) {
        capture(world, pos, state, entity);
    }

    @Inject(method = "onLandedUpon", at = @At("RETURN"))
    private void coreprotect$logLand(World world, BlockState state, BlockPos pos, Entity entity, double fallDistance, CallbackInfo ci) {
        log(world);
    }

    @Unique
    private void capture(World world, BlockPos pos, BlockState state, Entity entity) {
        if (!(world instanceof ServerWorld serverWorld)) {
            coreprotect$eggInteraction.remove();
            return;
        }
        coreprotect$eggInteraction.set(new EggInteractionContext(serverWorld, pos.toImmutable(), state, entity));
    }

    @Unique
    private void log(World world) {
        EggInteractionContext context = coreprotect$eggInteraction.get();
        coreprotect$eggInteraction.remove();
        if (context == null) {
            return;
        }

        EntityInteractListener.logTurtleEggInteract(
            context.world(),
            context.pos(),
            context.entity(),
            context.previousState(),
            context.world().getBlockState(context.pos())
        );
    }

    @Unique
    private record EggInteractionContext(ServerWorld world, BlockPos pos, BlockState previousState, Entity entity) {
    }
}
