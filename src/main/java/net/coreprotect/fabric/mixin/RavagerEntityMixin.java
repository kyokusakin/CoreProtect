package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.EntityBlockChangeContext;
import net.minecraft.entity.mob.RavagerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RavagerEntity.class)
public abstract class RavagerEntityMixin {
    @Inject(method = "tickMovement", at = @At("HEAD"))
    private void coreprotect$beginTick(CallbackInfo ci) {
        EntityBlockChangeContext.push("#ravager");
    }

    @Inject(method = "tickMovement", at = @At("RETURN"))
    private void coreprotect$endTick(CallbackInfo ci) {
        EntityBlockChangeContext.pop();
    }
}
