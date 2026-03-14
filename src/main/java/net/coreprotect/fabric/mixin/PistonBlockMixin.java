package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.block.BlockPistonListener;
import net.minecraft.block.BlockState;
import net.minecraft.block.PistonBlock;
import net.minecraft.block.piston.PistonHandler;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Mixin(PistonBlock.class)
public abstract class PistonBlockMixin {
    @Unique
    private static final ThreadLocal<PistonMoveContext> coreprotect$pistonContext = new ThreadLocal<>();

    @Inject(method = "move", at = @At("HEAD"))
    private void coreprotect$captureMove(World world, BlockPos pos, Direction direction, boolean retract, CallbackInfoReturnable<Boolean> cir) {
        if (!(world instanceof ServerWorld serverWorld)) {
            coreprotect$pistonContext.remove();
            return;
        }

        PistonHandler pistonHandler = new PistonHandler(world, pos, direction, retract);
        if (!pistonHandler.calculatePush()) {
            coreprotect$pistonContext.remove();
            return;
        }

        List<BlockPos> movedBlocks = List.copyOf(pistonHandler.getMovedBlocks());
        List<BlockPos> brokenBlocks = List.copyOf(pistonHandler.getBrokenBlocks());
        if (movedBlocks.isEmpty() && brokenBlocks.isEmpty()) {
            coreprotect$pistonContext.remove();
            return;
        }

        Map<BlockPos, BlockState> movedStates = new LinkedHashMap<>();
        for (BlockPos movedPos : movedBlocks) {
            movedStates.put(movedPos.toImmutable(), serverWorld.getBlockState(movedPos));
        }

        Map<BlockPos, BlockState> brokenStates = new LinkedHashMap<>();
        for (BlockPos brokenPos : brokenBlocks) {
            brokenStates.put(brokenPos.toImmutable(), serverWorld.getBlockState(brokenPos));
        }

        coreprotect$pistonContext.set(new PistonMoveContext(
            serverWorld,
            pistonHandler.getMotionDirection(),
            movedBlocks,
            movedStates,
            brokenBlocks,
            brokenStates
        ));
    }

    @Inject(method = "move", at = @At("RETURN"))
    private void coreprotect$logMove(World world, BlockPos pos, Direction direction, boolean retract, CallbackInfoReturnable<Boolean> cir) {
        PistonMoveContext context = coreprotect$pistonContext.get();
        coreprotect$pistonContext.remove();
        if (context == null || !cir.getReturnValueZ()) {
            return;
        }

        BlockPistonListener.logPistonMove(
            context.world(),
            context.motionDirection(),
            context.movedBlocks(),
            context.movedStates(),
            context.brokenBlocks(),
            context.brokenStates()
        );
    }

    @Unique
    private record PistonMoveContext(
        ServerWorld world,
        Direction motionDirection,
        List<BlockPos> movedBlocks,
        Map<BlockPos, BlockState> movedStates,
        List<BlockPos> brokenBlocks,
        Map<BlockPos, BlockState> brokenStates
    ) {
    }
}
