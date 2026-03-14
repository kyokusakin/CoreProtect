package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.entity.HangingBreakByEntityListener;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.painting.PaintingEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PaintingEntity.class)
public abstract class PaintingEntityMixin {
    @Inject(method = "onBreak", at = @At("HEAD"))
    private void coreprotect$logPaintingBreak(ServerWorld world, Entity breaker, CallbackInfo ci) {
        PaintingEntity paintingEntity = (PaintingEntity) (Object) this;
        HangingBreakByEntityListener.logHangingBreak(world, paintingEntity.getAttachedBlockPos(), paintingEntity, breaker);
    }
}
