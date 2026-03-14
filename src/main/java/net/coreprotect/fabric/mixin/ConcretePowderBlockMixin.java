package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.block.BlockFormListener;
import net.minecraft.block.BlockState;
import net.minecraft.block.ConcretePowderBlock;
import net.minecraft.entity.FallingBlockEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;
import net.minecraft.world.WorldView;
import net.minecraft.world.tick.ScheduledTickView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ConcretePowderBlock.class)
public abstract class ConcretePowderBlockMixin {
    @Inject(method = "onLanding", at = @At("RETURN"))
    private void coreprotect$logLandingForm(World world, BlockPos pos, BlockState state, BlockState landedState, FallingBlockEntity fallingBlockEntity, CallbackInfo ci) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }

        BlockState currentState = serverWorld.getBlockState(pos);
        if (currentState.equals(landedState)) {
            return;
        }

        BlockFormListener.logLiquidForm(serverWorld, pos, landedState, currentState);
    }

    @Inject(method = "getStateForNeighborUpdate", at = @At("RETURN"))
    private void coreprotect$logNeighborForm(
        BlockState state,
        WorldView world,
        ScheduledTickView scheduledTickView,
        BlockPos pos,
        Direction direction,
        BlockPos neighborPos,
        BlockState neighborState,
        Random random,
        CallbackInfoReturnable<BlockState> cir
    ) {
        if (!(world instanceof ServerWorld serverWorld)) {
            return;
        }

        BlockState newState = cir.getReturnValue();
        if (newState == null || newState.equals(state)) {
            return;
        }

        BlockFormListener.logLiquidForm(serverWorld, pos, state, newState);
    }
}
