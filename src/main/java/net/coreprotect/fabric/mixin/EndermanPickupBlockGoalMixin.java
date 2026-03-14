package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.EntityBlockChangeContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.entity.mob.EndermanEntity$PickUpBlockGoal")
public abstract class EndermanPickupBlockGoalMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void coreprotect$beginPickup(CallbackInfo ci) {
        EntityBlockChangeContext.push("#enderman");
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void coreprotect$endPickup(CallbackInfo ci) {
        EntityBlockChangeContext.pop();
    }
}
