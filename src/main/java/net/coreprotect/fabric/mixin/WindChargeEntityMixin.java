package net.coreprotect.fabric.mixin;

import net.coreprotect.fabric.util.EntityBlockChangeContext;
import net.minecraft.entity.projectile.WindChargeEntity;
import net.minecraft.util.math.Vec3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(WindChargeEntity.class)
public abstract class WindChargeEntityMixin {
    @Inject(method = "createExplosion", at = @At("HEAD"))
    private void coreprotect$beginExplosion(Vec3d pos, CallbackInfo ci) {
        EntityBlockChangeContext.push("#windcharge");
    }

    @Inject(method = "createExplosion", at = @At("RETURN"))
    private void coreprotect$endExplosion(Vec3d pos, CallbackInfo ci) {
        EntityBlockChangeContext.pop();
    }
}
