package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.EntityBlockChangeContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.entity.mob.SilverfishEntity$WanderAndInfestGoal")
public abstract class SilverfishInfestGoalMixin {
    @Inject(method = "start", at = @At("HEAD"))
    private void coreprotect$beginInfest(CallbackInfo ci) {
        EntityBlockChangeContext.push("#silverfish");
    }

    @Inject(method = "start", at = @At("RETURN"))
    private void coreprotect$endInfest(CallbackInfo ci) {
        EntityBlockChangeContext.pop();
    }
}
