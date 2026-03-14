package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.block.BlockFormListener;
import net.minecraft.block.BlockState;
import net.minecraft.block.FluidBlock;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FluidBlock.class)
public abstract class FluidBlockMixin {
    @Unique
    private static final ThreadLocal<FormContext> coreprotect$formContext = new ThreadLocal<>();

    @Inject(method = "receiveNeighborFluids", at = @At("HEAD"))
    private void coreprotect$captureForm(World world, BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        if (!(world instanceof ServerWorld serverWorld)) {
            coreprotect$formContext.remove();
            return;
        }

        coreprotect$formContext.set(new FormContext(serverWorld, pos.toImmutable(), state));
    }

    @Inject(method = "receiveNeighborFluids", at = @At("RETURN"))
    private void coreprotect$logForm(World world, BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        FormContext context = coreprotect$formContext.get();
        coreprotect$formContext.remove();
        if (context == null) {
            return;
        }

        BlockState currentState = context.world().getBlockState(context.pos());
        if (currentState.equals(context.previousState())) {
            return;
        }

        BlockFormListener.logLiquidForm(context.world(), context.pos(), context.previousState(), currentState);
    }

    @Unique
    private record FormContext(ServerWorld world, BlockPos pos, BlockState previousState) {
    }
}
