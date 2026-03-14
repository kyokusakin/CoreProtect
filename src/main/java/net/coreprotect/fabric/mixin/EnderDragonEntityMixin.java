package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.EntityBlockChangeContext;
import net.minecraft.entity.boss.dragon.EnderDragonEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EnderDragonEntity.class)
public abstract class EnderDragonEntityMixin {
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void coreprotect$beginTick(CallbackInfo ci) {
        EntityBlockChangeContext.push("#enderdragon");
    }

    @Inject(method = "tickMovement", at = @At("RETURN"))
    private void coreprotect$endTick(CallbackInfo ci) {
        EntityBlockChangeContext.pop();
    }
}
