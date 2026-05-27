package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.EntityBlockChangeContext;
import net.minecraft.entity.ai.goal.BreakDoorGoal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(BreakDoorGoal.class)
public abstract class BreakDoorGoalMixin {
    @Inject(method = "tick", at = @At("HEAD"))
    private void coreprotect$beginBreakDoor(CallbackInfo ci) {
        EntityBlockChangeContext.push("#zombie");
    }

    @Inject(method = "tick", at = @At("RETURN"))
    private void coreprotect$endBreakDoor(CallbackInfo ci) {
        EntityBlockChangeContext.pop();
    }
}
