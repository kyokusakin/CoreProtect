package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.listener.entity.EntityDamageByEntityListener;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.LeashKnotEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LeashKnotEntity.class)
public abstract class BlockAttachedEntityMixin {
    @Inject(method = "onBreak", at = @At("HEAD"))
    private void coreprotect$logLeashKnotBreak(ServerWorld world, Entity breaker, CallbackInfo ci) {
        LeashKnotEntity entity = (LeashKnotEntity) (Object) this;
        EntityDamageByEntityListener.logEntityBreak(world, entity.getBlockPos(), entity, breaker);
    }
}
