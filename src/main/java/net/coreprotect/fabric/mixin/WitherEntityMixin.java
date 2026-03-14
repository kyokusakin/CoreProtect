package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.EntityBlockChangeContext;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.server.world.ServerWorld;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WitherEntity.class)
public abstract class WitherEntityMixin {
    @Inject(method = "mobTick", at = @At("HEAD"))
    private void coreprotect$beginTick(ServerWorld world, CallbackInfo ci) {
        EntityBlockChangeContext.push("#wither");
    }

    @Inject(method = "mobTick", at = @At("RETURN"))
    private void coreprotect$endTick(ServerWorld world, CallbackInfo ci) {
        EntityBlockChangeContext.pop();
    }
}
