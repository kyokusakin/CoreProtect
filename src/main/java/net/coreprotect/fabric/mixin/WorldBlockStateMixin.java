package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.block.BlockBurnListener;
import net.coreprotect.fabric.listener.block.BlockFadeListener;
import net.coreprotect.fabric.listener.block.BlockIgniteListener;
import net.coreprotect.fabric.listener.block.BlockSpreadListener;
import net.coreprotect.fabric.listener.entity.EntityChangeBlockListener;
import net.coreprotect.fabric.listener.world.LeavesDecayListener;
import net.coreprotect.fabric.util.BonemealFertilizeContext;
import net.coreprotect.fabric.util.EntityBlockChangeContext;
import net.coreprotect.fabric.util.NaturalSpreadContext;
import net.coreprotect.fabric.util.PortalCreateContext;
import net.coreprotect.fabric.util.StructureGrowContext;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;
import java.util.Deque;

@Mixin(World.class)
public abstract class WorldBlockStateMixin {
    @Unique
    private static final ThreadLocal<Deque<PendingChange>> coreprotect$pendingChanges = ThreadLocal.withInitial(ArrayDeque::new);
    @Unique
    private static final ThreadLocal<Deque<PendingRemoval>> coreprotect$pendingRemovals = ThreadLocal.withInitial(ArrayDeque::new);

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("HEAD"))
    private void coreprotect$captureSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        capturePendingChange(pos);
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;I)Z", at = @At("RETURN"))
    private void coreprotect$logSetBlockState(BlockPos pos, BlockState state, int flags, CallbackInfoReturnable<Boolean> cir) {
        logPendingChange(cir.getReturnValueZ());
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Z", at = @At("HEAD"))
    private void coreprotect$captureSetBlockStateSimple(BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        capturePendingChange(pos);
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;)Z", at = @At("RETURN"))
    private void coreprotect$logSetBlockStateSimple(BlockPos pos, BlockState state, CallbackInfoReturnable<Boolean> cir) {
        logPendingChange(cir.getReturnValueZ());
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("HEAD"))
    private void coreprotect$captureSetBlockStateDeep(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        capturePendingChange(pos);
    }

    @Inject(method = "setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z", at = @At("RETURN"))
    private void coreprotect$logSetBlockStateDeep(BlockPos pos, BlockState state, int flags, int maxUpdateDepth, CallbackInfoReturnable<Boolean> cir) {
        logPendingChange(cir.getReturnValueZ());
    }

    @Inject(method = "removeBlock(Lnet/minecraft/util/math/BlockPos;Z)Z", at = @At("HEAD"))
    private void coreprotect$captureRemoveBlock(BlockPos pos, boolean move, CallbackInfoReturnable<Boolean> cir) {
        if (!((Object) this instanceof ServerWorld serverWorld)) {
            return;
        }
        if (!coreprotect$hasTrackedContext()) {
            return;
        }

        String actor = coreprotect$resolveActor();
        if (actor == null) {
            return;
        }

        coreprotect$pendingRemovals.get().push(new PendingRemoval(actor, serverWorld, pos.toImmutable(), serverWorld.getBlockState(pos)));
    }

    @Inject(method = "removeBlock(Lnet/minecraft/util/math/BlockPos;Z)Z", at = @At("RETURN"))
    private void coreprotect$logRemoveBlock(BlockPos pos, boolean move, CallbackInfoReturnable<Boolean> cir) {
        Deque<PendingRemoval> pendingRemovals = coreprotect$pendingRemovals.get();
        if (pendingRemovals.isEmpty()) {
            return;
        }

        PendingRemoval pendingRemoval = pendingRemovals.pop();
        if (pendingRemovals.isEmpty()) {
            coreprotect$pendingRemovals.remove();
        }

        if (!cir.getReturnValueZ()) {
            return;
        }

        BlockState currentState = pendingRemoval.world().getBlockState(pendingRemoval.pos());

        if (BonemealFertilizeContext.isActive()) {
            BonemealFertilizeContext.recordChange(
                pendingRemoval.world(),
                pendingRemoval.pos(),
                pendingRemoval.previousState(),
                currentState
            );
            return;
        }

        if (EntityBlockChangeContext.isActive()) {
            EntityChangeBlockListener.logEntityBlockChange(
                pendingRemoval.actor(),
                pendingRemoval.world(),
                pendingRemoval.pos(),
                pendingRemoval.previousState(),
                currentState
            );
            return;
        }

        if (PortalCreateContext.isActive()) {
            PortalCreateContext.recordChange(
                pendingRemoval.world(),
                pendingRemoval.pos(),
                pendingRemoval.previousState(),
                currentState
            );
            return;
        }

        if (StructureGrowContext.isActive()) {
            StructureGrowContext.recordChange(
                pendingRemoval.world(),
                pendingRemoval.pos(),
                pendingRemoval.previousState(),
                currentState
            );
            return;
        }

        if ("#fire".equals(pendingRemoval.actor())) {
            BlockBurnListener.logBlockBurn(
                pendingRemoval.actor(),
                pendingRemoval.world(),
                pendingRemoval.pos(),
                pendingRemoval.previousState()
            );
            return;
        }

        if ("#decay".equals(pendingRemoval.actor())) {
            LeavesDecayListener.logLeavesDecay(
                pendingRemoval.world(),
                pendingRemoval.pos(),
                pendingRemoval.previousState()
            );
            return;
        }

        if ("#turtle".equals(pendingRemoval.actor())) {
            BlockFadeListener.logTurtleEggFade(
                pendingRemoval.world(),
                pendingRemoval.pos(),
                pendingRemoval.previousState()
            );
        }
    }

    @Unique
    private void capturePendingChange(BlockPos pos) {
        if (!((Object) this instanceof ServerWorld serverWorld)) {
            return;
        }
        if (!coreprotect$hasTrackedContext()) {
            return;
        }

        String actor = coreprotect$resolveActor();
        if (actor == null) {
            return;
        }

        coreprotect$pendingChanges.get().push(new PendingChange(actor, serverWorld, pos.toImmutable(), serverWorld.getBlockState(pos)));
    }

    @Unique
    private boolean coreprotect$hasTrackedContext() {
        return StructureGrowContext.isActive()
            || NaturalSpreadContext.isActive()
            || EntityBlockChangeContext.isActive()
            || BonemealFertilizeContext.isActive()
            || PortalCreateContext.isActive();
    }

    @Unique
    private String coreprotect$resolveActor() {
        String actor = StructureGrowContext.currentActor();
        if (actor == null || actor.isBlank()) {
            actor = NaturalSpreadContext.currentActor();
        }
        if (actor == null || actor.isBlank()) {
            actor = EntityBlockChangeContext.currentActor();
        }
        if ((actor == null || actor.isBlank()) && BonemealFertilizeContext.isActive()) {
            actor = "#bonemeal";
        }
        if ((actor == null || actor.isBlank()) && PortalCreateContext.isActive()) {
            actor = "#portal";
        }
        return actor == null || actor.isBlank() ? null : actor;
    }

    @Unique
    private void logPendingChange(boolean changed) {
        Deque<PendingChange> pendingChanges = coreprotect$pendingChanges.get();
        if (pendingChanges.isEmpty()) {
            return;
        }

        PendingChange pendingChange = pendingChanges.pop();
        if (pendingChanges.isEmpty()) {
            coreprotect$pendingChanges.remove();
        }

        if (!changed) {
            return;
        }

        BlockState currentState = pendingChange.world().getBlockState(pendingChange.pos());
        if (currentState.equals(pendingChange.previousState())) {
            return;
        }

        if (BonemealFertilizeContext.isActive()) {
            BonemealFertilizeContext.recordChange(
                pendingChange.world(),
                pendingChange.pos(),
                pendingChange.previousState(),
                currentState
            );
            return;
        }

        if (EntityBlockChangeContext.isActive()) {
            EntityChangeBlockListener.logEntityBlockChange(
                pendingChange.actor(),
                pendingChange.world(),
                pendingChange.pos(),
                pendingChange.previousState(),
                currentState
            );
            return;
        }

        if (PortalCreateContext.isActive()) {
            PortalCreateContext.recordChange(
                pendingChange.world(),
                pendingChange.pos(),
                pendingChange.previousState(),
                currentState
            );
            return;
        }

        if (StructureGrowContext.isActive()) {
            StructureGrowContext.recordChange(
                pendingChange.world(),
                pendingChange.pos(),
                pendingChange.previousState(),
                currentState
            );
            return;
        }

        if ("#fire".equals(pendingChange.actor())) {
            BlockIgniteListener.logFireIgnite(
                pendingChange.actor(),
                pendingChange.world(),
                pendingChange.pos(),
                pendingChange.previousState(),
                currentState
            );
            return;
        }

        BlockSpreadListener.logNaturalSpread(
            pendingChange.actor(),
            pendingChange.world(),
            pendingChange.pos(),
            pendingChange.previousState(),
            currentState
        );
    }

    @Unique
    private record PendingChange(String actor, ServerWorld world, BlockPos pos, BlockState previousState) {
    }

    @Unique
    private record PendingRemoval(String actor, ServerWorld world, BlockPos pos, BlockState previousState) {
    }
}
