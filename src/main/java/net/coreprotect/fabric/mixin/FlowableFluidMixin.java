package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.block.BlockFromToListener;
import net.minecraft.block.BlockState;
import net.minecraft.fluid.FlowableFluid;
import net.minecraft.fluid.Fluid;
import net.minecraft.fluid.FluidState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.WorldAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FlowableFluid.class)
public abstract class FlowableFluidMixin {
    @Unique
    private static final ThreadLocal<FlowContext> coreprotect$flowContext = new ThreadLocal<>();

    @Inject(method = "flow", at = @At("HEAD"))
    private void coreprotect$captureFlow(WorldAccess world, BlockPos pos, BlockState state, Direction direction, FluidState fluidState, CallbackInfo ci) {
        if (!(world instanceof ServerWorld serverWorld) || fluidState == null) {
            coreprotect$flowContext.remove();
            return;
        }

        Fluid fluid = fluidState.getFluid();
        if (state.getFluidState().getFluid().matchesType(fluid)) {
            coreprotect$flowContext.remove();
            return;
        }

        coreprotect$flowContext.set(new FlowContext(
            serverWorld,
            pos.toImmutable(),
            pos.offset(direction.getOpposite()).toImmutable(),
            state,
            fluid
        ));
    }

    @Inject(method = "flow", at = @At("RETURN"))
    private void coreprotect$logFlow(WorldAccess world, BlockPos pos, BlockState state, Direction direction, FluidState fluidState, CallbackInfo ci) {
        FlowContext context = coreprotect$flowContext.get();
        coreprotect$flowContext.remove();
        if (context == null) {
            return;
        }

        BlockFromToListener.logFluidFlow(
            context.world(),
            context.sourcePos(),
            context.targetPos(),
            context.previousTargetState(),
            context.world().getBlockState(context.targetPos()),
            context.fluid()
        );
    }

    @Unique
    private record FlowContext(
        ServerWorld world,
        BlockPos targetPos,
        BlockPos sourcePos,
        BlockState previousTargetState,
        Fluid fluid
    ) {
    }
}
