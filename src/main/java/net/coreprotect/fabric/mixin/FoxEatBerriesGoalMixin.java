package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.EntityBlockChangeContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.entity.passive.FoxEntity$EatBerriesGoal")
public abstract class FoxEatBerriesGoalMixin {
    @Inject(method = "eatBerries", at = @At("HEAD"))
    private void coreprotect$beginEat(CallbackInfo ci) {
        EntityBlockChangeContext.push("#fox");
    }

    @Inject(method = "eatBerries", at = @At("RETURN"))
    private void coreprotect$endEat(CallbackInfo ci) {
        EntityBlockChangeContext.pop();
    }
}
